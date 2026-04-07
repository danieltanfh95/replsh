(ns replsh.transport.http
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(defn request
  "Make an HTTP request. Returns parsed JSON body or throws."
  [{:keys [method url headers body]}]
  (let [opts (cond-> {:headers (merge {"Content-Type" "application/json"
                                       "Accept"       "application/json"}
                                      headers)}
               body (assoc :body (if (string? body) body (json/generate-string body))))
        resp (http/request (merge opts {:method method :uri url}))]
    (when (>= (:status resp) 400)
      (throw (ex-info (str "HTTP " (:status resp) ": " (:body resp))
                      {:code :http-error :status (:status resp) :body (:body resp)})))
    (when (and (:body resp) (not= "" (:body resp)))
      (json/parse-string (:body resp) true))))

(defn get-json
  "GET request, returns parsed JSON."
  [url & {:keys [token]}]
  (request {:method :get
            :url    url
            :headers (when token {"Authorization" (str "token " token)})}))

(defn post-json
  "POST request with JSON body, returns parsed JSON."
  [url body & {:keys [token]}]
  (request {:method  :post
            :url     url
            :headers (when token {"Authorization" (str "token " token)})
            :body    body}))

(defn delete!
  "DELETE request."
  [url & {:keys [token]}]
  (request {:method  :delete
            :url     url
            :headers (when token {"Authorization" (str "token " token)})}))
