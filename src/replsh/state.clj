(ns replsh.state
  (:require [clojure.java.io :as io]
            [replsh.util :as util]))

(defn state-path
  "Path to the global state file."
  []
  (or (System/getenv "REPLSH_STATE")
      (str (System/getProperty "user.home") "/.replsh/state.edn")))

(defn load-state
  "Load state from disk. Returns empty state if file doesn't exist."
  []
  (or (util/read-edn-file (state-path)) {:active nil :sessions {}}))

(defn save-state!
  "Persist state to disk. Creates parent dirs if needed."
  [state]
  (let [f (io/file (state-path))]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str state))))

(defn get-session
  "Get a session config by name, or the active session if name is nil."
  [state session-name]
  (let [n (or session-name (:active state))]
    (when n
      (get-in state [:sessions n]))))

(defn put-session!
  "Add or update a session in state and persist."
  [state session-config]
  (let [n (:name session-config)
        new-state (-> state
                      (assoc-in [:sessions n] session-config)
                      (cond-> (nil? (:active state)) (assoc :active n)))]
    (save-state! new-state)
    new-state))

(defn remove-session!
  "Remove a session from state and persist."
  [state session-name]
  (let [new-state (-> state
                      (update :sessions dissoc session-name)
                      (cond-> (= (:active state) session-name)
                        (assoc :active (first (keys (dissoc (:sessions state) session-name))))))]
    (save-state! new-state)
    new-state))
