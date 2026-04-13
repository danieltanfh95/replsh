(ns replsh.backend.nrepl
  (:require [clojure.edn :as edn]
            [replsh.backend :as backend]
            [replsh.transport.tcp :as tcp]
            [replsh.util :as util])
  (:import [java.net SocketTimeoutException Socket]))

(defn- send-msg
  "Send an nREPL message and return the msg-id."
  [handles msg]
  (let [msg-id (or (get msg "id") (util/gen-id "msg"))]
    (tcp/send-bencode handles (assoc msg "id" msg-id))
    msg-id))

(defn- response->chunks
  "Convert a single nREPL response map to a seq of replsh chunks."
  [resp session-name msg-id]
  (cond-> []
    (get resp "out")
    (conj {:type :out :content (get resp "out") :stream :stdout
           :meta {} :done? false :msg-id msg-id :name session-name})
    (get resp "err")
    (conj {:type :err :content (get resp "err") :stream :stderr
           :meta {} :done? false :msg-id msg-id :name session-name})
    (get resp "value")
    (conj {:type :value :content (get resp "value")
           :meta (cond-> {} (get resp "ns") (assoc :ns (get resp "ns")))
           :done? false :msg-id msg-id :name session-name})
    (get resp "ex")
    (conj {:type :error :content (or (get resp "root-ex") (get resp "ex"))
           :meta (cond-> {:ex (get resp "ex")}
                   (get resp "root-ex") (assoc :root-ex (get resp "root-ex")))
           :done? false :msg-id msg-id :name session-name})))

(defn- read-eval-responses
  "Read nREPL responses until done, converting to chunks as they arrive.
   When on-chunk is provided, calls it per chunk for streaming.
   Returns the accumulated chunks vector.
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
          (throw (ex-info "Timeout waiting for nREPL response"
                          {:code :timeout :msg-id msg-id
                           :chunks all-chunks})))
        (let [resp (try
                     (let [raw (tcp/read-bencode handles)]
                       (tcp/bytes->str raw))
                     (catch SocketTimeoutException _
                       nil))]
          (if-not resp
            (recur all-chunks)
            (if (not= (get resp "id") msg-id)
              (recur all-chunks)
              (let [new-chunks (response->chunks resp session-name msg-id)
                    _          (when on-chunk
                                 (doseq [c new-chunks] (on-chunk c)))
                    all-chunks (into all-chunks new-chunks)
                    status     (get resp "status")]
                (if (and (sequential? status) (some #{"done"} status))
                  ;; Finalize — mark last chunk as done
                  (if (seq all-chunks)
                    (update all-chunks (dec (count all-chunks)) assoc :done? true)
                    [{:type :status :content "" :meta {} :done? true
                      :msg-id msg-id :name session-name}])
                  (recur all-chunks)))))))
      (finally
        (.setSoTimeout sock 0)))))

(defn- read-responses
  "Read raw nREPL responses until done. Used for non-eval ops (clone, interrupt).
   On timeout, throws with accumulated :responses in ex-data."
  [handles msg-id timeout-ms]
  (let [deadline (if (pos? timeout-ms)
                   (+ (System/currentTimeMillis) timeout-ms)
                   Long/MAX_VALUE)
        ^Socket sock (:socket handles)]
    (.setSoTimeout sock 500)
    (try
      (loop [responses []]
        (when (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timeout waiting for nREPL response"
                          {:code :timeout :msg-id msg-id
                           :responses responses})))
        (let [resp (try
                     (let [raw (tcp/read-bencode handles)]
                       (tcp/bytes->str raw))
                     (catch SocketTimeoutException _
                       nil))]
          (if-not resp
            (recur responses)
            (if (not= (get resp "id") msg-id)
              (recur responses)
              (let [responses (conj responses resp)
                    status    (get resp "status")]
                (if (and (sequential? status) (some #{"done"} status))
                  responses
                  (recur responses)))))))
      (finally
        (.setSoTimeout sock 0)))))

(defn- probe-cwd!
  "Ask the nrepl server for its runtime cwd. Returns a string or nil.
   Used to detect cwd drift between what replsh *thinks* the session is
   in vs what the connected runtime actually reports (Fix 2)."
  [handles session-id]
  (try
    (let [msg-id (send-msg handles {"op"      "eval"
                                    "code"    "(System/getProperty \"user.dir\")"
                                    "session" session-id})
          chunks (read-eval-responses handles msg-id 3000 "probe" nil)
          value  (->> chunks
                      (filter #(= :value (:type %)))
                      first
                      :content)]
      (when (string? value)
        ;; nREPL returns the value pre-escaped — strip one layer of quoting
        (edn/read-string value)))
    (catch Exception _ nil)))

;; --- Multimethod implementations ---

(defmethod backend/open! :nrepl
  [session-config]
  (let [{:keys [host port]} (:transport session-config)
        handles  (tcp/open-socket host port)
        ;; Clone to get/reuse an nREPL session
        existing-sid (get-in session-config [:internal :session-id])
        session-id (if existing-sid
                     ;; Reuse existing session ID; if the server was restarted externally,
                     ;; the next eval will fail and the user should run `replsh restart`.
                     existing-sid
                     ;; Create new session via clone
                     (let [msg-id (send-msg handles {"op" "clone"})
                           resps  (read-responses handles msg-id 5000)]
                       (some #(get % "new-session") resps)))
        ;; Probe the connected runtime for its real cwd. Failures (timeout,
        ;; odd runtime, nil response) fall through to nil and the caller
        ;; keeps user-supplied metadata.
        actual-cwd (probe-cwd! handles session-id)]
    {:config  session-config
     :backend :nrepl
     :status  :connected
     :handles handles
     :internal (cond-> {:session-id session-id}
                 actual-cwd (assoc :actual-cwd actual-cwd))}))

(defmethod backend/close! :nrepl
  [live-state]
  (tcp/close-socket (:handles live-state))
  nil)

(defmethod backend/destroy! :nrepl
  [session-config]
  ;; Open a connection, send close for the nREPL session, then disconnect
  (let [{:keys [host port]} (:transport session-config)
        sid (get-in session-config [:internal :session-id])]
    (when sid
      (let [handles (tcp/open-socket host port)]
        (try
          (let [msg-id (send-msg handles {"op" "close" "session" sid})]
            (read-responses handles msg-id 5000))
          (finally
            (tcp/close-socket handles))))))
  nil)

(defmethod backend/eval! :nrepl
  [request live-state]
  (let [{:keys [code name timeout-ms msg-id on-chunk]} request
        sid     (get-in live-state [:internal :session-id])
        handles (:handles live-state)
        mid     (send-msg handles {"op"      "eval"
                                   "code"    code
                                   "session" sid
                                   "id"      msg-id})]
    (try
      (read-eval-responses handles mid (or timeout-ms 30000) name on-chunk)
      (catch clojure.lang.ExceptionInfo e
        (if (= :timeout (:code (ex-data e)))
          (let [chunks (or (:chunks (ex-data e)) [])]
            (with-meta
              (if (seq chunks)
                chunks
                [{:type :status :content "" :meta {} :done? true
                  :msg-id mid :name name}])
              {:timed-out? true}))
          (throw e))))))

(defmethod backend/interrupt! :nrepl
  [live-state]
  (let [handles (:handles live-state)
        sid     (get-in live-state [:internal :session-id])
        msg-id  (send-msg handles {"op"      "interrupt"
                                   "session" sid})]
    ;; Best-effort read of interrupt response — short timeout since
    ;; we don't need the response, just want the interrupt sent
    (try
      (read-responses handles msg-id 1000)
      (catch Exception _))
    :ok))
