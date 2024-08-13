(ns boost-scraper.core
  (:gen-class)
  (:require [boost-scraper.scrape :as scrape]
            [boost-scraper.reports :as reports]
            [boost-scraper.upstreams.lnd :as lnd]
            [boost-scraper.upstreams.alby :as alby]
            [babashka.http-client :as http]
            [babashka.cli :as cli]
            [clojure.core.async :as async]
            [datalevin.core :as d]
            [clojure.instant]))

(defn scrape-boosts-since [conn token items-per-page wait since]
  (->> (alby/get-all-boosts token items-per-page :wait wait :since since)
       (scrape/add-boosts conn)))

(defn scrape-boosts-after [conn token items-per-page wait after]
  (->> (alby/get-all-boosts token items-per-page :wait wait :after after)
       (map :data)
       (scrape/add-boosts conn)))

(defn scrape-lnd-boosts [conn macaroon wait]
  (->> (lnd/get-all-lnd-boosts macaroon :wait wait)
       (scrape/add-boosts conn)))

(alter-var-root #'*out* (constantly *out*))

(defn -main [& args]
  (let [conn (d/get-conn scrape/lnd-dbi scrape/schema)
        macaroon (lnd/read-macaroon (System/getenv "MACAROON_PATH"))
        runtime (Runtime/getRuntime)
        shutdown-hook (Thread. (fn []
                                 (println "closing")
                                 (reset! lnd/scrape-can-run false)
                                 (d/close conn)))
        _ (.addShutdownHook runtime shutdown-hook)]
    (while true
      (scrape-lnd-boosts conn macaroon 500)
      (Thread/sleep (* 1000 5)))
    (println args)))

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)

  (def alby-conn (d/get-conn scrape/alby-dbi scrape/schema))
  (def lnd-conn (d/get-conn scrape/lnd-dbi scrape/schema))

  (count (d/datoms (d/db alby-conn) :eav))
  (d/datoms (d/db alby-conn) :eav 1)

  (count (d/datoms (d/db lnd-conn) :eav))
  (d/datoms (d/db lnd-conn) :eav 1)

  ;; ALBY
  (reset! alby/scrape-can-run false)
  (reset! alby/scrape-can-run true)
  (async/take!
   (async/thread-call (fn []
                     ;;(scrape-boosts-since conn test-token
                        (scrape-boosts-after alby-conn alby/test-token
                                             100 3000
                                             #_#inst "2024-07-25T00:00"
                                             #inst "2023-12-31T11:59Z")))
   (fn [_] (println "ALL DONE!")))

  ;; LND
  (reset! lnd/scrape-can-run false)
  (reset! lnd/scrape-can-run true)
  (async/take!
   (async/thread-call (fn []
                        (scrape-lnd-boosts lnd-conn lnd/macaroon 500)))
   (fn [x] (println "========== DONE ==========" x)))

  (->> 1722901411
       (boost-scraper.reports/boost-report alby-conn #"(?i).*coder.*")
       (spit "/tmp/alby"))

  (->> 1722901411
       (boost-scraper.reports/boost-report lnd-conn #"(?i).*coder.*")
       (spit "/tmp/lnd"))

  (d/q '[:find (d/pull ?e [:*])
         :where [?e :invoice/created_at 1722553687]]
       (d/db alby-conn))

  (d/close alby-conn)
  (d/close lnd-conn))
