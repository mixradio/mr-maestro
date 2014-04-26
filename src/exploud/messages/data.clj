(ns exploud.messages.data
  (:require [clj-time.core :as time]
            [clojure.string :as str]
            [exploud
             [aws :as aws]
             [log :as log]
             [onix :as onix]
             [naming :as naming]
             [responses :refer :all]
             [shuppet :as shuppet]
             [tyranitar :as tyr]
             [userdata :as ud]
             [util :as util]]
            [ring.util.codec :refer [base64-decode]]))

(def ^:private required-security-group-names
  ["exploud-healthcheck" "nrpe"])

(defn start-deployment-preparation
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters]
    (log/write (format "Preparing deployment of '%s' to '%s'." application environment))
    (success (assoc parameters :phase "preparation"))))

(defn validate-region
  [{:keys [parameters]}]
  (log/write "Validating region.")
  (if-let [region (:region parameters)]
    (success parameters)
    (error-with (ex-info "Region has not been provided." {:type ::region-missing}))))

(defn validate-environment
  [{:keys [parameters]}]
  (log/write "Validating environment.")
  (if-let [environment (:environment parameters)]
    (success parameters)
    (error-with (ex-info "Environment has not been provided." {:type ::application-missing}))))

(defn validate-application
  [{:keys [parameters]}]
  (log/write "Validating application.")
  (if-let [application (:application parameters)]
    (success parameters)
    (error-with (ex-info "Application has not been provided." {:type ::application-missing}))))

(defn validate-user
  [{:keys [parameters]}]
  (log/write "Validating user.")
  (if-let [user (:user parameters)]
    (success parameters)
    (error-with (ex-info "User has not been provided." {:type ::user-missing}))))

(defn validate-image
  [{:keys [parameters]}]
  (log/write "Validating image.")
  (let [state (:new-state parameters)]
    (if-let [image (get-in state [:image-details :id])]
      (success parameters)
      (error-with (ex-info "Image has not been provided." {:type ::image-missing})))))

(defn validate-message
  [{:keys [parameters]}]
  (log/write "Validating message.")
  (if-let [message (:message parameters)]
    (success parameters)
    (error-with (ex-info "Message has not been provided." {:type ::message-missing}))))

(defn get-onix-metadata
  [{:keys [parameters]}]
  (log/write "Getting Onix metadata.")
  (when-let [application (:application parameters)]
    (try
      (if-let [onix-details (onix/application application)]
        (success (assoc-in parameters [:new-state :onix] onix-details))
        (error-with (ex-info "Failed to obtain Onix metadata." {:application application})))
      (catch Exception e
        (error-with e)))))

(defn ensure-tyranitar-hash
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:new-state parameters)
        {:keys [hash]} state]
    (try
      (if hash
        (log/write "Using given Tyranitar hash.")
        (log/write "Getting latest Tyranitar hash."))
      (success (assoc-in parameters [:new-state :hash] (or hash (tyr/last-commit-hash environment application))))
      (catch Exception e
        (error-with e)))))

(defn verify-tyranitar-hash
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:new-state parameters)
        {:keys [hash]} state]
    (try
      (log/write (format "Verifying Tyranitar hash '%s'." hash))
      (if (tyr/verify-commit-hash environment application hash)
        (success parameters)
        (do
          (log/write (format "Hash '%s' does not exist for application '%s' in environment '%s'." hash application environment))
          (error-with (ex-info "Hash does not exist for application and environment" {:type ::hash-verification-failed
                                                                                      :application application
                                                                                      :environment environment
                                                                                      :hash hash}))))
      (catch Exception e
        (error-with e)))))

(defn get-tyranitar-application-properties
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:new-state parameters)
        {:keys [hash]} state]
    (try
      (log/write "Retrieving application-properties.")
      (if-let [application-properties (tyr/application-properties environment application hash)]
        (success (assoc-in parameters [:new-state :tyranitar :application-properties] application-properties))
        (error-with (ex-info "No application properties found." {:type ::no-application-properties
                                                                 :application application
                                                                 :environment environment})))
      (catch Exception e
        (error-with e)))))

(def default-deployment-params
  {:default-cooldown 10
   :desired-capacity 1
   :health-check-grace-period 600
   :health-check-type "EC2"
   :instance-healthy-attempts 50
   :instance-type "t1.micro"
   :load-balancer-healthy-attempts 50
   :max 1
   :min 1
   :pause-after-instances-healthy false
   :pause-after-load-balancers-healthy false
   :selected-zones ["a" "b"]
   :subnet-purpose "internal"
   :termination-policy "Default"})

(defn get-tyranitar-deployment-params
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:new-state parameters)
        {:keys [hash]} state]
    (try
      (log/write "Retrieving deployment-params.")
      (if-let [actual-deployment-params (util/clojurize-keys (tyr/deployment-params environment application hash))]
        (success (assoc-in parameters [:new-state :tyranitar :deployment-params] (merge default-deployment-params
                                                                             (update-in actual-deployment-params [:selected-load-balancers] (comp seq util/list-from)))))
        (error-with (ex-info "No deployment params found." {:type ::no-deployment-params
                                                            :application application
                                                            :environment environment})))
      (catch Exception e
        (error-with e)))))

(defn get-tyranitar-launch-data
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:new-state parameters)
        {:keys [hash]} state]
    (try
      (log/write "Retrieving launch-data.")
      (if-let [launch-data (tyr/launch-data environment application hash)]
        (success (assoc-in parameters [:new-state :tyranitar :launch-data] launch-data))
        (error-with (ex-info "No launch data found." {:type ::no-launch-data
                                                      :application application
                                                      :environment environment})))
      (catch Exception e
        (error-with e)))))

(defn- extract-hash
  [user-data]
  (when user-data
    (second (re-find #"export HASH=([^\s]+)" (String. (base64-decode user-data))))))

(defn- assoc-auto-scaling-group-properties
  [state {:keys [auto-scaling-group-name availability-zones default-cooldown desired-capacity health-check-grace-period health-check-type load-balancer-names max-size min-size tags termination-policies vpczone-identifier] :as auto-scaling-group}]
  (if auto-scaling-group
    (-> state
        (assoc :auto-scaling-group-name auto-scaling-group-name
               :auto-scaling-group-tags tags
               :availability-zones availability-zones
               :termination-policies termination-policies
               :vpc-zone-identifier vpczone-identifier)
        (assoc-in [:tyranitar :deployment-params :default-cooldown] default-cooldown)
        (assoc-in [:tyranitar :deployment-params :desired-capacity] desired-capacity)
        (assoc-in [:tyranitar :deployment-params :health-check-grace-period] health-check-grace-period)
        (assoc-in [:tyranitar :deployment-params :health-check-type] health-check-type)
        (assoc-in [:tyranitar :deployment-params :max] max-size)
        (assoc-in [:tyranitar :deployment-params :min] min-size)
        (assoc-in [:tyranitar :deployment-params :selected-load-balancers] load-balancer-names))
    state))

(defn- assoc-launch-configuration-properties
  [state {:keys [image-id instance-type launch-configuration-name security-groups user-data] :as launch-configuration}]
  (if launch-configuration
    (-> state
        (assoc :launch-configuration-name launch-configuration-name
               :hash (extract-hash user-data)
               :selected-security-group-ids security-groups
               :user-data (String. (base64-decode user-data)))
        (assoc-in [:tyranitar :deployment-params :instance-type] instance-type)
        (assoc-in [:image-details :id] image-id))
    state))

(defn- assoc-previous-state
  [{:keys [application environment region] :as parameters} last-auto-scaling-group]
  (if last-auto-scaling-group
    (assoc parameters :previous-state (-> (:previous-status parameters)
                                          (assoc-auto-scaling-group-properties last-auto-scaling-group)
                                          (assoc-launch-configuration-properties (aws/launch-configuration (:launch-configuration-name last-auto-scaling-group) environment region))))
    parameters))

(defn populate-previous-state
  [{:keys [parameters]}]
  (let [{:keys [application environment region]} parameters]
    (try
      (if-let [last-auto-scaling-group (aws/last-application-auto-scaling-group application environment region)]
        (do
          (log/write "Populating previous state.")
          (success (assoc-previous-state parameters last-auto-scaling-group)))
        (success parameters))
      (catch Exception e
        (error-with e)))))

(defn populate-previous-tyranitar-application-properties
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters
        state (:previous-state parameters)
        {:keys [hash]} state]
    (if state
      (try
        (log/write "Populating previous application-properties.")
        (success (assoc-in parameters [:previous-state :tyranitar :application-properties] (select-keys (tyr/application-properties environment application hash) [:service.healthcheck.path :service.healthcheck.skip :service.port])))
        (catch Exception e
          (error-with e)))
      (success parameters))))

(defn get-previous-image-details
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:previous-state parameters)
        image (get-in state [:image-details :id])]
    (if state
      (try
        (log/write (format "Retrieving previous image details for '%s'." image))
        (let [{:keys [name]} (aws/image image environment region)]
          (success (update-in parameters [:previous-state :image-details] merge (util/ami-details name))))
        (catch Exception e
          (error-with e)))
      (success parameters))))

(defn create-names
  [{:keys [parameters]}]
  (let [{:keys [application environment previous-state]} parameters
        next-asg-info (naming/next-asg-info (:auto-scaling-group-name previous-state (naming/create-asg-name {:application application :environment environment :iteration 0})))
        launch-configuration-name (naming/create-launch-config-name next-asg-info)
        auto-scaling-group-name (naming/create-asg-name next-asg-info)]
    (log/write (format "New launch configuration will be called '%s' and new auto scaling group will be called '%s'." launch-configuration-name auto-scaling-group-name))
    (success (update-in parameters [:new-state] merge {:launch-configuration-name launch-configuration-name
                                                       :auto-scaling-group-name auto-scaling-group-name}))))

(defn get-image-details
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        image (get-in state [:image-details :id])]
    (try
      (log/write (format "Retrieving image details for '%s'." image))
      (let [{:keys [name]} (aws/image image environment region)]
        (success (update-in parameters [:new-state :image-details] merge (util/ami-details name))))
      (catch Exception e
        (error-with e)))))

(defn verify-image
  [{:keys [parameters]}]
  (let [{:keys [application]} parameters
        state (:new-state parameters)
        {:keys [image-details]} state
        {:keys [image-name]} image-details]
    (try
      (log/write (format "Checking that image name '%s' matches application '%s'." image-name application))
      (let [image-application (:application image-details)]
        (if (= image-application application)
          (success parameters)
          (error-with (ex-info "Image name does not match application being deployed." {:type ::mismatched-image
                                                                                        :application application
                                                                                        :image-name image-name}))))
      (catch Exception e
        (error-with e)))))

(defn check-contact-property
  [{:keys [parameters]}]
  (let [{:keys [application]} parameters
        state (:new-state parameters)
        {:keys [onix]} state
        {:keys [contact]} onix]
    (log/write (format "Checking that the 'contact' property is set for application '%s' in Onix." application))
    (if contact
      (success parameters)
      (error-with (ex-info "Contact property is not set in Onix metadata." {:type ::contact-missing})))))

(defn check-shuppet-configuration
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters]
    (log/write (format "Checking that Shuppet configuration exists for application '%s' in environment '%s'." application environment))
    (try
      (if (shuppet/configuration environment application)
        (success parameters)
        (error-with (ex-info "Shuppet configuration missing for the application." {:type ::missing-shuppet
                                                                                   :application application
                                                                                   :environment environment})))
      (catch Exception e
        (error-with e)))))

(defn add-required-security-groups
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [selected-security-groups]} deployment-params]
    (log/write (format "Adding required %s [%s]." (util/pluralise (count selected-security-groups) "security group") (str/join ", " required-security-group-names)))
    (success (assoc-in parameters [:new-state :tyranitar :deployment-params :selected-security-groups] (apply merge (or selected-security-groups []) required-security-group-names)))))

(defn- is-security-group-id?
  [name]
  (re-find #"^sg-" name))

(defn map-security-group-ids
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [selected-security-groups]} deployment-params
        names (remove is-security-group-id? selected-security-groups)
        ids (filter is-security-group-id? selected-security-groups)]
    (log/write "Mapping security group names to their IDs.")
    (try
      (let [groups-by-name (aws/security-groups-by-name environment region)
            groups-by-id (aws/security-groups-by-id environment region)
            named-groups (apply merge (map (fn [name] {name (get groups-by-name name)}) names))
            id-groups (apply merge (map (fn [id] {id (get groups-by-id id)}) ids))
            unknown-named-groups (or (keys (filter (fn [[_ v]] (nil? v)) named-groups)) [])
            unknown-id-groups (or (keys (filter (fn [[_ v]] (nil? v)) id-groups)) [])
            unknown-groups (sort (apply merge unknown-named-groups unknown-id-groups))]
        (if-not (seq unknown-groups)
          (success (assoc-in parameters [:new-state :selected-security-group-ids] (sort (apply merge (map :group-id (vals named-groups)) (map :group-id (vals id-groups))))))
          (error-with (ex-info "Unknown security groups." {:type ::unknown-security-groups
                                                           :security-groups unknown-groups}))))
      (catch Exception e
        (error-with e)))))

(defn verify-load-balancers
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [selected-load-balancers]} deployment-params]
    (if-let [selected-load-balancers (:selected-load-balancers deployment-params)]
      (do
        (log/write "Verifying specified load balancers exist.")
        (let [found-load-balancers (aws/load-balancers-with-names environment region selected-load-balancers)]
          (if (= (count found-load-balancers) (count selected-load-balancers))
            (success parameters)
            (error-with (ex-info "One or more load balancers could not be found." {:type ::missing-load-balancers
                                                                                   :load-balancers (filter (fn [[_ v]] (nil? v)) found-load-balancers)})))))
      (success parameters))))

(defn populate-subnets
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [subnet-purpose]} deployment-params]
    (log/write (format "Locating subnets with purpose '%s'." subnet-purpose))
    (if-let [subnets (aws/subnets-by-purpose environment region subnet-purpose)]
      (success (assoc-in parameters [:new-state :selected-subnets] (map :subnet-id subnets)))
      (error-with (ex-info "No subnets found for purpose." {:type ::no-subnets
                                                            :purpose subnet-purpose})))))

(defn populate-vpc-zone-identifier
  [{:keys [parameters]}]
  (let [{:keys [environment region]} parameters
        state (:new-state parameters)
        {:keys [selected-subnets]} state]
    (log/write "Populating VPC zone identifier.")
    (success (assoc-in parameters [:new-state :vpc-zone-identifier] (str/join "," selected-subnets)))))

(defn populate-availability-zones
  [{:keys [parameters]}]
  (let [{:keys [region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [selected-zones]} deployment-params]
    (log/write "Populating availability zones.")
    (success (assoc-in parameters [:new-state :availability-zones] (map #(str region %) selected-zones)))))

(defn populate-termination-policies
  [{:keys [parameters]}]
  (let [{:keys [region]} parameters
        state (:new-state parameters)
        {:keys [tyranitar]} state
        {:keys [deployment-params]} tyranitar
        {:keys [termination-policy]} deployment-params]
    (log/write "Populating termination policies.")
    (success (assoc-in parameters [:new-state :termination-policies] (util/list-from termination-policy)))))

(defn- to-auto-scaling-group-tag
  [auto-scaling-group-name [k v]]
  {:key (name k)
   :value v
   :propagate-at-launch true
   :resource-type "auto-scaling-group"
   :resource-id auto-scaling-group-name})

(defn create-auto-scaling-group-tags
  [{:keys [parameters]}]
  (let [{:keys [application user]} parameters
        state (:new-state parameters)
        {:keys [auto-scaling-group-name image-details onix]} state
        {:keys [version]} image-details]
    (log/write "Creating auto scaling group tags.")
    (success (assoc-in parameters [:new-state :auto-scaling-group-tags] (map (partial to-auto-scaling-group-tag auto-scaling-group-name) {:Application application
                                                                                                                                          :Contact (:contact onix)
                                                                                                                                          :DeployedBy user
                                                                                                                                          :DeployedOn (str (time/now))
                                                                                                                                          :Name (format "%s-%s" application version)
                                                                                                                                          :Version version})))))

(defn generate-user-data
  [{:keys [parameters]}]
  (log/write "Generating user data.")
  (success (assoc-in parameters [:new-state :user-data] (ud/create-user-data parameters))))

(defn complete-deployment-preparation
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters]
    (log/write (format "Done preparing deployment of '%s' to '%s'." application environment))
    (success parameters)))

(defn start-deployment
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters]
    (log/write (format "Starting deployment of '%s' to '%s'." application environment))
    (success (assoc parameters :phase "deployment"))))

(defn complete-deployment
  [{:keys [parameters]}]
  (let [{:keys [application environment]} parameters]
    (log/write (format "Deployment of '%s' to '%s' complete." application environment))
    (success (assoc parameters :end (time/now)))))
