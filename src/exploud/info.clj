(ns exploud.info
  "## For grabbing information about the things we're dealing with"
  (:require [exploud
             [asgard :as asgard]
             [onix :as onix]
             [tyranitar :as tyr]]))

(defn applications
  "The list of applications Onix knows about."
  []
  (onix/applications))

(defn application
  "The information about a particular application."
  [region application-name]
  (if-let [full-application (asgard/application region application-name)]
    (merge {:name application-name}
           (select-keys (:app full-application)
                        [:description :email :owner]))))

(defn upsert-application
  "Upserts an application into Onix, Tyranitar and Asgard. This function can be
   run many times, it won't fail if the application is present in any of the
   stores."
  [region application-name details]
  (let [onix-application (onix/upsert-application application-name)
        tyranitar-application (tyr/upsert-application application-name)]
    (asgard/upsert-application application-name details)
    (merge (application region application-name) tyranitar-application)))

(defn- is-named?
  "Checks if the given instance has the given name (ignoring version)."
  [app-name instance]
  (let [tags (get-in instance [:ec2Instance :tags])
        pattern (re-pattern (str app-name ".*"))]
    (some #(and (= "Name" (:key %)) (re-matches pattern (:value %))) tags)))

(defn instances-for-application
  "Gets those instances in the given region, which have the given name (ignoring version)."
  [region application-name]
  (let [all-instances (asgard/all-instances region)]
    (filter #(is-named? application-name %) all-instances)))
