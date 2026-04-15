(ns replsh.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.command]))

(def split-forms @#'replsh.command/split-forms)

(deftest split-forms-test
  (testing "nrepl splits on top-level forms"
    (let [forms (split-forms :nrepl "(+ 1 2)\n(+ 3 4)")]
      (is (= 2 (count forms)))))

  (testing "nrepl single form"
    (is (= 1 (count (split-forms :nrepl "(+ 1 2)")))))

  (testing "python splits on # ---"
    (is (= ["a = 1" "b = 2"]
           (split-forms :python "a = 1\n# ---\nb = 2"))))

  (testing "node splits on // ---"
    (is (= ["let x = 1" "let y = 2"]
           (split-forms :node "let x = 1\n// ---\nlet y = 2"))))

  (testing "unknown backend returns single-element vector"
    (is (= ["(foo)"] (split-forms :bash "(foo)")))))