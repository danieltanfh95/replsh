(ns replsh.cli
  (:require [babashka.cli :as cli]
            [clojure.java.io :as io]
            [replsh.command :as cmd]
            [replsh.config :as config]
            [replsh.util :as util]))

(defn- parse-env [env]
  (when env
    (util/parse-env-args (if (string? env) [env] env))))

(defn- resolve-config
  "Load global + project config and resolve a session by name.
   Returns the resolved spec or nil if no config/session found."
  [session-name cli-opts]
  (when session-name
    (let [global-cfg  (config/load-global-config)
          project-cfg (config/load-project-config)
          toolchains  (config/resolve-toolchains global-cfg)]
      (when (get-in project-cfg [:config :sessions session-name])
        (config/resolve-session toolchains project-cfg session-name cli-opts)))))

(defn- start-handler
  [backend-type {:keys [opts args]}]
  (let [{:keys [name cwd env kernel token prompt-re init port]} opts
        address (first args)
        url     (first args)
        ;; Try config resolution
        resolved (resolve-config name (cond-> {}
                                        cwd       (assoc :cwd cwd)
                                        env       (assoc :env (parse-env env))
                                        init      (assoc :init init)
                                        port      (assoc :port port)
                                        kernel    (assoc :kernel kernel)
                                        token     (assoc :token token)
                                        prompt-re (assoc :prompt-re prompt-re)))]
    (if resolved
      ;; Config-resolved start
      (let [bt (or backend-type (:backend-type resolved))]
        (cmd/start-cmd {:backend-type bt
                        :name         name
                        :address      (when (#{:nrepl :node :python} bt)
                                        (str (or (:host resolved) "localhost")
                                             ":" (:port resolved)))
                        :url          (when (= :jupyter bt)
                                        (str "http://" (or (:host resolved) "localhost")
                                             ":" (:port resolved)))
                        :cwd          (:cwd resolved)
                        :env          (or (parse-env env) (:env resolved))
                        :kernel       (or kernel (:kernel resolved))
                        :token        (or token (:token resolved))
                        :prompt-re    (or prompt-re (:prompt-re resolved))
                        :init         (or init (:init resolved))}))
      ;; CLI-only start
      (do
        (when-not name
          (throw (ex-info "--name is required" {:code :missing-arg})))
        (when-not (first args)
          (throw (ex-info "Address or URL is required" {:code :missing-arg})))
        (cmd/start-cmd {:backend-type backend-type
                        :name         name
                        :address      (when (#{:nrepl :node :python} backend-type) address)
                        :url          (when (= :jupyter backend-type) url)
                        :cwd          cwd
                        :env          (parse-env env)
                        :kernel       kernel
                        :token        token
                        :prompt-re    prompt-re
                        :init         init})))))

(defn- exec-mode?
  "Detect exec mode: --container flag, or --image without --cmd."
  [opts resolved]
  (or (:container opts)
      (and (or (:image opts) (when resolved (:image resolved)))
           (not (or (:cmd opts) (when resolved (:cmd resolved)))))))

(defn- launch-handler
  [backend-type {:keys [opts args]}]
  (let [{:keys [name cmd cwd env image container volume kernel token prompt-re init port timeout exec-port platform force]} opts
        ;; Try config resolution first (without port) to detect runtime
        pre-resolved (resolve-config name (cond-> {}
                                            cmd       (assoc :cmd cmd)
                                            cwd       (assoc :cwd cwd)
                                            image     (assoc :image image)
                                            container (assoc :container container)
                                            env       (assoc :env (parse-env env))
                                            init      (assoc :init init)
                                            kernel    (assoc :kernel kernel)
                                            token     (assoc :token token)
                                            prompt-re (assoc :prompt-re prompt-re)))]
    (if (exec-mode? opts pre-resolved)
      ;; Exec mode: inject REPL into existing/new container
      (let [bt (or backend-type
                    (when pre-resolved (:backend-type pre-resolved))
                    :python)]
        (when-not name
          (throw (ex-info "--name is required" {:code :missing-arg})))
        (when-not (or container image (when pre-resolved (:image pre-resolved)))
          (throw (ex-info "--container or --image is required for exec mode"
                          {:code :missing-arg})))
        (cmd/launch-exec-cmd! {:backend-type bt
                               :name         name
                               :container    container
                               :image        (or image (when pre-resolved (:image pre-resolved)))
                               :env          (or (parse-env env) (when pre-resolved (:env pre-resolved)))
                               :volumes      (or (seq volume) (when pre-resolved (:volumes pre-resolved)))
                               :platform     (or platform (when pre-resolved (:platform pre-resolved)))
                               :init         (or init (when pre-resolved (:init pre-resolved)))
                               :timeout      timeout
                               :exec-port    exec-port}))
      ;; Port mode (existing behavior)
      (let [is-docker? (or image
                           (and pre-resolved (:image pre-resolved))
                           (and pre-resolved (= :docker (:runtime pre-resolved))))
            ;; Always pre-allocate a port — for local it's the server port,
            ;; for Docker it's the container-internal port used for -p mapping.
            port       (or port (util/find-free-port))
            ;; Resolve config again with port
            resolved   (resolve-config name (cond-> (if port {:port port} {})
                                              cmd       (assoc :cmd cmd)
                                              cwd       (assoc :cwd cwd)
                                              image     (assoc :image image)
                                              env       (assoc :env (parse-env env))
                                              init      (assoc :init init)
                                              kernel    (assoc :kernel kernel)
                                              token     (assoc :token token)
                                              prompt-re (assoc :prompt-re prompt-re)))]
        (if resolved
          ;; Config-resolved launch
          (let [bt (or backend-type (:backend-type resolved))]
            (when-not (:cmd resolved)
              (throw (ex-info "--cmd is required (not found in config either)"
                              {:code :missing-arg})))
            (cmd/launch-cmd {:backend-type bt
                             :name         name
                             :host         (or (:host resolved) "localhost")
                             :port         (:port resolved)
                             :cmd          (:cmd resolved)
                             :cwd          (:cwd resolved)
                             :image        (or image (:image resolved))
                             :volumes      (or (seq volume) (:volumes resolved))
                             :env          (or (parse-env env) (:env resolved))
                             :kernel       (or kernel (:kernel resolved))
                             :token        (or token (:token resolved))
                             :prompt-re    (or prompt-re (:prompt-re resolved))
                             :init         (or init (:init resolved))
                             :timeout      timeout
                             :force        force}))
          ;; CLI-only launch
          (do
            (when-not name
              (throw (ex-info "--name is required" {:code :missing-arg})))
            (when-not backend-type
              (throw (ex-info "Backend type required (e.g., replsh launch nrepl ...)"
                              {:code :missing-arg})))
            (when-not cmd
              (throw (ex-info "--cmd is required" {:code :missing-arg})))
            (cmd/launch-cmd {:backend-type backend-type
                             :name         name
                             :host         "localhost"
                             :port         port
                             :cmd          cmd
                             :cwd          cwd
                             :image        image
                             :volumes      volume
                             :env          (parse-env env)
                             :kernel       kernel
                             :token        token
                             :prompt-re    prompt-re
                             :init         init
                             :timeout      timeout
                             :force        force})))))))

(defn- eval-handler
  [{:keys [opts args]}]
  (let [file (:file opts)
        code (cond
               file          (slurp file)
               (first args)  (first args)
               :else         (let [input (slurp *in*)]
                               (when (empty? input)
                                 (throw (ex-info "No code provided (pass as argument, --file, or pipe to stdin)"
                                                 {:code :missing-arg})))
                               input))]
    (cond
      (:bg opts)
      (cmd/eval-bg-cmd {:name         (:name opts)
                         :code         code
                         :timeout      (:timeout opts)
                         :hard-timeout (:hard-timeout opts)
                         :chunked?     (:chunked opts)})

      (:bg-child opts)
      (let [result (cmd/eval-cmd {:name         (:name opts)
                                   :code         code
                                   :timeout      (:timeout opts)
                                   :hard-timeout (:hard-timeout opts)
                                   :stream?      true
                                   :chunked?     (:chunked opts)})]
        (cmd/finalize-bg-eval! (:bg-child opts) result)
        result)

      :else
      (cmd/eval-cmd {:name         (:name opts)
                     :code         code
                     :timeout      (:timeout opts)
                     :hard-timeout (:hard-timeout opts)
                     :stream?      (:stream opts)
                     :chunked?     (:chunked opts)}))))

(defn- ls-handler [_] (cmd/ls-cmd))

(defn- stop-handler
  [{:keys [opts args]}]
  (let [name (or (first args) (:name opts))]
    (cmd/stop-cmd {:name name})))

(defn- restart-handler
  [{:keys [opts args]}]
  (let [name (or (first args) (:name opts))]
    (cmd/restart-cmd {:name    name
                      :timeout (:timeout opts)})))

(defn- status-handler
  [{:keys [opts]}]
  (cmd/status-cmd {:name (:name opts)}))

(defn- interrupt-handler
  [{:keys [opts]}]
  (cmd/interrupt-cmd {:name (:name opts)}))

(defn- output-handler
  [{:keys [opts]}]
  (cmd/output-cmd {:eval-id (:eval-id opts)
                    :follow  (:follow opts)}))

(defn- evals-handler [_] (cmd/evals-cmd))

(defn- toolchains-handler [_] (cmd/toolchains-cmd))

(defn- logs-handler
  [{:keys [opts]}]
  (cmd/logs-cmd {:name   (:name opts)
                  :tail   (:tail opts)
                  :follow (:follow opts)}))

(defn- history-handler
  [{:keys [opts]}]
  (cmd/history-cmd {:name   (:name opts)
                    :format (:format opts)}))

(defn- replay-handler
  [{:keys [opts args]}]
  (let [file (:file opts)
        code (cond
               file          (slurp file)
               (first args)  (first args)
               :else         (let [input (slurp *in*)]
                               (when (empty? input)
                                 (throw (ex-info "No code provided (pass as argument, --file, or pipe to stdin)"
                                                 {:code :missing-arg})))
                               input))]
    (cmd/replay-cmd {:name         (:name opts)
                     :code         code
                     :timeout      (:timeout opts)
                     :hard-timeout (:hard-timeout opts)})))

(def ^:private base-spec
  {:name      {:alias :n :desc "Session name"}
   :port      {:alias :p :desc "Port number" :coerce :long}
   :cwd       {:desc "Working directory context"}
   :env       {:alias :e :desc "Environment var (K=V)" :coerce []}
   :init      {:alias :i :desc "Bootstrap code to run on session start"}})

(def ^:private launch-extra
  {:cmd       {:desc "Command to spawn the REPL server"}
   :image     {:desc "Docker image for containerized REPL"}
   :container {:alias :c :desc "Exec into existing Docker container"}
   :volume    {:alias :v :desc "Volume mount (host:container[:mode])" :coerce []}
   :platform  {:desc "Docker platform (e.g., linux/amd64)"}
   :exec-port {:desc "Bridge port inside container (exec mode)" :coerce :long :default 9876}
   :timeout   {:alias :t :desc "Port readiness timeout in ms" :coerce :long :default 30000}
   :force     {:alias :f :desc "Replace existing session with same name"
               :coerce :boolean}})

(def dispatch-table
  [;; Start (connect to existing server)
   {:cmds ["start" "nrepl"]   :fn (partial start-handler :nrepl)
    :spec base-spec}
   {:cmds ["start" "jupyter"] :fn (partial start-handler :jupyter)
    :spec (merge base-spec
                 {:token  {:desc "Jupyter auth token"}
                  :kernel {:alias :k :desc "Kernel name" :default "python3"}})}
   {:cmds ["start" "node"]    :fn (partial start-handler :node)
    :spec (merge base-spec
                 {:prompt-re {:desc "Prompt string to detect" :default "> "}})}
   {:cmds ["start" "python"]  :fn (partial start-handler :python)
    :spec base-spec}
   {:cmds ["start"]           :fn (partial start-handler nil)
    :spec base-spec}

   ;; Launch (spawn server + connect)
   {:cmds ["launch" "nrepl"]   :fn (partial launch-handler :nrepl)
    :spec (merge base-spec launch-extra)}
   {:cmds ["launch" "jupyter"] :fn (partial launch-handler :jupyter)
    :spec (merge base-spec launch-extra
                 {:token  {:desc "Jupyter auth token"}
                  :kernel {:alias :k :desc "Kernel name" :default "python3"}})}
   {:cmds ["launch" "node"]    :fn (partial launch-handler :node)
    :spec (merge base-spec launch-extra
                 {:prompt-re {:desc "Prompt string to detect" :default "> "}})}
   {:cmds ["launch" "python"]  :fn (partial launch-handler :python)
    :spec (merge base-spec launch-extra)}
   {:cmds ["launch"]           :fn (partial launch-handler nil)
    :spec (merge base-spec launch-extra)}

   ;; Other commands
   {:cmds ["eval"]             :fn eval-handler
    :spec {:name         {:alias :n :desc "Session name"}
           :timeout      {:alias :t :desc "Soft timeout in ms (returns partial output)" :coerce :long :default 30000}
           :hard-timeout {:desc "Hard timeout in ms (interrupts eval)" :coerce :long}
           :stream       {:alias :s :desc "Stream output as NDJSON" :coerce :boolean :default false}
           :chunked      {:desc "Include raw chunks array in output" :coerce :boolean :default false}
           :bg           {:desc "Run eval in background, return eval-id" :coerce :boolean :default false}
           :bg-child     {:desc "Internal: background child mode" :coerce :string}
           :file         {:alias :f :desc "Read code from file (use /dev/stdin for stdin)" :coerce :string}}}
   {:cmds ["ls"]               :fn ls-handler}
   {:cmds ["stop"]             :fn stop-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["restart"]          :fn restart-handler
    :spec {:name    {:alias :n :desc "Session name"}
           :timeout {:alias :t :desc "Port readiness timeout in ms" :coerce :long}}}
   {:cmds ["status"]           :fn status-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["interrupt"]        :fn interrupt-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["output"]           :fn output-handler
    :spec {:eval-id {:alias :e :desc "Background eval ID" :coerce :string :require true}
           :follow  {:alias :f :desc "Tail output until eval completes" :coerce :boolean :default false}}}
   {:cmds ["evals"]            :fn evals-handler}
   {:cmds ["toolchains"]       :fn toolchains-handler}
   {:cmds ["history"]          :fn history-handler
    :spec {:name   {:alias :n :desc "Session name"}
           :format {:desc "Output format: json (default) or script" :coerce :string}}}
   {:cmds ["replay"]           :fn replay-handler
    :spec {:name         {:alias :n :desc "Session name"}
           :file         {:alias :f :desc "Read code from file" :coerce :string}
           :timeout      {:alias :t :desc "Soft timeout per form in ms" :coerce :long :default 30000}
           :hard-timeout {:desc "Hard timeout per form in ms" :coerce :long}}}
   {:cmds ["logs"]             :fn logs-handler
    :spec {:name   {:alias :n :desc "Session name"}
           :tail   {:alias :t :desc "Show last N lines" :coerce :long}
           :follow {:alias :f :desc "Tail log until process exits" :coerce :boolean :default false}}}])

(defn- load-help-text []
  (or (some-> (io/resource "HELP.md") slurp)
      "replsh — run 'replsh --help' for usage (HELP.md resource not found)"))

(def ^:private skill-frontmatter
  "---\nname: replsh\ndescription: >\n  A thinking medium for LLM agents. Use the REPL to verify assumptions, inspect\n  runtime state, and test hypotheses — think before you write. Timeouts return\n  partial output (never lose work). Streaming and background eval handle any\n  timescale. Supports Clojure (deps.edn, Leiningen, Babashka), Python (native\n  bridge — zero deps, or Jupyter for rich output), and Node.js. Activate when\n  working on any project with a .replsh/config.edn, or any Clojure/Python/Node\n  project where a REPL would help you understand the code.\nlicense: EPL-2.0\ncompatibility: Requires Babashka (bb) installed. REPL servers started by replsh or running externally.\nmetadata:\n  author: Daniel Tan\n  repository: https://github.com/danieltanfh95/replsh\n---")

(defn dispatch
  [args]
  (cond
    (some #{"--install-skill"} args)
    (let [args-vec  (vec args)
          path-idx  (.indexOf args-vec "--path")
          path      (when (and (pos? path-idx) (< (inc path-idx) (count args-vec)))
                      (nth args-vec (inc path-idx)))
          dest      (or path "skills/replsh/SKILL.md")
          help-text (load-help-text)
          content   (str skill-frontmatter "\n\n" help-text)]
      (.mkdirs (.getParentFile (io/file dest)))
      (spit dest content)
      (println (str "Wrote skill file to " dest))
      nil)

    (or (empty? args)
        (some #{"--help" "-h" "help"} args))
    (do (println (load-help-text)) nil)

    :else
    (cli/dispatch dispatch-table args)))
