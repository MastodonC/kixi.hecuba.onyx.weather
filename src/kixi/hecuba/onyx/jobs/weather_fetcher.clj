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
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]))

;; Date/time formatters
(def tformat (f/formatter "YYYY-MM-dd HH:mm"))
(def dformat (f/formatter "dd/MM/YYYY"))

(defn format-key [str-key]
  (when (string? str-key)
    (-> str-key
        clojure.string/lower-case
        (clojure.string/replace #" " "-")
        keyword)))

(defn convert-day-data-to-seq
  "Return the data as a sequence of maps."
  [data-str]
  (let [data (csv/read-csv data-str)
        headers (map format-key (first data))
        body (vec (rest data))]
    (map #(zipmap headers %) body)))

;; The McKiver method (or British Gas method) as employed by the Met Office
;; For more info see http://www.vesma.com/ddd/ddcalcs.htm
(defn calc-degreedays-mckiver [tmin tmax]
  (let [tbase 15.5]
    (double
     (cond
       (> tmin tbase)                0.0
       (> (/ (+ tmax tmin) 2) tbase) (/ (- tbase tmin) 4)
       (>= tmax tbase)               (- (/ (- tbase tmin) 2) (/ (- tmax tbase) 4))
       (< tmax tbase)                (- tbase (/ (+ tmax tmin) 2))
       :else -1))))

(defn get-max-and-min [daily-readings]
  (try
    (let [data (map (fn [t] (Float/parseFloat (:value t))) daily-readings)]
      {:max (apply max data) :min (apply min data)})
    (catch Exception e
      {:max 0 :min 0})))

(defn extract-temp-date-time-from-seq [data-seq]
  (map #(set/rename-keys (select-keys % [:screen-temperature :site-code
                                         :observation-date :observation-time])
                         {:screen-temperature :temperature
                          :observation-date :date
                          :observation-time :time})
       data-seq))

(defn met-office-post-request [querydate querytime siteid]
  (:body (client/post "http://datagovuk.cloudapp.net/query"
                      {:form-params {:Type "Observation"
                                     :PredictionSiteID siteid
                                     :ObservationSiteID siteid
                                     :Date querydate ;; dd/mm/yyyy
                                     :PredictionTime querytime ;; 0000
                                     }
                       :follow-redirects true})))

(defn get-met-office-csv-for-day-hour
  "Get Metoffice data as a string"
  [querydate querytime siteid]
  (->> (met-office-post-request querydate querytime siteid)
       (re-find #"https://datagovuk.blob.core.windows.net/csv/[a-z0-9]+.csv")
       (client/get)
       :body))


(defn pull-weather-station-day-data [querydate siteid]
  (into [] (keep (fn [hour] (try (-> (get-met-office-csv-for-day-hour querydate (format "%02d00" hour) siteid)
                                     (convert-day-data-to-seq)
                                     (extract-temp-date-time-from-seq))
                                 (catch Exception e (str "Exception caught: " (.getMessage e)))))
                 (range 0 24))))

(defn create-degree-day-measurement [measurements]
  (let [min-max (get-max-and-min measurements)]
    {:value (calc-degreedays-mckiver (:min min-max) (:max min-max))
     :type "Temperature_degreedays"
     :timestamp (:timestamp (first measurements))}))

(defn create-measurements [measurement-data]
  (map (fn [reading]
         (let [{:keys [temperature date time]} (first reading)]
           {:value temperature
            :type "Temperature"
            :timestamp (f/unparse (f/formatters :date-time)
                                  (f/parse tformat (str date " " time)))})) measurement-data))

(defn build-payload [weather-date fn-data]
  (let [measurements (create-measurements (pull-weather-station-day-data weather-date (:property-code fn-data)))
        degree-day (create-degree-day-measurement measurements)]
    {:kafka-payload fn-data
     :entity-id fn-data
     :measurements measurements
     :degree-day degree-day}))

;; incoming payload from onyx workflow arrives here (fn-data)
;; we're just adding to it and passing it on to the outgoing
;; kafka queue.
;; Onyx expects a map with {:message your-message}
(defn get-data [fn-data]
  {:message (build-payload
             (f/unparse dformat (t/minus (t/now) (t/days 2)))
             fn-data)})
