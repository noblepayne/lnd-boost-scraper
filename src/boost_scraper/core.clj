(ns boost-scraper.core
  {:clj-kondo/config '{:lint-as {datalevin.core/with-transaction clojure.core/let}
                       :linters {:unresolved-symbol {:exclude [(datalevin.core/with-conn)]}}}}
  (:gen-class)
  (:require [boost-scraper.db :as db]
            [boost-scraper.reports :as reports]
            [boost-scraper.upstreams.lnd :as lnd]
            [boost-scraper.upstreams.alby :as alby]
            [boost-scraper.web :as web]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.instant]
            [datalevin.core :as d]))

(defn ->epoch [inst]
  (-> inst .toInstant .getEpochSecond))

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
                [(clojure.core/< ?since ?cd ?stop)]]
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
         [(clojure.core/<= ?start ?cd ?stop)]]
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
    (load-missing-boosts! dest-conn src-conn uniq-to-src)))

(defn two-days-ago []
  (let [now (/ (System/currentTimeMillis) 1000)
        two-days-ago (- now (* 60 60 24 2))]
    two-days-ago))

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

(defn q
  "A version of d/q with sorted maps as output."
  [& args]
  (into []
        (comp cat (map #(into (sorted-map) %)))
        (apply d/q args)))

(alter-var-root #'*out* (constantly *out*))

(def scrape-sleep-interval 60000)

(defn -main [& args]
  ;; TODO: proper startup validation
  (let [env (System/getenv)
        {:keys [JBNODE_MACAROON_PATH NODECAN_MACAROON_PATH ALBY_ACCESS_CODE]} (zipmap (map keyword (keys env)) (vals env))]
    (when (not (and JBNODE_MACAROON_PATH NODECAN_MACAROON_PATH ALBY_ACCESS_CODE))
      (println "Missing required credentials!" {:jbnode JBNODE_MACAROON_PATH :nodecan NODECAN_MACAROON_PATH :alby ALBY_ACCESS_CODE})
      (System/exit 1)))
  (let [lnd-conn (d/get-conn db/lnd-dbi db/schema)
        alby-conn (d/get-conn db/alby-dbi db/schema)
        nodecan-conn (d/get-conn db/nodecan-dbi db/schema)
        lnd-macaroon (lnd/read-macaroon (System/getenv "JBNODE_MACAROON_PATH"))
        nodecan-macaroon (lnd/read-macaroon (System/getenv "NODECAN_MACAROON_PATH"))
        alby-token (System/getenv "ALBY_ACCESS_CODE")
        runtime (Runtime/getRuntime)
        webserver (web/-main lnd-conn)
        shutdown-hook (Thread. (fn []
                                 (println "stopping scraper")
                                 (.close webserver)
                                 (reset! lnd/scrape-can-run false)
                                 (reset! alby/scrape-can-run false)
                                 (d/close lnd-conn)
                                 (d/close nodecan-conn)
                                 (d/close alby-conn)))
        _ (.addShutdownHook runtime shutdown-hook)]
    (while true
      (try
        (println "Starting Scrape Cycle...")
        (println "Scraping Alby")
        (autoscrape-alby alby-conn alby-token 3000)
        (println)
        (println "Scraping NodeCan LND")
        (autoscrape-nodecan nodecan-conn nodecan-macaroon 500)
        (println)
        (println "Scraping JB LND")
        (autoscrape-lnd lnd-conn lnd-macaroon 500)
        (println)
        (println "Syncing missing boosts")
        (sync-mising-boosts! lnd-conn nodecan-conn (two-days-ago))
        (sync-mising-boosts! lnd-conn alby-conn (two-days-ago))
        (println)
        (println "Scrape Cycle finished, sleeping.")
        (Thread/sleep scrape-sleep-interval)
        (catch Exception e (println "ERROR WHILE SCRAPING! " (bean e)))))))

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
                   (->epoch #inst "2024-08-01T07:00")
                   2000)))
               (fn [_] (println "alby sync complete")))

  (async/take! (async/thread-call
                (fn []
                  (scrape-lnd-boosts-until-epoch
                   lnd-conn
                   lnd/macaroon
                   (->epoch #inst "2024-08-01T07:00")
                   50)))
               (fn [_] (println "lnd sync complete")))

  (async/take! (async/thread-call
                (fn []
                  (scrape-nodecan-boosts-until-epoch
                   nodecan-conn
                   lnd/nodecan-macaroon
                   (->epoch #inst "2024-08-01T07:00")
                   500)))
               (fn [_] (println "nodecan sync complete")))

  (autoscrape-alby alby-conn alby/test-token 2000)
  (autoscrape-lnd lnd-conn lnd/macaroon 500)
  (autoscrape-nodecan nodecan-conn lnd/nodecan-macaroon 500)

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

  (def alby-ids
    (unique-content-ids alby-conn
                        (->epoch #inst "2024-07-01T07:00")))

  (def lnd-ids
    (unique-content-ids lnd-conn
                        (->epoch #inst "2024-07-01T07:00")))

  (def nodecan-ids
    (unique-content-ids nodecan-conn
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
                            :boostagram/ts
                            :invoice/created_at
                            :invoice/creation_date
                            :invoice/identifier
                            :boostagram/message]) ...] :in $ ids :where [?e :boostagram/content_id ?cd]
         [(in ?cd ids)]] (d/db alby-conn) *1)

  (clojure.set/difference nodecan-ids lnd-ids)
  (d/q '[:find [(d/pull ?e [:boostagram/sender_name_normalized
                            :boostagram/value_sat_total
                            :boostagram/podcast
                            :boostagram/episode
                            :boostagram/app_name
                            :boostagram/ts
                            :invoice/created_at
                            :invoice/creation_date
                            :invoice/identifier
                            :boostagram/message]) ...] :in $ ids :where [?e :boostagram/content_id ?cd]
         [(in ?cd ids)]] (d/db nodecan-conn) *1)

  (load-missing-boosts! lnd-conn alby-conn *1)
  (load-missing-boosts! lnd-conn nodecan-conn *1)

  (sync-mising-boosts! lnd-conn alby-conn (->epoch #inst "2024-07-01T07:00"))
  (sync-mising-boosts! lnd-conn nodecan-conn (->epoch #inst "2024-07-01T07:00"))

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
         [(clojure.core/< ?last ?cd)]]
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
  (d/close nodecan-conn))
