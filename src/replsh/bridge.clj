(ns replsh.bridge
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(def ^:private bridge-dir
  (str (System/getProperty "user.home") "/.replsh/bridge/"))

(def ^:private bridge-filename "replsh_bridge.py")
(def ^:private bridge-resource "replsh/replsh_bridge.py")

(defn bridge-path
  "Absolute path to the deployed bridge script."
  []
  (str bridge-dir bridge-filename))

(defn ensure-bridge!
  "Write the bridge script to ~/.replsh/bridge/ if missing or outdated.
   Returns the absolute path."
  []
  (let [dest (File. (bridge-path))
        src  (io/resource bridge-resource)]
    (when-not src
      (throw (ex-info "Bridge script not found on classpath"
                      {:code :bridge-missing :resource bridge-resource})))
    (let [src-bytes (.readAllBytes (io/input-stream src))
          needs-write? (or (not (.exists dest))
                           (not= (seq src-bytes)
                                 (seq (when (.exists dest)
                                        (.readAllBytes (java.io.FileInputStream. dest))))))]
      (when needs-write?
        (.mkdirs (.getParentFile dest))
        (io/copy (java.io.ByteArrayInputStream. src-bytes) dest)))
    (.getAbsolutePath dest)))
