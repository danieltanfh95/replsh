(ns replsh.watch
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [replsh.backend :as backend]
            [replsh.util :as util]))

(def idle-threshold-ms 1800000) ; 30 minutes

(defn idle?
  "Returns true if session has been idle for more than idle-threshold-ms,
   or has never been evaled (new session needing baseline)."
  [session]
  (let [last-eval-at (:last-eval-at session)]
    (if last-eval-at
      (> (- (System/currentTimeMillis) last-eval-at) idle-threshold-ms)
      true)))

(defn introspection-code
  "Returns backend-specific code string to introspect loaded modules.
   Each backend returns a value (pr-str EDN for nREPL, JSON string for others)
   representing [{:ns :file :mtime}] for locally loaded files."
  [backend-kw]
  (case backend-kw
    :nrepl
    (str "(let [cwd (System/getProperty \"user.dir\")]"
         " (->> (all-ns)"
         "      (keep (fn [ns]"
         "              (let [file (some #(:file (meta %)) (vals (ns-publics ns)))]"
         "                (when (and file (.exists (java.io.File. file)) (.startsWith file cwd))"
         "                  {:ns (str ns)"
         "                   :file (subs file (inc (count cwd)))"
         "                   :mtime (.lastModified (java.io.File. file))}))))"
         "      (into [])))")

    (:python :jupyter)
    (str "import sys, os, json\n"
         "cwd = os.getcwd()\n"
         "result = []\n"
         "for name, mod in list(sys.modules.items()):\n"
         "    f = getattr(mod, '__file__', None)\n"
         "    if f and os.path.isfile(f) and os.path.abspath(f).startswith(cwd):\n"
         "        result.append({'ns': name, 'file': os.path.relpath(f, cwd), 'mtime': os.path.getmtime(f)})\n"
         "json.dumps(result)")

    :node
    (str "const fs = require('fs'), path = require('path');\n"
         "const cwd = process.cwd();\n"
         "JSON.stringify(Object.keys(require.cache)\n"
         "  .filter(f => f.startsWith(cwd))\n"
         "  .map(f => ({ns: path.relative(cwd, f), file: path.relative(cwd, f), mtime: fs.statSync(f).mtimeMs})))")))

(defn parse-introspection
  "Parses introspection chunks into a normalized vector of {:ns :file :mtime} maps.
   Returns nil on failure."
  [backend-kw chunks]
  (try
    (when-let [value-chunk (last (filter #(= :value (:type %)) chunks))]
      (let [content (:content value-chunk)]
        (case backend-kw
          :nrepl
          (edn/read-string content)
          (:python :jupyter :node)
          (json/parse-string content true))))
    (catch Exception _ nil)))

(defn introspect
  "Runs a side-eval to get a snapshot of currently loaded modules.
   Returns [{:ns :file :mtime}] or nil on failure."
  [session live-state]
  (try
    (let [backend-kw (:backend session)
          code       (introspection-code backend-kw)
          request    {:code       code
                      :name       (:name session)
                      :backend    backend-kw
                      :timeout-ms 10000
                      :msg-id     (util/gen-id "watch")}
          chunks     (backend/eval! request live-state)]
      (parse-introspection backend-kw chunks))
    (catch Exception _ nil)))

(defn diff-mtimes
  "Returns entries from loaded-snapshot where the mtime differs from stored-mtimes.
   Returns nil when stored-mtimes is nil (first check — establishes baseline only)
   or when no files are stale."
  [loaded-snapshot stored-mtimes]
  (when stored-mtimes
    (let [stale (into []
                      (comp (filter #(let [stored (get stored-mtimes (:file %))]
                                       (and stored (not= stored (:mtime %)))))
                            (map #(select-keys % [:ns :file])))
                      loaded-snapshot)]
      (when (seq stale) stale))))

(defn detect-stale
  "Gate 1: skip if session not idle (< 30 min since last eval) → return nil.
   Gate 2: introspect runtime, diff mtimes vs stored baseline.
   Returns {:stale [{:ns :file}] :loaded-mtimes {file→mtime}} or nil (gate 1 skipped)."
  [session live-state]
  (when (idle? session)
    (let [snapshot   (introspect session live-state)
          stale      (diff-mtimes snapshot (:loaded-mtimes session))
          new-mtimes (when snapshot
                       (into {} (map (juxt :file :mtime)) snapshot))]
      {:stale      stale
       :loaded-mtimes new-mtimes})))
