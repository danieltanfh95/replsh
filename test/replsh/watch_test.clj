(ns replsh.watch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [replsh.watch :as watch]))

(deftest idle?-test
  (testing "nil last-eval-at is treated as idle (new session needs baseline)"
    (is (true? (watch/idle? {})))
    (is (true? (watch/idle? {:last-eval-at nil}))))

  (testing "recent eval is not idle"
    (is (false? (watch/idle? {:last-eval-at (System/currentTimeMillis)}))))

  (testing "eval older than threshold is idle"
    (let [expired (- (System/currentTimeMillis) (inc (watch/idle-threshold-ms)))]
      (is (true? (watch/idle? {:last-eval-at expired}))))))

(deftest diff-mtimes-test
  (testing "returns nil when stored-mtimes is nil (first check, baseline)"
    (let [snapshot [{:ns "foo" :file "foo.py" :mtime 1000}]]
      (is (nil? (watch/diff-mtimes snapshot nil)))))

  (testing "returns nil when no files are stale"
    (let [snapshot [{:ns "foo" :file "foo.py" :mtime 1000}
                    {:ns "bar" :file "bar.py" :mtime 2000}]
          stored   {"foo.py" 1000 "bar.py" 2000}]
      (is (nil? (watch/diff-mtimes snapshot stored)))))

  (testing "returns stale entries when mtime changed"
    (let [snapshot [{:ns "foo" :file "foo.py" :mtime 9999}
                    {:ns "bar" :file "bar.py" :mtime 2000}]
          stored   {"foo.py" 1000 "bar.py" 2000}
          result   (watch/diff-mtimes snapshot stored)]
      (is (= [{:ns "foo" :file "foo.py"}] result))))

  (testing "ignores files not present in stored-mtimes (new files)"
    (let [snapshot [{:ns "new" :file "new.py" :mtime 5000}]
          stored   {"old.py" 1000}]
      (is (nil? (watch/diff-mtimes snapshot stored))))))

(deftest parse-introspection-test
  (testing "parses nREPL EDN value chunk"
    (let [chunks [{:type :value :content "[{:ns \"foo.core\" :file \"foo/core.clj\" :mtime 12345}]"}]
          result (watch/parse-introspection :nrepl chunks)]
      (is (= [{:ns "foo.core" :file "foo/core.clj" :mtime 12345}] result))))

  (testing "parses Python JSON value chunk"
    (let [chunks [{:type :value :content "[{\"ns\": \"foo\", \"file\": \"foo.py\", \"mtime\": 1234.5}]"}]
          result (watch/parse-introspection :python chunks)]
      (is (= 1 (count result)))
      (is (= "foo" (:ns (first result))))))

  (testing "parses jupyter the same as python"
    (let [chunks [{:type :value :content "[{\"ns\": \"bar\", \"file\": \"bar.py\", \"mtime\": 99.0}]"}]
          result (watch/parse-introspection :jupyter chunks)]
      (is (= "bar" (:ns (first result))))))

  (testing "returns nil when no value chunk present"
    (let [chunks [{:type :out :content "some stdout"}]]
      (is (nil? (watch/parse-introspection :nrepl chunks)))))

  (testing "returns nil on malformed content"
    (let [chunks [{:type :value :content "{{not valid edn"}]]
      (is (nil? (watch/parse-introspection :nrepl chunks)))))

  (testing "returns nil on nil input"
    (is (nil? (watch/parse-introspection :nrepl nil)))))

(deftest introspection-code-test
  (testing "returns non-empty string for each supported backend"
    (doseq [backend [:nrepl :python :jupyter :node]]
      (let [code (watch/introspection-code backend)]
        (is (string? code) (str "expected string for " backend))
        (is (not (clojure.string/blank? code)) (str "expected non-blank for " backend)))))

  (testing "nREPL code is valid Clojure syntax (parseable)"
    (let [code (watch/introspection-code :nrepl)]
      (is (some? (read-string code)))))

  (testing "Python IIFE does not assign bare variables"
    (let [code (watch/introspection-code :python)]
      (is (not (re-find #"(?m)^cwd\s*=" code)))
      (is (not (re-find #"(?m)^result\s*=" code)))
      (is (str/includes? code "(lambda"))))

  (testing "Node code references require.cache and JSON.stringify"
    (let [code (watch/introspection-code :node)]
      (is (str/includes? code "require.cache"))
      (is (str/includes? code "JSON.stringify")))))
