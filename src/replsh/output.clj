(ns replsh.output
  (:require [cheshire.core :as json]))

(defn success
  "Build a success envelope."
  [command data]
  {:ok true :command (name command) :data data})

(defn failure
  "Build a failure envelope. error-map should have :code, :message, and optionally :detail, :data."
  [command error-map]
  (cond-> {:ok false
           :command (name command)
           :error (select-keys error-map [:code :message :detail])}
    (:data error-map) (assoc :data (:data error-map))))

(defn emit!
  "Print result as JSON to stdout. Returns exit code."
  [result]
  (println (json/generate-string result))
  (if (:ok result)
    0
    (case (get-in result [:error :code])
      "eval_error" 1
      "timeout"    3
      2)))

(defn emit-chunk!
  "Print a single chunk as a JSON line to stdout (NDJSON). Flushes immediately."
  [chunk]
  (println (json/generate-string
             (assoc (select-keys chunk [:type :content :stream :meta])
                    :status "streaming")))
  (flush))

(defn emit-summary!
  "Print the final summary envelope as the last NDJSON line. Returns exit code."
  [result]
  (println (json/generate-string result))
  (flush)
  (if (:ok result)
    0
    (case (get-in result [:error :code])
      "eval_error" 1
      "timeout"    3
      2)))
