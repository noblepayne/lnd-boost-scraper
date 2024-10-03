(ns boost-scraper.core
  {:clj-kondo/config '{:lint-as {datalevin.core/with-transaction clojure.core/let}
                       :linters {:unresolved-symbol {:exclude [(datalevin.core/with-conn)]}}}}
  (:gen-class)
  (:require [boost-scraper.db :as db]
            [boost-scraper.reports :as reports]
            [boost-scraper.upstream :as upstream]
            [boost-scraper.upstream.alby :as alby]
            [boost-scraper.upstream.lnd :as lnd]
            [boost-scraper.utils :as utils]
            [boost-scraper.web :as web]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.instant]
            [datalevin.core :as d])
  (:import [boost_scraper.upstream IBoostScrape]))

(defn ->epoch [inst]
  (-> inst .toInstant .getEpochSecond))

(defn format-boosts [{:keys [boostagram/sender_name_normalized
                             boostagram/value_sat_total
                             boostagram/podcast
                             boostagram/episode
                             boostagram/app_name
                             invoice/created_at
                             invoice/creation_date
                             boostagram/message
                             scraper/source]}]
  (str "### From: " sender_name_normalized
       "\n + " (reports/int-comma value_sat_total) " sats"
       "\n + " podcast " / " episode
       "\n + " app_name " " created_at " (" creation_date ")"
       "\n + " source
       "\n" (str/join "\n" (map #(str "   > " %)
                                (str/split-lines (or message ""))))
       "\n"))

(defn unique-content-ids
  ([conn since] (unique-content-ids conn since (/ (System/currentTimeMillis) 1000)))
  ([conn since stop]
   (into #{} cat
         (d/q '[:find ?cid
                :in $ ?since ?stop
                :where
                #_[?e :boostagram/action "boost"]
                [?e :invoice/creation_date ?cd]
                [?e :boostagram/content_id ?cid]
                [(< ?since ?cd ?stop)]]
              (d/db conn)
              since
              stop))))

(defn find-similar [conn action snn timestamp delta]
  (d/q '[:find [(d/pull ?e [:*]) ...]
         :in $ ?action ?snn ?start ?stop
         :where
         [?e :boostagram/action ?action]
         [?e :boostagram/sender_name_normalized ?snn]
         [?e :invoice/creation_date ?cd]
         [(<= ?start ?cd ?stop)]]
       (d/db conn)
       action
       snn
       (- timestamp delta)
       (+ timestamp delta)))

(defn load-missing-boosts! [dest-conn src-conn src-boost-cids]
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

(def max-allowed-time-for-boost
  "10 minutes
   
  This value defines the maximum time we expect a boost to take to send to all splits.

  A boost may have many splits, and these are often sent serially. Thus,
  it takes some for a boost to send to all splits. When we sync from two sources
  it is possible that during a single scrape cycle, one source has received the boost
  while another has not. When performing a sync of missing boosts, it is possible that
  the 'syncing from' db has the boost, while the 'syncing to' db does not, but will shortly.
  We would then incorrectly sync that boost to the 'syncing to' db, only for it to then
  receive the same boost some time after, resulting in a duplicate.
   
  Our solution is to implement a capped lookback window when syncing missing boosts.
  This window has both a start and a stop, with the stop being set by the value defined here.

  N.B. this window is only applied when pulling content ids from the 'syncing from' db."
  (* 60 10))

(defn sync-lookback-stop []
  (let [now (/ (System/currentTimeMillis) 1000)
        lookback-stop (- now max-allowed-time-for-boost)]
    lookback-stop))

(defn sync-mising-boosts! [dest-conn src-conn since]
  (let [dest-ids (unique-content-ids dest-conn since)
        src-ids (unique-content-ids src-conn since (sync-lookback-stop))
        uniq-to-src (clojure.set/difference src-ids dest-ids)]
    (println "Destination IDs: " (count dest-ids))
    (println "Source IDs: " (count src-ids))
    (println "Unique to Source: " (count uniq-to-src))
    (load-missing-boosts! dest-conn src-conn uniq-to-src)))

(defn two-days-ago []
  (let [now (/ (System/currentTimeMillis) 1000)
        two-days-ago (- now (* 60 60 24 2))]
    two-days-ago))

(defn get-all-boosts [^IBoostScrape scraper token & get-boost-args]
  (iteration #(upstream/get-boosts scraper %)
             {:somef (comp seq :data)
              :kf :next
              :vf :data
              :initk (into {:token token}
                           (apply hash-map get-boost-args))}))

(defn get-all-boosts-until-epoch [^IBoostScrape scraper token epoch & get-boost-args]
  (->> {:somef (comp seq :data)
        :vf :data
        :kf :next
        :initk (into {:token token}
                     (apply hash-map get-boost-args))}
       (iteration #(upstream/get-boosts scraper %))
       (take-while
        (fn [boost-batch]
          (let [filtered-batch (filter :creation_date boost-batch)
                creation_dates (map (comp #(if (int? %) % (Integer/parseInt %))
                                          :creation_date)
                                    filtered-batch)
                first_creation_date (apply max creation_dates)]
            ;; TODO: tests; <= or <?
            (<= epoch first_creation_date))))))

#_(def AUTOSCRAPE_START (->epoch #inst "2023-12-31T23:59Z"))
(def AUTOSCRAPE_START (->epoch #inst "2024-08-31T23:59Z"))

(defn scrape-alby-boosts-until-epoch [conn token epoch wait]
  (->> (get-all-boosts-until-epoch (alby/->Scraper) token epoch :wait wait)
       (db/add-boosts conn "alby")))

(defn autoscrape-alby [conn token wait]
  (let [[most-recent-timestamp]
        (if-not (empty? (d/entity (d/db conn) 1))
          (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
               (d/db conn))
          [AUTOSCRAPE_START])]
    (println "alby most-recent-timestamp: " most-recent-timestamp)
    (->> (get-all-boosts-until-epoch (alby/->Scraper) token most-recent-timestamp :wait wait)
         (db/add-boosts conn "alby"))))

(defn scrape-lnd-boosts-until-epoch [conn macaroon epoch wait]
  (->> (get-all-boosts-until-epoch (lnd/->Scraper) macaroon epoch :wait wait)
       (db/add-boosts conn "JB")))

(defn autoscrape-lnd [conn token wait]
  (let [[most-recent-timestamp]
        (if-not (empty? (d/entity (d/db conn) 1))
          (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
               (d/db conn))
          [AUTOSCRAPE_START])]
    (println "jbnode most-recent-timestamp: " most-recent-timestamp)
    (->> (get-all-boosts-until-epoch (lnd/->Scraper) token most-recent-timestamp :wait wait)
         (db/add-boosts conn "JB"))))

(defn scrape-nodecan-boosts-until-epoch [conn macaroon epoch wait]
  (->> (get-all-boosts-until-epoch (lnd/->Scraper)
                                   macaroon
                                   epoch
                                   :wait wait
                                   :url "https://100.120.212.39:8080/v1/invoices")

       (db/add-boosts conn "nodecan")))

(defn autoscrape-nodecan [conn token wait]
  (let [[most-recent-timestamp]
        (if-not (empty? (d/entity (d/db conn) 1))
          (d/q '[:find [(max ?cd)] :where [?e :invoice/creation_date ?cd]]
               (d/db conn))
          [AUTOSCRAPE_START])]
    (println "nodecan most-recent-timestamp: " most-recent-timestamp)
    (->> (get-all-boosts-until-epoch (lnd/->Scraper)
                                     token
                                     most-recent-timestamp
                                     :wait wait
                                     :url "https://100.120.212.39:8080/v1/invoices")
         (db/add-boosts conn "nodecan"))))

(defn q
  "A version of d/q with sorted maps as output."
  [& args]
  (into []
        (comp cat (map #(into (sorted-map) %)))
        (apply d/q args)))

(alter-var-root #'*out* (constantly *out*))

(def scrape-sleep-interval 60000)

(defn -main [& _]
  ;; TODO: proper startup validation
  (let [env (System/getenv)
        {:strs [JBNODE_MACAROON_PATH NODECAN_MACAROON_PATH ALBY_TOKEN_PATH ALBY_DBI JBNODE_DBI NODECAN_DBI]} env]
    (when (not (and JBNODE_MACAROON_PATH NODECAN_MACAROON_PATH ALBY_TOKEN_PATH ALBY_DBI JBNODE_DBI NODECAN_DBI))
      (println "Missing required credentials!" {:jbnode JBNODE_MACAROON_PATH :nodecan NODECAN_MACAROON_PATH :alby ALBY_TOKEN_PATH})
      (System/exit 1))
    (let [lnd-conn (d/get-conn JBNODE_DBI db/schema)
          alby-conn (d/get-conn ALBY_DBI db/schema)
          nodecan-conn (d/get-conn NODECAN_DBI db/schema)
          lnd-macaroon (lnd/read-macaroon JBNODE_MACAROON_PATH)
          nodecan-macaroon (lnd/read-macaroon  NODECAN_MACAROON_PATH)
          alby-token (alby/load-key ALBY_TOKEN_PATH)
          runtime (Runtime/getRuntime)
          webserver (web/serve nodecan-conn)
          shutdown-hook (Thread. (fn []
                                   (println "stopping scraper")
                                   (.close webserver)
                                   (reset! upstream/scrape false)
                                   (d/close lnd-conn)
                                   (d/close nodecan-conn)
                                   (d/close alby-conn)))
          _ (.addShutdownHook runtime shutdown-hook)]
      (while true
        (try
          (println "Starting Scrape Cycle...")
          ;; Run in parallel and wait for all to complete.
          (println "Scraping in parallel...")
          (run! deref [(utils/apply-virtual autoscrape-alby alby-conn alby-token 3000)
                       (utils/apply-virtual autoscrape-lnd lnd-conn lnd-macaroon 500)
                       (utils/apply-virtual autoscrape-nodecan nodecan-conn nodecan-macaroon 500)])
          (println "Scrape phase complete.")
          (println)
          (println "Syncing missing boosts")
          (println "Syncing JB")
          (sync-mising-boosts! nodecan-conn lnd-conn (- AUTOSCRAPE_START 3600) #_(two-days-ago))
          (println "Syncing alby")
          (sync-mising-boosts! nodecan-conn alby-conn (- AUTOSCRAPE_START 3600) #_(two-days-ago))
          (println "Finished syncing missing boosts")
          (println)
          (println "Scrape Cycle finished, sleeping.")
          (Thread/sleep scrape-sleep-interval)
          (catch Exception e (println "ERROR WHILE SCRAPING! " (bean e))))))))

(comment
  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)

  (def alby-conn (d/get-conn (System/getenv "ALBY_DBI") db/schema))
  (def lnd-conn (d/get-conn (System/getenv "JBNODE_DBI") db/schema))
  (def nodecan-conn (d/get-conn (System/getenv "NODECAN_DBI") db/schema))

  (count (d/datoms (d/db alby-conn) :eav))
  (d/datoms (d/db alby-conn) :eav 1)

  (count (d/datoms (d/db lnd-conn) :eav))
  (d/datoms (d/db lnd-conn) :eav 1)

  ;; ALBY
  (reset! alby/scrape-can-run false)
  (reset! alby/scrape-can-run true)

  ;; LND
  (reset! lnd/scrape-can-run false)
  (reset! lnd/scrape-can-run true)

  ;; scrape-until-epoch
  (utils/apply-virtual
   (fn []
     (scrape-alby-boosts-until-epoch
      alby-conn
      alby/alby-token
      (->epoch #inst "2024-09-14T00:00")
      2000)
     (println "alby sync complete")))

  (utils/apply-virtual
   (fn []
     (scrape-lnd-boosts-until-epoch
      lnd-conn
      lnd/macaroon
      (->epoch #inst "2024-09-14T00:00")
      50)
     (println "lnd sync complete")))

  (utils/apply-virtual
   (fn []
     (scrape-nodecan-boosts-until-epoch
      nodecan-conn
      lnd/nodecan-macaroon
      (->epoch #inst "2024-09-18T00:00")
      500)
     (println "nodecan sync complete")))

  ;; autoscrape
  (utils/apply-virtual autoscrape-alby alby-conn alby/alby-token 2000)
  (utils/apply-virtual autoscrape-lnd lnd-conn lnd/macaroon 500)
  (utils/apply-virtual autoscrape-nodecan nodecan-conn lnd/nodecan-macaroon 500)

  (->> #_1722901411 (->epoch #inst "2024-07-01T07:00") #_1722388429
       (boost-scraper.reports/get-boost-summary-for-report' lnd-conn #"(?i).*")
       (into [] cat)
       (sort-by :invoice/creation_date)
       (spit "/tmp/after_sync"))

  (->> #_(->epoch #inst "2024-08-05T06:00")   1724611594
       (boost-scraper.reports/boost-report lnd-conn #"(?i).*unplugged.*")
       #_(into [] cat)
       #_(sort-by :invoice/creation_date)
       (spit "/tmp/lnd.md"))

  (->> #_1722901411 (->epoch #inst "2024-08-01T07:00")
       (boost-scraper.reports/boost-report nodecan-conn #"(?i).*")
       (spit "/tmp/nodecan"))

  (d/q '[:find (d/pull ?e [:*])
         :where [?e :invoice/created_at 1722553687]]
       (d/db alby-conn))

  ;; diffing between upstreams
  (sync-mising-boosts! nodecan-conn alby-conn (->epoch #inst "2024-07-01T07:00"))
  (sync-mising-boosts! nodecan-conn lnd-conn (->epoch #inst "2024-07-01T07:00"))

  (sync-mising-boosts! nodecan-conn alby-conn 1)
  (sync-mising-boosts! nodecan-conn lnd-conn 1)

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

  (d/q '[:find (count ?e) :where [?e]] (d/db nodecan-conn))

  (d/close alby-conn)
  (d/close lnd-conn)
  (d/close nodecan-conn))
