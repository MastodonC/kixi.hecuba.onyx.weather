(ns kixi.hecuba.onyx.jobs.weather
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
            [kixi.hecuba.onyx.jobs.weather-connector :refer [run-weather]]))

(def kafka-partitions 1)

(def workflow
  [[:event/in-weather-queue       :event/run-weather]
   [:event/run-weather  :event/out-weather-confirm]])

(defn weather-job
  [batch-settings kafka-in-opts kafka-out-opts]
  (let [base-job {:workflow workflow
                  :catalog []
                  :lifecycles []
                  :windows []
                  :triggers []
                  :flow-conditions []
                  :task-scheduler :onyx.task-scheduler/balanced}]
    (let [kafka-merged-opts-in (merge kafka-in-opts batch-settings)
          kafka-merged-opts-out (merge kafka-out-opts batch-settings)]
      (-> base-job
          (add-task (kafka-task/consumer :event/in-weather-queue kafka-in-opts))
          (add-task (run-weather :event/run-weather batch-settings))
          (add-task (kafka-task/producer :event/out-weather-confirm kafka-out-opts))))))

(defmethod register-job "weather-job"
  [job-name config]
  (let [batch-settings {:onyx/batch-size 1
                        :onyx/batch-timeout 1000
                        :onyx/min-peers 1
                        :onyx/max-peers 1}
        kafka-in-opts {:onyx/batch-size 1
                       :onyx/batch-timeout 1000
                       :onyx/min-peers kafka-partitions ;; should be number of partitions
                       :onyx/max-peers kafka-partitions
                       :kafka/topic "hecuba-weather-queue"
                       :kafka/group-id "kixi-hecuba-weather-wgroup"
                       :kafka/zookeeper "127.0.0.1:2181"
                       :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
                       :kafka/fetch-size 307200
                       :kafka/chan-capacity 1000
                       :kafka/offset-reset :smallest
                       :kafka/force-reset? true
                       :kafka/empty-read-back-off 500
                       :kafka/commit-interval 500
                       :onyx/doc "Reads messages from a Kafka topic"}
        kafka-out-opts {:onyx/batch-size 1
                        :onyx/batch-timeout 1000
                        :onyx/min-peers kafka-partitions
                        :onyx/max-peers kafka-partitions
                        :onyx/type :output
                        :onyx/medium :kafka
                        :kafka/topic "hecuba-measurements-queue"
                        :kafka/zookeeper "127.0.0.1:2181"
                        :kafka/serializer-fn :kixi.hecuba.onyx.jobs.shared/serialize-message-json
                        :kafka/request-size 307200
                        :onyx/doc "Writes outgoing measurements to Kafka topic"}]
    (weather-job batch-settings kafka-in-opts kafka-out-opts)))
