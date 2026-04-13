(ns replsh.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [replsh.command :as cmd]
            [replsh.state :as state]
            [replsh.process :as process]
            [replsh.watch :as watch])
  (:import [java.io File]))

;; Use a temp state file so tests don't pollute real state
(def ^:dynamic *test-state-dir* nil)

(defn with-temp-state [f]
  (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/replsh-test-" (System/currentTimeMillis))
        state-file (str tmp-dir "/state.edn")]
    (.mkdirs (File. tmp-dir))
    (System/setProperty "REPLSH_STATE" state-file)
    ;; Override state path via env not possible in-process, so we set system property
    ;; Actually, state.clj reads REPLSH_STATE env var. We need a different approach.
    ;; Use with-redefs to override state-path
    (binding [*test-state-dir* tmp-dir]
      (with-redefs [state/state-path (constantly state-file)]
        (try
          (f)
          (finally
            ;; Clean up any launched processes
            (let [st (state/load-state)]
              (doseq [[_ session] (:sessions st)]
                (when-let [pid (get-in session [:launch :pid])]
                  (try (process/kill! pid) (catch Exception _)))))
            ;; Clean up temp files
            (doseq [file (reverse (file-seq (File. tmp-dir)))]
              (.delete file))))))))

(use-fixtures :each with-temp-state)

(deftest launch-eval-stop-test
  (testing "full lifecycle: launch -> eval -> stop"
    ;; Launch a bb nREPL server
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest"
                                         :host         "localhost"
                                         :port         16670
                                         :cmd          "bb --nrepl-server 16670"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         nil
                                         :timeout      30000})]
      (is (true? (:ok launch-result)))
      (is (= "launch" (:command launch-result)))

      ;; Eval
      (let [eval-result (cmd/eval-cmd {:name "itest"
                                       :code "(+ 1 2)"
                                       :timeout 10000})]
        (is (true? (:ok eval-result)))
        (is (= "3" (get-in eval-result [:data :value])))
        (is (= "complete" (:status eval-result)))
        (is (nil? (get-in eval-result [:data :chunks])) "chunks should not be in default output"))

      ;; Eval with stdout
      (let [eval-result (cmd/eval-cmd {:name "itest"
                                       :code "(do (println \"hi\") 42)"
                                       :timeout 10000})]
        (is (true? (:ok eval-result)))
        (is (= "42" (get-in eval-result [:data :value])))
        (is (= "hi\n" (get-in eval-result [:data :output])))
        (is (nil? (get-in eval-result [:data :chunks]))))

      ;; Eval with --chunked
      (let [eval-result (cmd/eval-cmd {:name "itest"
                                       :code "(do (println \"hi\") 42)"
                                       :timeout 10000
                                       :chunked? true})]
        (is (true? (:ok eval-result)))
        (is (= "42" (get-in eval-result [:data :value])))
        (is (= "hi\n" (get-in eval-result [:data :output])))
        (is (vector? (get-in eval-result [:data :chunks])) "chunks should be present with --chunked"))

      ;; Status
      (let [status-result (cmd/status-cmd {:name "itest"})]
        (is (true? (:ok status-result)))
        (is (true? (get-in status-result [:data :reachable]))))

      ;; Stop
      (let [stop-result (cmd/stop-cmd {:name "itest"})]
        (is (true? (:ok stop-result)))))))

(deftest launch-with-init-test
  (testing "launch with --init runs bootstrap code"
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest-init"
                                         :host         "localhost"
                                         :port         16671
                                         :cmd          "bb --nrepl-server 16671"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         "(def test-var 42)"
                                         :timeout      30000})]
      (is (true? (:ok launch-result)))

      ;; Verify init code ran
      (let [eval-result (cmd/eval-cmd {:name "itest-init"
                                       :code "test-var"
                                       :timeout 10000})]
        (is (true? (:ok eval-result)))
        (is (= "42" (get-in eval-result [:data :value])))
        (is (= "complete" (:status eval-result))))

      (cmd/stop-cmd {:name "itest-init"}))))

(deftest restart-test
  (testing "restart relaunches with new PID"
    (let [launch-result (cmd/launch-cmd {:backend-type :nrepl
                                         :name         "itest-restart"
                                         :host         "localhost"
                                         :port         16672
                                         :cmd          "bb --nrepl-server 16672"
                                         :cwd          "/tmp"
                                         :env          {}
                                         :kernel       nil
                                         :token        nil
                                         :prompt-re    nil
                                         :init         nil
                                         :timeout      30000})
          old-pid (get-in launch-result [:data :pid])]
      (is (true? (:ok launch-result)))

      (let [restart-result (cmd/restart-cmd {:name "itest-restart"})]
        (is (true? (:ok restart-result)))
        (is (not= old-pid (get-in restart-result [:data :pid]))))

      (cmd/stop-cmd {:name "itest-restart"}))))

(deftest ls-test
  (testing "ls lists launched sessions"
    (let [_ (cmd/launch-cmd {:backend-type :nrepl
                              :name         "itest-ls"
                              :host         "localhost"
                              :port         16673
                              :cmd          "bb --nrepl-server 16673"
                              :cwd          "/tmp"
                              :env          {}
                              :kernel       nil
                              :token        nil
                              :prompt-re    nil
                              :init         nil
                              :timeout      30000})
          ls-result (cmd/ls-cmd)]
      (is (true? (:ok ls-result)))
      (is (= 1 (count (get-in ls-result [:data :sessions]))))
      (is (= "itest-ls" (get-in ls-result [:data :sessions 0 :name])))

      (cmd/stop-cmd {:name "itest-ls"}))))

(def ^:private base-launch-opts
  {:backend-type :nrepl
   :host         "localhost"
   :env          {}
   :kernel       nil
   :token        nil
   :prompt-re    nil
   :init         nil
   :timeout      30000})

(deftest same-name-collision-test
  (testing "Fix 0 — launching with an existing live name throws :session-exists without --force"
    (let [launch1 (cmd/launch-cmd (merge base-launch-opts
                                         {:name "itest-collider"
                                          :port 16674
                                          :cmd  "bb --nrepl-server 16674"
                                          :cwd  "/tmp"}))]
      (is (true? (:ok launch1)))
      (try
        (let [ex (try
                   (cmd/launch-cmd (merge base-launch-opts
                                          {:name "itest-collider"
                                           :port 16675
                                           :cmd  "bb --nrepl-server 16675"
                                           :cwd  "/tmp"}))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "relaunch should have thrown")
          (is (= :session-exists (:code (ex-data ex)))))
        (finally
          (cmd/stop-cmd {:name "itest-collider"})))))

  (testing "Fix 0 — --force replaces an existing live session"
    (let [_     (cmd/launch-cmd (merge base-launch-opts
                                       {:name "itest-forced"
                                        :port 16676
                                        :cmd  "bb --nrepl-server 16676"
                                        :cwd  "/tmp"}))
          relaunched (cmd/launch-cmd (merge base-launch-opts
                                            {:name  "itest-forced"
                                             :port  16677
                                             :cmd   "bb --nrepl-server 16677"
                                             :cwd   "/tmp"
                                             :force true}))]
      (is (true? (:ok relaunched)))
      (cmd/stop-cmd {:name "itest-forced"})))

  (testing "Fix 0 — dead corpse is silently cleaned up without --force"
    ;; Launch a session, then kill the process and remove the port listener so
    ;; session-reachable? returns false — the corpse should be overwritten
    ;; silently on the next launch call.
    (let [launch1 (cmd/launch-cmd (merge base-launch-opts
                                         {:name "itest-corpse"
                                          :port 16678
                                          :cmd  "bb --nrepl-server 16678"
                                          :cwd  "/tmp"}))
          pid     (get-in launch1 [:data :pid])]
      (is (true? (:ok launch1)))
      ;; Kill the real process (bypassing replsh) so both liveness checks fail
      (try (process/kill! pid) (catch Exception _))
      ;; Wait for the port to actually be freed
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (loop []
          (when (and (< (System/currentTimeMillis) deadline)
                     (try (let [s (java.net.Socket. "localhost" 16678)]
                            (.close s)
                            true)
                          (catch Exception _ false)))
            (Thread/sleep 200)
            (recur))))
      (let [relaunched (cmd/launch-cmd (merge base-launch-opts
                                              {:name "itest-corpse"
                                               :port 16679
                                               :cmd  "bb --nrepl-server 16679"
                                               :cwd  "/tmp"}))]
        (is (true? (:ok relaunched)) "dead corpse should be silently overwritten")
        (cmd/stop-cmd {:name "itest-corpse"})))))

(deftest port-in-use-test
  (testing "Fix 1 — launching when the port is already bound throws :port-already-in-use"
    (let [srv (java.net.ServerSocket. 16680)]
      (try
        (let [ex (try
                   (cmd/launch-cmd (merge base-launch-opts
                                          {:name "itest-port-busy"
                                           :port 16680
                                           :cmd  "bb --nrepl-server 16680"
                                           :cwd  "/tmp"}))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "launch should have aborted")
          (is (= :port-already-in-use (:code (ex-data ex))))
          ;; No session should have been written to state
          (is (nil? (state/get-session (state/load-state) "itest-port-busy"))))
        (finally
          (.close srv))))))

(deftest probe-cwd-test
  (testing "Fix 2 — nrepl open! probes the runtime's real cwd and it lands in session :env :cwd"
    ;; Use a path that has no symlink indirection so the probed value matches
    ;; the path we passed. (On macOS, /tmp is a symlink to /private/tmp, and
    ;; the JVM's user.dir reports the resolved path — which is the point of
    ;; probing: replsh should report reality, not CLI input.)
    (let [real-tmp (.getCanonicalPath (File. "/tmp"))
          _ (cmd/launch-cmd (merge base-launch-opts
                                   {:name "itest-probe"
                                    :port 16681
                                    :cmd  "bb --nrepl-server 16681"
                                    :cwd  real-tmp}))
          session (state/get-session (state/load-state) "itest-probe")]
      (is (= real-tmp (get-in session [:env :cwd]))
          "session :env :cwd should reflect what the runtime reports, not CLI input")
      (cmd/stop-cmd {:name "itest-probe"}))))

(deftest stale-detection-test
  (testing "stale files are detected after source file mtime changes"
    (let [util-file (io/file "src/replsh/util.clj")]
      ;; Only run if the source file exists (i.e., running from project root)
      (when (.exists util-file)
        (let [_ (cmd/launch-cmd (merge base-launch-opts
                                       {:name "itest-stale"
                                        :port 16686
                                        :cmd  "bb --nrepl-server 16686"
                                        :cwd  (System/getProperty "user.dir")}))]
          (try
            ;; With idle threshold = 0, every eval triggers stale detection.
            (with-redefs [watch/idle-threshold-ms (constantly 0)]
              ;; First eval: establishes baseline mtimes (no stale yet since no prior baseline)
              (let [r1 (cmd/eval-cmd {:name    "itest-stale"
                                      :code    "(require '[replsh.util])"
                                      :timeout 10000})]
                (is (true? (:ok r1)) "require should succeed")
                (is (nil? (get-in r1 [:data :stale])) "no stale on first check (baseline only)"))
              ;; Touch the source file to advance its mtime
              (.setLastModified util-file (System/currentTimeMillis))
              ;; Second eval: should detect the changed mtime
              (let [r2 (cmd/eval-cmd {:name    "itest-stale"
                                      :code    "(+ 1 1)"
                                      :timeout 10000})]
                (is (true? (:ok r2)) "eval should succeed")
                (is (seq (get-in r2 [:data :stale])) "stale files should be reported after mtime change")))
            (finally
              (cmd/stop-cmd {:name "itest-stale"}))))))))

;; --- bbin install smoke tests ---
;; These shell out to the actual installed binary to verify end-to-end behavior.
;; They don't use the temp-state fixture (no in-process state needed).

(defn- run-cmd
  "Run a command and return {:exit int :out string :err string}."
  [& args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec args))
             (.directory (File. (System/getProperty "user.dir")))
             (.redirectErrorStream false))
        proc (.start pb)
        out  (slurp (.getInputStream proc))
        err  (slurp (.getErrorStream proc))
        exit (.waitFor proc)]
    {:exit exit :out out :err err}))

(defn- bbin-available? []
  (try
    (let [{:keys [exit]} (run-cmd "which" "bbin")]
      (zero? exit))
    (catch Exception _ false)))

(deftest bbin-install-smoke-test
  (if-not (bbin-available?)
    (println "  SKIP bbin-install-smoke-test: bbin not found on PATH")
    (do
      (testing "bbin install succeeds"
        (let [{:keys [exit err]} (run-cmd "bbin" "install" ".")]
          (is (zero? exit) (str "bbin install failed: " err))))

      (testing "replsh --help shows full reference (not fallback stub)"
        (let [{:keys [exit out]} (run-cmd "replsh" "--help")]
          (is (zero? exit))
          (is (.contains out "# replsh") "--help should show the HELP.md content")
          (is (.contains out "Command Reference") "--help should include the full reference")
          (is (not (.contains out "resource not found")) "--help should not show fallback message")))

      (testing "replsh toolchains returns valid JSON envelope"
        (let [{:keys [exit out]} (run-cmd "replsh" "toolchains")]
          (is (zero? exit))
          (let [parsed (json/parse-string (str/trim out) true)]
            (is (true? (:ok parsed)))
            (is (= "toolchains" (:command parsed)))
            (is (pos? (count (get-in parsed [:data :toolchains])))))))

      (testing "replsh defaults to exit 0 on error"
        (let [{:keys [exit out]} (run-cmd "replsh" "eval" "--name" "nonexistent" "(+ 1 2)")]
          (is (zero? exit) "should exit 0 by default even on error")
          (let [parsed (json/parse-string (str/trim out) true)]
            (is (false? (:ok parsed))))))

      (testing "replsh --exit-on-error exits non-zero on error"
        (let [{:keys [exit out]} (run-cmd "replsh" "--exit-on-error" "eval" "--name" "nonexistent" "(+ 1 2)")]
          (is (= 2 exit) "should exit 2 (client error) with --exit-on-error")
          (let [parsed (json/parse-string (str/trim out) true)]
            (is (false? (:ok parsed)))))))))

