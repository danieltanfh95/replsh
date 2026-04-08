(ns replsh.main
  (:require [replsh.cli :as cli]
            [replsh.output :as output]))

(defn -main
  [& args]
  (try
    (let [result (cli/dispatch args)]
      (when (map? result)
        (let [exit-code (if (:stream? result)
                          (output/emit-summary! (dissoc result :stream?))
                          (output/emit! result))]
          (System/exit exit-code))))
    (catch clojure.lang.ExceptionInfo e
      (let [data    (ex-data e)
            command (or (:command data) "error")]
        (output/emit! (output/failure command
                                      {:code    (let [c (or (:code data) "unknown")]
                                                  (if (keyword? c) (name c) (str c)))
                                       :message (ex-message e)
                                       :detail  (dissoc data :code :command)}))
        (System/exit 2)))
    (catch Exception e
      (output/emit! (output/failure "error"
                                    {:code    "unknown"
                                     :message (ex-message e)
                                     :detail  {}}))
      (System/exit 2))))
