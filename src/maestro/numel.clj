(ns maestro.numel
  (:require [cemerick.url :refer [url]]
            [cheshire.core :as json]
            [environ.core :refer [env]]
            [maestro
             [environments :as environments]
             [http :as http]]))

(def timeout
  "The number of milliseconds we'll wait for a response."
  10000)

(def poke-numel-url
  "The base URL for Numel in poke"
  (url (env :numel-poke-baseurl)))

(def prod-numel-url
  "The base URL for Numel in prod"
  (url (env :numel-prod-baseurl)))

(defn application-registrations-url
  [environment application]
  (if (environments/prod-account? environment)
    (str (url prod-numel-url "registrations" environment application))
    (str (url poke-numel-url "registrations" environment application))))

(defn application-registrations
  [environment application]
  (let [url (application-registrations-url environment application)
        {:keys [body status] :as response} (http/simple-get url {:socket-timeout timeout})]
    (cond (= 200 status) (json/parse-string body true)
          (= 404 status) nil
          :else (throw (ex-info "Unexpected response" {:type ::unexpected-response :response response})))))
