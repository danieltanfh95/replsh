(ns replsh.command
  (:require [replsh.backend :as backend]
            [replsh.backend.nrepl]
            [replsh.backend.node]
            [replsh.backend.jupyter]
            [replsh.process :as process]
            [replsh.state :as state]
            [replsh.output :as output]
            [replsh.util :as util]))

(defn- run-init!
  "Execute init code on a live connection. Throws on error."
  [session-config live-state init-code]
  (let [msg-id  (util/gen-id "init")
        request {:code       init-code
                 :name       (:name session-config)
                 :backend    (:backend session-config)
                 :timeout-ms 10000
                 :msg-id     msg-id}
        chunks  (backend/eval! request live-state)]
    (when-let [err (first (filter #(= :error (:type %)) chunks))]
      (throw (ex-info (str "Init code failed: " (:content err))
                      {:code   :init-failed
                       :detail (:meta err)})))))

(defn start-cmd
  "Register a new named session and verify connectivity."
  [{:keys [backend-type name address url cwd env kernel token prompt-re init]}]
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
        session-config (cond-> session-config
                         init (assoc :init-code init))
        ;; Verify connectivity by opening and immediately getting session info
        live-state (backend/open! session-config)
        ;; Persist with internal state from open (e.g., nREPL session-id)
        session-config (assoc session-config :internal (:internal live-state))
        state (state/load-state)]
    ;; Run init code if provided — abort on failure
    (when init
      (try
        (run-init! session-config live-state init)
        (catch Exception e
          (backend/close! live-state)
          (try (backend/destroy! session-config) (catch Exception _))
          (throw e))))
    (backend/close! live-state)
    (state/put-session! state session-config)
    (output/success :start {:name    name
                            :backend (clojure.core/name backend-type)
                            :env     (:env session-config)})))

(defn launch-cmd
  "Spawn a REPL server process, wait for readiness, connect, and register session."
  [{:keys [backend-type name host port cmd cwd env kernel token prompt-re init]}]
  (let [effective-cwd (or cwd (System/getProperty "user.dir"))
        ;; 1. Spawn the server process
        {:keys [pid process]} (process/spawn! {:cmd      cmd
                                               :cwd      effective-cwd
                                               :env-vars (or env {})
                                               :name     name})]
    (try
      ;; 2. Wait for port/HTTP readiness
      (case backend-type
        (:nrepl :node)
        (process/wait-for-port! {:host       (or host "localhost")
                                 :port       port
                                 :timeout-ms 30000
                                 :process    process})
        :jupyter
        (let [url (str "http://" (or host "localhost") ":" port)]
          (process/wait-for-http! {:url        url
                                   :timeout-ms 30000
                                   :process    process})))
      ;; 3. Build session config
      (let [transport (case backend-type
                        :nrepl   {:type :tcp :host (or host "localhost") :port port}
                        :jupyter {:type :http
                                  :url (str "http://" (or host "localhost") ":" port)
                                  :token token}
                        :node    {:type :tcp :host (or host "localhost") :port port})
            session-config {:name         name
                            :backend      backend-type
                            :created-at   (util/timestamp)
                            :transport    transport
                            :env          {:cwd  effective-cwd
                                           :vars (or env {})}
                            :launch       {:cmd cmd
                                           :cwd effective-cwd
                                           :pid pid}
                            :backend-opts (cond-> {}
                                           kernel    (assoc :kernel-name kernel)
                                           prompt-re (assoc :prompt-re prompt-re))
                            :internal     {}}
            session-config (cond-> session-config
                             init (assoc :init-code init))
            ;; 4. Verify connectivity
            live-state     (backend/open! session-config)
            session-config (assoc session-config :internal (:internal live-state))
            state          (state/load-state)]
        ;; 5. Run init code if provided
        (when init
          (try
            (run-init! session-config live-state init)
            (catch Exception e
              (backend/close! live-state)
              (process/kill! pid)
              (throw e))))
        (backend/close! live-state)
        (state/put-session! state session-config)
        (output/success :launch {:name    name
                                 :backend (clojure.core/name backend-type)
                                 :pid     pid
                                 :env     (:env session-config)}))
      (catch Exception e
        ;; Kill orphaned process on any failure
        (process/kill! pid)
        (throw e)))))

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
    ;; Kill launched process if present
    (when-let [pid (get-in session [:launch :pid])]
      (process/kill! pid))
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
    (if (:launch session)
      ;; Launched session: kill → re-spawn → wait → reconnect
      (let [{:keys [cmd cwd pid]} (:launch session)
            env-vars (get-in session [:env :vars])]
        (process/kill! pid)
        (let [{new-pid :pid new-process :process}
              (process/spawn! {:cmd cmd :cwd cwd :env-vars env-vars :name name})]
          (try
            (case (:backend session)
              (:nrepl :node)
              (let [{:keys [host port]} (:transport session)]
                (process/wait-for-port! {:host host :port port
                                         :timeout-ms 30000 :process new-process}))
              :jupyter
              (let [url (get-in session [:transport :url])]
                (process/wait-for-http! {:url url :timeout-ms 30000 :process new-process})))
            (let [fresh-config (-> session
                                   (assoc :internal {})
                                   (assoc-in [:launch :pid] new-pid))
                  live-state   (backend/open! fresh-config)
                  new-session  (assoc fresh-config :internal (:internal live-state))]
              ;; Re-run init code if present
              (when-let [init-code (:init-code session)]
                (run-init! new-session live-state init-code))
              (backend/close! live-state)
              (state/put-session! state new-session)
              (output/success :restart {:name    (:name session)
                                        :backend (clojure.core/name (:backend session))
                                        :pid     new-pid}))
            (catch Exception e
              (process/kill! new-pid)
              (throw e)))))
      ;; Non-launched session: existing behavior
      (do
        (try (backend/destroy! session) (catch Exception _))
        (let [fresh-config (assoc session :internal {})
              live-state   (backend/open! fresh-config)
              new-session  (assoc fresh-config :internal (:internal live-state))]
          ;; Re-run init code if present
          (when-let [init-code (:init-code session)]
            (run-init! new-session live-state init-code))
          (backend/close! live-state)
          (state/put-session! state new-session)
          (output/success :restart {:name (:name session)
                                    :backend (clojure.core/name (:backend session))}))))))

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
      (output/success :status (cond-> {:name      (:name session)
                                       :backend   (clojure.core/name (:backend session))
                                       :transport (:transport session)
                                       :env       (:env session)
                                       :reachable reachable?}
                               (:launch session)
                               (assoc :launch {:pid   (get-in session [:launch :pid])
                                               :alive (process/alive? (get-in session [:launch :pid]))
                                               :cmd   (get-in session [:launch :cmd])}))))))

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
