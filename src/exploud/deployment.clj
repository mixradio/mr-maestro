(ns exploud.deployment
  "## Creating and managing deployment chains"
  (:require [dire.core :refer [with-post-hook!
                               with-pre-hook!]]
            [clojure.tools.logging :as log]
            [exploud
             [asgard :as asgard]
             [healthchecks :as health]
             [store :as store]
             [tyranitar :as tyr]
             [util :as util]]))

;; # Concerning tasks

(defn new-task
  "Creates a new task with a random ID, the given `:action` and a `:status` of
   `pending`."
  [action]
  {:id (util/generate-id)
   :action action
   :status "pending"})

(defn create-standard-deployment-tasks
  "Creates a standard deployment using the following actions:

   - __create-asg__ - create either the next ASG or a new starting ASG for the
     application
   - __wait-for-instance-health__ - wait until all instances in the newly-
     created ASG are passing their healthchecks (by showing a 200 status)
   - __enable-asg__ - enable traffic to the newly-created ASG
   - __wait-for-elb-health__ - if adding instances to any ELBs, wait until
     __all__ instances in the newly-created ASG are listed as healthy in the
     ELBs, if not adding instances to any ELBs this is a no-op
   - __disable-asg__ - disable traffic to the old ASG
   - __delete-asg__ - delete the old ASG, terminating any instances within it"
  []
  [(new-task :create-asg)
   (new-task :wait-for-instance-health)
   (new-task :enable-asg)
   (new-task :wait-for-elb-health)
   (new-task :disable-asg)
   (new-task :delete-asg)])

(defn task-after
  "Given a deployment and a task ID will find the task which occurs after the
   one with an `:id` of `task-id` in the `:tasks` of `deployment`. It returns
   either the next task or `nil` if the given ID was last."
  [{:keys [tasks]} task-id]
  (->> tasks
       reverse
       (take-while (fn [{:keys [id]}] (not= id task-id)))
       last))

;; Pre-hook attached to `task-after` to log parameters.
(with-pre-hook! #'task-after
  (fn [d id]
    (log/debug "Getting task after" id "in" d)))

(with-post-hook! #'task-after
  (fn [t]
    (log/debug "Task after came back as" t)))

(defn check-elb-health?
  "If the `:parameters` of `deployment` have both a `selectedLoadBalancers`
   value and `healthCheckType` of `ELB` then we should be checking the health
   of the ELB. Otherwise, we're not going to do anything. They're on their own."
  [{:keys [parameters]}]
  (and (pos? (count (util/list-from (:selectedLoadBalancers parameters))))
       (= "ELB" (:healthCheckType parameters))))

;; We're going to need these before we can defn them.
(declare task-finished)
(declare task-timed-out)
(declare finish-deployment)

(defn start-task
  "Starts the given task based on its `:action`."
  [{:keys [region] deployment-id :id :as deployment} {:keys [action] :as task}]
  (let [task (assoc task :start (util/now-string))
        action (keyword action)]
    (cond (= action :create-asg)
          (let [{:keys [ami application environment parameters]} deployment]
            (asgard/create-auto-scaling-group region application environment ami
                                              parameters deployment-id task
                                              task-finished task-timed-out))
          (= action :wait-for-instance-health)
          (let [{:keys [application environment hash]} deployment
                service-properties (tyr/application-properties
                                    environment application hash)
                port (:service.port service-properties 8080)
                healthcheck (:service.healthcheck.path service-properties
                                                       "/healthcheck")]
            (health/wait-until-asg-healthy
             region
             (get-in deployment [:parameters :newAutoScalingGroupName])
             port healthcheck deployment-id task task-finished task-timed-out))
          (= action :enable-asg)
          (asgard/enable-asg
           region
           (get-in deployment [:parameters :newAutoScalingGroupName])
           deployment-id task task-finished task-timed-out)
          (= action :wait-for-elb-health)
          (if (check-elb-health? deployment)
            (let [elb-names (util/list-from
                             (get-in deployment
                                     [:parameters :selectedLoadBalancers]))
                  asg-name (get-in deployment
                                   [:parameters :newAutoScalingGroupName])]
              (health/wait-until-elb-healthy region elb-names asg-name
                                             deployment-id task task-finished
                                             task-timed-out))
            (task-finished deployment-id task))
          (= action :disable-asg)
          (if-let [asg (get-in deployment [:parameters
                                           :oldAutoScalingGroupName])]
            (asgard/disable-asg region asg deployment-id task task-finished
                                task-timed-out)
            (do
              (let [task (assoc task
                           :end (util/now-string)
                           :status "completed")])
              (store/store-task deployment-id task)
              (task-finished deployment-id task)))
          (= action :delete-asg)
          (if-let [asg (get-in deployment [:parameters
                                           :oldAutoScalingGroupName])]
            (asgard/delete-asg
             region asg deployment-id task task-finished task-timed-out)
            (do
              (let [task (assoc task
                           :end (util/now-string)
                           :status "completed")])
              (store/store-task deployment-id task)
              (task-finished deployment-id task)))
          :else (throw (ex-info "Unrecognised action."
                                {:type ::unrecogized-action
                                 :action action})))))

(defn task-finished
  "Function called when a task has completed. Deals with moving the deployment
   to the next phase."
  [deployment-id {task-id :id :as task}]
  (store/store-task deployment-id (assoc task
                                    :end (util/now-string)
                                    :status "completed"))
  (let [deployment (store/get-deployment deployment-id)]
    (let [next-task (task-after deployment task-id)]
      (if next-task
        (start-task deployment next-task)
        (finish-deployment deployment)))))

;; Pre-hook attached to `task-finished` to log parameters.
(with-pre-hook! #'task-finished
  (fn [deployment-id task]
    (log/debug "Task" task "finished for deployment with ID" deployment-id)))

(defn task-timed-out
  "Function called when a task has timed-out. Deals with the repercussions of
   that."
  [deployment-id task]
  (store/store-task deployment-id (assoc task
                                    :end (util/now-string)
                                    :status "failed"))
  nil)

;; Pre-hook attached to `task-timed-out` to log parameters.
(with-pre-hook! #'task-timed-out
  (fn [deployment-id task]
    (log/debug "Task" task "timed-out for deployment with ID" deployment-id)))

;; # Concerning deployments

(defn prepare-deployment
  "Prepares a deployment of the `application` in an `environment` within the
   given `region`. It'll mark the deployment as being done by `user` and will
   use `ami` when telling what Asgard should deploy then store it.

   Will return the newly-created deployment."
  [region application environment user ami]
  (let [hash (tyr/last-commit-hash environment application)
        parameters (tyr/deployment-params environment application hash)
        tasks (create-standard-deployment-tasks)
        deployment {:ami ami
                    :application application
                    :created (util/now-string)
                    :environment environment
                    :hash hash
                    :id (util/generate-id)
                    :parameters parameters
                    :region region
                    :tasks tasks
                    :user user}]
    (store/store-deployment deployment)
    deployment))

(defn start-deployment
  "Kicks off the first task of the deployment with `deployment-id`."
  [deployment-id]
  (let [deployment (store/get-deployment deployment-id)
        first-task (first (:tasks deployment))
        deployment (assoc deployment :start (util/now-string))]
    (store/store-deployment deployment)
    (start-task deployment first-task)
    nil))

(defn finish-deployment
  "Puts an `:end` date on the deployment and we all breathe a sigh of relief!"
  [deployment]
  (store/store-deployment (assoc deployment :end (util/now-string)))
  nil)
