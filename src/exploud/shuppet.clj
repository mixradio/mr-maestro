(ns exploud.shuppet
  "## Integration with Shuppet"
  (:require [cheshire.core :as json]
            [environ.core :refer [env]]
            [exploud.http :as http]))

(def shuppet-url
  "We only need the URL for the 'poke' Shuppet."
  (env :service-shuppet-url))

(defn create-application-url
  "The URL to create a new app in Shuppet."
  [app-name]
  (str shuppet-url "/1.x/apps/" app-name))

(defn apply-url
  "The URL we use to apply configuration."
  [environment app-name]
  (str shuppet-url "/1.x/envs/" environment "/apps/" app-name "/apply"))

(defn envs-url
  "The URL to find environments in Shuppet."
  []
  (str shuppet-url "/1.x/envs"))

(defn apply-config
  "tells shuppet to apply a config to all environments."
  [app-name]
  (let [{:keys [body status] :as response} (http/simple-get (envs-url))]
    (if (= 200 status)
      (let [envs (:environments (json/parse-string body true))
            responses (pmap #(http/simple-get (apply-url % app-name))
                            envs)]
        (map (fn [{:keys [status] :as r}]
               (when-not (= 200 status)
                 (throw (ex-info (str "Unexpected status while applying Shuppet config: " status)
                                 {:type ::unexpected-response
                                  :response r})))
               r)
             responses))
      (throw (ex-info (str "Unexpected status while getting Shuppet environments: " status)
                      {:type ::unexpected-response
                       :response response})))))

(defn upsert-application
  "Insert an application into Shuppet if it doesn't exist, or update it if it does."
  [app-name]
  (let [{:keys [body status] :as response}
        (http/simple-post (create-application-url app-name))]
    (if (or (= status 200) (= status 201))
      (json/parse-string body true)
      (throw (ex-info (str "Unexpected status while creating application in Shuppet: " status)
                      {:type ::unexpected-response
                       :response response})))))
