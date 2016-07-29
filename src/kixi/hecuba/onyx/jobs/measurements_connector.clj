(ns kixi.hecuba.onyx.jobs.measurements-connector
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [environ.core :refer [env]]
            [schema.core :as s]
            [taoensso.timbre :as timbre]))


(defn push-payload-to-hecuba
  "Create the http post request for measurements
  uploads"
  [json-payload entity-id device-id]
  (let [json-to-send (json/generate-string {:measurements json-payload})
        endpoint (str (env :hecuba-endpoint) "entities/" entity-id "/devices/" device-id "/measurements/")]

    (timbre/info (format "DATA: %s" json-to-send))


    (timbre/info "Using endpoint: %s" (str (env :hecuba-endpoint) "entities/" entity-id "/devices/" device-id "/measurements/"))

    (try (client/post
          endpoint
          {:basic-auth [(env :hecuba-username) (env :hecuba-password)]
           :body json-to-send
           :headers {"X-Api-Version" "2"}
           :content-type :json
           :socket-timeout 20000
           :conn-timeout 20000
           :accept "application/json"})
         (catch Exception e (doall (str "Caught Exception " (.getMessage e))
                                   (timbre/error e "> There was an error during the upload to entity " entity-id)))
         (finally {:message "push-payload-to-hecuba complete."}))))

(defn get-data [fn-data]
  ;; map of data passed in from the workflow here.
  ;; TODO - needs to take the measurements and save them via Hecuba API
  (let [entity-id (get-in fn-data [:kafka-payload :entity-id])
        device-id (get-in fn-data [:kafka-payload :device_id])
        measurements (:measurements fn-data)
        degree-day [(:degree-day fn-data)]]
    (timbre/info (format "Received measurements to write for device-id:%s on entity:%s" device-id entity-id))
    (push-payload-to-hecuba measurements entity-id device-id)
    (timbre/info (format "Writing measurements to write for device-id:%s on entity:%s" device-id entity-id))
    (push-payload-to-hecuba degree-day entity-id device-id)
    (timbre/info (format "Writing degree-day to write for device-id:%s on entity:%s" device-id entity-id)))
  {:done true})

(s/defn save-measurements
  ([task-name :- s/Keyword task-opts]
   {:task {:task-map (merge {:onyx/name task-name
                             :onyx/type :function
                             :onyx/fn :kixi.hecuba.onyx.jobs.measurements-connector/get-data}
                            task-opts)}}))
