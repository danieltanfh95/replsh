(ns replsh.runtime
  (:require [replsh.process :as process]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader File InputStreamReader PrintWriter OutputStreamWriter]
           [java.lang ProcessBuilder$Redirect]
           [java.net Socket]
           [java.nio.charset StandardCharsets]))

;; --- Multimethod definitions ---
;; Dispatch on :runtime keyword (analogous to backend dispatching on :backend)

(defmulti spawn!      :runtime)
(defmulti stop!       :runtime)
(defmulti alive?      :runtime)
(defmulti logs        (fn [info _opts] (:runtime info)))
(defmulti send-signal! :runtime)
(defmulti mapped-port (fn [info _port] (:runtime info)))
(defmulti wait-ready! (fn [info _opts] (:runtime info)))
(defmulti deploy-files! (fn [info _files] (:runtime info)))
(defmulti exec!          (fn [info _cmd _opts] (:runtime info)))
(defmulti deploy-bridge! :runtime)

;; --- Helpers ---

(defn session->runtime-info
  "Build a runtime-info map from a persisted session config."
  [session]
  (let [rt    (or (:runtime session) :local)
        launch (:launch session)]
    (cond-> {:runtime rt :name (:name session)}
      (= rt :local)  (assoc :pid (:pid launch))
      (= rt :docker) (assoc :container-id (:container-id launch))
      (= rt :exec)   (assoc :pid          (:bridge-pid launch)
                            :ssh-host     (:ssh-host launch)
                            :container-id (:container-id launch)))))

;; --- Docker CLI helpers ---

(defn- run-docker!
  "Run a docker CLI command. Returns trimmed stdout on success.
   Throws on non-zero exit."
  [& args]
  (let [pb      (ProcessBuilder. ^java.util.List (into ["docker"] args))
        process (.start pb)
        stdout  (slurp (.getInputStream process))
        stderr  (slurp (.getErrorStream process))
        exit    (.waitFor process)]
    (if (zero? exit)
      (str/trim stdout)
      (throw (ex-info (str "Docker command failed: docker " (str/join " " args))
                      {:code    :docker-error
                       :exit    exit
                       :stderr  (str/trim stderr)
                       :args    (vec args)})))))

(defn- run-docker-quiet!
  "Run a docker CLI command, ignoring failures. Returns trimmed stdout or nil."
  [& args]
  (try
    (apply run-docker! args)
    (catch Exception _ nil)))

;; --- Session normalization ---

(defn normalize-session
  "Ensure a session config has :runtime set (defaults to :local).
   Also migrates old :docker exec-mode sessions to the unified :exec runtime."
  [session]
  (cond-> session
    (not (:runtime session))
    (assoc :runtime :local)
    (and (= :docker (:runtime session))
         (= :exec (get-in session [:transport :type])))
    (assoc :runtime :exec)))

;; ==========================================================================
;; :local runtime — delegates to existing process.clj
;; ==========================================================================

(defmethod spawn! :local [{:keys [cmd cwd env-vars name]}]
  (let [{:keys [pid process]} (process/spawn! {:cmd cmd :cwd cwd :env-vars env-vars :name name})]
    {:runtime :local :pid pid :process process}))

(defmethod stop! :local [{:keys [pid]}]
  (process/kill! pid))

(defmethod alive? :local [{:keys [pid]}]
  (process/alive? pid))

(defmethod logs :local [{:keys [name]} {:keys [tail]}]
  (let [log-dir  (str (System/getProperty "user.home") "/.replsh/logs/")
        log-file (File. (str log-dir name ".log"))]
    (when (.exists log-file)
      (let [content (slurp log-file)
            lines   (str/split-lines content)]
        (if tail
          (str/join "\n" (take-last tail lines))
          content)))))

(defmethod send-signal! :local [{:keys [pid]} signal]
  (-> (ProcessBuilder. ["kill" (str "-" signal) (str pid)])
      .start
      .waitFor))

(defmethod mapped-port :local [_info port]
  port)

(defmethod wait-ready! :local
  [{:keys [process]} {:keys [host port url timeout-ms check]}]
  (case (or check :tcp)
    :tcp  (process/wait-for-port! {:host host :port port
                                   :timeout-ms (or timeout-ms 30000)
                                   :process process})
    :http (process/wait-for-http! {:url url :timeout-ms (or timeout-ms 30000)
                                   :process process})))

(defmethod deploy-files! :local [_info _files]
  ;; Local filesystem — paths are directly accessible, nothing to deploy
  nil)

(defmethod exec! :local
  [{:keys [name]} cmd {:keys [stderr-log?]}]
  (let [log-dir  (str (System/getProperty "user.home") "/.replsh/logs/")
        _        (.mkdirs (File. log-dir))
        log-file (File. (str log-dir name ".exec.log"))
        pb       (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" cmd])]
    (.redirectErrorStream pb false)
    (.redirectError pb (ProcessBuilder$Redirect/appendTo log-file))
    (let [process (.start pb)
          in      (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
          out     (PrintWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8) true)]
      {:in in :out out :process process})))

(defmethod deploy-bridge! :local [_info]
  ;; Local: bridge is on the host filesystem, just ensure it's deployed
  (require 'replsh.bridge)
  ((resolve 'replsh.bridge/ensure-bridge!)))

;; ==========================================================================
;; :docker runtime — manages containers via Docker CLI
;; ==========================================================================

(defmethod spawn! :docker
  [{:keys [cmd name image volumes env-vars container-cwd port platform]}]
  (let [cname   (str "replsh-" name)
        args    (cond-> ["run" "-d" "--name" cname]
                  ;; Override ENTRYPOINT only when we have a custom command (port mode)
                  cmd
                  (into ["--entrypoint" "sh"])
                  ;; Platform override (e.g., "linux/amd64" for Rosetta on ARM Mac)
                  platform
                  (into ["--platform" platform])
                  ;; Port mapping — Docker auto-allocates host port
                  port
                  (into ["-p" (str port)])
                  ;; Volume mounts — each string is a complete -v arg
                  (seq volumes)
                  (into (mapcat (fn [v] ["-v" v]) volumes))
                  ;; Working directory
                  container-cwd
                  (into ["--workdir" container-cwd])
                  ;; Environment variables
                  (seq env-vars)
                  (into (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env-vars))
                  ;; Image
                  true
                  (conj image)
                  ;; Command — -c exec for signal forwarding (only when cmd is provided)
                  cmd
                  (into ["-c" (str "exec " cmd)]))
        cid     (apply run-docker! args)]
    {:runtime     :docker
     :container-id cid
     :container-name cname}))

(defmethod stop! :docker [{:keys [container-id]}]
  (run-docker-quiet! "stop" container-id)
  (run-docker-quiet! "rm" container-id)
  :stopped)

(defmethod alive? :docker [{:keys [container-id]}]
  (= "true" (run-docker-quiet! "inspect" "--format" "{{.State.Running}}" container-id)))

(defmethod logs :docker [{:keys [container-id]} {:keys [tail follow]}]
  (let [args (cond-> ["logs"]
               tail   (into ["--tail" (str tail)])
               follow (conj "-f")
               true   (conj container-id))]
    (try
      (apply run-docker! args)
      (catch Exception e
        (or (ex-message e) "")))))

(defmethod send-signal! :docker [{:keys [container-id]} signal]
  (run-docker! "kill" "--signal" (str signal) container-id))

(defmethod mapped-port :docker [{:keys [container-id]} container-port]
  (when-let [output (run-docker-quiet! "port" container-id (str container-port))]
    (when-let [m (re-find #":(\d+)$" output)]
      (Integer/parseInt (second m)))))

(defmethod wait-ready! :docker
  [runtime-info {:keys [host port url timeout-ms check]}]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 30000))
        host     (or host "localhost")
        check    (or check :tcp)]
    (loop []
      ;; Check container liveness each iteration (replaces Process handle check)
      (when-not (alive? runtime-info)
        (throw (ex-info "Container exited before becoming ready"
                        {:code :launch-failed
                         :container-id (:container-id runtime-info)})))
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str (if (= :http check)
                               (str "HTTP endpoint " url)
                               (str "Port " host ":" port))
                             " not reachable after timeout")
                        {:code :port-timeout :host host :port port})))
      (let [ready? (case check
                     :tcp  (try
                             (let [sock (Socket. ^String host ^int port)]
                               (.close sock)
                               true)
                             (catch Exception _ false))
                     :http (try
                             (let [resp (http/get (str url "/api/status")
                                                  {:timeout 2000 :throw false})]
                               (<= 200 (:status resp) 299))
                             (catch Exception _ false)))]
        (if ready?
          true
          (do
            (Thread/sleep 250)
            (recur)))))))

(defmethod deploy-files! :docker [_info _files]
  ;; Docker file deployment is handled at spawn time via volume mounts.
  ;; The spawn! method converts :deploy-files entries to -v args.
  ;; Post-spawn deploy via `docker cp` can be added later if needed.
  nil)

(defmethod exec! :docker
  [{:keys [container-id name]} cmd {:keys [_stderr-log?]}]
  (let [log-dir  (str (System/getProperty "user.home") "/.replsh/logs/")
        _        (.mkdirs (File. log-dir))
        log-file (File. (str log-dir (or name "docker") ".exec.log"))
        pb       (ProcessBuilder.
                   ^java.util.List ["docker" "exec" "-i" container-id "sh" "-c" cmd])]
    (.redirectErrorStream pb false)
    (.redirectError pb (ProcessBuilder$Redirect/appendTo log-file))
    (let [process (.start pb)
          in      (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
          out     (PrintWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8) true)]
      {:in in :out out :process process})))

(defmethod deploy-bridge! :docker [{:keys [container-id]}]
  (require 'replsh.bridge)
  (let [host-path ((resolve 'replsh.bridge/ensure-bridge!))
        dest-path "/tmp/replsh/replsh_bridge.py"]
    ;; Create directory inside container and copy bridge
    (run-docker! "exec" container-id "mkdir" "-p" "/tmp/replsh")
    (run-docker! "cp" host-path (str container-id ":" dest-path))
    dest-path))

;; ==========================================================================
;; :exec runtime — bridge via SSH and/or Docker exec (compose as needed)
;; ==========================================================================

(defn- shell-escape
  "Wrap a string in single quotes, escaping any embedded single quotes."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- exec-args
  "Build a ProcessBuilder args vector for the given exec combination.
   ssh-host and container-id are both optional."
  [{:keys [ssh-host container-id]} cmd]
  (cond
    (and ssh-host container-id)
    ["ssh" "-o" "BatchMode=yes" ssh-host
     (str "docker exec -i " container-id " sh -c " (shell-escape cmd))]

    ssh-host
    ["ssh" "-o" "BatchMode=yes" ssh-host (str "sh -c " (shell-escape cmd))]

    container-id
    ["docker" "exec" "-i" container-id "sh" "-c" cmd]

    :else
    ["/bin/sh" "-c" cmd]))

(defmethod exec! :exec
  [{:keys [name] :as runtime-info} cmd _opts]
  (let [log-dir  (str (System/getProperty "user.home") "/.replsh/logs/")
        _        (.mkdirs (File. log-dir))
        log-file (File. (str log-dir (or name "exec") ".exec.log"))
        pb       (doto (ProcessBuilder. ^java.util.List (exec-args runtime-info cmd))
                   (.redirectError (ProcessBuilder$Redirect/appendTo log-file)))
        process  (.start pb)]
    {:in  (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
     :out (PrintWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8) true)
     :process process}))

(defmethod deploy-bridge! :exec [{:keys [ssh-host container-id]}]
  (require 'replsh.bridge)
  (let [local-path  ((resolve 'replsh.bridge/ensure-bridge!))
        remote-path "/tmp/replsh/replsh_bridge.py"
        dest-cmd    (if container-id
                      (str "docker exec -i " container-id
                           " sh -c 'mkdir -p /tmp/replsh && cat > " remote-path "'")
                      (str "mkdir -p /tmp/replsh && cat > " remote-path))
        args        (if ssh-host
                      ["ssh" "-o" "BatchMode=yes" ssh-host dest-cmd]
                      ["/bin/sh" "-c" dest-cmd])
        pb          (ProcessBuilder. ^java.util.List args)
        process     (.start pb)]
    (let [proc-out (.getOutputStream process)]
      (io/copy (io/file local-path) proc-out)
      (.flush proc-out)
      (.close proc-out))
    (when-not (zero? (.waitFor process))
      (throw (ex-info "Failed to deploy bridge" {:code :deploy-failed})))
    remote-path))

(defmethod alive? :exec [{:keys [pid ssh-host container-id]}]
  (cond
    container-id
    (try
      (let [inspect-cmd (str "docker inspect --format '{{.State.Running}}' " container-id)
            args        (if ssh-host
                          ["ssh" "-o" "BatchMode=yes" ssh-host inspect-cmd]
                          ["/bin/sh" "-c" inspect-cmd])
            proc        (-> (ProcessBuilder. ^java.util.List args) .start)
            out         (str/trim (slurp (.getInputStream proc)))]
        (.waitFor proc)
        (= out "true"))
      (catch Exception _ false))
    pid (process/alive? pid)
    :else false))

(defmethod stop! :exec [{:keys [ssh-host container-id]}]
  (when container-id
    (let [stop-cmd (str "docker stop " container-id " && docker rm " container-id)
          args     (if ssh-host
                     ["ssh" "-o" "BatchMode=yes" ssh-host stop-cmd]
                     ["/bin/sh" "-c" stop-cmd])]
      (try (-> (ProcessBuilder. ^java.util.List args) .start .waitFor)
           (catch Exception _)))))

(defmethod spawn! :exec [{:keys [ssh-host image name env-vars volumes platform]}]
  (let [cname       (str "replsh-" name)
        docker-args (cond-> ["docker" "run" "-d" "--name" cname]
                      platform       (into ["--platform" platform])
                      (seq volumes)  (into (mapcat (fn [v] ["-v" v]) volumes))
                      (seq env-vars) (into (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env-vars))
                      true           (conj image))
        args        (if ssh-host
                      ["ssh" "-o" "BatchMode=yes" ssh-host
                       (str/join " " docker-args)]
                      docker-args)
        proc        (-> (ProcessBuilder. ^java.util.List args) .start)
        cid         (str/trim (slurp (.getInputStream proc)))]
    (.waitFor proc)
    {:runtime :exec :container-id cid}))

(defmethod send-signal! :exec [{:keys [pid]} signal]
  (when pid
    (-> (ProcessBuilder. ["kill" (str "-" signal) (str pid)]) .start .waitFor)))

(defmethod mapped-port :exec [_info port] port)

(defmethod wait-ready! :exec [_info _opts] true)

(defmethod deploy-files! :exec [_info _files] nil)

(defmethod logs :exec [{:keys [name]} {:keys [tail]}]
  (let [log-dir  (str (System/getProperty "user.home") "/.replsh/logs/")
        log-file (File. (str log-dir (or name "exec") ".exec.log"))]
    (when (.exists log-file)
      (let [content (slurp log-file)
            lines   (str/split-lines content)]
        (if tail
          (str/join "\n" (take-last tail lines))
          content)))))
