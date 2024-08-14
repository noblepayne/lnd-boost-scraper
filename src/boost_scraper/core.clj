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
                        (scrape-lnd-boosts lnd-conn lnd/macaroon 100)))
   (fn [x] (println "========== DONE ==========" x)))

  (->> #_1722901411 (->epoch #inst "2024-07-01T07:00")
       (boost-scraper.reports/boost-report alby-conn #"(?i).*")
       (spit "/tmp/alby"))

  (->> 1722901411 #_(->epoch #inst "2024-07-01T07:00")
       (boost-scraper.reports/boost-report lnd-conn #"(?i).*")
       (spit "/tmp/lnd"))

  (d/q '[:find (d/pull ?e [:*])
         :where [?e :invoice/created_at 1722553687]]
       (d/db alby-conn))

  ;; diffing between upstreams

  (defn unique-content-ids [conn since]
    (first (d/q '[:find [(distinct ?cid)]
                  :in $ ?since
                  :where
                  #_[?e :boostagram/action "boost"]
                  [?e :invoice/creation_date ?cd]
                  [(< ?since ?cd)]
                  [?e :boostagram/content_id ?cid]]
                (d/db conn)
                since)))

  (defn ->epoch [inst]
    (-> inst .toInstant .getEpochSecond))

  (def alby-ids
    (unique-content-ids alby-conn
                        (->epoch #inst "2024-07-01T07:00")))

  (def lnd-ids
    (unique-content-ids lnd-conn
                        (->epoch #inst "2024-07-01T07:00")))

  (clojure.set/difference lnd-ids alby-ids)
  (d/q '[:find [(d/pull ?e [#_:*
                            :boostagram/sender_name_normalized
                            :boostagram/value_sat_total
                            :boostagram/podcast
                            :boostagram/episode
                            :boostagram/app_name
                            :invoice/created_at
                            :invoice/creation_date
                            :invoice/identifier
                            :boostagram/message]) ...] :in $ ids :where [?e :boostagram/content_id ?cd]
         [(in ?cd ids)]] (d/db lnd-conn) *1)

  (clojure.set/difference alby-ids lnd-ids)
  (d/q '[:find [(d/pull ?e [:boostagram/sender_name_normalized
                            :boostagram/value_sat_total
                            :boostagram/podcast
                            :boostagram/episode
                            :boostagram/app_name
                            :invoice/created_at
                            :invoice/creation_date
                            :invoice/identifier
                            :boostagram/message]) ...] :in $ ids :where [?e :boostagram/content_id ?cd]
         [(in ?cd ids)]] (d/db alby-conn) *1)


  (defn load-missing-boosts [dest-conn src-conn src-boost-cids]
    (let [entities (d/pull-many (d/db src-conn)
                                [:*]
                                (map #(vector :boostagram/content_id %)
                                     src-boost-cids))
          entities (map #(dissoc % :db/id) entities)]
      (d/transact! dest-conn entities)))

  (load-missing-boosts lnd-conn alby-conn *1)

  (defn format-boosts [{:keys [boostagram/sender_name_normalized
                               boostagram/value_sat_total
                               boostagram/podcast
                               boostagram/episode
                               boostagram/app_name
                               invoice/created_at
                               invoice/creation_date
                               boostagram/message]}]
    (str "### From: " sender_name_normalized
         "\n + " (reports/int-comma value_sat_total) " sats"
         "\n + " podcast " / " episode
         "\n + " app_name " " created_at " (" creation_date ")"
         "\n" (clojure.string/join "\n" (map #(str "   > " %)
                                             (clojure.string/split-lines (or message ""))))
         "\n"))


  (identity (clojure.string/join "\n" (map format-boosts (sort-by :invoice/created_at *1))))

  (d/q '[:find [(d/pull ?e [:invoice/created_at])]
         :where
         [(d/q [:find (min ?cd)
                :where
                [?e' :boostagram/action "boost"]
                [?e' :invoice/creation_date ?cd]] $) [[?maxcd]]]
         [?e :invoice/creation_date ?maxcd]]
       (d/db alby-conn))

  (d/close alby-conn)
  (d/close lnd-conn)
  )
