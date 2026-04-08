(ns replsh.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [replsh.command :as cmd]
            [replsh.state :as state]
            [replsh.process :as process])
  (:import [java.io File]))

;; Use a temp state file so tests don't pollute real state
(def ^:dynamic *test-state-dir* nil)

(defn with-temp-state [f]
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/replsh-test-" (System/currentTimeMillis))
        state-file (str tmp-dir "/state.edn")]
    (.mkdirs (File. tmp-dir))
    (System/setProperty "REPLSH_STATE" state-file)
    ;; Override state path via env not possible in-process, so we set system property
    ;; Actually, state.clj reads REPLSH_STATE env var. We need a different approach.
    ;; Use with-redefs to override state-path
    (binding [*test-state-dir* tmp-dir]
      (with-redefs [state/state-path (constantly state-file)]
        (try
          (f)
          (finally
            ;; Clean up any launched processes
            (let [st (state/load-state)]
              (doseq [[_ session] (:sessions st)]
                (when-let [pid (get-in session [:launch :pid])]
                  (try (process/kill! pid) (catch Exception _)))))
            ;; Clean up temp files
            (doseq [file (reverse (file-seq (File. tmp-dir)))]
              (.delete file))))))))

(use-fixtures :each with-temp-state)

(deftest launch-eval-stop-test
  (testing "full lifecycle: launch -> eval -> stop"
    ;; Launch a bb nREPL server
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest"
                                         :host         "localhost"
                                         :port         16670
                                         :cmd          "bb --nrepl-server 16670"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         nil
                                         :timeout      30000})]
      (is (true? (:ok launch-result)))
      (is (= "launch" (:command launch-result)))

      ;; Eval
      (let [eval-result (cmd/eval-cmd {:name "itest"
                                       :code "(+ 1 2)"
                                       :timeout 10000})]
        (is (true? (:ok eval-result)))
        (is (= "3" (get-in eval-result [:data :value]))))

      ;; Status
      (let [status-result (cmd/status-cmd {:name "itest"})]
        (is (true? (:ok status-result)))
        (is (true? (get-in status-result [:data :reachable]))))

      ;; Stop
      (let [stop-result (cmd/stop-cmd {:name "itest"})]
        (is (true? (:ok stop-result)))))))

(deftest launch-with-init-test
  (testing "launch with --init runs bootstrap code"
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest-init"
                                         :host         "localhost"
                                         :port         16671
                                         :cmd          "bb --nrepl-server 16671"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         "(def test-var 42)"
                                         :timeout      30000})]
      (is (true? (:ok launch-result)))

      ;; Verify init code ran
      (let [eval-result (cmd/eval-cmd {:name "itest-init"
                                       :code "test-var"
                                       :timeout 10000})]
        (is (true? (:ok eval-result)))
        (is (= "42" (get-in eval-result [:data :value]))))

      (cmd/stop-cmd {:name "itest-init"}))))

(deftest restart-test
  (testing "restart relaunches with new PID"
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest-restart"
                                         :host         "localhost"
                                         :port         16672
                                         :cmd          "bb --nrepl-server 16672"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         nil
                                         :timeout      30000})
          old-pid (get-in launch-result [:data :pid])]
      (is (true? (:ok launch-result)))

      (let [restart-result (cmd/restart-cmd {:name "itest-restart"})]
        (is (true? (:ok restart-result)))
        (is (not= old-pid (get-in restart-result [:data :pid]))))

      (cmd/stop-cmd {:name "itest-restart"}))))

(deftest ls-test
  (testing "ls lists launched sessions"
    (let [_ (cmd/launch-cmd {:backend-type :nrepl
                              :name         "itest-ls"
                              :host         "localhost"
                              :port         16673
                              :cmd          "bb --nrepl-server 16673"
                              :cwd          "/tmp"
                              :env          {}
                              :kernel       nil
                              :token        nil
                              :prompt-re    nil
                              :init         nil
                              :timeout      30000})
          ls-result (cmd/ls-cmd)]
      (is (true? (:ok ls-result)))
      (is (= 1 (count (get-in ls-result [:data :sessions]))))
      (is (= "itest-ls" (get-in ls-result [:data :sessions 0 :name])))

      (cmd/stop-cmd {:name "itest-ls"}))))
