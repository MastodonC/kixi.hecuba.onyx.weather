(ns kixi.hecuba.onyx.jobs.weather-fetcher
  (:require [clojure.string        :as str]
            [clj-time.core         :as t]
            [clj-time.format       :as f]
            [clj-time.periodic     :as tp]
            [clojure.java.io       :as io]
            [clj-http.client       :as client]
            [clojure.data.csv      :as csv]
            [clojure.data.json     :as json]
            [clojure.set           :as set]
            [clojure.tools.logging :as log]))

(defn format-key [str-key]
  (when (string? str-key)
    (-> str-key
        clojure.string/lower-case
        (clojure.string/replace #" " "-")
        keyword)))

(defn process-data-str
  "Return the data as a sequence of maps."
  [data-str]
  (let [data (csv/read-csv data-str)
        headers (map format-key (first data))
        body (vec (rest data))]
    (map #(zipmap headers %) body)))

(defn keep-temp-date-time [data-seq]
  (map #(set/rename-keys (select-keys % [:screen-temperature :site-code
                                         :observation-date :observation-time])
                         {:screen-temperature :temperature
                          :observation-date :date
                          :observation-time :time})
       data-seq))

(defn pull-data
  "Get Metoffice data as a string"
  ([querydate querytime siteid]
   (->> (client/post "http://datagovuk.cloudapp.net/query"
                     {:form-params {:Type "Observation"
                                    :PredictionSiteID siteid
                                    :ObservationSiteID siteid
                                    :Date querydate ;; dd/mm/yyyy
                                    :PredictionTime querytime ;; 0000
                                    }
                      :follow-redirects true})
        :body
        (re-find #"https://datagovuk.blob.core.windows.net/csv/[a-z0-9]+.csv")
        slurp
        )))

(defn pull-weather-station-day-data [querydate siteid]
  (mapv (fn [hour] (try (-> (pull-data querydate (format "%02d00" hour) siteid)
                            (process-data-str)
                            (keep-temp-date-time))
                        (catch Exception e (str "Exception caught: " (.getMessage e)))))
        (range 0 24)))