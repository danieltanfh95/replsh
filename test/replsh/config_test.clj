(ns replsh.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.config :as config]))

;; Access private functions for testing
(def substitute-template @#'config/substitute-template)
(def resolve-cwd @#'config/resolve-cwd)

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

(deftest resolve-toolchains-test
  (testing "returns builtins when no global config"
    (let [result (config/resolve-toolchains nil)]
      (is (contains? result "clojure.bb"))
      (is (contains? result "python.poetry"))
      (is (contains? result "node"))))

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
