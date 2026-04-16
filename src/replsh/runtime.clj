(ns replsh.runtime
  (:require [replsh.process :as process]
            [replsh.util :as util]
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

;; --- Session normalization ---

(defn normalize-session
  "Ensure a session config has :runtime set (defaults to :local).
   Also migrates old :docker exec-mode sessions to the unified :exec runtime,
   and migrates old flat-key :exec sessions (ssh-host/container-id) to :via format."
  [session]
  (let [session (cond-> session
                  (not (:runtime session))
                  (assoc :runtime :local)
                  (and (= :docker (:runtime session))
                       (= :exec (get-in session [:transport :type])))
                  (assoc :runtime :exec))
        ;; Migrate old flat-key :exec sessions to :via format
        session (if (and (= :exec (:runtime session))
                         (not (get-in session [:launch :via]))
                         (or (get-in session [:launch :ssh-host])
                             (get-in session [:launch :container-id])))
                  (update session :launch assoc :via
                          (cond-> []
                            (get-in session [:launch :ssh-host])
                            (conj {:type :ssh :host (get-in session [:launch :ssh-host])})
                            (get-in session [:launch :container-id])
                            (conj {:type :docker :container (get-in session [:launch :container-id])})))
                  session)]
    session))

;; --- Helpers ---

(defn session->runtime-info
  "Build a runtime-info map from a persisted session config.
   Normalizes the session first (migrates old flat-key :exec format to :via)."
  [session]
  (let [session (normalize-session session)
        rt      (or (:runtime session) :local)
        launch  (:launch session)]
    (cond-> {:runtime rt :name (:name session)}
      (= rt :local)  (assoc :pid (:pid launch))
      (= rt :docker) (assoc :container-id (:container-id launch))
      (= rt :exec)   (assoc :pid (:bridge-pid launch)
                            :via (:via launch)))))

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
  (let [log-file (File. (str util/log-dir name ".log"))]
    (when (.exists log-file)
      (if tail
        (process/read-log-tail (.getAbsolutePath log-file) tail)
        (slurp log-file)))))

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
  (let [_        (.mkdirs (File. util/log-dir))
        log-file (File. (str util/log-dir name ".exec.log"))
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
  (let [_        (.mkdirs (File. util/log-dir))
        log-file (File. (str util/log-dir (or name "docker") ".exec.log"))
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
;; :exec runtime — ordered via-chain: SSH, Docker, and Bash layers
;; ==========================================================================

(defn- shell-escape
  "Wrap a string in single quotes, escaping any embedded single quotes."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- layer->str
  "Wrap inner-cmd string through a non-outermost exec layer.
   Returns a shell string suitable for passing as an argument to an outer process."
  [layer inner-cmd]
  (case (:type layer)
    :ssh    (str "ssh -o BatchMode=yes "
                 (when (:port layer) (str "-p " (:port layer) " "))
                 (when (:key layer) (str "-i " (:key layer) " "))
                 (:host layer) " " (shell-escape inner-cmd))
    :docker (str "docker exec -i "
                 (when (:user layer) (str "--user " (:user layer) " "))
                 (:container layer) " sh -c " (shell-escape inner-cmd))))

(defn- layer->vec
  "Render the outermost exec layer as a ProcessBuilder argv vector."
  [layer inner-cmd]
  (case (:type layer)
    :ssh    (cond-> ["ssh" "-o" "BatchMode=yes"]
              (:port layer) (into ["-p" (str (:port layer))])
              (:key layer)  (into ["-i" (:key layer)])
              true          (into [(:host layer) inner-cmd]))
    :docker (cond-> ["docker" "exec" "-i"]
              (:user layer) (into ["--user" (:user layer)])
              true          (into [(:container layer) "sh" "-c" inner-cmd]))))

(defn via-args
  "Build ProcessBuilder args for executing cmd through the via chain (outermost first).
   :bash layers fold their setup commands into the cmd string rather than becoming
   outer processes. The outermost process is always the first non-bash exec layer."
  [via cmd]
  (let [exec-via    (remove #(= :bash (:type %)) via)
        bash-layers (filter #(= :bash (:type %)) via)
        ;; Fold bash setup into the command string
        inner-cmd   (reduce (fn [s bl]
                              (str (str/join " && " (:setup bl)) " && " s))
                            cmd bash-layers)
        wrapped (if (empty? exec-via)
                  ["/bin/sh" "-c" inner-cmd]
                  ;; Build from innermost outward: reverse(rest) layers wrap the cmd,
                  ;; then the first (outermost) layer becomes the ProcessBuilder process.
                  (let [s (reduce #(layer->str %2 %1) inner-cmd (reverse (rest exec-via)))]
                    (layer->vec (first exec-via) s)))]
    wrapped))

(defmethod exec! :exec
  [{:keys [name via]} cmd _opts]
  (let [_        (.mkdirs (File. util/log-dir))
        log-file (File. (str util/log-dir (or name "exec") ".exec.log"))
        pb       (doto (ProcessBuilder. ^java.util.List (via-args (or via []) cmd))
                   (.redirectError (ProcessBuilder$Redirect/appendTo log-file)))
        process  (.start pb)]
    {:in  (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
     :out (PrintWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8) true)
     :process process}))

(defmethod deploy-bridge! :exec [{:keys [via]}]
  (require 'replsh.bridge)
  (let [local-path  ((resolve 'replsh.bridge/ensure-bridge!))
        remote-path "/tmp/replsh/replsh_bridge.py"
        ;; Strip bash layers for file transfer — they affect the runtime env, not destinations
        deploy-via  (vec (remove #(= :bash (:type %)) (or via [])))
        dest-cmd    (str "mkdir -p /tmp/replsh && cat > " remote-path)
        args        (via-args deploy-via dest-cmd)
        pb          (ProcessBuilder. ^java.util.List args)
        process     (.start pb)]
    (let [proc-out (.getOutputStream process)]
      (io/copy (io/file local-path) proc-out)
      (.flush proc-out)
      (.close proc-out))
    (when-not (zero? (.waitFor process))
      (throw (ex-info "Failed to deploy bridge" {:code :deploy-failed})))
    remote-path))

(defmethod alive? :exec [{:keys [pid via]}]
  (let [exec-via     (vec (remove #(= :bash (:type %)) (or via [])))
        docker-layer (last (filter #(= :docker (:type %)) exec-via))
        outer-via    (vec (take-while #(not= % docker-layer) exec-via))]
    (cond
      docker-layer
      (try
        (let [inspect-cmd (str "docker inspect --format '{{.State.Running}}' " (:container docker-layer))
              args        (via-args outer-via inspect-cmd)
              proc        (-> (ProcessBuilder. ^java.util.List args) .start)
              out         (str/trim (slurp (.getInputStream proc)))]
          (.waitFor proc)
          (= out "true"))
        (catch Exception _ false))
      pid   (process/alive? pid)
      :else false)))

(defmethod stop! :exec [{:keys [via]}]
  (let [exec-via     (vec (remove #(= :bash (:type %)) (or via [])))
        docker-layer (last (filter #(= :docker (:type %)) exec-via))
        outer-via    (vec (take-while #(not= % docker-layer) exec-via))]
    (when docker-layer
      (let [stop-cmd (str "docker stop " (:container docker-layer) " && docker rm " (:container docker-layer))
            args     (via-args outer-via stop-cmd)]
        (try (-> (ProcessBuilder. ^java.util.List args) .start .waitFor)
             (catch Exception _))))))

(defmethod spawn! :exec [{:keys [via name env-vars volumes platform]}]
  (let [exec-via     (vec (remove #(= :bash (:type %)) (or via [])))
        docker-idx   (first (keep-indexed (fn [i l] (when (and (= :docker (:type l)) (:image l)) i)) exec-via))
        docker-layer (when docker-idx (nth exec-via docker-idx))
        outer-via    (when docker-idx (vec (take docker-idx exec-via)))]
    (when-not docker-layer
      (throw (ex-info "No docker layer with :image found in :via for spawn!"
                      {:code :missing-image :via via})))
    (let [cname       (str "replsh-" name)
          docker-args (cond-> ["docker" "run" "-d" "--name" cname]
                        (:platform docker-layer) (into ["--platform" (:platform docker-layer)])
                        platform                  (into ["--platform" platform])
                        (seq volumes)             (into (mapcat (fn [v] ["-v" v]) volumes))
                        (seq env-vars)            (into (mapcat (fn [[k v]] ["-e" (str k "=" v)]) env-vars))
                        true                      (conj (:image docker-layer)))
          ;; Route docker run through any outer layers (e.g., SSH)
          args        (if (seq outer-via)
                        (via-args outer-via (str/join " " docker-args))
                        docker-args)
          proc        (-> (ProcessBuilder. ^java.util.List args) .start)
          cid         (str/trim (slurp (.getInputStream proc)))]
      (.waitFor proc)
      ;; Return updated :via with container-id substituted for :image in the docker layer
      {:runtime :exec
       :via     (mapv (fn [l]
                        (if (and (= :docker (:type l)) (:image l))
                          (-> l (dissoc :image) (assoc :container cid))
                          l))
                      (or via []))})))

(defmethod send-signal! :exec [{:keys [pid]} signal]
  (when pid
    (-> (ProcessBuilder. ["kill" (str "-" signal) (str pid)]) .start .waitFor)))

(defmethod mapped-port :exec [_info port] port)

(defmethod wait-ready! :exec [_info _opts] true)

(defmethod deploy-files! :exec [_info _files] nil)

(defmethod logs :exec [{:keys [name]} {:keys [tail]}]
  (let [log-file (File. (str util/log-dir (or name "exec") ".exec.log"))]
    (when (.exists log-file)
      (if tail
        (process/read-log-tail (.getAbsolutePath log-file) tail)
        (slurp log-file)))))
