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

(def workflow
  [[:event/in-queue       :event/save-measurements]
   [:event/save-measurements  :event/out-confirm]])

(def kafka-consumer-task-opts
  {:onyx/min-peers 1 ;; should be number of partitions
   :onyx/max-peers 1
   :kafka/topic "hecuba-measurements-queue"
   :kafka/group-id "kixi-hecuba-weather"
   :kafka/zookeeper "127.0.0.1:2181"
   :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
   :kafka/fetch-size 307200
   :kafka/chan-capacity 1000
   :kafka/offset-reset :smallest
   :kafka/force-reset? true
   :kafka/empty-read-back-off 500
   :kafka/commit-interval 500
   :onyx/doc "Reads messages from a Kafka topic"})

(defn measurements-job
  [batch-settings]
  (let [base-job {:workflow workflow
                  :catalog []
                  :lifecycles []
                  :windows []
                  :triggers []
                  :flow-conditions []
                  :task-scheduler :onyx.task-scheduler/balanced}]
    (let [kafka-merged-opts (merge kafka-consumer-task-opts batch-settings)]
      (timbre/info "kafka merged opts: " kafka-merged-opts)
      (-> base-job
          (add-task (kafka-task/consumer :event/in-queue kafka-merged-opts))
          (add-task (save-measurements :event/save-measurements batch-settings))
          (add-task (core-async-task/output :event/out-confirm batch-settings))))))

(defmethod register-job "measurements-job"
  [job-name config]
  (let [batch-settings {:onyx/batch-size 1 :onyx/batch-timeout 1000}]
    (measurements-job batch-settings)))
