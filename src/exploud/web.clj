(ns exploud.web
  "## Setting up our RESTful interface"
  (:require [bouncer.core :as b]
            [cheshire.core :as json]
            [clj-time.format :as fmt]
            [clojure
             [string :refer [split]]
             [walk :refer [keywordize-keys postwalk]]]
            [clojure.tools.logging :as log]
            [compojure
             [core :refer [defroutes context GET PUT POST DELETE]]
             [handler :as handler]
             [route :as route]]
            [environ.core :refer [env]]
            [exploud
             [aws :as aws]
             [deployments :as deployments]
             [elasticsearch :as es]
             [info :as info]
             [jsonp :refer [wrap-json-with-padding]]
             [pokemon :as pokemon]
             [redis :as redis]
             [stats :as stats]
             [util :as util]
             [validators :as v]]
            [metrics.ring
             [expose :refer [expose-metrics-as-json]]
             [instrument :refer [instrument]]]
            [nokia.ring-utils
             [error :refer [wrap-error-handling error-response]]
             [ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]]
            [overtone.at-at :as at-at]
            [ring.middleware
             [format-response :refer [wrap-json-response]]
             [json-params :refer [wrap-json-params]]
             [params :refer [wrap-params]]
             [keyword-params :refer [wrap-keyword-params]]]
            [ring.util.response :refer [header]]))

(def ^:dynamic *version*
  "The version of our application."
  "none")

(defn set-version!
  "Sets the version of the application."
  [version]
  (alter-var-root #'*version* (fn [_] version)))

(def default-environment
  "The default environment we'll use for queries."
  "poke")

(def default-region
  "The default region we'll deploy to (temporarily)."
  "eu-west-1")

(def default-user
  "The default user we'll say deployments came from (if one isn't provided)."
  "exploud")

(def application-regex
  "The regular expression which we'll use to determine whether an application
   name is valid."
  #"[a-z]+")

(defn response
  "Accepts a body an optionally a content type and status. Returns a response
   object."
  [body & [content-type status]]
  (if body
    {:status (or status 200)
     :headers {"Content-Type" (or content-type
                                  "application/json; charset=utf-8")}
     :body body}
    (if status
      {:status status}
      {:status 404})))

(defn- date-string-to-date
  "If the value is a string and a valid ISO8601 date then turn it into a date. Otherwise leave it alone."
  [v]
  (if (string? v)
    (try (if-let [vd (fmt/parse (fmt/formatters :date-time) v)]
           vd
           v)
         (catch Exception e v))
    v))

(defmacro guarded
  "Replace the given body with a Conflict response if Exploud has been locked."
  [& body]
  `(if (deployments/locked?)
     (response "Exploud is currently closed for business." "text/plain" 409)
     (do ~@body)))

(defroutes routes
  "The RESTful routes we provide."

  (context
   "/1.x" []

   (GET "/ping"
        []
        (response "pong" "text/plain"))

   (GET "/status"
        []
        (response {:name "exploud"
                   :version *version*
                   :success true}))

   (GET "/pokemon"
        []
        (response pokemon/pokemon "text/plain"))

   (GET "/icon"
        []
        (response (clojure.java.io/input-stream
                   (clojure.java.io/resource "exploud.jpg"))
                  "image/jpeg"))

   (GET "/queue-status"
        []
        (response (redis/queue-status)))

   (GET "/lock"
        []
        (if (deployments/locked?)
          (response "Exploud is currently locked." "text/plain")
          (response "Exploud is currently unlocked." "text/plain" 404)))

   (POST "/lock"
         []
         (deployments/lock)
         (response "" "text/plain" 204))

   (DELETE "/lock"
           []
           (deployments/unlock)
           (response "" "text/plain" 204))

   (GET "/deployments"
        [application environment from region size start-from start-to]
        (let [parameters {:application application
                          :environment environment
                          :from from
                          :region region
                          :size size
                          :start-from start-from
                          :start-to start-to}
              result (apply b/validate parameters v/query-param-validators)]
          (if-let [details (first result)]
            (response {:message "Query parameter validation failed"
                       :details details} nil 400)
            (response {:deployments (es/get-deployments
                                     {:application application
                                      :environment environment
                                      :from (util/string->int from)
                                      :region region
                                      :size (util/string->int size)
                                      :start-from (fmt/parse start-from)
                                      :start-to (fmt/parse start-to)})}))))

   (GET "/deployments/:deployment-id"
        [deployment-id]
        (response (es/deployment deployment-id)))

   (GET "/deployments/:deployment-id/tasks"
        [deployment-id]
        (response (es/deployment-tasks deployment-id)))

   (GET "/deployments/:deployment-id/logs"
        [deployment-id]
        (response (es/deployment-logs deployment-id)))

   (GET "/applications"
        []
        (response (info/applications)))

   (GET "/applications/:application"
        [application]
        (response (info/application application)))

   (PUT "/applications/:application"
        [application description email owner]
        (guarded
         (if (re-matches application-regex application)
           (let [body (info/upsert-application default-region application
                                               {:description description
                                                :email email
                                                :owner owner})]
             (response body nil 201))
           (error-response "Illegal application name" 400))))

   (POST "/applications/:application/:environment/deploy"
         [application ami environment hash message user]
         (guarded
          (let [id (util/generate-id)]
            (deployments/begin {:application application
                                :environment environment
                                :id id
                                :message message
                                :new-state {:hash hash
                                            :image-details {:id ami}}
                                :region default-region
                                :user (or user default-user)})
            (response {:id id}))))

   (POST "/applications/:application/:environment/undo"
         [application environment]
         (guarded
          (let [id (deployments/undo {:application application
                                      :environment environment
                                      :region default-region})]
            (response {:id id}))))

   (POST "/applications/:application/:environment/rollback"
         [application environment message user]
         (guarded
          (let [id (util/generate-id)]
            (deployments/rollback {:application application
                                   :environment environment
                                   :id id
                                   :message message
                                   :region default-region
                                   :user user})
            (response {:id id}))))

   (GET "/environments"
        []
        (response {:environments (info/environments default-region)}))

   (GET "/in-progress"
        []
        (response {:deployments (deployments/in-progress)}))

   (DELETE "/in-progress/:application/:environment"
           [application environment])

   (GET  "/describe-instances/:name/:environment"
         [name environment state :as req]
         (if (= (get-in req [:headers "accept"]) "text/plain")
           (response (aws/describe-instances-plain environment default-region name state) "text/plain")
           (response (aws/describe-instances environment default-region name state))))

   (GET "/stats/deployments/by-user"
        []
        (response {:result (stats/deployments-by-user)}))

   (GET "/stats/deployments/by-application"
        []
        (response {:result (stats/deployments-by-application)}))

   (GET "/stats/deployments/by-month"
        []
        (response {:result (stats/deployments-by-month)}))

   (GET "/stats/deployments/by-month/environment/:environment"
        [environment]
        (response {:result (stats/deployments-in-environment-by-month environment)})))

  (route/not-found (error-response "Resource not found" 404)))

(defn wrap-cors-headers
  "Adds in `Access-Control-Allow-Headers` and `Access-Control-Allow-Origin`
   headers to every response (because I'm lazy)."
  [handler]
  (fn [request]
    (-> (handler request)
        (header "Access-Control-Allow-Headers" "Access,Origin,X-Prototype-Version,X-Requested-With")
        (header "Access-Control-Allow-Origin" "*"))))

(def app
  "Sets up our application, adding in various bits of middleware."
  (-> routes
      (instrument)
      (wrap-error-handling)
      (wrap-cors-headers)
      (wrap-ignore-trailing-slash)
      (wrap-json-response)
      (wrap-json-with-padding)
      (wrap-json-params)
      (wrap-keyword-params)
      (wrap-params)
      (expose-metrics-as-json)))
