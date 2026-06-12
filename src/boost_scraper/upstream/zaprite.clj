(ns boost-scraper.upstream.zaprite
  (:require [babashka.http-client :as http]
            [boost-scraper.db :as db]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]
            [clojure.string :as string]
            [datalevin.core :as d])
  (:import [java.time Instant]
           [java.util Date]))

(def zaprite-api-base "https://api.zaprite.com")

(defn fetch-orders
  "Fetch a page of paid Zaprite orders since cursor.
   Returns {:items [...] :meta {:page N :pagesCount N}} or nil."
  [api-key cursor page]
  (let [query (merge {"status[]" ["PAID" "COMPLETE" "OVERPAID"]
                      "sortBy" "paidAt"
                      "sortOrder" "asc"
                      "page" (str page)}
                     (when cursor {"paidAtMin" cursor}))
        url (str zaprite-api-base "/v1/orders")]
    (utils/with-retries
      (fn []
        (-> (http/get url {:headers {"Authorization" (str "Bearer " api-key)}
                           :query-params query})
            (utils/check-http-status "Zaprite")
            :body
            (json/parse-string true))))))

(defn infer-payment-rail
  "Map Zaprite transaction method to our payment rail."
  [order]
  (if-let [method (get-in order [:transactions 0 :method])]
    (case method
      "LIGHTNING" "lightning"
      "BITCOIN" "onchain"
      "CARD" "card"
      "ACH" "ach"
      "APPLEPAY" "card"
      "GOOGLEPAY" "card"
      (string/lower-case method))
    "unknown"))

(defn process-order
  "Convert a Zaprite order map into a boost entity for Datalevin."
  [order]
  (let [meta (get order :metadata {})
        currency (get order :currency)
        total-amount (get order :totalAmount)
        paid-at (get order :paidAt)
        paid-instant (when paid-at (Instant/parse paid-at))
        paid-epoch (when paid-instant (.getEpochSecond paid-instant))
        paid-date (when paid-instant (Date/from paid-instant))
        username (get meta :username)
        podcast-name (get meta :podcastName)
        episode-title (get meta :episodeTitle)
        message (get meta :message)]
    (cond-> {:boostagram/zaprite_order_id (get order :id)
             :invoice/identifier (str "zaprite-" (get order :id))
             :boostagram/podcast podcast-name
             :boostagram/episode episode-title
             :boostagram/sender_name username
             :boostagram/sender_name_normalized (db/normalize-name (or username ""))
             :boostagram/message (or message "")
             :boostagram/action "boost"
             :boostagram/payment_rail (infer-payment-rail order)
             :boostagram/app_name "Zaprite"
             :scraper/source "zaprite"
             :invoice/creation_date paid-epoch
             :invoice/created_at paid-date
             :boostagram/received_at paid-date
             :boostagram/podcast_slug (get meta :slug (get meta :podcastSlug))
             :boostagram/episode_guid (get meta :episodeGuid)
             :boostagram/memberful_member_id (some-> (get meta :memberId) str)}
      (= "BTC" currency)
      (assoc :boostagram/type :sat
             :boostagram/value_sat_total total-amount)

      (not= "BTC" currency)
      (assoc :boostagram/type :fiat
             :boostagram/value_sat_total 0
             :boostagram/amount_fiat_cents total-amount
             :boostagram/amount_fiat_currency (or currency "USD")))))

(defn sync-zaprite-boosts!
  "Fetch new paid orders from Zaprite and upsert into nodecan.
   Cursor is stored as sync-cursor entity keyed 'zaprite'.

   On first run (no cursor), only fetches the most recent 10 pages (~250 orders)
   to avoid scanning the entire history."
  [nodecan-conn api-key]
  (let [[cursor] (d/q '[:find [?value]
                        :where [?e :sync-cursor/key "zaprite"]
                        [?e :sync-cursor/value ?value]]
                      (d/db nodecan-conn))
        _ (println "Zaprite sync starting, cursor:" cursor)
        total (atom 0)
        new-cursor (atom cursor)
        max-pages (if cursor 100 10)]
    (loop [page 1]
      (when (<= page max-pages)
        (when-let [resp (fetch-orders api-key cursor page)]
          (let [items (get resp :items [])
                meta (get resp :meta {})
                pages (get meta :pagesCount 1)]
            (doseq [order items]
              (let [meta (get order :metadata {})]
                ;; Only process Zaprite orders that came through the podcast web-boost
                ;; widget. Direct Zaprite invoice payments (not web-boost) are skipped.
                (when (= "web-boost" (get meta :app))
                  (let [entity (db/remove-empty-vals (process-order order))]
                    (d/transact! nodecan-conn [entity])
                    (swap! total inc)
                    (when-let [paid (get order :paidAt)]
                      (reset! new-cursor (str (-> (Instant/parse paid) (.plusMillis 1)))))))))
            (when (< page pages)
              (recur (inc page)))))))
    (when (and @new-cursor (not= @new-cursor cursor))
      (d/transact! nodecan-conn [{:sync-cursor/key "zaprite"
                                  :sync-cursor/value @new-cursor}])
      (println "Zaprite cursor updated to:" @new-cursor))
    (println (str "Zaprite sync complete. Processed " @total " new boosts."))
    @total))
