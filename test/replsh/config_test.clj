(ns replsh.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.config :as config]))

;; Access private functions for testing
(def substitute-template @#'config/substitute-template)
(def resolve-cwd @#'config/resolve-cwd)
(def container-cwd @#'config/container-cwd)

(deftest substitute-template-test
  (testing "replaces {port} and {cwd}"
    (is (= "bb --nrepl-server 1667"
           (substitute-template "bb --nrepl-server {port}" {:port 1667 :cwd "/tmp"}))))

  (testing "replaces {host}"
    (is (= "connect localhost:8080"
           (substitute-template "connect {host}:{port}" {:host "localhost" :port 8080 :cwd "/tmp"}))))

  (testing "replaces {cwd}"
    (is (= "/repo/.venv/bin/jupyter server"
           (substitute-template "{cwd}/.venv/bin/jupyter server" {:cwd "/repo" :port 8888}))))

  (testing "no-op when no placeholders"
    (is (= "echo hello"
           (substitute-template "echo hello" {:port 1234})))))

(deftest resolve-cwd-test
  (testing "absolute cwd is returned as-is"
    (is (= "/some/path" (resolve-cwd "/some/path" "/config-dir"))))

  (testing "relative cwd is resolved against config dir"
    (let [result (resolve-cwd "ml/" "/project")]
      (is (.endsWith result "ml"))))

  (testing "nil cwd falls back to config dir"
    (is (= "/config-dir" (resolve-cwd nil "/config-dir")))))

(deftest container-cwd-test
  (testing "extracts container path from first volume"
    (is (= "/app" (container-cwd ["/host/path:/app"]))))

  (testing "extracts from volume with mode"
    (is (= "/workspace" (container-cwd ["/host:/workspace:ro"]))))

  (testing "defaults to /workspace when no volumes"
    (is (= "/workspace" (container-cwd nil)))
    (is (= "/workspace" (container-cwd [])))))

(deftest resolve-toolchains-test
  (testing "returns builtins when no global config"
    (let [result (config/resolve-toolchains nil)]
      (is (contains? result "clojure.bb"))
      (is (contains? result "python.poetry"))
      (is (contains? result "node"))))

  (testing "bash toolchains are included"
    (let [result (config/resolve-toolchains nil)]
      (is (contains? result "bash"))
      (is (= :bash (:backend (get result "bash"))))
      (is (contains? result "bash.container"))
      (is (= :docker (:runtime (get result "bash.container"))))))

  (testing "container toolchains are included"
    (let [result (config/resolve-toolchains nil)]
      (is (contains? result "clojure.bb.container"))
      (is (contains? result "clojure.deps.container"))
      (is (contains? result "python.container"))
      (is (contains? result "node.container"))))

  (testing "user toolchains merge with builtins"
    (let [result (config/resolve-toolchains
                   {:toolchains {"custom" {:backend :nrepl :cmd "custom-cmd"}}})]
      (is (contains? result "custom"))
      (is (contains? result "clojure.bb"))))

  (testing "user toolchains override builtins"
    (let [result (config/resolve-toolchains
                   {:toolchains {"clojure.bb" {:backend :nrepl :cmd "my-bb"}}})]
      (is (= "my-bb" (:cmd (get result "clojure.bb")))))))

(deftest resolve-session-test
  (let [toolchains (config/resolve-toolchains nil)
        project-cfg {:config {:sessions {"dev" {:toolchain "clojure.bb"
                                                :port 1667}}}
                     :dir "/project"}]

    (testing "resolves toolchain defaults"
      (let [resolved (config/resolve-session toolchains project-cfg "dev" {})]
        (is (= :nrepl (:backend-type resolved)))
        (is (= :local (:runtime resolved)))
        (is (= 1667 (:port resolved)))
        (is (string? (:cmd resolved)))
        (is (.contains (:cmd resolved) "1667"))))

    (testing "CLI opts override session spec"
      (let [resolved (config/resolve-session toolchains project-cfg "dev" {:port 9999})]
        (is (= 9999 (:port resolved)))
        (is (.contains (:cmd resolved) "9999"))))

    (testing "init is passed through"
      (let [resolved (config/resolve-session toolchains project-cfg "dev"
                                             {:init "(require 'foo)"})]
        (is (= "(require 'foo)" (:init resolved)))))

    (testing "missing toolchain throws"
      (let [bad-cfg {:config {:sessions {"x" {:toolchain "nonexistent"}}}
                     :dir "/tmp"}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Toolchain not found"
                              (config/resolve-session toolchains bad-cfg "x" {})))))))

(deftest resolve-container-session-test
  (let [toolchains (config/resolve-toolchains nil)]

    (testing "container toolchain resolves with runtime :docker"
      (let [pc {:config {:sessions {"bb" {:toolchain "clojure.bb.container"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "bb" {})]
        (is (= :docker (:runtime resolved)))
        (is (= "babashka/babashka:latest" (:image resolved)))
        (is (= :nrepl (:backend-type resolved)))
        (is (= "/workspace" (:container-cwd resolved)))
        (is (.contains (:cmd resolved) "1667"))))

    (testing "python container resolves bridge to container path"
      (let [pc {:config {:sessions {"py" {:toolchain "python.container"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "py" {})]
        (is (= :docker (:runtime resolved)))
        (is (.contains (:cmd resolved) "/tmp/replsh/replsh_bridge.py"))
        (is (not (.contains (:cmd resolved) "{bridge}")))))

    (testing "session-level runtime override"
      (let [pc {:config {:sessions {"bb" {:toolchain "clojure.bb"
                                           :runtime :docker
                                           :image "custom/bb"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "bb" {})]
        (is (= :docker (:runtime resolved)))
        (is (= "custom/bb" (:image resolved)))))

    (testing "custom volumes resolve container-cwd"
      (let [pc {:config {:sessions {"bb" {:toolchain "clojure.bb"
                                           :runtime :docker
                                           :image "custom/bb"
                                           :volumes ["/host:/app"]}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "bb" {})]
        (is (= "/app" (:container-cwd resolved)))
        (is (= ["/host:/app"] (:volumes resolved)))))

    (testing "local toolchain still resolves to :local runtime"
      (let [pc {:config {:sessions {"bb" {:toolchain "clojure.bb"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "bb" {:port 1667})]
        (is (= :local (:runtime resolved)))
        (is (nil? (:image resolved)))))

    (testing "exec mode: container session without cmd resolves correctly"
      (let [pc {:config {:sessions {"api" {:toolchain "python.container"
                                            :container "my-flask-app"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "api" {})]
        (is (= :docker (:runtime resolved)))
        (is (= "my-flask-app" (:container resolved)))
        (is (= :python (:backend-type resolved)))))

    (testing "exec mode: image without cmd preserves image, no cmd required"
      (let [pc {:config {:sessions {"api" {:runtime :docker
                                            :backend :python
                                            :image "myapp:latest"}}}
                :dir "/project"}
            resolved (config/resolve-session toolchains pc "api" {})]
        (is (= :docker (:runtime resolved)))
        (is (= "myapp:latest" (:image resolved)))))))
