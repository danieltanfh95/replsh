(ns replsh.transport.tcp
  (:require [bencode.core :as bencode])
  (:import [java.net Socket]
           [java.io PushbackInputStream BufferedOutputStream]))

(defn open-socket
  "Open a TCP socket. Returns {:socket s :in PushbackInputStream :out BufferedOutputStream}."
  [host port]
  (let [sock (Socket. ^String host ^int port)
        in   (PushbackInputStream. (.getInputStream sock))
        out  (BufferedOutputStream. (.getOutputStream sock))]
    {:socket sock :in in :out out}))

(defn close-socket
  "Close a TCP socket and its streams."
  [{:keys [socket]}]
  (when socket
    (.close ^Socket socket)))

(defn send-bencode
  "Write a bencode message (map) to the output stream."
  [{:keys [out]} msg]
  (bencode/write-bencode out msg)
  (.flush ^BufferedOutputStream out))

(defn read-bencode
  "Read one bencode message from the input stream. Blocks until data available.
   Returns a map with string keys."
  [{:keys [in]}]
  (bencode/read-bencode in))

(defn bytes->str
  "Convert bencode byte arrays to strings in a response map."
  [m]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (instance? (Class/forName "[B") v) (String. ^bytes v "UTF-8")
                    (sequential? v) (mapv (fn [x]
                                           (if (instance? (Class/forName "[B") x)
                                             (String. ^bytes x "UTF-8")
                                             x))
                                         v)
                    :else v)]))
        m))
