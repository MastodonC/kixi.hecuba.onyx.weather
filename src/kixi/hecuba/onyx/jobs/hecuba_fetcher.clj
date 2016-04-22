(ns kixi.hecuba.onyx.jobs.hecuba-fetcher
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [environ.core :refer [env]]))

(def api-endpoint "http://www.getembed.com/4/")

(defn run-api-search [{:keys [entity-id] :as args-map}]
  (let [url-to-get (str api-endpoint
                        "entities/"
                        entity-id
                        "/devices/")]
    (try (let [response-json (-> (:body (client/get
                                         url-to-get
                                         {:basic-auth [(env :hecuba-username)
                                                       (env :hecuba-password)]
                                          :Headers {"X-Api-Version" "2"}
                                          :content-type :json
                                          :socket-timeout 20000
                                          :conn-timeout 20000}))
                                 (json/parse-string))]
           response-json)
         (catch Exception e (println e)))))

;; fetch the device info from Hecuba and add the readings to the incoming payload.
;; so we end up with entity/type/property-code info + device/sensors
;; note we don't pass Hecuba username/password info in the settings, use environment
;; variables instead:
;; export HECUBA_PASSWORD=youremail@email.thing and
;; export HECUBA_PASSWORD=mypassword

(defn get-data [fn-data]
  (println (str "k.h.o.j.hf - data - " fn-data))
  (let [data (-> fn-data
                 (run-api-search))]
    (assoc fn-data :readings data)))
