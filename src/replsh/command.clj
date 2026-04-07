(ns replsh.command
  (:require [replsh.backend :as backend]
            [replsh.backend.nrepl]
            [replsh.backend.node]
            [replsh.backend.jupyter]
            [replsh.state :as state]
            [replsh.output :as output]
            [replsh.util :as util]))

(defn start-cmd
  "Register a new named session and verify connectivity."
  [{:keys [backend-type name address url cwd env kernel token prompt-re]}]
  (let [transport (case backend-type
                    :nrepl   (merge {:type :tcp} (util/parse-address address))
                    :jupyter {:type :http :url url :token token}
                    :node    (merge {:type :tcp} (util/parse-address address)))
        session-config {:name         name
                        :backend      backend-type
                        :created-at   (util/timestamp)
                        :transport    transport
                        :env          {:cwd  (or cwd (System/getProperty "user.dir"))
                                       :vars (or env {})}
                        :backend-opts (cond-> {}
                                        kernel    (assoc :kernel-name kernel)
                                        prompt-re (assoc :prompt-re prompt-re))
                        :internal     {}}
        ;; Verify connectivity by opening and immediately getting session info
        live-state (backend/open! session-config)
        ;; Persist with internal state from open (e.g., nREPL session-id)
        session-config (assoc session-config :internal (:internal live-state))
        state (state/load-state)]
    (backend/close! live-state)
    (state/put-session! state session-config)
    (output/success :start {:name    name
                            :backend (clojure.core/name backend-type)
                            :env     (:env session-config)})))

(defn eval-cmd
  "Evaluate code in a named session."
  [{:keys [name code timeout]}]
  (let [state      (state/load-state)
        session    (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    (let [live-state (backend/open! session)
          msg-id     (util/gen-id "eval")
          request    {:code       code
                      :name       (:name session)
                      :backend    (:backend session)
                      :timeout-ms (or timeout 30000)
                      :msg-id     msg-id}
          chunks     (backend/eval! request live-state)
          ;; Update internal state (e.g., nREPL ns may have changed)
          new-session (assoc session :internal (:internal live-state))]
      (backend/close! live-state)
      (state/put-session! state new-session)
      (let [value-chunk (last (filter #(= :value (:type %)) chunks))
            has-error?  (some #(= :error (:type %)) chunks)]
        (if has-error?
          (let [err-chunk (first (filter #(= :error (:type %)) chunks))]
            (output/failure :eval {:code    "eval_error"
                                   :message (:content err-chunk)
                                   :detail  (:meta err-chunk)
                                   :data    {:name   (:name session)
                                             :chunks (mapv #(select-keys % [:type :content :stream :meta])
                                                           chunks)}}))
          (output/success :eval
                          (cond-> {:name   (:name session)
                                   :chunks (mapv #(select-keys % [:type :content :stream :meta])
                                                 chunks)}
                            value-chunk (assoc :value (:content value-chunk))
                            (get-in value-chunk [:meta :ns]) (assoc :ns (get-in value-chunk [:meta :ns])))))))))

(defn ls-cmd
  "List all sessions."
  []
  (let [state (state/load-state)]
    (output/success :ls {:sessions (mapv (fn [[_ s]]
                                          {:name    (:name s)
                                           :backend (clojure.core/name (:backend s))
                                           :env     (:env s)})
                                        (:sessions state))
                         :active   (:active state)})))

(defn stop-cmd
  "Stop and remove a named session."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    (try
      (backend/destroy! session)
      (catch Exception _))
    (state/remove-session! state (:name session))
    (output/success :stop {:name (:name session)})))

(defn restart-cmd
  "Restart a named session (destroy + re-open)."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    ;; Destroy old server-side resources
    (try
      (backend/destroy! session)
      (catch Exception _))
    ;; Re-open to get fresh internal state
    (let [fresh-config (assoc session :internal {})
          live-state   (backend/open! fresh-config)
          new-session  (assoc fresh-config :internal (:internal live-state))]
      (backend/close! live-state)
      (state/put-session! state new-session)
      (output/success :restart {:name (:name session)
                                :backend (clojure.core/name (:backend session))}))))

(defn status-cmd
  "Show detailed status for a named session."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    (let [reachable? (try
                       (let [ls (backend/open! session)]
                         (backend/close! ls)
                         true)
                       (catch Exception _ false))]
      (output/success :status {:name      (:name session)
                               :backend   (clojure.core/name (:backend session))
                               :transport (:transport session)
                               :env       (:env session)
                               :reachable reachable?}))))

(defn interrupt-cmd
  "Interrupt a running eval."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    (let [live-state (backend/open! session)
          result     (backend/interrupt! live-state)]
      (backend/close! live-state)
      (if (= :ok result)
        (output/success :interrupt {:name (:name session)})
        (output/failure :interrupt result)))))
