(ns kixi.hecuba.onyx.weather
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [kixi.hecuba.onyx.jobs.events]
            [kixi.hecuba.onyx.components.onyx-job :refer [new-onyx-job]]))

(defn new-system
  []
  (let [mode :dev]
    (component/system-map
     :onyx-events   (new-onyx-job mode 'kixi.hecuba.onyx.jobs.events/build-job))))
