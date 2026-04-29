(ns replsh.state
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [replsh.util :as util])
  (:import (java.nio.file Files
                          FileAlreadyExistsException
                          Paths
                          StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

(defn state-path
  "Path to the global state file."
  []
  (or (System/getenv "REPLSH_STATE")
      (str (System/getProperty "user.home") "/.replsh/state.edn")))

(defn lock-path
  "Path to the lockfile guarding state.edn read-modify-write."
  []
  (str (state-path) ".lock"))

(defn- ->path [s] (Paths/get s (into-array String [])))

(defn load-state
  "Load state from disk. Returns empty state if file doesn't exist."
  []
  (or (util/read-edn-file (state-path)) {:active nil :sessions {}}))

(defn- current-pid []
  (.pid (java.lang.ProcessHandle/current)))

(defn- alive-pid?
  "True iff <pid> exists on this host (best-effort via `kill -0`)."
  [pid]
  (try
    (zero? (-> (ProcessBuilder. ["kill" "-0" (str pid)])
               (.redirectErrorStream true)
               .start
               .waitFor))
    (catch Exception _ false)))

(defn- read-lock-holder
  "Read the {:pid ...} record from the lockfile, or nil if unreadable."
  [path]
  (try
    (-> path str slurp edn/read-string)
    (catch Exception _ nil)))

(defn- try-acquire!
  "Attempt to create the lockfile atomically. Returns true on success, false
   if the file already exists. Steals stale locks (owner pid not alive)."
  [path]
  (try
    (Files/createFile path (into-array FileAttribute []))
    (spit (.toFile path) (pr-str {:pid (current-pid)
                                  :acquired-at (util/timestamp)}))
    true
    (catch FileAlreadyExistsException _
      (let [holder (read-lock-holder path)]
        (when (and holder (not (alive-pid? (:pid holder))))
          (Files/deleteIfExists path))
        false))))

(defn- acquire-lock!
  "Block until the state lock is held. Throws after `timeout-ms` if no progress."
  [timeout-ms]
  (let [path     (->path (lock-path))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [backoff 5]
      (cond
        (try-acquire! path)
        nil

        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "Could not acquire state lock after " timeout-ms "ms")
                        {:code :state-lock-timeout :path (str path)}))

        :else
        (do (Thread/sleep backoff)
            (recur (min 200 (* 2 backoff))))))))

(defn- release-lock! []
  (try
    (Files/deleteIfExists (->path (lock-path)))
    (catch Exception _)))

(defn save-state!
  "Atomically replace state.edn — write to a per-pid tmp file, then ATOMIC_MOVE."
  [state]
  (let [final-file (io/file (state-path))
        tmp-file   (io/file (str (state-path) "." (current-pid) ".tmp"))]
    (.mkdirs (.getParentFile final-file))
    (spit tmp-file (pr-str state))
    (Files/move (.toPath tmp-file) (.toPath final-file)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))))

(defn update-state!
  "Atomic read-modify-write of the whole state map under the file lock.
   `f` is called with the freshest state and must return the new state.
   Returns the new state."
  [f]
  (acquire-lock! 30000)
  (try
    (let [s' (f (load-state))]
      (save-state! s')
      s')
    (finally (release-lock!))))

(defn get-session
  "Get a session config by name, or the active session if name is nil."
  [state session-name]
  (let [n (or session-name (:active state))]
    (when n
      (get-in state [:sessions n]))))

(defn update-session!
  "Atomic update of a single session record. `f` receives the freshest session
   record (or nil if absent) and returns the new session map (or nil to skip).
   Returning nil leaves the state untouched — useful when concurrent deletion
   should not be reverted. Sets `:active` to `name` on insert if state has no
   active session. Returns the new session."
  [name f]
  (let [s' (update-state!
             (fn [s]
               (let [old     (get-in s [:sessions name])
                     updated (f old)]
                 (cond
                   (nil? updated) s
                   :else (-> s
                             (assoc-in [:sessions name] updated)
                             (cond-> (nil? (:active s)) (assoc :active name)))))))]
    (get-in s' [:sessions name])))

(defn remove-session!
  "Remove a session from state under the lock. If it was the active session,
   pick any remaining session as the new active (or nil)."
  [session-name]
  (update-state!
    (fn [s]
      (let [active' (when (= (:active s) session-name)
                      (first (keys (dissoc (:sessions s) session-name))))]
        (-> s
            (update :sessions dissoc session-name)
            (cond-> (= (:active s) session-name) (assoc :active active')))))))
