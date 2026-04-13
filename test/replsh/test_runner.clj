(ns replsh.test-runner
  (:require [clojure.test :as t]
            [replsh.util-test]
            [replsh.output-test]
            [replsh.config-test]
            [replsh.cli-test]
            [replsh.runtime-test]
            [replsh.watch-test]
            [replsh.process-test]
            [replsh.integration-test]))

(defn run-all
  "Run tests and return result map. Safe to call from a live REPL."
  [& {:keys [unit-only? reload?]}]
  (let [namespaces (cond-> ['replsh.util-test
                            'replsh.output-test
                            'replsh.config-test
                            'replsh.cli-test
                            'replsh.runtime-test
                            'replsh.watch-test]
                   (not unit-only?) (into ['replsh.process-test
                                           'replsh.integration-test]))]
    (when reload?
      (doseq [ns-sym namespaces]
        (require ns-sym :reload-all)))
    (apply t/run-tests namespaces)))

(defn -main [& args]
  (let [{:keys [fail error]} (run-all :unit-only? (some #{"--unit"} args))]
    (System/exit (if (zero? (+ fail error)) 0 1))))
