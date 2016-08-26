(ns kixi.hecuba.onyx.weather
  (:require [clojure.java.io :as io]
            [lib-onyx.peer :as peer]
            [onyx.job]
            [onyx.api]
            [onyx.test-helper]
            [clojure.tools.cli :refer [parse-opts]]
            [aero.core :refer [read-config]]
            [com.stuartsierra.component :as component]
            [onyx.plugin.kafka]
            [taoensso.timbre :as timbre]
            ;; load job
            [kixi.hecuba.onyx.heartbeat_server :as heartbeat]
            [kixi.hecuba.onyx.jobs.weather]
            [kixi.hecuba.onyx.jobs.measurements]
            [kixi.hecuba.onyx.new-onyx-job :refer [new-onyx-job]])
  (:gen-class))

(defn file-exists?
  "Check both the file system and the resources/ directory
  on the classpath for the existence of a file"
  [file]
  (let [f (clojure.string/trim file)
        classf (io/resource file)
        relf (when (.exists (io/as-file f)) (io/as-file f))]
    (or classf relf)))

(def web-server-config
  {:web-server {:port 8082}
   :logging {:level :info
             :ns-blacklist ["org.eclipse.jetty"]}})

(defn cli-options []
  [["-c" "--config FILE" "Aero/EDN config file"
    :default (io/resource "config.edn")
    :default-desc "resources/config.edn"
    :parse-fn file-exists?
    :validate [identity "File does not exist relative to the workdir or on the classpath"
               read-config "Not a valid Aero or EDN file"]]

   ["-p" "--profile PROFILE" "Aero profile"
    :parse-fn (fn [profile] (clojure.edn/read-string (clojure.string/trim profile)))]

   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Onyx Peer and Job Launcher"
        ""
        "Usage: [options] action [arg]"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  start-peers [npeers]    Start Onyx peers."
        ""]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit [status msg]
  (timbre/info msg)
  (System/exit status))

;; not related to the lib-onyx.peer api.
(defn start-peer-internal [n peer-config env-config config]
  (let [n-peers (or (try (Integer/parseInt 1) (catch Exception e)) n)
        _ (timbre/info "Starting peer-group")
        peer-group (onyx.api/start-peer-group peer-config)
        _ (timbre/info "Starting env")
        env (onyx.api/start-env env-config)
        _ (timbre/info "Starting peers")
        peers (onyx.api/start-peers n-peers peer-group)]
    (timbre/infof "Attempting to connect to Zookeeper %s" (:zookeeper/address peer-config))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                       (fn []
                         (doseq [v-peer peers]
                           (onyx.api/shutdown-peer v-peer))
                         (onyx.api/shutdown-peer-group peer-group)
                         (shutdown-agents))))
    (timbre/info "Started peers. Blocking forever.")
    ;; submit the jobs.
    (onyx.api/submit-job peer-config
                         (onyx.job/register-job "weather-job" config))
    (onyx.api/submit-job peer-config
                         (onyx.job/register-job "measurements-job" config))
    (.join (Thread/currentThread))))

(defn assert-job-exists [job-name]
  (let [jobs (methods onyx.job/register-job)]
    (when-not (contains? jobs job-name)
      (error-msg (into [(str "There is no job registered under the name " job-name "\n")
                        "Available jobs: "] (keys jobs))))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary] :as pargs} (parse-opts args (cli-options))
        action (first args)
        argument (clojure.edn/read-string (second args))]
    (cond (:help options) (exit 0 (usage summary))
          (not= (count arguments) 2) (exit 1 (usage summary))
          errors (exit 1 (error-msg errors)))
    (case action
      "start-peers" (let [{:keys [env-config peer-config] :as config}
                          (read-config (:config options) {:profile (:profile options)})]
                      (.start
                       (heartbeat/new-web-server web-server-config))
                      (start-peer-internal argument peer-config env-config config)))))

(defn new-system
  []
  (let [mode :dev]
    (component/system-map
     :onyx-events   (let [config-file (io/resource "config.edn")
                          {:keys [peer-config] :as config} (read-config config-file {:profile :default})
                          job-name "weather-job"]
                      (assert-job-exists job-name)
                      (new-onyx-job job-name config)))))
