(ns replsh.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [replsh.cli :as cli]
            [replsh.command :as cmd]))

;; Access private/public functions for testing
(def load-help-text @#'cli/load-help-text)

(deftest help-resource-available-test
  (testing "HELP.md is on classpath (critical for bbin install)"
    (is (some? (io/resource "HELP.md"))
        "HELP.md must be on the classpath — without it, --help shows a fallback message after bbin install")))

(deftest load-help-text-test
  (testing "loads full help text from HELP.md resource"
    (let [text (load-help-text)]
      (is (string? text))
      (is (> (count text) 100) "help text should be the full reference, not a stub")
      (is (.contains text "replsh") "help text should mention replsh")
      (is (.contains text "eval") "help text should document eval command"))))

(deftest help-dispatch-test
  (testing "--help returns nil (prints help, no envelope)"
    (is (nil? (binding [*out* (java.io.StringWriter.)]
                (cli/dispatch ["--help"])))))

  (testing "-h returns nil"
    (is (nil? (binding [*out* (java.io.StringWriter.)]
                (cli/dispatch ["-h"])))))

  (testing "help returns nil"
    (is (nil? (binding [*out* (java.io.StringWriter.)]
                (cli/dispatch ["help"])))))

  (testing "no args returns nil (shows help)"
    (is (nil? (binding [*out* (java.io.StringWriter.)]
                (cli/dispatch []))))))

(deftest install-skill-dispatch-test
  (testing "--install-skill writes SKILL.md with frontmatter"
    (let [tmp (java.io.File/createTempFile "skill" ".md")
          path (.getAbsolutePath tmp)]
      (try
        (binding [*out* (java.io.StringWriter.)]
          (cli/dispatch ["--install-skill" "--path" path]))
        (let [content (slurp path)]
          (is (.startsWith content "---") "should start with YAML frontmatter")
          (is (.contains content "name: replsh") "frontmatter should have name")
          (is (.contains content "# replsh") "should contain help content body"))
        (finally
          (.delete tmp))))))

(deftest toolchains-cmd-test
  (testing "returns success envelope with toolchain list"
    (let [result (cmd/toolchains-cmd)]
      (is (true? (:ok result)))
      (is (= "toolchains" (:command result)))
      (let [toolchains (get-in result [:data :toolchains])]
        (is (vector? toolchains))
        (is (pos? (count toolchains)))
        ;; Check a known built-in toolchain
        (let [bb (first (filter #(= "clojure.bb" (:name %)) toolchains))]
          (is (some? bb) "clojure.bb should be in the list")
          (is (= "nrepl" (:backend bb)))
          (is (= "local" (:runtime bb)))
          (is (= 1667 (:port bb))))
        ;; Check bash toolchain
        (let [b (first (filter #(= "bash" (:name %)) toolchains))]
          (is (some? b) "bash should be in the list")
          (is (= "bash" (:backend b)))
          (is (= "local" (:runtime b))))
        ;; Should be sorted by name
        (is (= (sort (map :name toolchains))
               (map :name toolchains))
            "toolchains should be sorted by name")))))

(deftest parse-pipeline-test
  (testing "empty args returns empty :via and nil toolchain"
    (is (= {:via [] :toolchain-name nil}
           (cli/parse-pipeline []))))

  (testing "ssh + toolchain"
    (is (= {:via [{:type :ssh :host "user@host"}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "user@host"
                                 "--toolchain" "python"]))))

  (testing "ssh → docker → toolchain"
    (is (= {:via [{:type :ssh :host "user@host"}
                  {:type :docker :container "my-container"}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "user@host"
                                 "--runtime" "docker" "--container" "my-container"
                                 "--toolchain" "python"]))))

  (testing "docker → ssh (reversed order)"
    (is (= {:via [{:type :docker :container "outer"}
                  {:type :ssh :host "internal.host"}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "docker" "--container" "outer"
                                 "--runtime" "ssh" "--host" "internal.host"
                                 "--toolchain" "python"]))))

  (testing "ssh → docker → bash → toolchain"
    (is (= {:via [{:type :ssh :host "user@host"}
                  {:type :docker :container "my-container"}
                  {:type :bash :setup ["source /etc/profile.d/rbenv.sh"]}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "user@host"
                                 "--runtime" "docker" "--container" "my-container"
                                 "--runtime" "bash" "--setup" "source /etc/profile.d/rbenv.sh"
                                 "--toolchain" "python"]))))

  (testing "bash --setup accumulates multiple values"
    (is (= {:via [{:type :bash :setup ["cmd1" "cmd2"]}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "bash"
                                 "--setup" "cmd1" "--setup" "cmd2"
                                 "--toolchain" "python"]))))

  (testing "ssh with port and key"
    (is (= {:via [{:type :ssh :host "user@host" :port 2222 :key "~/.ssh/work"}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "user@host"
                                 "--port" "2222" "--key" "~/.ssh/work"
                                 "--toolchain" "python"]))))

  (testing "docker with image (spawn new container)"
    (is (= {:via [{:type :ssh :host "bastion"}
                  {:type :docker :image "python:3.12"}]
            :toolchain-name "python"}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "bastion"
                                 "--runtime" "docker" "--image" "python:3.12"
                                 "--toolchain" "python"]))))

  (testing "no toolchain flag"
    (is (= {:via [{:type :ssh :host "user@host"}]
            :toolchain-name nil}
           (cli/parse-pipeline ["--runtime" "ssh" "--host" "user@host"])))))
