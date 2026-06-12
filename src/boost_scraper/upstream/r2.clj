(ns boost-scraper.upstream.r2
  (:require [boost-scraper.db :as db]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [datalevin.core :as d])
  (:import [java.time Instant]
           [java.util Date]))

(def ^:private prefix "member-boosts/v1/r/")
(def ^:private ^:const invoke-timeout-ms 30000)

(defn- invoke-with-timeout
  "Invoke an AWS operation with a timeout. Throws on timeout."
  [s3-client op request]
  (let [result (deref (future (aws/invoke s3-client {:op op :request request}))
                      invoke-timeout-ms
                      ::timeout)]
    (when (= result ::timeout)
      (throw (ex-info (str "R2 " (name op) " timed out after " invoke-timeout-ms "ms")
                      {:op op :timeout invoke-timeout-ms})))
    result))

(defn- make-s3-client
  "Create an S3 client configured for Cloudflare R2."
  [{:keys [account-id access-key secret-key]}]
  (aws/client {:api :s3
               :region "us-east-1"
               :credentials-provider
               (credentials/basic-credentials-provider
                {:access-key-id access-key
                 :secret-access-key secret-key})
               :endpoint-override
               {:protocol :https
                :hostname (str account-id ".r2.cloudflarestorage.com")}}))

(defn- list-objects
  "List objects under prefix. Returns {:keys [...] :next-token ...}."
  [s3-client bucket start-after continuation-token]
  (let [req (cond-> {:Bucket bucket :Prefix prefix}
              start-after (assoc :StartAfter start-after)
              continuation-token (assoc :ContinuationToken continuation-token))
        resp (invoke-with-timeout s3-client :ListObjectsV2 req)]
    (when-let [err (:cognitect.anomalies/category resp)]
      (throw (ex-info (str "R2 list error: " err " " (:cognitect.aws.cli/message resp))
                      {:anomaly err :response resp})))
    {:keys (mapv :Key (:Contents resp))
     :next-token (:NextContinuationToken resp)
     :truncated (:IsTruncated resp)}))

(defn- get-object-body
  "Fetch an object's body as a string."
  [s3-client bucket key]
  (let [resp (invoke-with-timeout s3-client :GetObject {:Bucket bucket :Key key})]
    (when-let [err (:cognitect.anomalies/category resp)]
      (throw (ex-info (str "R2 get error: " err " " (:cognitect.aws.cli/message resp))
                      {:anomaly err :response resp})))
    (slurp (:Body resp))))

(defn process-record
  "Convert an R2 record into a boost entity for Datalevin.

  See web-boost-worker docs/shared-interface.md §1 for the 10-field schema."
  [record object-key]
  (let [created-at (get record :createdAt)
        created-instant (when created-at (Instant/parse created-at))
        created-date (when created-instant (Date/from created-instant))
        created-epoch (when created-instant (.getEpochSecond created-instant))
        username (get record :username)]
    {:boostagram/type :member-free
     :boostagram/r2_object_key object-key
     :invoice/identifier (str "member-r2-" (get record :boostId))
     :boostagram/podcast (get record :podcastName)
     :boostagram/episode (get record :episodeTitle)
     :boostagram/sender_name username
     :boostagram/sender_name_normalized (db/normalize-name (or username ""))
     :boostagram/message (or (get record :message) "")
     :boostagram/action "boost"
     :boostagram/value_sat_total 0
     :boostagram/payment_rail "member-free"
     :boostagram/memberful_member_id (some-> (get record :memberId) str)
     :boostagram/app_name "Memberful (Free)"
     :scraper/source "r2-member"
     :invoice/creation_date created-epoch
     :invoice/created_at created-date
     :boostagram/received_at created-date
     :boostagram/podcast_slug (get record :podcastSlug)
     :boostagram/episode_guid (get record :episodeGuid)
     :boostagram/amount_fiat_cents (get record :amountFiatCents)
     :boostagram/amount_fiat_currency (get record :amountFiatCurrency "USD")}))


(defn sync-r2-boosts!
  "Fetch new member boosts from the R2 bucket and upsert into nodecan.
   Lists objects under the flat prefix using start-after cursors.
   On first run (no cursor), lists everything in the bucket."
  [nodecan-conn {:keys [account-id access-key secret-key bucket]}]
  (let [s3 (make-s3-client {:account-id account-id
                            :access-key access-key
                            :secret-key secret-key})
        [cursor-str] (d/q '[:find [?value]
                              :where [?e :sync-cursor/key "r2-member"]
                              [?e :sync-cursor/value ?value]]
                            (d/db nodecan-conn))
        _ (println "R2 sync starting, cursor:" (or cursor-str "(full scan)")
                    "| bucket:" bucket
                    "| key-prefix:" (subs (or access-key "") 0 (min 8 (count (or access-key "")))))
        total (atom 0)
        last-key (atom nil)]
    (loop [start-after cursor-str continuation-token nil]
      (let [{:keys [keys next-token truncated]}
            (utils/with-retries
              (fn [] (list-objects s3 bucket start-after continuation-token)))]
        (doseq [key keys]
          (utils/with-retries
            (fn []
              (let [body (get-object-body s3 bucket key)]
                (when body
                  (let [record (json/parse-string body true)
                        entity (db/remove-empty-vals (process-record record key))]
                    (d/transact! nodecan-conn [entity])
                    (swap! total inc)
                    (reset! last-key key)))))))
        ;; Cursor advances only on successful object processing, not on
        ;; successful listing. If the last page returns keys but every
        ;; object fetch fails, last-key stays nil and the cursor does not
        ;; advance — the errored keys will be retried on next sync.
        (if truncated
          (recur nil next-token)
          (when (seq keys)
            (recur (last keys) nil)))))
    (when @last-key
      (d/transact! nodecan-conn [{:sync-cursor/key "r2-member"
                                   :sync-cursor/value @last-key}])
      (println "R2 cursor updated to:" @last-key))
    (println (str "R2 sync complete. Processed " @total " new member boosts."))
    @total))
