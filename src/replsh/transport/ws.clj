(ns replsh.transport.ws
  (:require [cheshire.core :as json])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit]))

(defn connect
  "Open a WebSocket connection. Returns {:ws WebSocket :queue LinkedBlockingQueue}.
   Messages are accumulated (potentially across fragments) and placed on the queue as complete strings."
  [url]
  (let [queue   (LinkedBlockingQueue.)
        buf     (StringBuilder.)
        client  (HttpClient/newHttpClient)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     (.request ws 1))
                   (onText [_ ws data last?]
                     (.append buf data)
                     (when last?
                       (.put queue (.toString buf))
                       (.setLength buf 0))
                     (.request ws 1)
                     (CompletableFuture/completedFuture nil))
                   (onClose [_ _ws status-code reason]
                     (.put queue {:closed true :code status-code :reason (str reason)}))
                   (onError [_ _ws error]
                     (.put queue {:error true :message (.getMessage error)})))
        ws      (-> client
                    (.newWebSocketBuilder)
                    (.buildAsync (URI/create url) listener)
                    (.get))]
    {:ws ws :queue queue}))

(defn send-text
  "Send a text message over the WebSocket."
  [{:keys [ws]} text]
  (.sendText ^WebSocket ws ^CharSequence text true)
  nil)

(defn send-json
  "Send a JSON message over the WebSocket."
  [conn data]
  (send-text conn (json/generate-string data)))

(defn recv
  "Receive one message from the queue. Returns the string, or nil on timeout.
   Throws on close/error."
  [{:keys [queue]} timeout-ms]
  (let [msg (.poll ^LinkedBlockingQueue queue timeout-ms TimeUnit/MILLISECONDS)]
    (cond
      (nil? msg)            nil
      (and (map? msg) (:closed msg)) (throw (ex-info "WebSocket closed" msg))
      (and (map? msg) (:error msg))  (throw (ex-info "WebSocket error" msg))
      :else                 msg)))

(defn recv-json
  "Receive and parse one JSON message. Returns parsed map or nil on timeout."
  [conn timeout-ms]
  (when-let [s (recv conn timeout-ms)]
    (json/parse-string s true)))

(defn close!
  "Close the WebSocket."
  [{:keys [ws]}]
  (when ws
    (try
      (.sendClose ^WebSocket ws WebSocket/NORMAL_CLOSURE "")
      (catch Exception _)))
  nil)
