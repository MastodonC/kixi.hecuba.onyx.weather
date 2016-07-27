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

(def workflow
  [[:event/in-queue       :event/run-weather]
   [:event/run-weather  :event/out-confirm]])

(def kafka-consumer-task-opts
  {:onyx/min-peers 1 ;; should be number of partitions
   :onyx/max-peers 1
   :kafka/topic "hecuba-weather-queue"
   :kafka/group-id "kixi-hecuba-weather"
   :kafka/zookeeper "zookeeper:2181"
   :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
   :kafka/fetch-size 307200
   :kafka/chan-capacity 1000
   :kafka/offset-reset :smallest
   :kafka/force-reset? false
   :kafka/empty-read-back-off 500
   :kafka/commit-interval 500
   :onyx/doc "Reads messages from a Kafka topic"})

(def kafka-outgoing-task-opts
  {:onyx/min-peers 1 ;; should be number of partitions
   :onyx/max-peers 1
   :kafka/topic "hecuba-measurements-queue"
   :kafka/group-id "kixi-hecuba-weather"
   :kafka/zookeeper "zookeeper:2181"
   :kafka/serializer-fn :kixi.hecuba.onyx.jobs.shared/serialize-message-json
   :kafka/request-size 307200
   :kafka/chan-capacity 1000
   :kafka/offset-reset :smallest
   :kafka/force-reset? false
   :kafka/empty-read-back-off 500
   :kafka/commit-interval 500
   :onyx/doc "Writes outgoing measurements to Kafka topic"}
  )

(defn weather-job
  [batch-settings]
  (let [base-job {:workflow workflow
                  :catalog []
                  :lifecycles []
                  :windows []
                  :triggers []
                  :flow-conditions []
                  :task-scheduler :onyx.task-scheduler/balanced}]
    (let [kafka-merged-opts-in (merge kafka-consumer-task-opts batch-settings)
          kafka-merged-opts-out (merge kafka-outgoing-task-opts batch-settings)]
      (timbre/info "kafka merged opts incoming: " kafka-merged-opts-in)
      (timbre/info "kafka merged opts outgoing: " kafka-merged-opts-out)
      (-> base-job
          (add-task (kafka-task/consumer :event/in-queue kafka-merged-opts-in))
          (add-task (run-weather :event/run-weather batch-settings))
          (add-task (kafka-task/producer :event/out-confirm kafka-merged-opts-out))))))

(defmethod register-job "weather-job"
  [job-name config]
  (let [batch-settings {:onyx/batch-size 1 :onyx/batch-timeout 1000}]
    (weather-job batch-settings)))
