(ns replsh.backend.python
  (:require [replsh.backend :as backend]
            [cheshire.core :as json])
  (:import [java.net Socket SocketTimeoutException]
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
   On timeout, throws with accumulated :chunks in ex-data."
  [handles msg-id timeout-ms session-name on-chunk]
  (let [deadline (if (pos? timeout-ms)
                   (+ (System/currentTimeMillis) timeout-ms)
                   Long/MAX_VALUE)
        ^Socket sock (:socket handles)]
    (.setSoTimeout sock 500)
    (try
      (loop [all-chunks []]
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timeout waiting for Python response"
                          {:code :timeout :msg-id msg-id
                           :chunks all-chunks})))
        (let [msg (try
                    (read-json-line (:in handles))
                    (catch SocketTimeoutException _
                      nil))]
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
                    (recur (conj all-chunks chunk)))))))))
      (finally
        (.setSoTimeout sock 0)))))

;; --- Multimethod implementations ---

(defmethod backend/open! :python
  [session-config]
  (let [{:keys [host port]} (:transport session-config)
        handles (open-tcp host port)]
    ;; Verify connectivity with ping
    (send-json! handles {:op "ping" :msg_id "open-ping"})
    (.setSoTimeout ^Socket (:socket handles) 5000)
    (try
      (let [resp (read-json-line (:in handles))]
        (when-not (= "pong" (:type resp))
          (throw (ex-info "Python bridge did not respond to ping"
                          {:code :connection-refused :response resp}))))
      (finally
        (.setSoTimeout ^Socket (:socket handles) 0)))
    {:config   session-config
     :backend  :python
     :status   :connected
     :handles  handles
     :internal {}}))

(defmethod backend/close! :python
  [live-state]
  (when-let [sock (get-in live-state [:handles :socket])]
    (.close ^Socket sock))
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
  ;; Send SIGINT to the bridge process
  (if-let [pid (get-in live-state [:config :launch :pid])]
    (do
      (try
        (-> (ProcessBuilder. ["kill" "-2" (str pid)]) .start .waitFor)
        (catch Exception _))
      :ok)
    {:error   true
     :code    "unsupported"
     :message "Interrupt requires a launched session (need PID)"
     :detail  {}}))
