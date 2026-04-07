(ns replsh.cli
  (:require [babashka.cli :as cli]
            [replsh.command :as cmd]
            [replsh.util :as util]))

(defn- start-handler
  [backend-type {:keys [opts args]}]
  (let [{:keys [name cwd env kernel token prompt-re]} opts
        address (first args)
        url     (first args)]
    (when-not name
      (throw (ex-info "--name is required" {:code :missing-arg})))
    (when-not (first args)
      (throw (ex-info "Address or URL is required" {:code :missing-arg})))
    (cmd/start-cmd {:backend-type backend-type
                    :name         name
                    :address      (when (#{:nrepl :node} backend-type) address)
                    :url          (when (= :jupyter backend-type) url)
                    :cwd          cwd
                    :env          (when env (util/parse-env-args (if (string? env) [env] env)))
                    :kernel       kernel
                    :token        token
                    :prompt-re    prompt-re})))

(defn- eval-handler
  [{:keys [opts args]}]
  (let [code (first args)]
    (when-not code
      (throw (ex-info "Code argument is required" {:code :missing-arg})))
    (cmd/eval-cmd {:name    (:name opts)
                   :code    code
                   :timeout (:timeout opts)})))

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

(def dispatch-table
  [{:cmds ["start" "nrepl"]   :fn (partial start-handler :nrepl)
    :spec {:name      {:alias :n :desc "Session name" :require true}
           :cwd       {:desc "Working directory context"}
           :env       {:alias :e :desc "Environment var (K=V)" :coerce []}}}
   {:cmds ["start" "jupyter"] :fn (partial start-handler :jupyter)
    :spec {:name      {:alias :n :desc "Session name" :require true}
           :token     {:desc "Jupyter auth token"}
           :kernel    {:alias :k :desc "Kernel name" :default "python3"}
           :cwd       {:desc "Working directory context"}
           :env       {:alias :e :desc "Environment var (K=V)" :coerce []}}}
   {:cmds ["start" "node"]    :fn (partial start-handler :node)
    :spec {:name      {:alias :n :desc "Session name" :require true}
           :prompt-re {:desc "Prompt string to detect" :default "> "}
           :cwd       {:desc "Working directory context"}
           :env       {:alias :e :desc "Environment var (K=V)" :coerce []}}}
   {:cmds ["eval"]             :fn eval-handler
    :spec {:name    {:alias :n :desc "Session name"}
           :timeout {:alias :t :desc "Timeout in ms" :coerce :long :default 30000}}}
   {:cmds ["ls"]               :fn ls-handler}
   {:cmds ["stop"]             :fn stop-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["restart"]          :fn restart-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["status"]           :fn status-handler
    :spec {:name {:alias :n :desc "Session name"}}}
   {:cmds ["interrupt"]        :fn interrupt-handler
    :spec {:name {:alias :n :desc "Session name"}}}])

(defn dispatch
  [args]
  (cli/dispatch dispatch-table args))
