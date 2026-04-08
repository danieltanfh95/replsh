(ns replsh.test-runner
  (:require [clojure.test :as t]
            [replsh.util-test]
            [replsh.output-test]
            [replsh.config-test]
            [replsh.process-test]
            [replsh.integration-test]))

(defn -main [& args]
  (let [integration? (not (some #{"--unit"} args))
        namespaces   (cond-> ['replsh.util-test
                              'replsh.output-test
                              'replsh.config-test]
                       integration? (into ['replsh.process-test
                                           'replsh.integration-test]))]
    (let [{:keys [fail error]} (apply t/run-tests namespaces)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
