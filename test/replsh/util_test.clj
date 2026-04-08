(ns replsh.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [replsh.util :as util]))

(deftest gen-id-test
  (testing "produces prefixed string"
    (let [id (util/gen-id "eval")]
      (is (string? id))
      (is (.startsWith id "eval-"))
      (is (> (count id) 5))))

  (testing "each call produces a unique id"
    (is (not= (util/gen-id "a") (util/gen-id "a")))))

(deftest timestamp-test
  (testing "returns ISO-8601 string"
    (let [ts (util/timestamp)]
      (is (string? ts))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*" ts)))))

(deftest parse-address-test
  (testing "host:port"
    (is (= {:host "myhost" :port 1234}
           (util/parse-address "myhost:1234"))))

  (testing "port only defaults to localhost"
    (is (= {:host "localhost" :port 5555}
           (util/parse-address "5555"))))

  (testing "invalid address throws"
    (is (thrown? clojure.lang.ExceptionInfo
                (util/parse-address "a:b:c")))))

(deftest parse-env-args-test
  (testing "parses K=V pairs"
    (is (= {"FOO" "bar" "BAZ" "qux"}
           (util/parse-env-args ["FOO=bar" "BAZ=qux"]))))

  (testing "value with equals sign"
    (is (= {"KEY" "a=b"}
           (util/parse-env-args ["KEY=a=b"]))))

  (testing "missing equals throws"
    (is (thrown? clojure.lang.ExceptionInfo
                (util/parse-env-args ["NOPE"])))))
