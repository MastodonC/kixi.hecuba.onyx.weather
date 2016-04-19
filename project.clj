(defproject kixi.hecuba.onyx.weather "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :target-path "target/%s"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.371"]
                 [com.stuartsierra/component "0.3.1"]
                 [org.onyxplatform/onyx "0.8.11"]
                 [org.onyxplatform/onyx-kafka "0.8.11.0"]
                 [cheshire "5.5.0"]
                 [com.taoensso/timbre "4.3.1"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.4"]]
                   :source-paths ["dev"]}})
