(ns replsh.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [replsh.cli :as cli]
            [replsh.command :as cmd]))

;; Access private functions for testing
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
        ;; Check container toolchain
        (let [pyc (first (filter #(= "python.container" (:name %)) toolchains))]
          (is (some? pyc) "python.container should be in the list")
          (is (= "docker" (:runtime pyc))))
        ;; Should be sorted by name
        (is (= (sort (map :name toolchains))
               (map :name toolchains))
            "toolchains should be sorted by name")))))
