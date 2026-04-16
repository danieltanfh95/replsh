(ns replsh.util
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net ServerSocket]))

(def log-dir
  "Path to the replsh logs directory."
  (str (System/getProperty "user.home") "/.replsh/logs/"))

(defn read-edn-file
  "Read and parse an EDN file. Returns nil if file doesn't exist."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn gen-id
  "Generate a short random ID with an optional prefix."
  [prefix]
  (str prefix "-" (subs (str (random-uuid)) 0 8)))

(defn timestamp
  "ISO-8601 timestamp string for now."
  []
  (str (java.time.Instant/now)))

(defn parse-address
  "Parse 'host:port' into {:host h :port p}. Defaults host to localhost."
  [s]
  (let [parts (str/split s #":")]
    (case (count parts)
      1 {:host "localhost" :port (parse-long (first parts))}
      2 {:host (first parts) :port (parse-long (second parts))}
      (throw (ex-info (str "Invalid address: " s) {:address s})))))

(defn find-free-port
  "Find a free TCP port. Binds to port 0, reads assigned port, closes."
  []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn parse-env-args
  "Parse a sequence of 'K=V' strings into a map."
  [envs]
  (into {}
        (map (fn [s]
               (let [idx (str/index-of s "=")]
                 (if idx
                   [(subs s 0 idx) (subs s (inc idx))]
                   (throw (ex-info (str "Invalid env: " s " (expected K=V)") {:env s}))))))
        envs))
