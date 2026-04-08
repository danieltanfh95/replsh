(ns replsh.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.process :as process]))

(deftest spawn-test
  (testing "spawns a process and returns pid"
    (let [result (process/spawn! {:cmd "sleep 10"
                                  :cwd "/tmp"
                                  :env-vars {}
                                  :name "test-spawn"})]
      (try
        (is (integer? (:pid result)))
        (is (some? (:process result)))
        (is (process/alive? (:pid result)))
        (finally
          (process/kill! (:pid result))))))

  (testing "bad command throws launch-failed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Process exited immediately"
                          (process/spawn! {:cmd "nonexistent-binary-xyz"
                                           :cwd "/tmp"
                                           :env-vars {}
                                           :name "test-bad"})))))

(deftest wait-for-port-test
  (testing "succeeds when port is open"
    ;; Start a simple TCP server
    (let [server (java.net.ServerSocket. 0) ; random port
          port   (.getLocalPort server)]
      (try
        (is (true? (process/wait-for-port! {:host "localhost"
                                            :port port
                                            :timeout-ms 2000})))
        (finally
          (.close server)))))

  (testing "times out on closed port"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not reachable"
                          (process/wait-for-port! {:host "localhost"
                                                   :port 19999
                                                   :timeout-ms 500})))))

(deftest kill-test
  (testing "kills a running process"
    (let [{:keys [pid]} (process/spawn! {:cmd "sleep 60"
                                         :cwd "/tmp"
                                         :env-vars {}
                                         :name "test-kill"})]
      (is (= :killed (process/kill! pid)))
      (Thread/sleep 100)
      (is (false? (process/alive? pid)))))

  (testing "already-dead process returns :already-dead"
    ;; Use a PID that definitely doesn't exist
    (is (= :already-dead (process/kill! 999999999)))))

(deftest alive-test
  (testing "alive process returns true"
    (let [{:keys [pid]} (process/spawn! {:cmd "sleep 10"
                                         :cwd "/tmp"
                                         :env-vars {}
                                         :name "test-alive"})]
      (try
        (is (true? (process/alive? pid)))
        (finally
          (process/kill! pid)))))

  (testing "dead process returns false"
    (is (false? (process/alive? 999999999)))))
