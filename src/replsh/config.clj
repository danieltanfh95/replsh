(ns replsh.config
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [replsh.bridge :as bridge])
  (:import [java.io File]))

;; --- Built-in toolchain presets ---

(def builtin-toolchains
  {;; --- Local runtimes ---
   "clojure.deps"   {:backend  :nrepl
                     :cmd      "clj -M:nrepl -m nrepl.cmdline --port {port}"
                     :defaults {:port 7888}}

   "clojure.lein"   {:backend  :nrepl
                     :cmd      "lein repl :headless :port {port}"
                     :defaults {:port 7888}}

   "clojure.bb"     {:backend  :nrepl
                     :cmd      "bb --nrepl-server {port}"
                     :defaults {:port 1667}}

   "python"         {:backend  :python
                     :cmd      "python3 {bridge} --port {port}"
                     :defaults {:port 9876}}

   "python.poetry"  {:backend  :python
                     :cmd      "poetry run python {bridge} --port {port}"
                     :defaults {:port 9876}}

   "python.venv"    {:backend  :python
                     :cmd      "{cwd}/.venv/bin/python {bridge} --port {port}"
                     :defaults {:port 9876}}

   "python.poetry.jupyter" {:backend  :jupyter
                            :cmd      "poetry run jupyter server --port {port}"
                            :defaults {:port 8888 :kernel "python3"}}

   "python.venv.jupyter"   {:backend  :jupyter
                            :cmd      "{cwd}/.venv/bin/jupyter server --port {port}"
                            :defaults {:port 8888 :kernel "python3"}}

   "node"           {:backend  :node
                     :cmd      "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen({port})\""
                     :defaults {:port 5001 :prompt-re "> "}}

   ;; --- Docker runtimes ---
   "clojure.bb.container"
   {:backend  :nrepl
    :cmd      "bb --nrepl-server 0.0.0.0:{port}"
    :defaults {:port 1667}
    :runtime  :docker
    :image    "babashka/babashka:latest"}

   "clojure.deps.container"
   {:backend  :nrepl
    :cmd      "clj -M:nrepl -m nrepl.cmdline --port {port} --bind 0.0.0.0"
    :defaults {:port 7888}
    :runtime  :docker
    :image    "clojure:temurin-21-tools-deps-1.12.0.1530"}

   "python.container"
   {:backend  :python
    :cmd      "python3 {bridge} --host 0.0.0.0 --port {port}"
    :defaults {:port 9876}
    :runtime  :docker
    :image    "python:3.12-slim"}

   "node.container"
   {:backend  :node
    :cmd      "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen({port},'0.0.0.0')\""
    :defaults {:port 5001 :prompt-re "> "}
    :runtime  :docker
    :image    "node:22-slim"}})

;; --- Config file loading ---

(defn- read-edn-file
  "Read and parse an EDN file. Returns nil if file doesn't exist."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- walk-up
  "Walk from dir upward to filesystem root, returning lazy seq of paths."
  [^String dir]
  (let [f (File. dir)]
    (when f
      (lazy-seq (cons (.getAbsolutePath f)
                      (when-let [parent (.getParent f)]
                        (walk-up parent)))))))

(defn load-global-config
  "Load global config from ~/.replsh/config.edn (or REPLSH_CONFIG_GLOBAL)."
  []
  (let [path (or (System/getenv "REPLSH_CONFIG_GLOBAL")
                 (str (System/getProperty "user.home") "/.replsh/config.edn"))]
    (read-edn-file path)))

(defn load-project-config
  "Search for .replsh/config.edn walking up from cwd.
   Returns {:config map :dir project-root-path} or nil."
  []
  (let [override (System/getenv "REPLSH_CONFIG")]
    (if override
      (when-let [config (read-edn-file override)]
        {:config config :dir (.getParent (File. ^String override))})
      (let [cwd (System/getProperty "user.dir")]
        (some (fn [dir]
                (let [path (str dir "/.replsh/config.edn")]
                  (when-let [config (read-edn-file path)]
                    {:config config :dir dir})))
              (walk-up cwd))))))

;; --- Resolution ---

(defn resolve-toolchains
  "Merge built-in toolchains with user-defined ones from global config."
  [global-config]
  (merge builtin-toolchains (:toolchains global-config)))

(defn- substitute-template
  "Replace {key} placeholders in a command template string."
  [template values]
  (reduce-kv (fn [s k v]
               (str/replace s (str "{" (name k) "}") (str v)))
             template
             values))

(defn- resolve-cwd
  "Resolve a potentially relative cwd against the project config directory."
  [cwd config-dir]
  (if (or (nil? cwd) (str/starts-with? cwd "/"))
    (or cwd config-dir (System/getProperty "user.dir"))
    ;; Relative path — resolve against config dir
    (let [base (or config-dir (System/getProperty "user.dir"))]
      (.getAbsolutePath (File. ^String base ^String cwd)))))

(defn- container-cwd
  "Derive the container working directory from volumes config.
   Uses the container path of the first volume mount, or /workspace as default."
  [volumes]
  (or (when (seq volumes)
        (let [first-vol (first volumes)]
          (when-let [parts (str/split first-vol #":")]
            (when (> (count parts) 1)
              (second parts)))))
      "/workspace"))

(defn resolve-session
  "Resolve a session by merging: toolchain defaults → toolchain → session spec → CLI opts.
   Returns a flat Resolved Spec map ready for launch-cmd/start-cmd.

   toolchains   — merged toolchain map (from resolve-toolchains)
   project-cfg  — result of load-project-config (or nil)
   session-name — string session name
   cli-opts     — map of CLI options (may be empty)"
  [toolchains project-cfg session-name cli-opts]
  (let [session-spec (get-in project-cfg [:config :sessions session-name])
        ;; Toolchain can come from session spec or CLI
        toolchain-name (or (:toolchain cli-opts)
                           (:toolchain session-spec))
        toolchain      (when toolchain-name
                         (or (get toolchains toolchain-name)
                             (throw (ex-info (str "Toolchain not found: " toolchain-name)
                                            {:code :toolchain-not-found
                                             :toolchain toolchain-name
                                             :available (keys toolchains)}))))
        ;; Merge layers: toolchain defaults → toolchain top-level → session spec → CLI opts
        ;; Remove :defaults and :toolchain keys from the merge
        merged (merge (:defaults toolchain)
                      (dissoc toolchain :defaults)
                      (dissoc session-spec :toolchain)
                      (into {} (remove (fn [[_ v]] (nil? v))) cli-opts))
        ;; Resolve cwd (host path)
        cwd (resolve-cwd (:cwd merged) (:dir project-cfg))
        ;; Determine runtime (default :local)
        runtime (or (:runtime merged) :local)
        ;; Build the resolved spec
        resolved (-> merged
                     (assoc :name session-name)
                     (assoc :cwd cwd)
                     (assoc :runtime runtime)
                     (assoc :backend-type (:backend merged)))
        ;; For Docker: derive container-cwd from volumes
        resolved (if (= runtime :docker)
                   (assoc resolved :container-cwd (container-cwd (:volumes resolved)))
                   resolved)
        ;; Substitute template variables in :cmd
        ;; Runtime-aware: {bridge} and {cwd} resolve differently for local vs docker
        resolved (if (:cmd resolved)
                   (let [template-vals
                         (cond-> {:port (or (:port resolved) "")
                                  :host (or (:host resolved) "localhost")}
                           (= runtime :local)
                           (assoc :cwd    cwd
                                  :bridge (bridge/ensure-bridge!))
                           (= runtime :docker)
                           (assoc :cwd    (or (:container-cwd resolved) "/workspace")
                                  :bridge "/tmp/replsh/replsh_bridge.py"))]
                     (update resolved :cmd substitute-template template-vals))
                   resolved)
        ;; Derive host from defaults if not set
        resolved (if (and (nil? (:host resolved))
                          (#{:nrepl :node :python} (:backend-type resolved)))
                   (assoc resolved :host "localhost")
                   resolved)]
    resolved))
