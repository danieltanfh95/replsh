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
    (is (not= (util/gen-id "a") (util/gen-id "a"))))

  (testing "suffix is 8 hex characters"
    (let [id (util/gen-id "eval")]
      (is (re-matches #"eval-[0-9a-f]{8}" id)))))

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

(deftest find-free-port-test
  (testing "returns a valid port number"
    (let [port (util/find-free-port)]
      (is (integer? port))
      (is (> port 0))
      (is (< port 65536)))))

(deftest log-dir-test
  (testing "ends with /.replsh/logs/"
    (is (clojure.string/ends-with? util/log-dir "/.replsh/logs/"))))

(deftest read-edn-file-test
  (testing "returns nil when file does not exist"
    (is (nil? (util/read-edn-file "/nonexistent/path/that/does/not/exist.edn"))))

  (testing "reads and parses EDN from existing file"
    (let [f (java.io.File/createTempFile "test" ".edn")]
      (try
        (spit f (pr-str {:a 1 :b "two"}))
        (is (= {:a 1 :b "two"} (util/read-edn-file (.getAbsolutePath f))))
        (finally (.delete f))))))

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
