(ns replsh.process
  (:require [babashka.http-client :as http]
            [clojure.string :as str])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect]
           [java.net Socket]))

(def ^:private log-dir
  (str (System/getProperty "user.home") "/.replsh/logs/"))

(defn- ensure-log-dir! []
  (.mkdirs (File. log-dir)))

(defn- read-log-tail
  "Read last N lines from a log file, or empty string if not readable."
  [log-path n]
  (try
    (let [lines (str/split-lines (slurp log-path))]
      (str/join "\n" (take-last n lines)))
    (catch Exception _ "")))

(defn spawn!
  "Spawn a background process. Returns {:pid long :process Process}.
   Throws :launch-failed if the process dies within 200ms."
  [{:keys [cmd cwd env-vars name]}]
  (ensure-log-dir!)
  (let [log-file (File. (str log-dir name ".log"))
        pb       (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" (str "exec " cmd)])]
    (.redirectErrorStream pb true)
    (.redirectOutput pb (ProcessBuilder$Redirect/appendTo log-file))
    (when cwd
      (.directory pb (File. ^String cwd)))
    (when (seq env-vars)
      (let [env (.environment pb)]
        (doseq [[k v] env-vars]
          (.put env (str k) (str v)))))
    (let [process (.start pb)
          pid     (.pid process)]
      ;; Wait briefly to catch immediate failures
      (Thread/sleep 200)
      (when-not (.isAlive process)
        (let [exit-code (.exitValue process)
              log-tail  (read-log-tail (.getAbsolutePath log-file) 20)]
          (throw (ex-info (str "Process exited immediately (exit code " exit-code ")")
                          {:code     :launch-failed
                           :exit     exit-code
                           :log-tail log-tail}))))
      {:pid pid :process process})))

(defn wait-for-port!
  "Poll a TCP port until it accepts connections. Checks process liveness each iteration.
   Returns true on success. Throws on timeout or process death."
  [{:keys [host port timeout-ms process]}]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 30000))
        host     (or host "localhost")]
    (loop []
      (when (and process (not (.isAlive ^Process process)))
        (throw (ex-info "Process exited before port became available"
                        {:code :launch-failed
                         :host host :port port})))
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "Port " host ":" port " not reachable after timeout")
                        {:code :port-timeout
                         :host host :port port})))
      (if (try
            (let [sock (Socket. ^String host ^int port)]
              (.close sock)
              true)
            (catch Exception _ false))
        true
        (do
          (Thread/sleep 250)
          (recur))))))

(defn wait-for-http!
  "Poll an HTTP endpoint until it responds with 2xx. Checks process liveness.
   Returns true on success. Throws on timeout or process death."
  [{:keys [url timeout-ms process]}]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 30000))
]
    (loop []
      (when (and process (not (.isAlive ^Process process)))
        (throw (ex-info "Process exited before HTTP endpoint became available"
                        {:code :launch-failed :url url})))
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "HTTP endpoint " url " not reachable after timeout")
                        {:code :port-timeout :url url})))
      (if (try
            (let [resp (http/get (str url "/api/status")
                                 {:timeout 2000 :throw false})]
              (<= 200 (:status resp) 299))
            (catch Exception _ false))
        true
        (do
          (Thread/sleep 250)
          (recur))))))

(defn alive?
  "Check if a process with the given PID is alive."
  [pid]
  (try
    (let [p (.start (ProcessBuilder. ["kill" "-0" (str pid)]))]
      (.waitFor p)
      (zero? (.exitValue p)))
    (catch Exception _ false)))

(defn kill!
  "Kill a process by PID. Returns :killed or :already-dead."
  [pid]
  (if-not (alive? pid)
    :already-dead
    (do
      ;; SIGTERM
      (-> (ProcessBuilder. ["kill" (str pid)]) .start .waitFor)
      ;; Wait up to 3 seconds for graceful shutdown
      (loop [attempts 12]
        (cond
          (not (alive? pid))  :killed
          (<= attempts 0)     (do (-> (ProcessBuilder. ["kill" "-9" (str pid)]) .start .waitFor)
                                  :killed)
          :else               (do (Thread/sleep 250)
                                  (recur (dec attempts))))))))
