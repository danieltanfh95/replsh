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

  (testing "exec session with :via vector"
    (let [info (runtime/session->runtime-info
                 {:name "remote" :runtime :exec
                  :launch {:via         [{:type :ssh :host "my-machine"}
                                         {:type :docker :container "my-app"}]
                           :bridge-pid 42000}})]
      (is (= :exec (:runtime info)))
      (is (= [{:type :ssh :host "my-machine"}
              {:type :docker :container "my-app"}]
             (:via info)))
      (is (= 42000 (:pid info)))
      (is (= "remote" (:name info)))))

  (testing "exec session migrates old flat-key format to :via"
    (let [info (runtime/session->runtime-info
                 {:name "remote" :runtime :exec
                  :launch {:ssh-host "my-machine" :container-id "my-app"
                           :bridge-pid 42000}})]
      (is (= :exec (:runtime info)))
      (is (= [{:type :ssh :host "my-machine"}
              {:type :docker :container "my-app"}]
             (:via info)))
      (is (= 42000 (:pid info)))))

  (testing "exec session without ssh-host (container-only, migrated)"
    (let [info (runtime/session->runtime-info
                 {:name "local-docker" :runtime :exec
                  :launch {:container-id "my-app" :bridge-pid 12345}})]
      (is (= :exec (:runtime info)))
      (is (= [{:type :docker :container "my-app"}] (:via info))))))

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
  (testing "exec! :exec (empty :via) runs command via /bin/sh"
    (let [handles (runtime/exec! {:runtime :exec :name "test-exec-local" :via []}
                                 "echo hello-exec" {})]
      (is (instance? java.io.BufferedReader (:in handles)))
      (is (instance? java.io.PrintWriter (:out handles)))
      (is (instance? Process (:process handles)))
      (let [line (.readLine ^java.io.BufferedReader (:in handles))]
        (is (= "hello-exec" line)))
      (.destroy ^Process (:process handles))))

  (testing "alive? :exec with no pid and empty :via returns false"
    (is (not (runtime/alive? {:runtime :exec :via []}))))

  (testing "alive? :exec with nonexistent docker container returns false"
    (is (not (runtime/alive? {:runtime :exec
                              :via [{:type :docker :container "nonexistent-xyz-exec"}]}))))

  (testing "mapped-port :exec returns same port"
    (is (= 9876 (runtime/mapped-port {:runtime :exec} 9876))))

  (testing "wait-ready! :exec returns true immediately"
    (is (true? (runtime/wait-ready! {:runtime :exec} {}))))

  (testing "deploy-files! :exec is a no-op"
    (is (nil? (runtime/deploy-files! {:runtime :exec} [{:src "/a" :dest "/b"}]))))

  (testing "send-signal! :exec with nil pid does nothing"
    (is (nil? (runtime/send-signal! {:runtime :exec :pid nil} "INT")))))

(deftest via-args-test
  (testing "empty :via runs command locally"
    (is (= ["/bin/sh" "-c" "echo hi"] (runtime/via-args [] "echo hi"))))

  (testing "nil :via treated as empty"
    (is (= ["/bin/sh" "-c" "echo hi"] (runtime/via-args nil "echo hi"))))

  (testing "single ssh layer"
    (is (= ["ssh" "-o" "BatchMode=yes" "user@host" "echo hi"]
           (runtime/via-args [{:type :ssh :host "user@host"}] "echo hi"))))

  (testing "ssh with port and key"
    (is (= ["ssh" "-o" "BatchMode=yes" "-p" "2222" "-i" "~/.ssh/work" "user@host" "echo hi"]
           (runtime/via-args [{:type :ssh :host "user@host" :port 2222 :key "~/.ssh/work"}]
                             "echo hi"))))

  (testing "single docker layer"
    (is (= ["docker" "exec" "-i" "myapp" "sh" "-c" "echo hi"]
           (runtime/via-args [{:type :docker :container "myapp"}] "echo hi"))))

  (testing "docker layer with user"
    (is (= ["docker" "exec" "-i" "--user" "root" "myapp" "sh" "-c" "echo hi"]
           (runtime/via-args [{:type :docker :container "myapp" :user "root"}] "echo hi"))))

  (testing "ssh → docker"
    (let [args (runtime/via-args [{:type :ssh :host "user@host"}
                                   {:type :docker :container "abc"}]
                                  "echo hi")]
      (is (= "ssh" (first args)))
      (is (= "user@host" (nth args 3)))
      ;; The 4th arg is the docker exec command as a string
      (is (.contains ^String (nth args 4) "docker exec -i abc"))))

  (testing "docker → ssh (reversed order)"
    (let [args (runtime/via-args [{:type :docker :container "outer"}
                                   {:type :ssh :host "internal.host"}]
                                  "echo hi")]
      ;; args = ["docker" "exec" "-i" "outer" "sh" "-c" "<ssh cmd>"]
      (is (= "docker" (first args)))
      (is (= "outer" (nth args 3)))
      (is (.contains ^String (nth args 6) "ssh -o BatchMode=yes internal.host"))))

  (testing "bash layer folds into cmd"
    (let [args (runtime/via-args [{:type :bash :setup ["source /etc/profile.d/rbenv.sh"]}]
                                  "python3 /tmp/bridge.py")]
      (is (= "/bin/sh" (first args)))
      (is (.contains ^String (nth args 2) "source /etc/profile.d/rbenv.sh"))
      (is (.contains ^String (nth args 2) "python3 /tmp/bridge.py"))))

  (testing "ssh → docker → bash folds bash into inner cmd"
    (let [args (runtime/via-args [{:type :ssh :host "user@host"}
                                   {:type :docker :container "abc"}
                                   {:type :bash :setup ["export FOO=bar"]}]
                                  "python3 /tmp/bridge.py")]
      (is (= "ssh" (first args)))
      ;; The docker exec arg should contain the bash setup
      (let [cmd-str (nth args 4)]
        (is (.contains ^String cmd-str "export FOO=bar"))
        (is (.contains ^String cmd-str "python3 /tmp/bridge.py")))))

  (testing "bash setup with multiple commands"
    (let [args (runtime/via-args [{:type :bash :setup ["cmd1" "cmd2"]}]
                                  "run")]
      (is (.contains ^String (nth args 2) "cmd1 && cmd2 && run")))))
