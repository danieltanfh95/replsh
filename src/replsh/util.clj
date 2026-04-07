(ns replsh.util
  (:require [clojure.string :as str]))

(defn gen-id
  "Generate a short random ID with an optional prefix."
  [prefix]
  (str prefix "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

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
