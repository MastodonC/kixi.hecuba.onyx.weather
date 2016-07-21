(ns kixi.hecuba.onyx.new-onyx-job
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]))

(defonce state (atom nil))

(defn start-onyx-env!
  [n-peers {:keys [env-config peer-config]}]
  (let [env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)
        v-peers (onyx.api/start-peers n-peers peer-group)]
    (reset! state {:v-peers v-peers
                   :peer-group peer-group
                   :env env
                   :ref-count 0})))

(defrecord OnyxJob [name config]
  component/Lifecycle
  (start [component]
    (when-not @state
      (start-onyx-env! 10 config)
      (timbre/info "Onyx environment was started"))
    (swap! state update-in [:ref-count] inc)
    (timbre/info "Starting Onyx job:" name)
    (let [{:keys [env-config peer-config]} config
          {:keys [job-id]} (onyx.api/submit-job
                            peer-config
                            (onyx.job/register-job name config))]
      (-> component
          (assoc :job-id job-id)
          (assoc :peer-config peer-config))))

  (stop [{:keys [job-id peer-config] :as component}]
    (when job-id
      (timbre/info "Stopping Onyx job:" name)
      (onyx.api/kill-job peer-config job-id))
    (when @state
      (swap! state update-in [:ref-count] dec)
      (when (-> @state :ref-count zero?)
        (timbre/info "Shutting down Onyx environment" )
        (let [{:keys [v-peers peer-group env]} @state]
          (doseq [v-peer v-peers]
            (onyx.api/shutdown-peer v-peer))
          (onyx.api/shutdown-peer-group peer-group)
          (onyx.api/shutdown-env env)
          (reset! state nil))))
    (-> component
        (dissoc component :job-id)
        (dissoc component :peer-config))))


(defn new-onyx-job [job-name config]
  (map->OnyxJob {:config config
                 :name job-name}))
