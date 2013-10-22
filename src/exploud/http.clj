(ns exploud.http
  "## Talking to the outside world"
  (:require [clj-http.client :as http]
            [dire.core :refer [with-handler! with-post-hook! with-pre-hook!]]
            [clojure.tools.logging :as log]))

(defn merge-with-default-params
  "Merges our default params with the overrides provided."
  [& [overrides]]
  (merge {:throw-exceptions false
          :conn-timeout 5000
          :socket-timeout 15000}
         overrides))

(defn simple-get
  "Performs a GET request to the URL provided."
  [url & [params]]
  (http/get url (merge-with-default-params params)))

(defn simple-post
  "Performs a POST request to the URL provided."
  [url & [params]]
  (http/post url (merge-with-default-params params)))

;; A pre-hook attached to `simple-get` to log the URL and params.
(with-pre-hook! #'simple-get
  (fn [url & [params]]
    (log/debug "GETting" url "with params" params)))

;; A post-hook attched to `simple-get` to log the response.
(with-post-hook! #'simple-get
  (fn [response]
    (log/debug "Response was" response)))

;; Exception handler attached to `simple-get` which deals with
;; `ConnectException`.
(with-handler! #'simple-get
  java.net.ConnectException
  (fn [e url]
    (throw (ex-info (.getMessage e) {:type ::connection-refused
                                     :class :http}))))

;; Exception handler attached to `simple-get` which deals with
;; `ConnectTimeoutException`.
(with-handler! #'simple-get
  org.apache.http.conn.ConnectTimeoutException
  (fn [e url]
    (throw (ex-info (.getMessage e) {:type ::connect-timeout
                                     :class :http}))))

;; Exception handler attached to `simple-get` which deals with
;; `SocketTimeoutException`.
(with-handler! #'simple-get
  java.net.SocketTimeoutException
  (fn [e url]
    (throw (ex-info (.getMessage e) {:type ::socket-timeout
                                     :class :http}))))

;; Exception handler attached to `simple-get` which deals with
;; `UnknownHostException`.
(with-handler! #'simple-get
  java.net.UnknownHostException
  (fn [e url]
    (throw (ex-info (.getMessage e) {:type ::unknown-host
                                     :class :http}))))

;; A pre-hook attached to `simple-post` to log the URL and params.
(with-pre-hook! #'simple-post
  (fn [url & [params]]
    (log/debug "POSTing to" url "with params" params)))

;; A post-hook attched to `simple-post` to log the response.
(with-post-hook! #'simple-post
  (fn [response]
    (log/debug "Response was" response)))
