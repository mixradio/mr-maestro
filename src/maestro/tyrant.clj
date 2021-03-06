(ns maestro.tyrant
  "## Integration with Tyrant

   We use Tyrant as a store for version-controlled information about
   applications. We use it to store three main parts of configuration:

   - __application-properties__ - the properties that are given to an
     application when it launches
   - __deployment-params__ - the properties used to control the deployment of
     the application
   - __launch-data__ - a list of commands to be run before the application is
     started

   The information can be obtained for each application in different
   environments. Each version of the files can be obtained by its commit hash."
  (:require [cemerick.url :refer [url]]
            [cheshire.core :as json]
            [environ.core :refer [env]]
            [maestro.http :as http]))

(def get-timeout
  "The number of milliseconds we'll wait for GET requests."
  30000)

(def post-timeout
  "The number of millisecond we'll wait for POST requests."
  180000)

(def tyrant-url
  "The URL where Tyrant is deployed."
  (url (env :tyrant-baseurl)))

(defn- file-url
  "Creates the URL where we can find the content of a particular file for an
   application and environment combination."
  [environment application-name commit-hash file-name]
  (str (url tyrant-url "applications" environment application-name commit-hash file-name)))

(defn- commits-url
  "Creates the URL where we can find the commits for an application in an
   environment."
  [environment application-name]
  (str (url tyrant-url "applications" environment application-name)))

(defn- applications-url
  "The URL where we'll find a list of applications."
  []
  (str (url tyrant-url "applications")))

(defn- get-file-content
  "Gets the content of a file for an application and environment."
  [environment application-name commit-hash file-name]
  (let [url (file-url environment application-name commit-hash file-name)
        {:keys [body status] :as response} (http/simple-get url {:socket-timeout get-timeout})]
    (cond (= status 200) (json/parse-string body true)
          (= status 500) (throw (ex-info "Error retrieving file content - is the JSON valid?" {:type ::file-invalid :response response}))
          (= status 404) (throw (ex-info "Resource was not found." {:type ::resource-not-found :response response}))
          :else (throw (ex-info "Unexpected response" {:type ::unexpected-response :response response})))))

(defn application-properties
  "Gets the application properties for an application and environment with a
   particular commit hash."
  [environment application-name commit-hash]
  (:data (get-file-content environment application-name commit-hash "application-properties")))

(defn application-config
  "Gets the application config for an application and environment with a
   particular commit hash. Returns nil if it doesn't exist."
  [environment application-name commit-hash]
  (try
    (:data (get-file-content environment application-name commit-hash "application-config"))
    (catch Exception e
      (when-not (= (:type (ex-data e)) ::resource-not-found)
        (throw e)))))

(defn deployment-params
  "Gets the deployment parameters for an application and environment with a
   particular commit hash."
  [environment application-name commit-hash]
  (:data (get-file-content environment application-name commit-hash "deployment-params")))

(defn launch-data
  "Gets the launch data for an application and environment with a particular
   commit hash."
  [environment application-name commit-hash]
  (:data (get-file-content environment application-name commit-hash "launch-data")))

(defn commits
  "Gets the list of commits for an application and environment."
  [environment application-name]
  (let [url (commits-url environment application-name)
        {:keys [body status] :as response} (http/simple-get url {:socket-timeout get-timeout})]
    (if (= status 200)
      (:commits (json/parse-string body true))
      (throw (ex-info "Unexpected response" {:type ::unexpected-response
                                             :response response})))))

(defn last-commit-hash
  "Gets the last commit for an application and environment."
  [environment application-name]
  (:hash (first (commits environment application-name))))

(defn verify-commit-hash
  "Verifies that the given hash exists for an application and environment."
  [environment application-name commit-hash]
  (contains? (apply hash-set (map :hash (commits environment application-name))) commit-hash))

(defn create-application
  "Creates an application in Tyrant. Will fail if the application already
   exists."
  [application-name]
  (let [content (json/generate-string {:name application-name})
        post-body {:content-type :json :body content :socket-timeout post-timeout}
        raw-response (http/simple-post (applications-url) post-body)
        {:keys [body status] :as response} raw-response]
    (if (= status 201)
      (json/parse-string body true)
      (throw ( ex-info "Unexpected response" {:type ::unexpected-response
                                              :response response})))))

(defn application
  "Gets the information about the environments for which we have the properties
   of an application. Returns `nil` if we don't know about the application in
   any environment."
  [application-name]
  (let [{:keys [body status] :as response} (http/simple-get (applications-url) {:socket-timeout get-timeout})]
    (if (= status 200)
      ((keyword application-name) (:applications (json/parse-string body true)))
      (throw ( ex-info "Unexpected response" {:type ::unexpected-response
                                              :response response})))))

(defn upsert-application
  "Checks whether an application exists and creates it if it doesn't exist.
   Returns information about the application."
  [application-name]
  (if-let [application (application application-name)]
    application
    (create-application application-name)))
