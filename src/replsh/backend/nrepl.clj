(ns replsh.backend.nrepl
  (:require [replsh.backend :as backend]
            [replsh.transport.tcp :as tcp]
            [replsh.util :as util]))

(defn- send-msg
  "Send an nREPL message and return the msg-id."
  [handles msg]
  (let [msg-id (or (get msg "id") (util/gen-id "msg"))]
    (tcp/send-bencode handles (assoc msg "id" msg-id))
    msg-id))

(defn- read-responses
  "Read nREPL responses until we see {:status [\"done\"]} for our msg-id.
   Returns a vector of decoded response maps."
  [handles msg-id timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [responses []]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for nREPL response"
                        {:code :timeout :msg-id msg-id})))
      (let [raw  (tcp/read-bencode handles)
            resp (tcp/bytes->str raw)]
        (if (not= (get resp "id") msg-id)
          ;; not our message, skip it
          (recur responses)
          (let [responses (conj responses resp)
                status    (get resp "status")]
            (if (and (sequential? status) (some #{"done"} status))
              responses
              (recur responses))))))))

(defn- responses->chunks
  "Convert nREPL response maps to replsh response chunks."
  [responses session-name msg-id]
  (let [chunks (atom [])]
    (doseq [resp responses]
      (when-let [out (get resp "out")]
        (swap! chunks conj {:type :out :content out :stream :stdout
                            :meta {} :done? false
                            :msg-id msg-id :name session-name}))
      (when-let [err (get resp "err")]
        (swap! chunks conj {:type :err :content err :stream :stderr
                            :meta {} :done? false
                            :msg-id msg-id :name session-name}))
      (when-let [val (get resp "value")]
        (swap! chunks conj {:type :value :content val
                            :meta (cond-> {}
                                    (get resp "ns") (assoc :ns (get resp "ns")))
                            :done? false
                            :msg-id msg-id :name session-name}))
      (when-let [ex (get resp "ex")]
        (swap! chunks conj {:type :error :content (or (get resp "root-ex") ex)
                            :meta (cond-> {:ex ex}
                                    (get resp "root-ex") (assoc :root-ex (get resp "root-ex")))
                            :done? false
                            :msg-id msg-id :name session-name})))
    (let [cs @chunks]
      (if (seq cs)
        (update cs (dec (count cs)) assoc :done? true)
        [{:type :status :content "" :meta {} :done? true
          :msg-id msg-id :name session-name}]))))

;; --- Multimethod implementations ---

(defmethod backend/open! :nrepl
  [session-config]
  (let [{:keys [host port]} (:transport session-config)
        handles  (tcp/open-socket host port)
        ;; Clone to get/reuse an nREPL session
        existing-sid (get-in session-config [:internal :session-id])
        session-id (if existing-sid
                     ;; Verify existing session is still valid
                     existing-sid
                     ;; Create new session via clone
                     (let [msg-id (send-msg handles {"op" "clone"})
                           resps  (read-responses handles msg-id 5000)]
                       (some #(get % "new-session") resps)))]
    {:config  session-config
     :backend :nrepl
     :status  :connected
     :handles handles
     :internal {:session-id session-id}}))

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
  (let [{:keys [code name timeout-ms msg-id]} request
        sid     (get-in live-state [:internal :session-id])
        handles (:handles live-state)
        mid     (send-msg handles {"op"      "eval"
                                   "code"    code
                                   "session" sid
                                   "id"      msg-id})
        resps   (read-responses handles mid (or timeout-ms 30000))]
    (responses->chunks resps name mid)))

(defmethod backend/interrupt! :nrepl
  [live-state]
  (let [handles (:handles live-state)
        sid     (get-in live-state [:internal :session-id])
        msg-id  (send-msg handles {"op"      "interrupt"
                                   "session" sid})]
    (read-responses handles msg-id 5000)
    :ok))
