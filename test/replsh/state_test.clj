(ns replsh.state-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [replsh.state :as state]))

(defn- with-tmp-state-path*
  "Run f with state-path redirected to a fresh tmp file."
  [f]
  (let [tmp     (str (System/getProperty "java.io.tmpdir") "/replsh-state-test-" (System/currentTimeMillis) "-" (rand-int 1000000) ".edn")]
    (with-redefs [state/state-path (constantly tmp)]
      (try
        (f)
        (finally
          (.delete (io/file tmp))
          (.delete (io/file (str tmp ".lock")))
          (doseq [stray (.listFiles (.getParentFile (io/file tmp)))]
            (when (re-find #"replsh-state-test-.*\.tmp$" (.getName stray))
              (.delete stray))))))))

(defmacro ^:private with-tmp-state [& body]
  `(with-tmp-state-path* (fn [] ~@body)))

(deftest update-session!-inserts-and-updates
  (with-tmp-state
    (testing "inserts a new session and sets :active when none"
      (state/update-session! "a" (constantly {:name "a" :backend :python}))
      (let [s (state/load-state)]
        (is (= "a" (:active s)))
        (is (= {:name "a" :backend :python} (state/get-session s "a")))))

    (testing "updates an existing session preserving other sessions"
      (state/update-session! "b" (constantly {:name "b" :backend :nrepl}))
      (state/update-session! "a" (fn [old] (assoc old :counter 1)))
      (let [s (state/load-state)]
        (is (= 1 (:counter (state/get-session s "a"))))
        (is (= :nrepl (:backend (state/get-session s "b"))))))

    (testing "merge fn returning nil leaves state untouched"
      (let [before (state/load-state)]
        (state/update-session! "ghost" (constantly nil))
        (is (= before (state/load-state)))))))

(deftest remove-session!-test
  (with-tmp-state
    (state/update-session! "a" (constantly {:name "a"}))
    (state/update-session! "b" (constantly {:name "b"}))
    (testing "removing the active session promotes another to :active"
      (state/remove-session! "a")
      (let [s (state/load-state)]
        (is (nil? (state/get-session s "a")))
        (is (= "b" (:active s)))))
    (testing "removing the last session leaves :active nil"
      (state/remove-session! "b")
      (let [s (state/load-state)]
        (is (nil? (:active s)))
        (is (empty? (:sessions s)))))))

(deftest concurrent-updaters-do-not-clobber
  (with-tmp-state
    (testing "100 parallel increments to the same session all persist"
      (state/update-session! "ctr" (constantly {:name "ctr" :counter 0}))
      (let [n    100
            futs (mapv (fn [_]
                         (future
                           (state/update-session! "ctr"
                             (fn [s] (update s :counter (fnil inc 0))))))
                       (range n))]
        (run! deref futs)
        (is (= n (:counter (state/get-session (state/load-state) "ctr"))))))

    (testing "parallel inserts of distinct sessions all persist"
      (let [n    50
            futs (mapv (fn [i]
                         (future
                           (state/update-session! (str "s-" i)
                             (constantly {:name (str "s-" i) :i i}))))
                       (range n))]
        (run! deref futs)
        (let [s (state/load-state)]
          (is (every? #(= % (:i (state/get-session s (str "s-" %)))) (range n))))))))

(deftest stale-lock-is-stolen
  (with-tmp-state
    (testing "lockfile owned by a dead pid is stolen by next acquirer"
      ;; Use pid 999999999 — outside the legal range, guaranteed not alive.
      (spit (state/lock-path)
            (pr-str {:pid 999999999 :acquired-at "2026-01-01T00:00:00Z"}))
      ;; Should complete — the stale lock is reaped.
      (state/update-state! identity)
      (is (true? true)))))

(deftest save-state!-is-atomic
  (with-tmp-state
    (testing "save-state! never leaves a half-written file readable"
      ;; Hammer save-state! while readers race against it. With a non-atomic
      ;; spit, readers occasionally observe partial content and get parse
      ;; errors. With ATOMIC_MOVE, every read sees a complete prior version
      ;; or the new version — never a torn write.
      (state/update-state! (constantly {:active "x" :sessions {"x" {:name "x"}}}))
      (let [stop?   (atom false)
            errors  (atom 0)
            writers (mapv (fn [i]
                            (future
                              (dotimes [_ 50]
                                (state/update-state!
                                  (fn [s] (assoc-in s [:sessions "x" :i] i))))))
                          (range 4))
            readers (mapv (fn [_]
                            (future
                              (while (not @stop?)
                                (try
                                  (let [s (state/load-state)]
                                    (when-not (and (map? s) (map? (:sessions s)))
                                      (swap! errors inc)))
                                  (catch Exception _
                                    (swap! errors inc))))))
                          (range 2))]
        (run! deref writers)
        (reset! stop? true)
        (run! deref readers)
        (is (zero? @errors))))))
