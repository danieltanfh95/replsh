(ns replsh.backend.python
  (:require [replsh.backend :as backend]
            [replsh.runtime :as runtime]
            [cheshire.core :as json])
  (:import [java.net Socket]
           [java.io BufferedReader InputStreamReader OutputStreamWriter PrintWriter]
           [java.nio.charset StandardCharsets]))

(defn- open-tcp
  "Open a TCP socket for NDJSON I/O."
  [host port]
  (let [sock (Socket. ^String host ^int port)
        in   (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
        out  (PrintWriter. (OutputStreamWriter. (.getOutputStream sock) StandardCharsets/UTF_8) true)]
    {:socket sock :in in :out out}))

(defn- send-json!
  "Send a JSON message as one NDJSON line."
  [{:keys [^PrintWriter out]} msg]
  (.println out (json/generate-string msg))
  (.flush out))

(defn- read-json-line
  "Read one NDJSON line. Returns parsed map or nil on SocketTimeoutException."
  [^BufferedReader in]
  (let [line (.readLine in)]
    (when line
      (json/parse-string line true))))

(defn- msg->chunk
  "Convert a bridge JSON response to a replsh chunk map."
  [msg session-name]
  (let [msg-id (:msg_id msg (:msg-id msg ""))]
    {:type    (keyword (:type msg))
     :content (or (:content msg) "")
     :stream  (when (:stream msg) (keyword (:stream msg)))
     :meta    (or (:meta msg) {})
     :done?   (boolean (:done msg))
     :msg-id  msg-id
     :name    session-name}))

(defn- read-eval-responses
  "Read NDJSON responses until done, converting to chunks.
   Calls on-chunk per chunk for streaming.
   On timeout, throws with accumulated :chunks in ex-data.
   Transport-agnostic: uses BufferedReader.ready() polling instead of socket timeout."
  [handles msg-id timeout-ms session-name on-chunk]
  (let [deadline    (if (pos? timeout-ms)
                      (+ (System/currentTimeMillis) timeout-ms)
                      Long/MAX_VALUE)
        ^BufferedReader in (:in handles)]
    (loop [all-chunks []]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for Python response"
                        {:code :timeout :msg-id msg-id
                         :chunks all-chunks})))
      (let [msg (if (.ready in)
                  (read-json-line in)
                  (do (Thread/sleep 50) nil))]
        (if-not msg
          (recur all-chunks)
          ;; Filter by msg-id
          (let [resp-id (or (:msg_id msg) (:msg-id msg) "")]
            (if (not= resp-id msg-id)
              (recur all-chunks)
              (let [chunk (msg->chunk msg session-name)]
                (when (and on-chunk (not= :status (:type chunk)))
                  (on-chunk chunk))
                (if (:done? chunk)
                  ;; Done — finalize chunks
                  (let [all-chunks (if (= :status (:type chunk))
                                    all-chunks ;; don't add the bare status chunk
                                    (conj all-chunks chunk))]
                    (if (seq all-chunks)
                      (update all-chunks (dec (count all-chunks)) assoc :done? true)
                      [{:type :status :content "" :meta {} :done? true
                        :msg-id msg-id :name session-name}]))
                  (recur (conj all-chunks chunk)))))))))))

(defn- open-via-exec
  "Start an ephemeral proxy process that connects to the persistent bridge.
   Returns handles map with :in :out :process."
  [session-config]
  (let [exec-port  (get-in session-config [:transport :exec-port])
        bridge-path (get-in session-config [:launch :bridge-path] "/tmp/replsh/replsh_bridge.py")
        runtime-info (runtime/session->runtime-info session-config)
        cmd         (str "python3 " bridge-path " --connect 127.0.0.1:" exec-port)
        handles     (runtime/exec! runtime-info cmd {})]
    handles))

(defn- ping-pong!
  "Send a ping and wait for pong. Throws on failure."
  [handles]
  (send-json! handles {:op "ping" :msg_id "open-ping"})
  (let [deadline (+ (System/currentTimeMillis) 5000)
        ^BufferedReader in (:in handles)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Python bridge did not respond to ping"
                        {:code :connection-refused})))
      (if (.ready in)
        (let [resp (read-json-line in)]
          (when-not (= "pong" (:type resp))
            (throw (ex-info "Python bridge did not respond to ping"
                            {:code :connection-refused :response resp}))))
        (do (Thread/sleep 50) (recur))))))

;; --- Multimethod implementations ---

(defmethod backend/open! :python
  [session-config]
  (let [transport-type (get-in session-config [:transport :type])
        handles (case transport-type
                  :tcp  (let [{:keys [host port]} (:transport session-config)]
                          (open-tcp host port))
                  :exec (open-via-exec session-config))]
    (ping-pong! handles)
    {:config   session-config
     :backend  :python
     :status   :connected
     :handles  handles
     :internal {}}))

(defmethod backend/close! :python
  [live-state]
  (let [transport-type (get-in live-state [:config :transport :type])]
    (case transport-type
      :tcp  (when-let [sock (get-in live-state [:handles :socket])]
              (.close ^Socket sock))
      :exec (when-let [^Process proc (get-in live-state [:handles :process])]
              (.destroy proc))))
  nil)

(defmethod backend/destroy! :python
  [_session-config]
  ;; Bridge process is managed by the launch system (process/kill!).
  ;; No server-side session to clean up.
  nil)

(defmethod backend/eval! :python
  [request live-state]
  (let [{:keys [code name timeout-ms msg-id on-chunk]} request
        handles (:handles live-state)]
    (send-json! handles {:op "eval" :code code :msg_id msg-id})
    (try
      (read-eval-responses handles msg-id (or timeout-ms 30000) name on-chunk)
      (catch clojure.lang.ExceptionInfo e
        (if (= :timeout (:code (ex-data e)))
          (let [chunks (or (:chunks (ex-data e)) [])]
            (with-meta
              (if (seq chunks)
                chunks
                [{:type :status :content "" :meta {} :done? true
                  :msg-id msg-id :name name}])
              {:timed-out? true}))
          (throw e))))))

(defmethod backend/interrupt! :python
  [live-state]
  (let [session-config (:config live-state)]
    (if (:launch session-config)
      (do
        (try
          (runtime/send-signal! (runtime/session->runtime-info session-config) "INT")
          (catch Exception _))
        :ok)
      {:error   true
       :code    "unsupported"
       :message "Interrupt requires a launched session (need PID or container-id)"
       :detail  {}})))
