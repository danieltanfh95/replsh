(ns replsh.backend.node
  (:require [replsh.backend :as backend]
            [clojure.string :as str])
  (:import [java.net Socket]
           [java.io BufferedReader InputStreamReader PrintWriter]))

(defn- open-tcp
  "Open a TCP socket for raw text I/O."
  [host port]
  (let [sock (Socket. ^String host ^int port)
        in   (BufferedReader. (InputStreamReader. (.getInputStream sock)))
        out  (PrintWriter. (.getOutputStream sock) true)]
    {:socket sock :in in :out out}))

(defn- read-until-prompt
  "Read from the socket until we see a trailing prompt (e.g. '> ').
   Node REPL sends: result\\n>
   We detect the prompt by checking if the buffer ends with the prompt string
   AND there's been a period of no new data (the prompt is the last thing sent).
   Returns the collected output (everything before the final prompt)."
  [^BufferedReader in prompt-str timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        sb       (StringBuilder.)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for Node REPL prompt"
                        {:code :timeout})))
      (if (.ready in)
        (let [ch (.read in)]
          (when (neg? ch)
            (throw (ex-info "Node REPL connection closed" {:code :connection-refused})))
          (.append sb (char ch))
          (recur))
        ;; No data ready — check if buffer ends with prompt
        (let [s (.toString sb)]
          (if (and (pos? (.length sb))
                   (str/ends-with? s prompt-str))
            ;; Strip the trailing prompt and return
            (let [output (subs s 0 (- (count s) (count prompt-str)))]
              output)
            (do
              (Thread/sleep 10)
              (recur))))))))

(defn- drain-prompt
  "Read and discard the initial prompt after connecting."
  [^BufferedReader in prompt-str]
  (let [deadline (+ (System/currentTimeMillis) 5000)
        sb       (StringBuilder.)]
    (loop []
      (when (< (System/currentTimeMillis) deadline)
        (if (.ready in)
          (let [ch (.read in)]
            (when-not (neg? ch)
              (.append sb (char ch))
              (if (str/ends-with? (.toString sb) prompt-str)
                nil
                (recur))))
          (do
            (Thread/sleep 10)
            (recur)))))))

(defn- parse-output
  "Parse raw text output into response chunks.
   Heuristic: last non-empty line before prompt = :value, rest = :out.
   Node interleaves stdout and return values in the same stream."
  [raw-output session-name msg-id]
  (if (str/blank? raw-output)
    [{:type :status :content "" :meta {} :done? true
      :msg-id msg-id :name session-name}]
    (let [lines (str/split-lines raw-output)
          ;; Filter out empty lines at the end
          lines (vec (reverse (drop-while str/blank? (reverse lines))))]
      (if (empty? lines)
        [{:type :status :content "" :meta {} :done? true
          :msg-id msg-id :name session-name}]
        (let [value-line (last lines)
              out-lines  (butlast lines)
              chunks     (cond-> []
                           (seq out-lines)
                           (conj {:type :out
                                  :content (str (str/join "\n" out-lines) "\n")
                                  :stream :stdout :meta {} :done? false
                                  :msg-id msg-id :name session-name})

                           true
                           (conj {:type :value
                                  :content value-line
                                  :meta {} :done? true
                                  :msg-id msg-id :name session-name}))]
          chunks)))))

;; --- Multimethod implementations ---

(defmethod backend/open! :node
  [session-config]
  (let [{:keys [host port]} (:transport session-config)
        prompt-str (get-in session-config [:backend-opts :prompt-re] "> ")
        handles   (open-tcp host port)]
    ;; Drain the initial prompt/banner
    (drain-prompt (:in handles) prompt-str)
    {:config  session-config
     :backend :node
     :status  :connected
     :handles handles
     :internal {}}))

(defmethod backend/close! :node
  [live-state]
  (when-let [sock (get-in live-state [:handles :socket])]
    (.close ^Socket sock))
  nil)

(defmethod backend/destroy! :node
  [_session-config]
  ;; Node has no server-side session to clean up.
  ;; The TCP connection IS the session.
  nil)

(defmethod backend/eval! :node
  [request live-state]
  (let [{:keys [code name timeout-ms msg-id]} request
        {:keys [in out]} (:handles live-state)
        prompt-str (get-in live-state [:config :backend-opts :prompt-re] "> ")]
    ;; Send code
    (.println ^PrintWriter out code)
    (.flush ^PrintWriter out)
    ;; Read output until prompt
    (let [raw-output (read-until-prompt in prompt-str (or timeout-ms 30000))]
      (parse-output raw-output name msg-id))))

(defmethod backend/interrupt! :node
  [_live-state]
  {:error   true
   :code    "unsupported"
   :message "Node backend does not support interrupt"
   :detail  {}})
