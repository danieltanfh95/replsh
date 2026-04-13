(ns replsh.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.runtime :as runtime]))

(deftest normalize-session-test
  (testing "adds :local runtime to sessions without :runtime"
    (is (= :local (:runtime (runtime/normalize-session {:name "test"})))))

  (testing "preserves existing :docker runtime (port-mode — no exec transport)"
    (is (= :docker (:runtime (runtime/normalize-session {:name "test" :runtime :docker})))))

  (testing "migrates :docker exec-mode sessions to :exec runtime"
    (let [session {:name "api" :runtime :docker
                   :transport {:type :exec :exec-port 9876}}]
      (is (= :exec (:runtime (runtime/normalize-session session))))))

  (testing "preserves existing :exec runtime"
    (is (= :exec (:runtime (runtime/normalize-session {:name "test" :runtime :exec}))))))

(deftest session->runtime-info-test
  (testing "local session extracts pid"
    (let [info (runtime/session->runtime-info
                 {:name "dev" :runtime :local :launch {:pid 12345}})]
      (is (= :local (:runtime info)))
      (is (= 12345 (:pid info)))
      (is (= "dev" (:name info)))))

  (testing "docker session extracts container-id"
    (let [info (runtime/session->runtime-info
                 {:name "ml" :runtime :docker :launch {:container-id "abc123"}})]
      (is (= :docker (:runtime info)))
      (is (= "abc123" (:container-id info)))
      (is (= "ml" (:name info)))))

  (testing "session without runtime defaults to local"
    (let [info (runtime/session->runtime-info
                 {:name "dev" :launch {:pid 999}})]
      (is (= :local (:runtime info)))))

  (testing "exec session extracts ssh-host, container-id, and bridge-pid"
    (let [info (runtime/session->runtime-info
                 {:name "remote" :runtime :exec
                  :launch {:ssh-host "my-machine" :container-id "my-app"
                           :bridge-pid 42000}})]
      (is (= :exec (:runtime info)))
      (is (= "my-machine" (:ssh-host info)))
      (is (= "my-app" (:container-id info)))
      (is (= 42000 (:pid info)))
      (is (= "remote" (:name info)))))

  (testing "exec session without ssh-host (container-only)"
    (let [info (runtime/session->runtime-info
                 {:name "local-docker" :runtime :exec
                  :launch {:container-id "my-app" :bridge-pid 12345}})]
      (is (= :exec (:runtime info)))
      (is (nil? (:ssh-host info)))
      (is (= "my-app" (:container-id info))))))

(deftest local-mapped-port-test
  (testing "local mapped-port returns same port"
    (is (= 8080 (runtime/mapped-port {:runtime :local} 8080)))
    (is (= 1667 (runtime/mapped-port {:runtime :local} 1667)))))

(deftest local-alive-test
  (testing "alive? for current process returns true"
    (is (runtime/alive? {:runtime :local :pid (.pid (java.lang.ProcessHandle/current))})))

  (testing "alive? for nonexistent pid returns false"
    (is (not (runtime/alive? {:runtime :local :pid 999999999})))))

(deftest docker-alive-nonexistent-test
  (testing "alive? for nonexistent container returns false"
    (is (not (runtime/alive? {:runtime :docker :container-id "nonexistent-container-xyz"})))))

(deftest docker-mapped-port-nonexistent-test
  (testing "mapped-port for nonexistent container returns nil"
    (is (nil? (runtime/mapped-port {:runtime :docker :container-id "nonexistent-container-xyz"} 8080)))))

(deftest local-wait-ready-tcp-test
  (testing "wait-ready! succeeds for an open port"
    ;; Start a quick TCP server on a free port
    (let [ss (java.net.ServerSocket. 0)
          port (.getLocalPort ss)]
      (try
        (is (true? (runtime/wait-ready! {:runtime :local :process nil}
                                        {:host "localhost" :port port
                                         :timeout-ms 5000 :check :tcp})))
        (finally
          (.close ss)))))

  (testing "wait-ready! throws on unreachable port"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"not reachable"
          (runtime/wait-ready! {:runtime :local :process nil}
                               {:host "localhost" :port 19999
                                :timeout-ms 500 :check :tcp})))))

(deftest docker-wait-ready-dead-container-test
  (testing "wait-ready! throws when container is dead"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"Container exited"
          (runtime/wait-ready! {:runtime :docker :container-id "nonexistent-xyz"}
                               {:host "localhost" :port 19999
                                :timeout-ms 5000 :check :tcp})))))

(deftest deploy-files-local-noop-test
  (testing "deploy-files! for local is a no-op"
    (is (nil? (runtime/deploy-files! {:runtime :local} [{:src "/a" :dest "/b"}]))))

  (testing "deploy-files! for docker is a no-op"
    (is (nil? (runtime/deploy-files! {:runtime :docker} [{:src "/a" :dest "/b"}])))))

(deftest exec-local-test
  (testing "exec! :local runs a command and returns process handles"
    (let [handles (runtime/exec! {:runtime :local :name "test-exec"}
                                 "echo hello" {})]
      (is (instance? java.io.BufferedReader (:in handles)))
      (is (instance? java.io.PrintWriter (:out handles)))
      (is (instance? Process (:process handles)))
      (let [line (.readLine ^java.io.BufferedReader (:in handles))]
        (is (= "hello" line)))
      (.destroy ^Process (:process handles))))

  (testing "exec! :local supports bidirectional I/O"
    (let [handles (runtime/exec! {:runtime :local :name "test-exec-io"}
                                 "cat" {})]
      (.println ^java.io.PrintWriter (:out handles) "ping")
      (.flush ^java.io.PrintWriter (:out handles))
      (let [line (.readLine ^java.io.BufferedReader (:in handles))]
        (is (= "ping" line)))
      (.destroy ^Process (:process handles)))))

(deftest deploy-bridge-local-test
  (testing "deploy-bridge! :local returns a path that exists"
    (let [path (runtime/deploy-bridge! {:runtime :local})]
      (is (string? path))
      (is (.exists (java.io.File. ^String path))))))

(deftest exec-runtime-test
  (testing "exec! :exec (local, no ssh, no container) runs command via /bin/sh"
    (let [handles (runtime/exec! {:runtime :exec :name "test-exec-local"}
                                 "echo hello-exec" {})]
      (is (instance? java.io.BufferedReader (:in handles)))
      (is (instance? java.io.PrintWriter (:out handles)))
      (is (instance? Process (:process handles)))
      (let [line (.readLine ^java.io.BufferedReader (:in handles))]
        (is (= "hello-exec" line)))
      (.destroy ^Process (:process handles))))

  (testing "alive? :exec with no pid and no container returns false"
    (is (not (runtime/alive? {:runtime :exec}))))

  (testing "alive? :exec with nonexistent container returns false"
    (is (not (runtime/alive? {:runtime :exec :container-id "nonexistent-xyz-exec"}))))

  (testing "mapped-port :exec returns same port"
    (is (= 9876 (runtime/mapped-port {:runtime :exec} 9876))))

  (testing "wait-ready! :exec returns true immediately"
    (is (true? (runtime/wait-ready! {:runtime :exec} {}))))

  (testing "deploy-files! :exec is a no-op"
    (is (nil? (runtime/deploy-files! {:runtime :exec} [{:src "/a" :dest "/b"}]))))

  (testing "send-signal! :exec with nil pid does nothing"
    (is (nil? (runtime/send-signal! {:runtime :exec :pid nil} "INT")))))
