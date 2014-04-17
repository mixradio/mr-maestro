(ns exploud.aws-test
  (:require [amazonica.aws.ec2 :as ec2]
            [cheshire.core :as json]
            [environ.core :refer :all]
            [exploud.aws :refer :all]
            [midje.sweet :refer :all]))

(fact-group :unit :describe-instances
  (fact "ec2/describe-instances is called with name and state"
        (describe-instances "env" "region" "name" "state") => truthy
        (provided
         (ec2/describe-instances anything
                                 :filters
                                 [{:name "tag:Name" :values ["name-*"]}
                                  {:name "instance-state-name" :values ["state"]}]) => [{}]))

  (fact "ec2/describe-instances defaults name and state if nil"
        (describe-instances "env" "region" nil nil) => truthy
        (provided
         (ec2/describe-instances anything
                                 :filters
                                 [{:name "tag:Name" :values ["*"]}
                                  {:name "instance-state-name" :values ["running"]}]) => [{}]))

  (fact "ec2/describe-instances preserves name if contains *"
        (describe-instances "env" "region" "part-*-part" nil) => truthy
        (provided
         (ec2/describe-instances anything
                                 :filters
                                 [{:name "tag:Name" :values ["part-*-part"]}
                                  {:name "instance-state-name" :values ["running"]}]) => [{}]))

  (fact "describe instances plain formats response for multiple reservations"
        (describe-instances-plain "env" "region" nil nil) => (contains "two")
        (provided
         (ec2/describe-instances anything
                                 anything
                                 anything) => {:reservations [{:instances [..instance1..]}
                                                              {:instances [..instance2..]}]}
                                 (transform-instance-description ..instance1..) => {:name "one"}
                                 (transform-instance-description ..instance2..) => {:name "two"}))

  (fact "describe instances plain formats response from multiple instances in one reservation"
        (describe-instances-plain "env" "region" nil nil) => (contains "two")
        (provided
         (ec2/describe-instances anything
                                 anything
                                 anything) => {:reservations [{:instances [..instance1.. ..instance2..]}]}
                                 (transform-instance-description ..instance1..) => {:name "one"}
                                 (transform-instance-description ..instance2..) => {:name "two"}))

  (fact "transform-instance-description returns a transformed description"
        (transform-instance-description
         {:tags [{:key "Name" :value ..name..}]
          :instance-id ..instance..
          :image-id ..image..
          :private-ip-address ..ip..})
        => {:name ..name.. :instance-id ..instance.. :image-id ..image.. :private-ip ..ip..})

  (fact "transform-instance-description handles missing Name tag"
        (transform-instance-description
         {:tags []
          :instance-id ..instance..
          :image-id ..image..
          :private-ip-address ..ip..})
        => {:name "none" :instance-id ..instance.. :image-id ..image.. :private-ip ..ip..})

  (fact "getting the last auto scaling group for an application works"
        (last-application-auto-scaling-group "search" "poke" "eu-west-1") => {:auto-scaling-group-name "search-poke-v023"}
        (provided
         (auto-scaling-groups "poke" "eu-west-1") => [{:auto-scaling-group-name "app1-something-v012"}
                                                      {:auto-scaling-group-name "search-poke"}
                                                      {:auto-scaling-group-name "search-poke-v023"}
                                                      {:auto-scaling-group-name "search-poke-v000"}
                                                      {:auto-scaling-group-name "app2-poke-v000"}]))

  (fact "getting the last auto scaling group for an application works when it's not got the v000 bit"
        (last-application-auto-scaling-group "search" "poke" "eu-west-1") => {:auto-scaling-group-name "search-poke"}
        (provided
         (auto-scaling-groups "poke" "eu-west-1") => [{:auto-scaling-group-name "app1-something-v012"}
                                                      {:auto-scaling-group-name "search-poke"}
                                                      {:auto-scaling-group-name "app2-poke-v000"}])))
