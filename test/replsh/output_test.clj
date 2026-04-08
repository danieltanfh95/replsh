(ns replsh.output-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.output :as output]))

(deftest success-test
  (testing "builds success envelope"
    (let [result (output/success :eval {:value "3"})]
      (is (true? (:ok result)))
      (is (= "eval" (:command result)))
      (is (= {:value "3"} (:data result))))))

(deftest failure-test
  (testing "builds failure envelope"
    (let [result (output/failure :eval {:code "eval_error" :message "oops"})]
      (is (false? (:ok result)))
      (is (= "eval" (:command result)))
      (is (= "eval_error" (get-in result [:error :code])))
      (is (= "oops" (get-in result [:error :message])))))

  (testing "includes data when present"
    (let [result (output/failure :eval {:code "eval_error"
                                        :message "oops"
                                        :data {:chunks []}})]
      (is (= {:chunks []} (:data result))))))

(deftest emit-exit-codes-test
  (testing "exit code for success"
    (is (= 0 (binding [*out* (java.io.StringWriter.)]
               (output/emit! (output/success :eval {:value "3"}))))))

  (testing "exit code for eval_error"
    (is (= 1 (binding [*out* (java.io.StringWriter.)]
               (output/emit! (output/failure :eval {:code "eval_error" :message "x"}))))))

  (testing "exit code for timeout"
    (is (= 3 (binding [*out* (java.io.StringWriter.)]
               (output/emit! (output/failure :eval {:code "timeout" :message "x"}))))))

  (testing "exit code for other errors"
    (is (= 2 (binding [*out* (java.io.StringWriter.)]
               (output/emit! (output/failure :eval {:code "unknown" :message "x"})))))))
