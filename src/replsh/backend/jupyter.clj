(ns replsh.backend.jupyter
  (:require [replsh.backend :as backend]
            [replsh.transport.http :as http]
            [replsh.transport.ws :as ws]
            [replsh.util :as util]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- api-url
  "Build a Jupyter API URL."
  [base-url & path-parts]
  (let [base (str/replace base-url #"/+$" "")]
    (str base "/api/" (str/join "/" path-parts))))

(defn- ws-url
  "Build a Jupyter WebSocket URL from the HTTP base URL."
  [base-url kernel-id token]
  (let [ws-base (-> base-url
                    (str/replace #"^http://" "ws://")
                    (str/replace #"^https://" "wss://")
                    (str/replace #"/+$" ""))]
    (cond-> (str ws-base "/api/kernels/" kernel-id "/channels")
      token (str "?token=" token))))

(defn- make-header
  "Build a Jupyter message header."
  [msg-type session-id]
  {:msg_id   (util/gen-id "jmsg")
   :msg_type msg-type
   :username "replsh"
   :session  session-id
   :version  "5.3"
   :date     (util/timestamp)})

(defn- make-execute-request
  "Build an execute_request message for the Jupyter kernel."
  [code session-id msg-id]
  {:channel       "shell"
   :header        (assoc (make-header "execute_request" session-id)
                         :msg_id msg-id)
   :parent_header {}
   :metadata      {}
   :content       {:code             code
                   :silent           false
                   :store_history    true
                   :user_expressions {}
                   :allow_stdin      false
                   :stop_on_error    true}})

(defn- collect-responses
  "Read WebSocket messages until we get an execute_reply for our msg-id.
   Returns vector of response chunks.
   When on-chunk is provided, calls it per chunk for streaming."
  [ws-conn msg-id session-name timeout-ms on-chunk]
  (let [deadline (if (pos? timeout-ms)
                   (+ (System/currentTimeMillis) timeout-ms)
                   Long/MAX_VALUE)]
    (loop [chunks []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (when (<= remaining 0)
          (throw (ex-info "Timeout waiting for Jupyter response"
                          {:code :timeout :msg-id msg-id
                           :chunks chunks})))
        (let [msg (ws/recv-json ws-conn (min remaining 1000))]
          (if-not msg
            (recur chunks)
            (let [parent-msg-id (get-in msg [:parent_header :msg_id])
                  msg-type      (get-in msg [:header :msg_type])
                  ;; Accumulate chunk if this message belongs to our request
                  chunks (if (= parent-msg-id msg-id)
                           (let [chunk (case msg-type
                                         "stream"
                                         (let [stream-name (get-in msg [:content :name])
                                               text        (get-in msg [:content :text])]
                                           {:type    (if (= "stderr" stream-name) :err :out)
                                            :content text
                                            :stream  (keyword stream-name)
                                            :meta    {}
                                            :done?   false
                                            :msg-id  msg-id
                                            :name    session-name})

                                         "execute_result"
                                         (let [data (get-in msg [:content :data :text/plain])]
                                           {:type    :value
                                            :content (or data "")
                                            :meta    {:execution_count (get-in msg [:content :execution_count])}
                                            :done?   false
                                            :msg-id  msg-id
                                            :name    session-name})

                                         "error"
                                         (let [ename  (get-in msg [:content :ename])
                                               evalue (get-in msg [:content :evalue])
                                               tb     (get-in msg [:content :traceback])]
                                           {:type    :error
                                            :content (str ename ": " evalue)
                                            :meta    {:ename ename :evalue evalue
                                                      :traceback (vec tb)}
                                            :done?   false
                                            :msg-id  msg-id
                                            :name    session-name})

                                         ;; Ignore other message types
                                         nil)]
                             (if chunk
                               (do (when on-chunk (on-chunk chunk))
                                   (conj chunks chunk))
                               chunks))
                           chunks)]
              ;; Check if we got execute_reply
              (if (and (= parent-msg-id msg-id)
                       (= msg-type "execute_reply"))
                ;; Done — finalize chunks
                (if (seq chunks)
                  (update chunks (dec (count chunks)) assoc :done? true)
                  [{:type    :status
                    :content ""
                    :meta    {:status (get-in msg [:content :status])}
                    :done?   true
                    :msg-id  msg-id
                    :name    session-name}])
                (recur chunks)))))))))

;; --- Multimethod implementations ---

(defmethod backend/open! :jupyter
  [session-config]
  (let [{:keys [url token]} (:transport session-config)
        kernel-id (get-in session-config [:internal :kernel-id])]
    (if kernel-id
      ;; Existing kernel — just open WebSocket
      (let [ws-conn (ws/connect (ws-url url kernel-id token))]
        {:config   session-config
         :backend  :jupyter
         :status   :connected
         :handles  {:ws ws-conn :base-url url :token token}
         :internal {:kernel-id kernel-id}})
      ;; New kernel — create via REST then open WebSocket
      (let [kernel-name (get-in session-config [:backend-opts :kernel-name] "python3")
            env-vars    (get-in session-config [:env :vars])
            body        (cond-> {:name kernel-name}
                          (seq env-vars) (assoc :env env-vars))
            result      (http/post-json (api-url url "kernels") body :token token)
            kid         (:id result)
            ws-conn     (ws/connect (ws-url url kid token))]
        {:config   session-config
         :backend  :jupyter
         :status   :connected
         :handles  {:ws ws-conn :base-url url :token token}
         :internal {:kernel-id kid}}))))

(defmethod backend/close! :jupyter
  [live-state]
  (when-let [ws-conn (get-in live-state [:handles :ws])]
    (ws/close! ws-conn))
  nil)

(defmethod backend/destroy! :jupyter
  [session-config]
  (let [{:keys [url token]} (:transport session-config)
        kernel-id (get-in session-config [:internal :kernel-id])]
    (when kernel-id
      (try
        (http/delete! (api-url url "kernels" kernel-id) :token token)
        (catch Exception _))))
  nil)

(defmethod backend/eval! :jupyter
  [request live-state]
  (let [{:keys [code name timeout-ms msg-id on-chunk]} request
        ws-conn   (get-in live-state [:handles :ws])
        session-id (util/gen-id "sess")
        exec-msg  (make-execute-request code session-id msg-id)]
    (ws/send-json ws-conn exec-msg)
    (try
      (collect-responses ws-conn msg-id name (or timeout-ms 30000) on-chunk)
      (catch clojure.lang.ExceptionInfo e
        (if (= :timeout (:code (ex-data e)))
          (let [partial-chunks (or (:chunks (ex-data e)) [])
                chunks (if (seq partial-chunks)
                         (update partial-chunks (dec (count partial-chunks))
                                 assoc :done? true)
                         [{:type :status :content "" :meta {} :done? true
                           :msg-id msg-id :name name}])]
            (with-meta chunks {:timed-out? true}))
          (throw e))))))

(defmethod backend/interrupt! :jupyter
  [live-state]
  (let [{:keys [base-url token]} (:handles live-state)
        kernel-id (get-in live-state [:internal :kernel-id])]
    (http/post-json (api-url base-url "kernels" kernel-id "interrupt") {} :token token)
    :ok))
