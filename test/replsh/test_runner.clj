(ns replsh.test-runner
  (:require [clojure.test :as t]
            [replsh.util-test]
            [replsh.output-test]
            [replsh.config-test]
            [replsh.runtime-test]
            [replsh.process-test]
            [replsh.integration-test]))

(defn run-all
  "Run tests and return result map. Safe to call from a live REPL."
  [& {:keys [unit-only?]}]
  (let [namespaces (cond-> ['replsh.util-test
                            'replsh.output-test
                            'replsh.config-test
                            'replsh.runtime-test]
                   (not unit-only?) (into ['replsh.process-test
                                           'replsh.integration-test]))]
    (apply t/run-tests namespaces)))

(defn -main [& args]
  (let [{:keys [fail error]} (run-all :unit-only? (some #{"--unit"} args))]
    (System/exit (if (zero? (+ fail error)) 0 1))))
