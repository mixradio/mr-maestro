(ns maestro.web
  "## Setting up our RESTful interface"
  (:require [bouncer.core :as b]
            [cheshire.core :as json]
            [clj-time.format :as fmt]
            [clojure
             [string :as str]
             [walk :refer [keywordize-keys postwalk]]]
            [clojure.tools.logging :as log]
            [compojure
             [core :refer [defroutes context GET PUT POST DELETE]]
             [handler :as handler]
             [route :as route]]
            [environ.core :refer [env]]
            [maestro
             [aws :as aws]
             [deployments :as deployments]
             [elasticsearch :as es]
             [environments :as environments]
             [info :as info]
             [jsonp :refer [wrap-json-with-padding]]
             [redis :as redis]
             [util :as util]
             [validators :as v]]
            [metrics.ring
             [expose :refer [expose-metrics-as-json]]
             [instrument :refer [instrument]]]
            [radix
             [error :refer [wrap-error-handling error-response]]
             [ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
             [setup :as setup]
             [reload :refer [wrap-reload]]]
            [ring.middleware
             [format-params :refer [wrap-json-kw-params]]
             [format-response :refer [wrap-json-response]]
             [params :refer [wrap-params]]]
            [ring.util.response :refer [header]]))

(def version
  "The version of our application."
  (setup/version "maestro"))

(def default-environment
  "The default environment we'll use for queries."
  "poke")

(def default-region
  "The default region we'll deploy to (temporarily)."
  "eu-west-1")

(def default-user
  "The default user we'll say deployments came from (if one isn't provided)."
  "maestro")

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

(defmacro guarded
  "Replace the given body with a Conflict response if Maestro has been locked."
  [& body]
  `(if (deployments/locked?)
     (response "Maestro is currently closed for business." "text/plain" 409)
     (do ~@body)))

(defroutes routes
  "The RESTful routes we provide."

  (GET "/ping"
       []
       (response "pong" "text/plain"))

  (GET "/healthcheck"
       []
       (response {:name "maestro"
                  :version version
                  :success true
                  :dependencies []}))

  (GET "/queue-status"
       []
       (response (redis/queue-status)))

  (GET "/lock"
       []
       (if (deployments/locked?)
         (response "Maestro is currently locked." "text/plain")
         (response "Maestro is currently unlocked." "text/plain" 404)))

  (POST "/lock"
        []
        (deployments/lock)
        (response "" "text/plain" 204))

  (DELETE "/lock"
          []
          (deployments/unlock)
          (response "" "text/plain" 204))

  (GET "/deployments"
       [application environment full from region size start-from start-to status]
       (let [parameters {:application application
                         :environment environment
                         :from from
                         :full full
                         :region region
                         :size size
                         :start-from start-from
                         :start-to start-to
                         :status status}
             result (b/validate parameters v/query-param-validators)]
         (if-let [details (first result)]
           (response {:message "Query parameter validation failed"
                      :details details} nil 400)
           (response {:deployments (es/get-deployments
                                    {:application application
                                     :environment environment
                                     :full? (Boolean/valueOf full)
                                     :from (util/string->int from)
                                     :region region
                                     :size (util/string->int size)
                                     :start-from (fmt/parse start-from)
                                     :start-to (fmt/parse start-to)
                                     :status status})}))))

  (GET "/deployments/:deployment-id"
       [deployment-id]
       (response (es/deployment deployment-id)))

  (DELETE "/deployments/:deployment-id"
          [deployment-id]
          (es/delete-deployment deployment-id)
          (response "Deployment deleted" "text/plain" 204))

  (GET "/deployments/:deployment-id/tasks"
       [deployment-id]
       (if-let [tasks (es/deployment-tasks deployment-id)]
         (response {:tasks tasks})
         (response nil)))

  (GET "/deployments/:deployment-id/logs"
       [deployment-id since]
       (let [parameters {:since since}
             result (b/validate parameters v/log-param-validators)]
         (if-let [details (first result)]
           (response {:message "Query parameter validation failed"
                      :details details} nil 400)
           (if-let [logs (es/deployment-logs deployment-id (fmt/parse since))]
             (response {:logs logs})
             (response nil)))))

  (GET "/applications"
       []
       (response (info/applications)))

  (GET "/applications/:application"
       [application]
       (response (info/application application)))

  (PUT "/applications/:application"
       [application email]
       (guarded
        (if (re-matches application-regex application)
          (let [body (info/upsert-application default-region application email)]
            (response body nil 201))
          (error-response "Illegal application name" 400))))

  (POST "/applications/:application/:environment/deploy"
        [ami application environment hash message silent user]
        (guarded
         (let [result (b/validate
                       {:ami ami
                        :application application
                        :environment environment
                        :hash hash
                        :message message
                        :silent silent
                        :user user}
                       v/deployment-request-validators)]
           (if-let [details (first result)]
             (response {:message "Deployment validation failed"
                        :details details} nil 400)
             (let [id (util/generate-id)]
               (deployments/begin {:application application
                                   :environment environment
                                   :id id
                                   :message message
                                   :new-state {:hash hash
                                               :image-details {:id ami}}
                                   :region default-region
                                   :silent (true? silent)
                                   :user (or user default-user)})
               (response {:id id}))))))

  (POST "/applications/:application/:environment/undo"
        [application environment message silent user]
        (guarded
         (let [id (deployments/undo {:application application
                                     :environment environment
                                     :message message
                                     :region default-region
                                     :silent silent
                                     :user user})]
           (response {:id id}))))

  (POST "/applications/:application/:environment/rollback"
        [application environment message silent user]
        (guarded
         (let [id (util/generate-id)]
           (deployments/rollback {:application application
                                  :environment environment
                                  :id id
                                  :message message
                                  :region default-region
                                  :rollback true
                                  :silent silent
                                  :user user})
           (response {:id id}))))

  (POST "/applications/:application/:environment/pause"
        [application environment]
        (let [parameters {:application application
                          :environment environment
                          :region default-region}]
          (if (deployments/in-progress? parameters)
            (do
              (deployments/register-pause parameters)
              (response "Pause registered and will take effect after the next task finishes" "text/plain" 200))
            (response "No deployment is in progress" "text/plain" 409))))

  (DELETE "/applications/:application/:environment/pause"
          [application environment]
          (let [parameters {:application application
                            :environment environment
                            :region default-region}]
            (if (deployments/pause-registered? parameters)
              (do
                (deployments/unregister-pause parameters)
                (response "Pause unregistered" "text/plain" 200))
              (response "No pause was registered" "text/plain" 409))))

  (POST "/applications/:application/:environment/resume"
        [application environment]
        (guarded
         (let [parameters {:application application
                           :environment environment
                           :region default-region}]
           (if (deployments/paused? parameters)
             (do
               (deployments/resume parameters)
               (response "Deployment resumed" "text/plain" 200))
             (response "No deployment is paused" "text/plain" 409)))))

  (GET "/environments"
       []
       (response {:environments (apply sorted-set (keys (environments/environments)))}))

  (GET "/in-progress"
       []
       (response {:deployments (deployments/in-progress)}))

  (DELETE "/in-progress/:application/:environment"
          [application environment]
          (redis/end-deployment {:application application
                                 :environment environment
                                 :region default-region})
          (response "In-progress deployment removed" "text/plain" 204))

  (GET "/paused"
       []
       (response {:deployments (deployments/paused)}))

  (GET "/awaiting-pause"
       []
       (response {:deployments (deployments/awaiting-pause)}))

  (GET  "/describe-instances/:application/:environment"
        [application environment state :as req]
        (if (= (get-in req [:headers "accept"]) "text/plain")
          (response (aws/describe-instances-plain environment default-region application state) "text/plain")
          (response (aws/describe-instances environment default-region application state))))

  (GET "/stats/deployments/by-user"
       []
       (response {:result (es/deployments-by-user)}))

  (GET "/stats/deployments/by-user/:user/by-application"
       [user]
       (response {:result (es/deployments-by-user-by-application user)}))

  (GET "/stats/deployments/by-application"
       []
       (response {:result (es/deployments-by-application)}))

  (GET "/stats/deployments/by-year"
       []
       (response {:result (es/deployments-by-year)}))

  (GET "/stats/deployments/by-month"
       []
       (response {:result (es/deployments-by-month)}))

  (GET "/stats/deployments/by-day"
       []
       (response {:result (es/deployments-by-day)}))

  (GET "/stats/deployments/by-year/environment/:environment"
       [environment]
       (response {:result (es/deployments-in-environment-by-year environment)}))

  (GET "/stats/deployments/by-month/environment/:environment"
       [environment]
       (response {:result (es/deployments-in-environment-by-month environment)}))

  (GET "/stats/deployments/by-day/environment/:environment"
       [environment]
       (response {:result (es/deployments-in-environment-by-day environment)}))

  (route/not-found (error-response "Resource not found" 404)))

(defn wrap-cors-headers
  "Adds in `Access-Control-Allow-Headers` and `Access-Control-Allow-Origin`
   headers to every response (because I'm lazy)."
  [handler]
  (fn [request]
    (-> (handler request)
        (header "Access-Control-Allow-Headers" "Access,Origin,X-Prototype-Version,X-Requested-With")
        (header "Access-Control-Allow-Origin" "*"))))

(defn remove-legacy-path
  "Temporarily redirect anything with /1.x in the path to somewhere without /1.x"
  [handler]
  (fn [request]
    (handler (update-in request [:uri] (fn [uri] (str/replace-first uri "/1.x" ""))))))

(def app
  "Sets up our application, adding in various bits of middleware."
  (-> routes
      (wrap-reload)
      (remove-legacy-path)
      (instrument)
      (wrap-error-handling)
      (wrap-cors-headers)
      (wrap-ignore-trailing-slash)
      (wrap-json-response)
      (wrap-json-with-padding)
      (wrap-json-kw-params)
      (wrap-params)
      (expose-metrics-as-json)))