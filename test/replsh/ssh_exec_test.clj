(ns replsh.ssh-exec-test
  "Integration tests for the :exec runtime covering docker-only, SSH-only,
   and SSH+Docker scenarios.

   Prerequisites:
   - Docker running locally (docker info must succeed)
   - ssh and ssh-keygen on PATH (for SSH tests)
   - python:3.11-slim image (pulled on first run)

   The SSH tests start containers with sshd installed via apt-get, generate a
   throwaway key pair, and write a Host block to ~/.ssh/config that is removed
   in the fixture teardown.  This is exactly what users do in practice, so it
   also exercises the real SSH config resolution path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [replsh.command :as cmd]
            [replsh.state :as state]
            [replsh.process :as process])
  (:import [java.io File]))

;; ── prerequisites ─────────────────────────────────────────────────────────────

(defn- docker-available? []
  (try (zero? (.waitFor (.start (ProcessBuilder. ["docker" "info"]))))
       (catch Exception _ false)))

(defn- ssh-available? []
  (try (zero? (.waitFor (.start (ProcessBuilder. ["which" "ssh-keygen"]))))
       (catch Exception _ false)))

;; ── process / container helpers ───────────────────────────────────────────────

(defn- run-proc!
  "Run a command synchronously.  Returns {:exit int :out string}."
  [args]
  (let [pb    (ProcessBuilder. ^java.util.List (vec args))
        proc  (.start pb)
        out   (slurp (.getInputStream proc))
        exit  (.waitFor proc)]
    {:exit exit :out (str/trim out)}))

(defn- start-container-alive!
  "Start a python:3.11-slim container that sleeps forever. Returns container id."
  []
  (let [{:keys [out]} (run-proc! ["docker" "run" "-d" "python:3.11-slim" "tail" "-f" "/dev/null"])]
    out))

(defn- stop-container!
  "Force-remove a container, ignoring errors."
  [cid]
  (try (run-proc! ["docker" "rm" "-f" cid]) (catch Exception _)))

(defn- mapped-port!
  "Return the host port mapped to container-internal port.  Returns nil if not found."
  [cid internal-port]
  (let [{:keys [out]} (run-proc! ["docker" "port" cid (str internal-port)])]
    (when-let [m (re-find #":(\d+)$" out)]
      (Integer/parseInt (second m)))))

(defn- wait-for-ssh!
  "Poll until an actual SSH connection succeeds (not just TCP open).
   Docker's port proxy accepts TCP before sshd is ready, so a plain
   TCP probe is not sufficient — we need the SSH banner to appear.
   Assumes the alias is already in ~/.ssh/config (HostName/Port/User/Key
   all configured there).  Polls every 3s up to timeout-ms."
  [alias timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "SSH not ready on " alias " after " timeout-ms "ms")
                        {:code :timeout :alias alias})))
      (let [proc (-> (ProcessBuilder. ^java.util.List
                                      ["ssh" "-o" "ConnectTimeout=5" alias "true"])
                     .start)
            exit (.waitFor proc)]
        (if (zero? exit)
          true
          (do (Thread/sleep 3000) (recur)))))))

;; ── SSH key helpers ───────────────────────────────────────────────────────────

(defn- gen-ssh-keypair!
  "Generate a throwaway ed25519 key pair under a temp dir.
   Returns {:private path :public content :dir dir}."
  []
  (let [dir  (str (System/getProperty "java.io.tmpdir")
                   "/replsh-ssh-test-" (System/currentTimeMillis))
        priv (str dir "/id_ed25519")]
    (.mkdirs (File. dir))
    (run-proc! ["ssh-keygen" "-t" "ed25519" "-f" priv "-N" "" "-q"])
    {:private priv
     :public  (str/trim (slurp (str priv ".pub")))
     :dir     dir}))

(defn- delete-dir! [dir]
  (doseq [f (reverse (file-seq (File. dir)))] (.delete f)))

;; ── SSH container helpers ─────────────────────────────────────────────────────

(def ^:private python-image "python:3.11-slim")

(defn- pull-image! [image]
  (run-proc! ["docker" "pull" "-q" image]))

(defn- shell-str [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- sshd-setup-cmd
  "Build the container startup command that installs sshd, seeds authorized_keys,
   and starts the daemon.  extra-pkgs is a seq of extra apt packages to install."
  [pub-key & extra-pkgs]
  (str/join " && "
    (concat
      ["apt-get update -q"
       (str "apt-get install -y -q openssh-server " (str/join " " extra-pkgs))
       "mkdir -p /root/.ssh"
       "chmod 700 /root/.ssh"
       ;; Use printf to avoid issues with key content in shell expansion
       (str "printf '%s\\n' " (shell-str pub-key) " > /root/.ssh/authorized_keys")
       "chmod 600 /root/.ssh/authorized_keys"
       "ssh-keygen -A -q"
       "mkdir -p /run/sshd"
       "/usr/sbin/sshd -D -o PermitRootLogin=yes -o UsePAM=no -o LogLevel=ERROR"])))

(defn- start-ssh-container!
  "Start a container with sshd + Python.  extra-docker-args are inserted before
   the image name (e.g. volume mounts).  Returns {:container-id :ssh-port}."
  [pub-key & {:keys [extra-docker-args extra-pkgs]
              :or   {extra-docker-args [] extra-pkgs []}}]
  (let [setup   (apply sshd-setup-cmd pub-key extra-pkgs)
        args    (concat ["docker" "run" "-d" "-p" "0:22"]
                        extra-docker-args
                        [python-image "/bin/sh" "-c" setup])
        {:keys [out]} (run-proc! args)
        cid     out
        ssh-port (mapped-port! cid 22)]
    {:container-id cid :ssh-port ssh-port}))

;; ── ~/.ssh/config management ──────────────────────────────────────────────────

(defn- ssh-config-path []
  (str (System/getProperty "user.home") "/.ssh/config"))

(defn- append-ssh-host!
  "Append a Host block for test use.  Returns the exact string appended
   (used to remove it in teardown)."
  [alias port key-file]
  (let [block (str "\n# replsh-test-block-start:" alias "\n"
                   "Host " alias "\n"
                   "  HostName localhost\n"
                   "  Port " port "\n"
                   "  User root\n"
                   "  IdentityFile " key-file "\n"
                   "  StrictHostKeyChecking no\n"
                   "  UserKnownHostsFile /dev/null\n"
                   "  BatchMode yes\n"
                   "# replsh-test-block-end:" alias "\n")]
    (.mkdirs (.getParentFile (File. (ssh-config-path))))  ; ensure ~/.ssh exists
    (spit (ssh-config-path) block :append true)
    block))

(defn- remove-ssh-host!
  "Remove a previously appended Host block from ~/.ssh/config."
  [block]
  (let [f (File. (ssh-config-path))]
    (when (.exists f)
      (spit f (str/replace (slurp f) block "")))))

;; ── test state fixture ────────────────────────────────────────────────────────

(defn- with-temp-state [f]
  (let [tmp   (str (System/getProperty "java.io.tmpdir")
                    "/replsh-exec-test-" (System/currentTimeMillis))
        sfile (str tmp "/state.edn")]
    (.mkdirs (File. tmp))
    (with-redefs [state/state-path (constantly sfile)]
      (try
        (f)
        (finally
          ;; Kill any bridge processes left in state
          (doseq [[_ sess] (:sessions (state/load-state))]
            (when-let [pid (get-in sess [:launch :bridge-pid])]
              (try (process/kill! pid) (catch Exception _))))
          (doseq [file (reverse (file-seq (File. tmp)))]
            (.delete file)))))))

(use-fixtures :each with-temp-state)

;; ── helpers used inside tests ─────────────────────────────────────────────────

(defn- exec-launch!
  "Call launch-exec-cmd! with sensible defaults.  Returns the result map."
  [opts]
  (cmd/launch-exec-cmd! (merge {:backend-type :python
                                 :container    nil
                                 :image        nil
                                 :ssh-host     nil
                                 :env          nil
                                 :volumes      nil
                                 :platform     nil
                                 :init         nil
                                 :timeout      30000
                                 :exec-port    9876}
                                opts)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Test 1: Docker exec-mode regression
;;
;; Verifies that the migrated :exec runtime works for plain docker exec-mode
;; (the original use case — no SSH).  This would have used :runtime :docker
;; before the SSH refactor.
;; ─────────────────────────────────────────────────────────────────────────────

(deftest docker-exec-regression-test
  (if-not (docker-available?)
    (println "  SKIP docker-exec-regression-test: Docker not available")
    (do
      (pull-image! python-image)
      (let [cid (start-container-alive!)]
        (try
          (testing "launch exec-mode into existing container"
            (let [r (exec-launch! {:name "exec-regr" :container cid})]
              (is (true? (:ok r)))
              (is (= "exec"    (get-in r [:data :runtime])))
              (is (= cid       (get-in r [:data :container-id])))
              (is (false?      (get-in r [:data :owned])))))

          (testing "eval returns correct output"
            (let [r (cmd/eval-cmd {:name "exec-regr" :code "print(1 + 1)" :timeout 10000})]
              (is (true? (:ok r)))
              (is (= "2\n" (get-in r [:data :output])))))

          (testing "state persists across evals"
            (cmd/eval-cmd {:name "exec-regr" :code "x = 42" :timeout 10000})
            (let [r (cmd/eval-cmd {:name "exec-regr" :code "print(x)" :timeout 10000})]
              (is (= "42\n" (get-in r [:data :output])))))

          (testing "stop succeeds"
            (is (true? (:ok (cmd/stop-cmd {:name "exec-regr"})))))

          (finally
            (stop-container! cid)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Test 2: SSH-only exec-mode
;;
;; Starts a container with sshd + Python, writes a temporary Host block to
;; ~/.ssh/config, and runs the full launch → eval → stop lifecycle.
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ssh-exec-test
  (if-not (and (docker-available?) (ssh-available?))
    (println "  SKIP ssh-exec-test: Docker or ssh-keygen not available")
    (let [kp (gen-ssh-keypair!)]
      (try
        (pull-image! python-image)
        (let [{:keys [container-id ssh-port]}
              (start-ssh-container! (:public kp))
              alias "replsh-test-ssh-only"
              block (append-ssh-host! alias ssh-port (:private kp))]
          (try
            ;; sshd setup via apt-get takes ~30s.  We write the SSH config
            ;; first so wait-for-ssh! can use the alias directly.
            (wait-for-ssh! alias 120000)

            (testing "launch SSH-only exec-mode"
              (let [r (exec-launch! {:name "ssh-test" :ssh-host alias})]
                (is (true? (:ok r)))
                (is (= "exec" (get-in r [:data :runtime])))))

            (testing "eval works through SSH bridge"
              (let [r (cmd/eval-cmd {:name "ssh-test" :code "print(2 + 2)" :timeout 15000})]
                (is (true? (:ok r)))
                (is (= "4\n" (get-in r [:data :output])))))

            (testing "state persists across SSH evals"
              (cmd/eval-cmd {:name "ssh-test" :code "msg = 'hello ssh'" :timeout 10000})
              (let [r (cmd/eval-cmd {:name "ssh-test" :code "print(msg)" :timeout 10000})]
                (is (= "hello ssh\n" (get-in r [:data :output])))))

            (testing "stop cleans up"
              (is (true? (:ok (cmd/stop-cmd {:name "ssh-test"})))))

            (finally
              (remove-ssh-host! block)
              (stop-container! container-id))))
        (finally
          (delete-dir! (:dir kp)))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Test 3: SSH + Docker exec-mode
;;
;; Starts a target Python container, then starts an SSH container that has
;; Docker CLI installed and the host Docker socket mounted (Docker-outside-of-
;; Docker).  The exec-mode launch SSHes into the SSH container and then uses
;; docker exec to inject the bridge into the target container.
;; ─────────────────────────────────────────────────────────────────────────────

(deftest ssh-docker-exec-test
  (if-not (and (docker-available?) (ssh-available?))
    (println "  SKIP ssh-docker-exec-test: Docker or ssh-keygen not available")
    (let [kp (gen-ssh-keypair!)]
      (try
        (pull-image! python-image)
        ;; Target container: stays alive, has Python
        (let [target-cid (start-container-alive!)]
          (try
            ;; SSH container: sshd + Docker CLI, socket mounted for DooD
            (let [{:keys [container-id ssh-port]}
                  (start-ssh-container!
                    (:public kp)
                    :extra-pkgs    ["docker.io"]
                    :extra-docker-args ["-v" "/var/run/docker.sock:/var/run/docker.sock"])
                  alias "replsh-test-ssh-docker"
                  block (append-ssh-host! alias ssh-port (:private kp))]
              (try
                ;; docker.io install is slow; write config first so wait-for-ssh!
                ;; can use the alias and verify sshd is actually answering.
                (wait-for-ssh! alias 180000)

                (testing "launch SSH+Docker exec-mode"
                  (let [r (exec-launch! {:name      "ssh-docker-test"
                                         :ssh-host  alias
                                         :container target-cid})]
                    (is (true? (:ok r)))
                    (is (= "exec"       (get-in r [:data :runtime])))
                    (is (= target-cid   (get-in r [:data :container-id])))))

                (testing "eval runs inside the target container via SSH hop"
                  (let [r (cmd/eval-cmd {:name    "ssh-docker-test"
                                         :code    "import sys; print(sys.version_info.major)"
                                         :timeout 15000})]
                    (is (true? (:ok r)))
                    (is (= "3\n" (get-in r [:data :output])))))

                (testing "state persists inside target container"
                  (cmd/eval-cmd {:name "ssh-docker-test" :code "v = 99" :timeout 10000})
                  (let [r (cmd/eval-cmd {:name "ssh-docker-test" :code "print(v)" :timeout 10000})]
                    (is (= "99\n" (get-in r [:data :output])))))

                (testing "stop cleans up"
                  (is (true? (:ok (cmd/stop-cmd {:name "ssh-docker-test"})))))

                (finally
                  (remove-ssh-host! block)
                  (stop-container! container-id))))
            (finally
              (stop-container! target-cid))))
        (finally
          (delete-dir! (:dir kp)))))))
