(ns replsh.cli
  (:require [babashka.cli :as cli]
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

(defn- launch-handler
  [backend-type {:keys [opts args]}]
  (let [{:keys [name cmd cwd env kernel token prompt-re init port timeout]} opts
        port (or port (util/find-free-port))
        ;; Try config resolution
        resolved (resolve-config name (cond-> {:port port}
                                        cmd       (assoc :cmd cmd)
                                        cwd       (assoc :cwd cwd)
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
                         :env          (or (parse-env env) (:env resolved))
                         :kernel       (or kernel (:kernel resolved))
                         :token        (or token (:token resolved))
                         :prompt-re    (or prompt-re (:prompt-re resolved))
                         :init         (or init (:init resolved))
                         :timeout      timeout}))
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
                         :env          (parse-env env)
                         :kernel       kernel
                         :token        token
                         :prompt-re    prompt-re
                         :init         init
                         :timeout      timeout})))))

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
                         :hard-timeout (:hard-timeout opts)})

      (:bg-child opts)
      (let [result (cmd/eval-cmd {:name         (:name opts)
                                   :code         code
                                   :timeout      (:timeout opts)
                                   :hard-timeout (:hard-timeout opts)
                                   :stream?      true})]
        (cmd/finalize-bg-eval! (:bg-child opts) result)
        result)

      :else
      (cmd/eval-cmd {:name         (:name opts)
                     :code         code
                     :timeout      (:timeout opts)
                     :hard-timeout (:hard-timeout opts)
                     :stream?      (:stream opts)}))))

(defn- ls-handler [_] (cmd/ls-cmd))

(defn- stop-handler
  [{:keys [opts args]}]
  (let [name (or (first args) (:name opts))]
    (cmd/stop-cmd {:name name})))

(defn- restart-handler
  [{:keys [opts args]}]
  (let [name (or (first args) (:name opts))]
    (cmd/restart-cmd {:name name})))

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

(defn- logs-handler
  [{:keys [opts]}]
  (cmd/logs-cmd {:name   (:name opts)
                  :tail   (:tail opts)
                  :follow (:follow opts)}))

(def ^:private base-spec
  {:name      {:alias :n :desc "Session name"}
   :port      {:alias :p :desc "Port number" :coerce :long}
   :cwd       {:desc "Working directory context"}
   :env       {:alias :e :desc "Environment var (K=V)" :coerce []}
   :init      {:alias :i :desc "Bootstrap code to run on session start"}})

(def ^:private launch-extra
  {:cmd     {:desc "Command to spawn the REPL server"}
   :timeout {:alias :t :desc "Port readiness timeout in ms" :coerce :long :default 30000}})

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
           :bg           {:desc "Run eval in background, return eval-id" :coerce :boolean :default false}
           :bg-child     {:desc "Internal: background child mode" :coerce :string}
           :file         {:alias :f :desc "Read code from file (use /dev/stdin for stdin)" :coerce :string}}}
   {:cmds ["ls"]               :fn ls-handler}
   {:cmds ["stop"]             :fn stop-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["restart"]          :fn restart-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["status"]           :fn status-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["interrupt"]        :fn interrupt-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["output"]           :fn output-handler
    :spec {:eval-id {:alias :e :desc "Background eval ID" :coerce :string :require true}
           :follow  {:alias :f :desc "Tail output until eval completes" :coerce :boolean :default false}}}
   {:cmds ["evals"]            :fn evals-handler}
   {:cmds ["logs"]             :fn logs-handler
    :spec {:name   {:alias :n :desc "Session name"}
           :tail   {:alias :t :desc "Show last N lines" :coerce :long}
           :follow {:alias :f :desc "Tail log until process exits" :coerce :boolean :default false}}}])

(def ^:private help-text
  "replsh — Unified CLI for REPL servers

Usage: replsh <command> [options]

Commands:
  launch [backend] --name <name>   Spawn a REPL server and connect
  start  [backend] --name <name>   Connect to an existing REPL server
  eval   --name <name> '<code>'    Evaluate code in a session
  eval   --name <name> --file <f>   Evaluate code from file (or stdin)
  ls                               List all sessions
  status --name <name>             Show session status
  stop   <name>                    Stop and remove a session
  restart <name>                   Restart a session
  interrupt --name <name>          Interrupt a running eval
  evals                            List background evals
  output --eval-id <id>            Read background eval output
  logs   --name <name>             Read process logs

Backends: nrepl, python, jupyter, node

Launch examples:
  replsh launch --name backend              # from .replsh/config.edn
  replsh launch nrepl --name dev --cmd \"bb --nrepl-server {port}\"
  replsh launch --name ml --init \"import pandas\"

Eval examples:
  replsh eval --name dev '(+ 1 2)'          # inline code
  replsh eval --name dev --file script.clj  # from file
  echo '(+ 1 2)' | replsh eval --name dev  # from stdin
  replsh eval --name dev --bg '(train-model)' # background eval
  replsh output --eval-id <id>              # read bg output
  replsh output --eval-id <id> --follow     # tail bg output

Start examples (connect to existing server):
  replsh start nrepl --name dev --port 1667
  replsh start --name dev                   # from .replsh/config.edn

Config files:
  ~/.replsh/config.edn        Global toolchain presets
  <project>/.replsh/config.edn  Project session definitions

Built-in toolchains: clojure.deps, clojure.lein, clojure.bb,
                     python, python.poetry, python.venv, node

All commands emit JSON to stdout. Exit codes: 0=ok, 1=eval error, 2=client error, 3=timeout

Run 'man replsh' or see doc/MANUAL.md for the full reference.")

(defn dispatch
  [args]
  (if (or (empty? args)
          (some #{"--help" "-h" "help"} args))
    (do (println help-text) nil)
    (cli/dispatch dispatch-table args)))
