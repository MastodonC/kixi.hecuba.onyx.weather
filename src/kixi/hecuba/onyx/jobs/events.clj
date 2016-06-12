(ns kixi.hecuba.onyx.jobs.events
  (:require [clojure.core.async :refer [chan >! <! close! timeout go-loop]]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]
            [onyx.plugin.kafka]
            [kixi.hecuba.onyx.jobs.shared]
            [kixi.hecuba.onyx.jobs.weather-fetcher]
            [kixi.hecuba.onyx.jobs.hecuba-fetcher]))

(def workflow
  [[:event/in-queue       :event/weather-station-data-request]
   [:event/weather-station-data-request     :event/message-queue-out]])

(defn build-catalog
  [batch-size batch-timeout]
  [{:onyx/name :event/in-queue
    :onyx/batch-size batch-size
    :onyx/min-peers 1 ;; should be number of partitions
    :onyx/max-peers 1
    :kafka/topic "weather-data-request-queue"
    :kafka/group-id "kixi-hecuba-weather"
    :kafka/zookeeper "127.0.0.1:2181"
    :kafka/deserializer-fn :kixi.hecuba.onyx.jobs.shared/deserialize-message-json
    :onyx/plugin :onyx.plugin.kafka/read-messages
    :onyx/type :input
    :onyx/medium :kafka
    :kafka/fetch-size 307200
    :kafka/chan-capacity 1000
    :kafka/offset-reset :smallest
    :kafka/force-reset? false
    :kafka/empty-read-back-off 500
    :kafka/commit-interval 500
    :onyx/doc "Reads messages from a Kafka topic"}

   {:onyx/name :event/weather-station-data-request
    :onyx/plugin :onyx.peer.function/function
    :onyx/fn :kixi.hecuba.onyx.jobs.weather-fetcher/get-data
    :onyx/type :function
    :onyx/batch-size batch-size
    :onyx/batch-timeout batch-timeout
    :onyx/doc "Identity output"}

   {:onyx/name :event/message-queue-out
    :onyx/plugin :onyx.plugin.kafka/write-messages
    :onyx/type :output
    :onyx/medium :kafka
    :kafka/topic "hecuba-measurements-queue"
    :kafka/zookeeper "127.0.0.1:2181"
    :kafka/serializer-fn :kixi.hecuba.onyx.jobs.shared/serialize-message-json
    :kafka/request-size 307200
    :onyx/batch-size batch-size
    :onyx/doc "Writes messages to a Kafka topic"}
   ])

(def lifecycles
  (->> (build-catalog 0 0)
       (map :onyx/name)
       (mapv #(hash-map :lifecycle/task %
                        :lifecycle/calls :kixi.hecuba.onyx.jobs.shared/log-calls))
       (into [{:lifecycle/task :event/in-queue
               :lifecycle/calls :onyx.plugin.kafka/read-messages-calls}
              {:lifecycle/task :event/message-queue-out
               :lifecycle/calls :onyx.plugin.kafka/write-messages-calls}])))

(def flow-conditions
  [])

(defn build-job
  [mode batch-size batch-timeout]
  {:catalog (build-catalog batch-size batch-timeout)
   :workflow workflow
   :lifecycles lifecycles
   :task-scheduler :onyx.task-scheduler/balanced
   :flow-conditions flow-conditions})
