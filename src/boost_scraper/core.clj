(ns boost-scraper.core
  (:gen-class)
  (:require [boost-scraper.db :as db]
            [boost-scraper.reports :as reports]
            [boost-scraper.upstreams.lnd :as lnd]
            [boost-scraper.upstreams.alby :as alby]
            [babashka.http-client :as http]
            [babashka.cli :as cli]
            [clojure.core.async :as async]
            [datalevin.core :as d]
            [clojure.instant]))

#_(defn scrape-boosts-since [conn token items-per-page wait since]
    (->> (alby/get-all-boosts token items-per-page :wait wait :since since)
         (db/add-boosts conn)))

#_(defn scrape-boosts-after [conn token items-per-page wait after]
    (->> (alby/get-all-boosts token items-per-page :wait wait :after after)
         (db/add-boosts conn)))

(defn scrape-alby-boosts [conn token wait]
  (->> (alby/get-all-boosts token :wait wait)
       (db/add-boosts conn)))

(defn scrape-alby-boosts-until-epoch [conn token epoch wait]
  (->> (alby/get-all-boosts-until-epoch token epoch :wait wait)
       (db/add-boosts conn)))

(defn autoscrape-alby [conn token wait]
  (let [[most-recent-timestamp]
        (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
             (d/db conn))]
    (->> (alby/get-all-boosts-until-epoch token most-recent-timestamp :wait wait)
         (db/add-boosts conn))))

(defn scrape-lnd-boosts [conn macaroon wait]
  (->> (lnd/get-all-boosts macaroon :wait wait)
       (db/add-boosts conn)))

(defn scrape-lnd-boosts-until-epoch [conn macaroon epoch wait]
  (->> (lnd/get-all-boosts-until-epoch macaroon epoch :wait wait)
       (db/add-boosts conn)))

(defn autoscrape-lnd [conn token wait]
  (let [[most-recent-timestamp]
        (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
             (d/db conn))]
    (->> (lnd/get-all-boosts-until-epoch token most-recent-timestamp :wait wait)
         (db/add-boosts conn))))

(defn scrape-nodecan-boosts [conn macaroon wait]
  (->> (lnd/get-all-boosts macaroon
                           :wait wait
                           :url "https://100.120.212.39:8080/v1/invoices")
       (db/add-boosts conn)))

(defn scrape-nodecan-boosts-until-epoch [conn macaroon epoch wait]
  (->> (lnd/get-all-boosts-until-epoch macaroon
                                       epoch
                                       :wait wait
                                       :url "https://100.120.212.39:8080/v1/invoices")

       (db/add-boosts conn)))

(defn autoscrape-nodecan [conn token wait]
  (let [[most-recent-timestamp]
        (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
             (d/db conn))]
    (->> (lnd/get-all-boosts-until-epoch token
                                         most-recent-timestamp
                                         :wait wait
                                         :url "https://100.120.212.39:8080/v1/invoices")
         (db/add-boosts conn))))



(alter-var-root #'*out* (constantly *out*))

(defn -main [& args]
  (let [lnd-conn (d/get-conn db/lnd-dbi db/schema)
        alby-conn (d/get-conn db/alby-dbi db/schema)
        lnd-macaroon (lnd/read-macaroon (System/getenv "MACAROON_PATH"))
        alby-token (System/getenv "ALBY_ACCESS_CODE")
        runtime (Runtime/getRuntime)
        shutdown-hook (Thread. (fn []
                                 (println "stopping scraper")
                                 (reset! lnd/scrape-can-run false)
                                 (reset! alby/scrape-can-run false)
                                 (d/close lnd-conn)
                                 (d/close alby-conn)))
        _ (.addShutdownHook runtime shutdown-hook)
        lnd-polling-thread (Thread. (fn []
                                      (while true
                                        (println "scraping lnd")
                                        (autoscrape-lnd lnd-conn lnd-macaroon 500)
                                        (println "sleeping")
                                        (Thread/sleep 10000))))
        alby-polling-thread (Thread. (fn []
                                       (while true
                                         (println "scraping alby")
                                         (autoscrape-alby alby-conn alby-token 3000)
                                         (println "sleeping")
                                         (Thread/sleep 60000))))]
    (.start lnd-polling-thread)
    (.start alby-polling-thread)
    (.join lnd-polling-thread)
    (.join alby-polling-thread)))

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)

  (def alby-conn (d/get-conn db/alby-dbi db/schema))
  (def lnd-conn (d/get-conn db/lnd-dbi db/schema))
  (def nodecan-conn (d/get-conn db/nodecan-dbi db/schema))

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
                        (scrape-alby-boosts alby-conn alby/test-token
                                            3000
                                            #_#inst "2024-07-25T00:00"
                                            #_#inst "2023-12-31T11:59Z")))
   (fn [_] (println "ALL DONE!")))

  ;; LND
  (reset! lnd/scrape-can-run false)
  (reset! lnd/scrape-can-run true)
  (async/take!
   (async/thread-call (fn []
                        (scrape-lnd-boosts lnd-conn lnd/macaroon 500)))
   (fn [x] (println "========== DONE ==========" x)))
  
  (scrape-nodecan-boosts nodecan-conn lnd/nodecan-macaroon 1000)

  (async/take! (async/thread-call
                (fn []
                  (scrape-alby-boosts-until-epoch
                   alby-conn
                   alby/test-token
                   (->epoch #inst "2024-07-01T07:00")
                   2000)))
               (fn [_] (println "alby sync complete")))

  (async/take! (async/thread-call
                (fn []
                  (scrape-lnd-boosts-until-epoch
                   lnd-conn
                   lnd/macaroon
                   (->epoch #inst "2024-07-01T07:00")
                   500)))
               (fn [_] (println "lnd sync complete")))

  (async/take! (async/thread-call
                (fn []
                  (scrape-nodecan-boosts-until-epoch
                   nodecan-conn
                   lnd/nodecan-macaroon
                   (->epoch #inst "2024-07-01T07:00")
                   500)))
               (fn [_] (println "nodecan sync complete")))

  (autoscrape-alby alby-conn alby/test-token 2000)
  (autoscrape-lnd lnd-conn lnd/macaroon 500)

  (->> #_1722901411 (->epoch #inst "2024-07-01T07:00") #_1722388429
       (boost-scraper.reports/get-boost-summary-for-report' alby-conn #"(?i).*")
       (into [] cat)
       (sort-by :invoice/creation_date)
       (spit "/tmp/alby"))

  (->> #_(->epoch #inst "2024-07-01T07:00")  1723408945
       (boost-scraper.reports/boost-report lnd-conn #"(?i).*Unplugged.*")
       #_(into [] cat)
       #_(sort-by :invoice/creation_date)
       (spit "/tmp/lnd"))

  (->> #_1722901411 (->epoch #inst "2024-08-01T07:00")
       (boost-scraper.reports/get-boost-summary-for-report nodecan-conn #"(?i).*")
       (spit "/tmp/nodecan"))

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
    (let [entities #_(d/pull-many (d/db src-conn)
                                [:*]
                                (map #(vector :boostagram/content_id %)
                                     src-boost-cids))
          (d/q '[:find [(d/pull ?e [:*]) ...]
                 :in $ [?cid ...]
                 :where
                 [?e :boostagram/content_id ?cid]]
               (d/db src-conn) src-boost-cids)
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
       (d/db lnd-conn))

  ;; removing some items
  (d/q '[:find [(max ?cd)]
         :where
         [?e :invoice/creation_date ?cd]
         #_[?e :boostagram/action "boost"]
         #_(not [?e :boostagram/podcast "LINUX Unplugged"])]
       (d/db lnd-conn))

  (d/q '[:find [?e ...]
         :in $ ?last
         :where
         [?e :invoice/creation_date ?cd]
         [(< ?last ?cd)]]
       (d/db lnd-conn)
       (->epoch #inst "2024-08-10T20:00"))

  
  (d/q '[:find (d/pull ?e [:invoice/creation_date :boostagram/action])
         :where [?e :invoice/identifier "453978"]]
       (d/db lnd-conn))

 (run!
  #(d/transact! lnd-conn
               (map (fn [e] [:db.fn/retractEntity e]) %))
  (partition 100 *1))

  (d/close alby-conn)
  (d/close lnd-conn)
  (d/close nodecan-conn)
  )
