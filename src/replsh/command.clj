(ns replsh.command
  (:require [replsh.backend :as backend]
            [replsh.backend.nrepl]
            [replsh.backend.node]
            [replsh.backend.jupyter]
            [replsh.backend.python]
            [replsh.bridge :as bridge]
            [replsh.process :as process]
            [replsh.state :as state]
            [replsh.output :as output]
            [replsh.util :as util]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect]))

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
                    :python  (merge {:type :tcp} (util/parse-address address)))
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
  [{:keys [backend-type name host port cmd cwd env kernel token prompt-re init timeout]}]
  (let [effective-cwd (or cwd (System/getProperty "user.dir"))
        ;; Substitute template variables in cmd if not already resolved by config
        cmd (-> cmd
                (str/replace "{port}" (str port))
                (str/replace "{host}" (or host "localhost"))
                (str/replace "{cwd}" effective-cwd)
                (str/replace "{bridge}" (try (bridge/ensure-bridge!)
                                             (catch Exception _ ""))))
        ;; 1. Spawn the server process
        {:keys [pid process]} (process/spawn! {:cmd      cmd
                                               :cwd      effective-cwd
                                               :env-vars (or env {})
                                               :name     name})]
    (try
      ;; 2. Wait for port/HTTP readiness
      (case backend-type
        (:nrepl :node :python)
        (process/wait-for-port! {:host       (or host "localhost")
                                 :port       port
                                 :timeout-ms (or timeout 30000)
                                 :process    process})
        :jupyter
        (let [url (str "http://" (or host "localhost") ":" port)]
          (process/wait-for-http! {:url        url
                                   :timeout-ms (or timeout 30000)
                                   :process    process})))
      ;; 3. Build session config
      (let [transport (case backend-type
                        :nrepl   {:type :tcp :host (or host "localhost") :port port}
                        :jupyter {:type :http
                                  :url (str "http://" (or host "localhost") ":" port)
                                  :token token}
                        :node    {:type :tcp :host (or host "localhost") :port port}
                        :python  {:type :tcp :host (or host "localhost") :port port})
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
  [{:keys [name code timeout hard-timeout stream?]}]
  (let [state      (state/load-state)
        session    (state/get-session state name)]
    (when-not session
      (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                      {:code :session-not-found})))
    (let [;; Compute effective backend timeout as min(soft, hard)
          ;; --timeout 0 means "no soft timeout" (wait forever unless hard timeout set)
          soft-ms    (let [t (or timeout 30000)] (if (zero? t) Long/MAX_VALUE t))
          hard-ms    (if (and hard-timeout (pos? hard-timeout)) hard-timeout Long/MAX_VALUE)
          effective-timeout (min soft-ms hard-ms)
          hard-deadline (when (and hard-timeout (pos? hard-timeout))
                          (+ (System/currentTimeMillis) hard-timeout))
          live-state (backend/open! session)
          msg-id     (util/gen-id "eval")
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
      (let [new-session (assoc session :internal (:internal live-state))]
        (backend/close! live-state)
        (state/put-session! state new-session)
        (let [clean-chunks (mapv #(select-keys % [:type :content :stream :meta]) chunks)]
          (if hard-expired?
            ;; Hard timeout — return as error (exit code 3)
            (cond-> (output/failure :eval {:code    "timeout"
                                           :message "Hard timeout: eval interrupted"
                                           :data    {:name   (:name session)
                                                     :chunks clean-chunks}})
              stream? (assoc :stream? true))
            ;; Normal or soft timeout
            (let [value-chunk (last (filter #(= :value (:type %)) chunks))
                  has-error?  (some #(= :error (:type %)) chunks)
                  result (if has-error?
                           (let [err-chunk (first (filter #(= :error (:type %)) chunks))]
                             (output/failure :eval {:code    "eval_error"
                                                    :message (:content err-chunk)
                                                    :detail  (:meta err-chunk)
                                                    :data    {:name   (:name session)
                                                              :chunks clean-chunks}}))
                           (output/success :eval
                                           (cond-> {:name   (:name session)
                                                    :chunks clean-chunks}
                                             value-chunk (assoc :value (:content value-chunk))
                                             (get-in value-chunk [:meta :ns]) (assoc :ns (get-in value-chunk [:meta :ns])))))]
              (cond-> result
                timed-out? (assoc :partial true)
                stream?    (assoc :stream? true)))))))))

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
              (:nrepl :node :python)
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
    ;; For backends that interrupt via PID (python, node), send SIGINT directly
    ;; without opening a connection (which would block if the bridge is busy).
    (if-let [pid (and (#{:python :node} (:backend session))
                      (get-in session [:launch :pid]))]
      (do
        (try
          (-> (ProcessBuilder. ["kill" "-2" (str pid)]) .start .waitFor)
          (catch Exception _))
        (output/success :interrupt {:name (:name session)}))
      ;; For other backends, open a connection to send the interrupt protocol message
      (let [live-state (backend/open! session)
            result     (backend/interrupt! live-state)]
        (backend/close! live-state)
        (if (= :ok result)
          (output/success :interrupt {:name (:name session)})
          (output/failure :interrupt result))))))

;; --- Background eval ---

(def ^:private evals-dir
  (str (System/getProperty "user.home") "/.replsh/evals/"))

(defn- ensure-evals-dir! []
  (.mkdirs (File. evals-dir)))

(defn eval-bg-cmd
  "Fork a background eval child process. Returns immediately with eval-id."
  [{:keys [name code timeout hard-timeout]}]
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
        cmd-args   (cond-> ["bb" "-m" "replsh.main"
                            "eval" "--name" name
                            "--stream"
                            "--file" code-file
                            "--bg-child" eval-id]
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
      ;; Follow mode: tail jsonl, emit chunks until done
      (loop [emitted 0]
        (let [fresh-meta (edn/read-string (slurp meta-file))
              lines      (when (.exists (File. jsonl-file))
                           (remove str/blank? (str/split-lines (slurp jsonl-file))))
              new-lines  (drop emitted lines)
              parsed     (mapv #(json/parse-string % true) new-lines)
              final-line (first (filter :final parsed))
              chunks     (vec (remove :final parsed))]
          (doseq [c chunks] (output/emit-chunk! c))
          (if final-line
            ;; Child wrote its summary — re-emit it as our result
            (assoc (dissoc final-line :final) :stream? true)
            (if (#{:completed :failed :timeout} (:status fresh-meta))
              ;; Meta says done but no final line — child crashed
              (cond-> (output/success :output {:eval-id eval-id
                                                :status  (clojure.core/name (:status fresh-meta))
                                                :session (:session fresh-meta)})
                true (assoc :stream? true))
              ;; Still running — wait and poll
              (do
                (Thread/sleep 200)
                (recur (+ emitted (count chunks))))))))
      ;; Non-follow: dump all at once
      (let [meta-data (edn/read-string (slurp meta-file))
            lines     (when (.exists (File. jsonl-file))
                        (remove str/blank? (str/split-lines (slurp jsonl-file))))
            parsed    (mapv #(json/parse-string % true) lines)
            final-line (first (filter :final parsed))
            chunks    (vec (remove :final parsed))]
        (output/success :output (cond-> {:eval-id    eval-id
                                          :status     (clojure.core/name (:status meta-data))
                                          :session    (:session meta-data)
                                          :started-at (:started-at meta-data)
                                          :ended-at   (:ended-at meta-data)
                                          :chunks     chunks}
                                  final-line (assoc :summary (dissoc final-line :final))))))))

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

;; --- Logs ---

(def ^:private logs-dir
  (str (System/getProperty "user.home") "/.replsh/logs/"))

(defn logs-cmd
  "Read process logs for a session."
  [{:keys [name tail follow]}]
  (let [state   (state/load-state)
        session (state/get-session state name)
        sname   (or name (:active state))
        _       (when-not sname
                  (throw (ex-info "No session name provided and no active session"
                                  {:code :missing-arg})))
        log-file (str logs-dir sname ".log")]
    (when-not (.exists (File. log-file))
      (throw (ex-info (str "No log file for session: " sname)
                      {:code :log-not-found})))
    (if follow
      ;; Follow mode: tail log, emit lines as chunks
      (let [pid (when session (get-in session [:launch :pid]))]
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
                       (or (nil? pid) (not (process/alive? pid))))
                (cond-> (output/success :logs {:name sname :lines (count (str/split-lines content))})
                  true (assoc :stream? true))
                (do
                  (Thread/sleep 200)
                  (recur new-offset)))))))
      ;; Non-follow: dump lines
      (let [content (slurp log-file)
            lines   (str/split-lines content)
            lines   (if tail (vec (take-last tail lines)) (vec lines))]
        (output/success :logs {:name  sname
                                :lines (count lines)
                                :content (str/join "\n" lines)})))))
