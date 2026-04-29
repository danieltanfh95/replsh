(ns replsh.command
  (:require [replsh.backend :as backend]
            [replsh.backend.bash]
            [replsh.backend.nrepl]
            [replsh.backend.node]
            [replsh.backend.jupyter]
            [replsh.backend.python]
            [replsh.bridge :as bridge]
            [replsh.config :as config]
            [replsh.process :as process]
            [replsh.runtime :as runtime]
            [replsh.state :as state]
            [replsh.output :as output]
            [replsh.util :as util]
            [replsh.watch :as watch]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as edamame])
  (:import [java.io File RandomAccessFile]
           [java.lang ProcessBuilder$Redirect]
           [java.nio.charset StandardCharsets]))

;; Forward declaration: stop-cmd is defined after launch-cmd but
;; launch-cmd's --force branch needs to call it.
(declare stop-cmd)

(defn- require-session! [session name]
  (when-not session
    (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                    {:code :session-not-found}))))

(defn- port-open?
  "Return true if `host:port` accepts TCP connections right now."
  [host port]
  (try
    (with-open [_ (java.net.Socket. ^String host ^int port)]
      true)
    (catch java.io.IOException _ false)))

(defn- session-reachable?
  "A session is 'live' if either its runtime is alive OR its transport
   still answers. Either is enough to risk confusion on a silent
   relaunch."
  [existing]
  (or (try (runtime/alive? (runtime/session->runtime-info existing))
           (catch Exception _ false))
      (let [{:keys [host port]} (:transport existing)]
        (when (and host port)
          (port-open? host port)))))

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
                    :node    (merge {:type :tcp} (util/parse-address address))
                    :python  (merge {:type :tcp} (util/parse-address address))
                    :bash    (merge {:type :tcp} (util/parse-address address)))
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
        ;; Persist with internal state from open (e.g., nREPL session-id).
        ;; If the backend probed the connected runtime for its real cwd,
        ;; trust that over the user-supplied metadata.
        session-config (cond-> (assoc session-config :internal (:internal live-state))
                         (get-in live-state [:internal :actual-cwd])
                         (assoc-in [:env :cwd] (get-in live-state [:internal :actual-cwd])))]
    ;; Run init code if provided — abort on failure
    (when init
      (try
        (run-init! session-config live-state init)
        (catch Exception e
          (backend/close! live-state)
          (try (backend/destroy! session-config) (catch Exception _))
          (throw e))))
    (backend/close! live-state)
    (state/update-session! name (constantly session-config))
    (output/success :start {:name    name
                            :backend (clojure.core/name backend-type)
                            :env     (:env session-config)})))

(defn launch-cmd
  "Spawn a REPL server process, wait for readiness, connect, and register session."
  [{:keys [backend-type name host port cmd cwd env image volumes kernel token prompt-re init timeout force]}]
  (let [runtime-type (if image :docker :local)
        is-docker?   (= runtime-type :docker)
        effective-cwd (or cwd (System/getProperty "user.dir"))
        ;; --- Fix 0: same-name collision check ---
        ;; If a session with this name already exists and is still
        ;; reachable, refuse to silently overwrite it.
        _ (when-let [existing (state/get-session (state/load-state) name)]
            (cond
              (not force)
              (if (session-reachable? existing)
                (throw (ex-info
                         (format (str "session %s already exists and is reachable. "
                                      "Stop it first with `replsh stop %s`, or pass "
                                      "--force to replace it. "
                                      "(previous: runtime=%s transport=%s)")
                                 name name
                                 (:runtime existing)
                                 (:transport existing))
                         {:code :session-exists :name name :existing existing}))
                ;; Old entry is a corpse — clean up silently and proceed.
                ;; remove-session! is atomic and persists.
                (state/remove-session! name))

              ;; --force: stop the old one first, then proceed.
              :else
              (try (stop-cmd {:name name})
                   (catch Exception e
                     (binding [*out* *err*]
                       (println "warning: --force stop failed, proceeding anyway:"
                                (.getMessage e)))))))
        ;; --- Fix 1: pre-spawn port collision check (local runtime only) ---
        ;; Docker publishes its own host port; let the daemon handle that.
        _ (when (and (not is-docker?) host port (port-open? host port))
            (throw (ex-info
                     (format (str "port %s:%s is already listening — something is "
                                  "bound there and a silent-collision launch would "
                                  "route you to the existing process instead of the "
                                  "new one. Kill the existing listener (try "
                                  "`lsof -iTCP:%s -sTCP:LISTEN`) or choose a "
                                  "different --port.")
                             host port port)
                     {:code :port-already-in-use :host host :port port})))
        ;; Template substitution for CLI-only launches (config-resolved cmds are already substituted)
        cmd (cond-> cmd
              (str/includes? cmd "{port}")
              (str/replace "{port}" (str (or port "")))
              true
              (str/replace "{host}" (or host "localhost"))
              is-docker?
              (str/replace "{cwd}" (or effective-cwd "/workspace"))
              (not is-docker?)
              (str/replace "{cwd}" effective-cwd))
        ;; Build runtime spawn config
        spawn-config (cond-> {:runtime runtime-type :cmd cmd :name name}
                       (= runtime-type :local)
                       (assoc :cwd effective-cwd :env-vars (or env {}))

                       is-docker?
                       (assoc :image image
                              :port port
                              :env-vars (or env {})
                              :container-cwd "/workspace"))

        ;; For Docker: add bridge mount and substitute container bridge path in cmd
        spawn-config (if is-docker?
                       (let [host-bridge (try (bridge/ensure-bridge!) (catch Exception _ nil))
                             vols        (or volumes [])
                             cmd         (if host-bridge
                                           (str/replace cmd "{bridge}" "/tmp/replsh/replsh_bridge.py")
                                           cmd)
                             vols        (if host-bridge
                                           (conj vols (str host-bridge ":/tmp/replsh/replsh_bridge.py:ro"))
                                           vols)]
                         (assoc spawn-config :volumes vols :cmd cmd))
                       ;; Local: substitute bridge path
                       (update spawn-config :cmd str/replace "{bridge}"
                               (try (bridge/ensure-bridge!) (catch Exception _ ""))))

        ;; 1. Start the server
        runtime-info (runtime/spawn! spawn-config)]

    (try
      ;; 2. Discover actual host port
      ;;    For Docker: internal port → mapped host port. For local: same port.
      (let [host-port (or (runtime/mapped-port runtime-info port) port)]

        ;; 3. Wait for port/HTTP readiness (runtime-aware: docker checks container liveness)
        (case backend-type
          (:nrepl :node :python :bash)
          (runtime/wait-ready! runtime-info
                               {:host       (or host "localhost")
                                :port       host-port
                                :timeout-ms (or timeout 30000)
                                :check      :tcp})
          :jupyter
          (runtime/wait-ready! runtime-info
                               {:url        (str "http://" (or host "localhost") ":" host-port)
                                :timeout-ms (or timeout 30000)
                                :check      :http}))

        ;; Brief settle time for server to be protocol-ready
        (Thread/sleep 500)

        ;; 4. Build session config
        (let [transport (case backend-type
                          :nrepl   {:type :tcp :host (or host "localhost") :port host-port}
                          :jupyter {:type :http
                                    :url (str "http://" (or host "localhost") ":" host-port)
                                    :token token}
                          :node    {:type :tcp :host (or host "localhost") :port host-port}
                          :python  {:type :tcp :host (or host "localhost") :port host-port}
                          :bash    {:type :tcp :host (or host "localhost") :port host-port})
              ;; Launch info — runtime-specific identifiers
              launch-info (cond-> {:cmd cmd :cwd effective-cwd}
                            (:pid runtime-info)
                            (assoc :pid (:pid runtime-info))
                            (:container-id runtime-info)
                            (assoc :container-id (:container-id runtime-info))
                            is-docker?
                            (assoc :image image
                                   :volumes (:volumes spawn-config)
                                   :container-cwd "/workspace"))
              session-config {:name         name
                              :backend      backend-type
                              :runtime      runtime-type
                              :created-at   (util/timestamp)
                              :transport    transport
                              :env          {:cwd  effective-cwd
                                             :vars (or env {})}
                              :launch       launch-info
                              :backend-opts (cond-> {}
                                              kernel    (assoc :kernel-name kernel)
                                              prompt-re (assoc :prompt-re prompt-re))
                              :internal     {}}
              session-config (cond-> session-config
                               init (assoc :init-code init))
              ;; 5. Verify connectivity
              live-state     (backend/open! session-config)
              ;; If the backend probed the connected runtime for its real
              ;; cwd (Fix 2), trust that over the spawn-time metadata.
              session-config (cond-> (assoc session-config :internal (:internal live-state))
                               (get-in live-state [:internal :actual-cwd])
                               (assoc-in [:env :cwd] (get-in live-state [:internal :actual-cwd])))]
          ;; 6. Run init code if provided
          (when init
            (try
              (run-init! session-config live-state init)
              (catch Exception e
                (backend/close! live-state)
                (runtime/stop! runtime-info)
                (throw e))))
          (backend/close! live-state)
          (state/update-session! name (constantly session-config))
          (output/success :launch (cond-> {:name    name
                                           :backend (clojure.core/name backend-type)
                                           :runtime (clojure.core/name runtime-type)
                                           :env     (:env session-config)}
                                    (:pid runtime-info)
                                    (assoc :pid (:pid runtime-info))
                                    (:container-id runtime-info)
                                    (assoc :container-id (:container-id runtime-info))))))
      (catch Exception e
        ;; Stop orphaned process/container on any failure
        (runtime/stop! runtime-info)
        (throw e)))))

(defn- exec-via-container-id
  "Extract container-id from a :via vector (innermost docker layer)."
  [via]
  (last (keep #(when (= :docker (:type %)) (:container %)) via)))

(defn- cleanup-bridge-zombies!
  "Sweep any prior bridge processes bound to exec-port inside the runtime
   target. Best-effort: errors are swallowed so launch/restart can proceed."
  [runtime-info bridge-path exec-port]
  (try
    (let [cmd     (str "python3 " bridge-path " --cleanup-port " exec-port)
          handles (runtime/exec! runtime-info cmd {})]
      (.waitFor ^Process (:process handles)))
    (catch Exception _)))

(defn- verify-bridge-ping!
  "Spawn an ephemeral proxy, send a ping, await pong, then destroy the proxy.
   Throws with fail-code on timeout or bad response."
  [runtime-info bridge-path exec-port timeout-ms fail-code]
  (let [proxy-handles (runtime/exec! runtime-info
                                     (str "python3 " bridge-path " --connect 127.0.0.1:" exec-port)
                                     {})]
    (try
      (.println ^java.io.PrintWriter (:out proxy-handles)
                (json/generate-string {:op "ping" :msg_id "ping"}))
      (.flush ^java.io.PrintWriter (:out proxy-handles))
      (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 10000))]
        (loop []
          (when (> (System/currentTimeMillis) deadline)
            (throw (ex-info "Bridge did not respond to ping"
                            {:code fail-code})))
          (if (.ready ^java.io.BufferedReader (:in proxy-handles))
            (let [line (.readLine ^java.io.BufferedReader (:in proxy-handles))]
              (when-not (and line (.contains ^String line "pong"))
                (throw (ex-info "Bridge ping failed"
                                {:code fail-code :response line}))))
            (do (Thread/sleep 100) (recur)))))
      (finally
        (.destroy ^Process (:process proxy-handles))))))

(defn launch-exec-cmd!
  "Launch exec-mode session: inject REPL bridge via an ordered via chain.
   The bridge runs persistently at the end of the chain; each eval opens an ephemeral proxy."
  [{:keys [backend-type name via env volumes platform init timeout exec-port]}]
  (let [exec-port    (or exec-port 9876)
        exec-via     (remove #(= :bash (:type %)) (or via []))
        docker-layer (some #(when (= :docker (:type %)) %) exec-via)
        owned?       (boolean (and docker-layer (:image docker-layer)))

        ;; Step 1: Verify existing container or spawn a new one, updating :via accordingly
        via (cond
              owned?
              ;; Spawn new container — spawn! returns updated :via with container-id
              (let [spawn-result (runtime/spawn! {:runtime  :exec
                                                  :name     name
                                                  :via      via
                                                  :env-vars env
                                                  :volumes  volumes
                                                  :platform platform})]
                (Thread/sleep 1000)
                (when-not (runtime/alive? {:runtime :exec :name name :via (:via spawn-result)})
                  (runtime/stop! {:runtime :exec :name name :via (:via spawn-result)})
                  (throw (ex-info "Container exited immediately"
                                  {:code :launch-failed :image (:image docker-layer)})))
                (:via spawn-result))

              docker-layer
              ;; Existing container — verify it's running
              (do
                (when-not (runtime/alive? {:runtime :exec :name name :via via})
                  (throw (ex-info (str "Container not running: " (:container docker-layer))
                                  {:code :container-not-running :container (:container docker-layer)})))
                via)

              :else via)

        runtime-info {:runtime :exec :name name :via via}]
    (try
      ;; Step 2: Deploy bridge into the end of the via chain
      (let [bridge-path (runtime/deploy-bridge! runtime-info)
            ;; Step 2b: Sweep any orphaned prior bridges still bound to exec-port
            _           (cleanup-bridge-zombies! runtime-info bridge-path exec-port)

            ;; Step 3: Start persistent bridge server — host Process wrapping the exec chain.
            ;; Killing the host Process kills the bridge.
            bridge-cmd  (str "python3 " bridge-path " --port " exec-port)
            bridge-proc (runtime/exec! runtime-info bridge-cmd {})]

        ;; Wait for bridge to start listening
        (Thread/sleep 1000)

        ;; Step 4: Verify readiness via temporary proxy + ping
        (verify-bridge-ping! runtime-info bridge-path exec-port (or timeout 10000) :launch-failed)

        ;; Step 5: Build and persist session
        (let [bridge-pid    (.pid ^Process (:process bridge-proc))
              container-id  (exec-via-container-id via)
              session-config {:name         name
                              :backend      backend-type
                              :runtime      :exec
                              :created-at   (util/timestamp)
                              :transport    {:type :exec :exec-port exec-port}
                              :env          {:cwd  (System/getProperty "user.dir")
                                             :vars (or env {})}
                              :launch       {:via         via
                                             :bridge-pid  bridge-pid
                                             :bridge-path bridge-path
                                             :exec-cmd    bridge-cmd
                                             :owned       owned?}
                              :backend-opts {}
                              :internal     {}}
              session-config (cond-> session-config
                               init (assoc :init-code init))
              ;; Verify full eval connectivity
              live-state     (backend/open! session-config)
              session-config (assoc session-config :internal (:internal live-state))]
          ;; Run init code if provided
          (when init
            (try
              (run-init! session-config live-state init)
              (catch Exception e
                (backend/close! live-state)
                (.destroy ^Process (:process bridge-proc))
                (when owned? (runtime/stop! runtime-info))
                (throw e))))
          (backend/close! live-state)
          (state/update-session! name (constantly session-config))
          (output/success :launch (cond-> {:name    name
                                           :backend (clojure.core/name backend-type)
                                           :runtime "exec"
                                           :owned   owned?
                                           :env     (:env session-config)}
                                    container-id (assoc :container-id container-id)))))
      (catch Exception e
        ;; Clean up on failure
        (when owned? (runtime/stop! runtime-info))
        (throw e)))))

(defn eval-cmd
  "Evaluate code in a named session."
  [{:keys [name code timeout hard-timeout stream? chunked?]}]
  (let [state      (state/load-state)
        session    (state/get-session state name)]
    (require-session! session name)
    (let [;; Compute effective backend timeout as min(soft, hard)
          ;; --timeout 0 means "no soft timeout" (wait forever unless hard timeout set)
          soft-ms    (let [t (or timeout 30000)] (if (zero? t) Long/MAX_VALUE t))
          hard-ms    (if (and hard-timeout (pos? hard-timeout)) hard-timeout Long/MAX_VALUE)
          effective-timeout (min soft-ms hard-ms)
          hard-deadline (when (and hard-timeout (pos? hard-timeout))
                          (+ (System/currentTimeMillis) hard-timeout))
          live-state     (backend/open! session)
          detect-result  (watch/detect-stale session live-state)
          msg-id         (util/gen-id "eval")
          on-chunk   (when stream? output/emit-chunk!)
          request    {:code       code
                      :name       (:name session)
                      :backend    (:backend session)
                      :timeout-ms effective-timeout
                      :msg-id     msg-id
                      :on-chunk   on-chunk}
          chunks     (backend/eval! request live-state)
          timed-out? (:timed-out? (meta chunks))
          ;; Hard timeout: if soft timed out and hard deadline passed, interrupt
          hard-expired? (and timed-out? hard-deadline
                             (>= (System/currentTimeMillis) hard-deadline))]
      (when hard-expired?
        (try (backend/interrupt! live-state) (catch Exception _)))
      ;; Update internal state (e.g., nREPL ns may have changed)
      (let [eval-status   (cond
                            hard-expired? "timeout"
                            timed-out?    "partial"
                            (some #(= :error (:type %)) chunks) "error"
                            :else "complete")
            history-entry {:code   code
                           :ts     (System/currentTimeMillis)
                           :status eval-status}
            mtimes        (:loaded-mtimes detect-result)
            new-internal  (:internal live-state)
            now           (System/currentTimeMillis)]
        (backend/close! live-state)
        ;; Atomic merge against the freshest session record. If the session
        ;; was concurrently removed, skip the persist (return nil).
        (state/update-session! name
          (fn [latest]
            (when latest
              (-> latest
                  (assoc :internal new-internal)
                  (assoc :last-eval-at now)
                  (cond-> mtimes (assoc :loaded-mtimes mtimes))
                  (update :history
                          (fn [h] (vec (take-last 100 (conj (or h []) history-entry)))))))))
        (let [clean-chunks (mapv #(select-keys % [:type :content :stream :meta]) chunks)
              ;; Join stdout for top-level :output field
              stdout (->> clean-chunks
                          (filter #(= :out (:type %)))
                          (map :content)
                          (str/join))]
          (if hard-expired?
            ;; Hard timeout — return as error (exit code 3)
            (cond-> (output/failure :eval {:code    "timeout"
                                           :message "Hard timeout: eval interrupted"
                                           :data    (cond-> {:name (:name session)}
                                                     (seq stdout) (assoc :output stdout)
                                                     chunked?     (assoc :chunks clean-chunks))})
              true    (assoc :status "partial")
              stream? (assoc :stream? true))
            ;; Normal or soft timeout
            (let [value-chunk (last (filter #(= :value (:type %)) chunks))
                  has-error?  (some #(= :error (:type %)) chunks)
                  result (if has-error?
                           (let [err-chunk (first (filter #(= :error (:type %)) chunks))
                                 ;; Prefer :err chunks (actual error text) over :error chunk
                                 ;; (which is just the class name for nREPL)
                                 err-text  (->> chunks
                                                (filter #(= :err (:type %)))
                                                (map :content)
                                                (str/join)
                                                str/trim)
                                 message   (if (seq err-text) err-text (:content err-chunk))]
                             (output/failure :eval {:code    "eval_error"
                                                    :message message
                                                    :detail  (:meta err-chunk)
                                                    :data    (cond-> {:name (:name session)}
                                                              (seq stdout) (assoc :output stdout)
                                                              chunked?     (assoc :chunks clean-chunks))}))
                           (output/success :eval
                                           (cond-> {:name (:name session)}
                                             value-chunk              (assoc :value (:content value-chunk))
                                             (seq stdout)             (assoc :output stdout)
                                             chunked?                 (assoc :chunks clean-chunks)
                                             (get-in value-chunk [:meta :ns]) (assoc :ns (get-in value-chunk [:meta :ns]))
                                             (:stale detect-result)   (assoc :stale (:stale detect-result)))))]
              (cond-> result
                (not timed-out?) (assoc :status "complete")
                timed-out?       (assoc :status "partial")
                stream?          (assoc :stream? true)))))))))

(defn ls-cmd
  "List all sessions."
  []
  (let [state (state/load-state)]
    (output/success :ls {:sessions (mapv (fn [[_ s]]
                                          (let [s   (runtime/normalize-session s)
                                                cid (exec-via-container-id (get-in s [:launch :via]))]
                                            (cond-> {:name    (:name s)
                                                     :backend (clojure.core/name (:backend s))
                                                     :runtime (clojure.core/name (:runtime s))
                                                     :env     (:env s)}
                                              (get-in s [:launch :pid])
                                              (assoc :pid (get-in s [:launch :pid]))
                                              ;; Legacy docker sessions still have :container-id directly
                                              (get-in s [:launch :container-id])
                                              (assoc :container-id (get-in s [:launch :container-id]))
                                              ;; Exec sessions store container-id in :via
                                              cid
                                              (assoc :container-id cid))))
                                        (:sessions state))
                         :active   (:active state)})))

(defn stop-cmd
  "Stop and remove a named session."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (if (= :exec (get-in session [:transport :type]))
      ;; Exec-mode session
      (let [bridge-pid (get-in session [:launch :bridge-pid])
            owned?     (get-in session [:launch :owned])]
        ;; Kill bridge wrapper process
        (when bridge-pid
          (try
            (replsh.process/kill! bridge-pid)
            (catch Exception _)))
        ;; If we own the container, stop it too
        (when owned?
          (try
            (runtime/stop! (runtime/session->runtime-info session))
            (catch Exception _)))
        (state/remove-session! (:name session))
        (output/success :stop {:name (:name session)}))
      ;; Port-mode / non-exec session (existing behavior)
      (do
        (when (:launch session)
          (try
            (runtime/stop! (runtime/session->runtime-info session))
            (catch Exception _)))
        (try
          (backend/destroy! session)
          (catch Exception _))
        (state/remove-session! (:name session))
        (output/success :stop {:name (:name session)})))))

(defn restart-cmd
  "Restart a named session (destroy + re-open)."
  [{:keys [name timeout]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (cond
      ;; Exec-mode session: kill bridge → re-deploy → re-start
      (= :exec (get-in session [:transport :type]))
      (let [bridge-pid   (get-in session [:launch :bridge-pid])
            exec-port    (get-in session [:transport :exec-port])
            runtime-info (-> (runtime/session->runtime-info session) (assoc :name name))
            container-id (exec-via-container-id (:via runtime-info))]
        ;; Kill existing bridge
        (when bridge-pid
          (try (process/kill! bridge-pid) (catch Exception _)))
        ;; Re-deploy bridge (in case container was restarted)
        (let [bridge-path  (runtime/deploy-bridge! runtime-info)
              ;; Sweep any orphaned prior bridges still bound to exec-port
              _            (cleanup-bridge-zombies! runtime-info bridge-path exec-port)
              bridge-cmd   (str "python3 " bridge-path " --port " exec-port)
              bridge-proc  (runtime/exec! runtime-info bridge-cmd {})]
          (Thread/sleep 1000)
          ;; Verify with proxy ping
          (verify-bridge-ping! runtime-info bridge-path exec-port 10000 :restart-failed)
          ;; Update session with new bridge PID
          (let [new-bridge-pid (.pid ^Process (:process bridge-proc))
                open-config    (-> session
                                   (assoc :internal {})
                                   (assoc-in [:launch :bridge-pid] new-bridge-pid)
                                   (assoc-in [:launch :bridge-path] bridge-path)
                                   (assoc-in [:launch :exec-cmd] bridge-cmd))
                live-state     (backend/open! open-config)
                new-internal   (:internal live-state)]
            (when-let [init-code (:init-code session)]
              (run-init! (assoc open-config :internal new-internal) live-state init-code))
            (backend/close! live-state)
            (state/update-session! name
              (fn [latest]
                (when latest
                  (-> latest
                      (assoc :internal new-internal)
                      (assoc-in [:launch :bridge-pid] new-bridge-pid)
                      (assoc-in [:launch :bridge-path] bridge-path)
                      (assoc-in [:launch :exec-cmd] bridge-cmd)))))
            (output/success :restart {:name         name
                                      :backend      (clojure.core/name (:backend session))
                                      :runtime      "exec"
                                      :mode         "exec"
                                      :container-id container-id}))))

      ;; Launched (port-mode) session: stop → re-spawn → wait → reconnect
      (:launch session)
      (let [{:keys [cmd cwd]} (:launch session)
            runtime-type (or (:runtime session) :local)
            env-vars     (get-in session [:env :vars])]
        ;; Stop existing
        (try
          (runtime/stop! (runtime/session->runtime-info session))
          (catch Exception _))
        ;; Re-spawn
        (let [spawn-config (cond-> {:runtime runtime-type :cmd cmd :name name}
                             (= runtime-type :local)
                             (assoc :cwd cwd :env-vars env-vars)

                             (= runtime-type :docker)
                             (assoc :image (get-in session [:launch :image])
                                    :volumes (get-in session [:launch :volumes])
                                    :env-vars env-vars
                                    :container-cwd (or (get-in session [:launch :container-cwd]) "/workspace")))
              runtime-info (runtime/spawn! spawn-config)]
          (try
            (let [host-port (or (runtime/mapped-port runtime-info
                                                     (get-in session [:transport :port]))
                                (get-in session [:transport :port]))]
              (case (:backend session)
                (:nrepl :node :python :bash)
                (runtime/wait-ready! runtime-info
                                     {:host (get-in session [:transport :host])
                                      :port host-port
                                      :timeout-ms (or timeout 30000)
                                      :check :tcp})
                :jupyter
                (runtime/wait-ready! runtime-info
                                     {:url (get-in session [:transport :url])
                                      :timeout-ms (or timeout 30000)
                                      :check :http}))
              (let [launch-info (cond-> {:cmd cmd :cwd cwd}
                                  (:pid runtime-info)
                                  (assoc :pid (:pid runtime-info))
                                  (:container-id runtime-info)
                                  (assoc :container-id (:container-id runtime-info))
                                  (get-in session [:launch :image])
                                  (assoc :image (get-in session [:launch :image])))
                    open-config  (-> session
                                     (cond-> (not= host-port (get-in session [:transport :port]))
                                       (assoc-in [:transport :port] host-port))
                                     (assoc :internal {})
                                     (assoc :launch launch-info))
                    live-state   (backend/open! open-config)
                    new-internal (:internal live-state)]
                ;; Re-run init code if present
                (when-let [init-code (:init-code session)]
                  (run-init! (assoc open-config :internal new-internal) live-state init-code))
                (backend/close! live-state)
                (state/update-session! (:name session)
                  (fn [latest]
                    (when latest
                      (-> latest
                          (cond-> (not= host-port (get-in latest [:transport :port]))
                            (assoc-in [:transport :port] host-port))
                          (assoc :launch launch-info)
                          (assoc :internal new-internal)))))
                (output/success :restart (cond-> {:name    (:name session)
                                                  :backend (clojure.core/name (:backend session))
                                                  :runtime (clojure.core/name runtime-type)}
                                           (:pid runtime-info)
                                           (assoc :pid (:pid runtime-info))
                                           (:container-id runtime-info)
                                           (assoc :container-id (:container-id runtime-info))))))
            (catch Exception e
              (runtime/stop! runtime-info)
              (throw e)))))

      ;; Non-launched session: existing behavior
      :else
      (do
        (try (backend/destroy! session) (catch Exception _))
        (let [open-config  (assoc session :internal {})
              live-state   (backend/open! open-config)
              new-internal (:internal live-state)]
          ;; Re-run init code if present
          (when-let [init-code (:init-code session)]
            (run-init! (assoc open-config :internal new-internal) live-state init-code))
          (backend/close! live-state)
          (state/update-session! (:name session)
            (fn [latest]
              (when latest
                (assoc latest :internal new-internal))))
          (output/success :restart {:name (:name session)
                                    :backend (clojure.core/name (:backend session))}))))))

(defn status-cmd
  "Show detailed status for a named session."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (let [session    (runtime/normalize-session session)
          rt-info    (runtime/session->runtime-info session)
          reachable? (try
                       (let [ls (backend/open! session)]
                         (backend/close! ls)
                         true)
                       (catch Exception _ false))]
      (output/success :status (cond-> {:name      (:name session)
                                       :backend   (clojure.core/name (:backend session))
                                       :runtime   (clojure.core/name (:runtime session))
                                       :transport (:transport session)
                                       :env       (:env session)
                                       :reachable reachable?}
                               (and (:launch session) (= :exec (get-in session [:transport :type])))
                               (assoc :launch
                                      (let [via          (:via rt-info)
                                            container-id (exec-via-container-id via)
                                            bridge-pid   (get-in session [:launch :bridge-pid])]
                                        (cond-> {:mode  "exec"
                                                 :alive (runtime/alive? rt-info)
                                                 :owned (boolean (get-in session [:launch :owned]))
                                                 :via   via}
                                          bridge-pid   (assoc :bridge-pid   bridge-pid
                                                              :bridge-alive (process/alive? bridge-pid))
                                          container-id (assoc :container-id container-id))))
                               (and (:launch session) (not= :exec (get-in session [:transport :type])))
                               (assoc :launch
                                      (cond-> {:cmd   (get-in session [:launch :cmd])
                                               :alive (runtime/alive? rt-info)}
                                        (get-in session [:launch :pid])
                                        (assoc :pid (get-in session [:launch :pid]))
                                        (get-in session [:launch :container-id])
                                        (assoc :container-id (get-in session [:launch :container-id])))))))))

(defn interrupt-cmd
  "Interrupt a running eval."
  [{:keys [name]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (let [session (runtime/normalize-session session)]
      (cond
        ;; Exec-mode: send SIGINT to the bridge wrapper Process (host PID)
        (and (= :exec (get-in session [:transport :type]))
             (get-in session [:launch :bridge-pid]))
        (do
          (try
            (let [bridge-pid (get-in session [:launch :bridge-pid])]
              ;; Send SIGINT to the bridge wrapper — Docker propagates into container
              (-> (ProcessBuilder. ["kill" "-INT" (str bridge-pid)]) .start .waitFor))
            (catch Exception _))
          (output/success :interrupt {:name (:name session)}))

        ;; Port-mode backends that interrupt via signal (python, node, bash)
        (and (#{:python :node :bash} (:backend session)) (:launch session))
        (do
          (try
            (runtime/send-signal! (runtime/session->runtime-info session) "INT")
            (catch Exception _))
          (output/success :interrupt {:name (:name session)}))

        ;; Other backends: open a connection to send the interrupt protocol message
        :else
        (let [live-state (backend/open! session)
              result     (backend/interrupt! live-state)]
          (backend/close! live-state)
          (if (= :ok result)
            (output/success :interrupt {:name (:name session)})
            (output/failure :interrupt result)))))))

;; --- Background eval ---

(def ^:private evals-dir
  (str (System/getProperty "user.home") "/.replsh/evals/"))

(defn- find-replsh-binary
  "Resolve the replsh entry point. Returns a vector of command args.
   Prefers the installed `replsh` binary; falls back to `bb -m replsh.main`."
  []
  (try
    (let [pb  (ProcessBuilder. ["which" "replsh"])
          p   (.start pb)
          out (str/trim (slurp (.getInputStream p)))]
      (.waitFor p)
      (if (seq out) [out] ["bb" "-m" "replsh.main"]))
    (catch Exception _ ["bb" "-m" "replsh.main"])))

(defn- ensure-evals-dir! []
  (.mkdirs (File. evals-dir)))

(defn eval-bg-cmd
  "Fork a background eval child process. Returns immediately with eval-id."
  [{:keys [name code timeout hard-timeout chunked?]}]
  (ensure-evals-dir!)
  (let [eval-id    (util/gen-id "eval")
        code-file  (str evals-dir eval-id ".code")
        jsonl-file (str evals-dir eval-id ".jsonl")
        meta-file  (str evals-dir eval-id ".meta.edn")
        _          (spit code-file code)
        meta-data  {:eval-id    eval-id
                    :session    name
                    :status     :running
                    :started-at (util/timestamp)}
        _          (spit meta-file (pr-str meta-data))
        cmd-args   (cond-> (into (find-replsh-binary)
                                 ["eval" "--name" name
                                  "--stream"
                                  "--file" code-file
                                  "--bg-child" eval-id])
                     chunked?
                     (into ["--chunked"])
                     (and timeout (pos? timeout))
                     (into ["--timeout" (str timeout)])
                     (and hard-timeout (pos? hard-timeout))
                     (into ["--hard-timeout" (str hard-timeout)]))
        pb         (doto (ProcessBuilder. ^java.util.List cmd-args)
                     (.redirectOutput (ProcessBuilder$Redirect/to (File. jsonl-file)))
                     (.redirectErrorStream true)
                     (.directory (File. (System/getProperty "user.dir"))))
        process    (.start pb)
        pid        (.pid process)]
    ;; Update meta with PID
    (spit meta-file (pr-str (assoc meta-data :pid pid)))
    (output/success :eval-bg {:eval-id eval-id
                               :session name
                               :pid     pid})))

(defn finalize-bg-eval!
  "Update meta file after a background eval child completes."
  [eval-id result]
  (let [meta-file (str evals-dir eval-id ".meta.edn")]
    (try
      (let [current-meta (edn/read-string (slurp meta-file))
            status       (cond
                           (:ok result)                                 :completed
                           (= "timeout" (get-in result [:error :code])) :timeout
                           :else                                        :failed)]
        (spit meta-file (pr-str (assoc current-meta
                                       :status   status
                                       :ended-at (util/timestamp)))))
      (catch Exception _))))

(defn output-cmd
  "Read output from a background eval."
  [{:keys [eval-id follow]}]
  (let [meta-file  (str evals-dir eval-id ".meta.edn")
        jsonl-file (str evals-dir eval-id ".jsonl")]
    (when-not (.exists (File. meta-file))
      (throw (ex-info (str "Eval not found: " eval-id)
                      {:code :eval-not-found})))
    (if follow
      ;; Follow mode: tail jsonl from last byte offset (efficient seek-based approach)
      (loop [emitted 0 byte-pos 0]
        (let [fresh-meta   (edn/read-string (slurp meta-file))
              f            (File. jsonl-file)
              file-len     (when (.exists f) (.length f))
              new-content  (when (and file-len (> file-len byte-pos))
                             (with-open [raf (RandomAccessFile. f "r")]
                               (.seek raf byte-pos)
                               (let [buf (byte-array (- file-len byte-pos))]
                                 (.readFully raf buf)
                                 (String. buf StandardCharsets/UTF_8))))
              new-lines    (when new-content
                             (remove str/blank? (str/split-lines new-content)))
              new-byte-pos (or file-len byte-pos)
              parsed       (mapv #(json/parse-string % true) (or new-lines []))
              summary-line (first (filter :ok parsed))
              chunks       (vec (remove :ok parsed))]
          (doseq [c chunks] (output/emit-chunk! c))
          (if summary-line
            ;; Child wrote its summary — re-emit it as our result
            (assoc summary-line :stream? true)
            (if (#{:completed :failed :timeout} (:status fresh-meta))
              ;; Meta says done but no final line — child crashed
              (cond-> (output/success :output {:eval-id eval-id
                                                :status  (clojure.core/name (:status fresh-meta))
                                                :session (:session fresh-meta)})
                true (assoc :stream? true))
              ;; Still running — wait and poll
              (do
                (Thread/sleep 200)
                (recur (+ emitted (count chunks)) new-byte-pos))))))
      ;; Non-follow: dump all at once
      (let [meta-data (edn/read-string (slurp meta-file))
            lines     (when (.exists (File. jsonl-file))
                        (remove str/blank? (str/split-lines (slurp jsonl-file))))
            parsed    (mapv #(json/parse-string % true) lines)
            summary-line (first (filter :ok parsed))
            chunks       (vec (remove :ok parsed))]
        (output/success :output (cond-> {:eval-id    eval-id
                                          :status     (clojure.core/name (:status meta-data))
                                          :session    (:session meta-data)
                                          :started-at (:started-at meta-data)
                                          :ended-at   (:ended-at meta-data)
                                          :chunks     chunks}
                                  summary-line (assoc :summary summary-line)))))))

(defn evals-cmd
  "List all background evals."
  []
  (ensure-evals-dir!)
  (let [files (->> (.listFiles (File. evals-dir))
                   (filter #(str/ends-with? (.getName %) ".meta.edn"))
                   (sort-by #(.lastModified %) >))
        evals (mapv (fn [f]
                      (try
                        (let [m (edn/read-string (slurp f))]
                          (cond-> {:eval-id    (:eval-id m)
                                   :session    (:session m)
                                   :status     (clojure.core/name (:status m))
                                   :started-at (:started-at m)}
                            (:ended-at m) (assoc :ended-at (:ended-at m))
                            (:pid m)      (assoc :pid (:pid m))))
                        (catch Exception _
                          {:eval-id (.getName f) :status "corrupt"})))
                    files)]
    (output/success :evals {:evals evals})))

;; --- Toolchains ---

(defn toolchains-cmd
  "List all available toolchains (built-in + user-defined)."
  []
  (let [global-cfg (config/load-global-config)
        all-tc     (config/resolve-toolchains global-cfg)]
    (output/success :toolchains
      {:toolchains (vec (sort-by :name
                          (map (fn [[tc-name tc-spec]]
                                 {:name    tc-name
                                  :backend (name (:backend tc-spec))
                                  :runtime (name (or (:runtime tc-spec) :local))
                                  :port    (get-in tc-spec [:defaults :port])})
                               all-tc)))})))

;; --- Logs ---

(defn logs-cmd
  "Read process logs for a session."
  [{:keys [name tail follow]}]
  (let [state   (state/load-state)
        session (state/get-session state name)
        sname   (or name (:active state))
        _       (when-not sname
                  (throw (ex-info "No session name provided and no active session"
                                  {:code :missing-arg})))
        session (when session (runtime/normalize-session session))
        is-docker? (and session (= :docker (:runtime session)))
        is-exec?   (and session (= :exec   (:runtime session)))]
    (cond
      ;; Docker session: use docker logs
      is-docker?
      (let [rt-info (runtime/session->runtime-info session)
            content (runtime/logs rt-info {:tail tail :follow follow})]
        (if follow
          (let [lines (when content (str/split-lines content))]
            (doseq [line lines]
              (output/emit-chunk! {:type :out :content (str line "\n")
                                   :stream :stdout :meta {}}))
            (cond-> (output/success :logs {:name sname :lines (count lines)})
              true (assoc :stream? true)))
          (let [lines (when content (str/split-lines content))]
            (output/success :logs {:name    sname
                                   :lines   (count lines)
                                   :content (or content "")}))))

      ;; Exec-mode session: bridge stderr lives in <name>.exec.log
      is-exec?
      (let [rt-info (runtime/session->runtime-info session)
            content (runtime/logs rt-info {:tail tail})]
        (when-not content
          (throw (ex-info (str "No log file for session: " sname)
                          {:code :log-not-found})))
        (let [lines (str/split-lines content)]
          (output/success :logs {:name    sname
                                 :lines   (count lines)
                                 :content content})))

      ;; Follow mode: tail log file, emit lines as chunks
      follow
      (let [log-file (str util/log-dir sname ".log")]
        (when-not (.exists (File. log-file))
          (throw (ex-info (str "No log file for session: " sname)
                          {:code :log-not-found})))
        (let [rt-info (runtime/session->runtime-info session)]
          (loop [offset 0]
            (let [content  (slurp log-file)
                  new-part (subs content (min offset (count content)))
                  lines    (when (pos? (count new-part))
                             (str/split-lines new-part))]
              (doseq [line lines]
                (output/emit-chunk! {:type :out :content (str line "\n")
                                     :stream :stdout :meta {}}))
              (let [new-offset (count content)]
                ;; If process is dead and no new data, we're done
                (if (and (= new-offset offset)
                         (not (runtime/alive? rt-info)))
                  (cond-> (output/success :logs {:name sname :lines (count (str/split-lines content))})
                    true (assoc :stream? true))
                  (do
                    (Thread/sleep 200)
                    (recur new-offset))))))))

      ;; Non-follow: dump lines from local log file
      :else
      (let [log-file (str util/log-dir sname ".log")]
        (when-not (.exists (File. log-file))
          (throw (ex-info (str "No log file for session: " sname)
                          {:code :log-not-found})))
        (let [content (if tail
                        (process/read-log-tail log-file tail)
                        (slurp log-file))
              lines   (str/split-lines content)]
          (output/success :logs {:name    sname
                                 :lines   (count lines)
                                 :content (str/join "\n" lines)}))))))

;; --- History ---

(defn history-cmd
  "Show recent eval history for a session."
  [{:keys [name format]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (let [history (or (:history session) [])]
      (if (= format "script")
        ;; Script format: one form per line, separated by blank line
        (do (println (str/join "\n\n" (map :code history))) nil)
        ;; Default: structured JSON
        (output/success :history {:name    (:name session)
                                   :history history})))))

;; --- Replay ---

(defn- split-forms
  "Split code into a sequence of individual forms for the given backend."
  [backend code]
  (case backend
    :nrepl   (mapv pr-str
                   (edamame/parse-string-all code {:all true :read-cond :allow :eof nil}))
    :python  (str/split code #"\n# ---\n")
    :jupyter (str/split code #"\n# ---\n")
    :node    (str/split code #"\n// ---\n")
    [code]))

(defn replay-cmd
  "Send each top-level form from code to a session sequentially, collecting per-form results."
  [{:keys [name code timeout hard-timeout]}]
  (let [state   (state/load-state)
        session (state/get-session state name)]
    (require-session! session name)
    (let [forms   (split-forms (:backend session) code)
          results (mapv (fn [form]
                          (eval-cmd {:name         name
                                     :code         form
                                     :timeout      timeout
                                     :hard-timeout hard-timeout}))
                        forms)]
      (output/success :replay {:name    name
                                :forms   (count forms)
                                :results results}))))
