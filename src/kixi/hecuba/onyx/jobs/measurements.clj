(ns kixi.hecuba.onyx.jobs.measurements
  (:require [clojure.core.async :refer [chan >! <! close! timeout go-loop]]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.tasks.core-async :as core-async-task]
            [onyx.api]
            [onyx.plugin.kafka]
            [onyx.tasks.kafka :as kafka-task]
            [onyx.job :refer [add-task register-job]]
            [kixi.hecuba.onyx.jobs.shared]
            [kixi.hecuba.onyx.jobs.measurements-connector :refer [save-measurements]]))

(def kafka-partitions 1)

(def workflow
  [[:event/in-measurements-queue       :event/save-measurements]
   [:event/save-measurements  :event/out-measurements-confirm]])

(defn measurements-job
  [batch-settings kafka-in-opts]
  (let [base-job {:workflow workflow
                  :catalog []
                  :lifecycles []
                  :windows []
                  :triggers []
                  :flow-conditions []
                  :task-scheduler :onyx.task-scheduler/balanced}]
    (let [kafka-merged-opts (merge kafka-in-opts batch-settings)]
      (timbre/info "Setting up measurements job")
      (-> base-job
          (add-task (kafka-task/consumer :event/in-measurements-queue kafka-in-opts))
          (add-task (save-measurements :event/save-measurements batch-settings))
          (add-task (core-async-task/output :event/out-measurements-confirm batch-settings))))))

(defmethod register-job "measurements-job"
  [job-name config]
  (let [batch-settings {:onyx/batch-size 1
                        :onyx/batch-timeout 1000
                        :onyx/min-peers 1
                        :onyx/max-peers 1}
        kafka-in-opts {:onyx/batch-size 1
                       :onyx/batch-timeout 1000
                       :onyx/min-peers kafka-partitions
                       :onyx/max-peers kafka-partitions
                       :kafka/topic "hecuba-measurements-queue"
                       :kafka/group-id "kixi-hecuba-weather-mgroup"
                       :kafka/zookeeper (get-in config [:env-config :zookeeper/address])
                       :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
                       :kafka/fetch-size 307200
                       :kafka/chan-capacity 1000
                       :kafka/offset-reset :smallest
                       :kafka/force-reset? true
                       :kafka/empty-read-back-off 500
                       :kafka/commit-interval 500
                       :onyx/doc "Reads messages from a Kafka topic"}]
    (measurements-job batch-settings kafka-in-opts)))
