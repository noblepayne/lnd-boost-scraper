(ns boost-scraper.publish
  "Publish campaign meter artifacts to R2.

   Each campaign becomes a JSON object at `campaign/<slug>.json`. Publishing is
   best-effort and deliberately isolated from ingest: every put is bounded by a
   hard timeout, and any failure or timeout is logged and swallowed so the
   scrape loop never stalls on a storage problem.

   The two refusals are load-bearing:
   - `campaigns/progress` => {:status :no-rate}: skip the put entirely and keep
     whatever object R2 already holds. A meter with a made-up rate is worse than
     a stale one.
   - missing bucket/credentials config: do not publish and say so once, rather
     than failing obscurely on every cycle."
  (:require [boost-scraper.campaigns :as campaigns]
            [boost-scraper.contributions :as contributions]
            [boost-scraper.shows :as shows]
            [cheshire.core :as json]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [datalevin.core :as d]))

(def ^:private put-timeout-ms 30000)

(defonce ^:private config-warned? (atom false))

(defn- warn-once! [log-fn msg]
  (when (compare-and-set! config-warned? false true)
    (log-fn msg)))

(defn- reset-config-warning!
  "Test helper: allow the unset-config message to be emitted again."
  []
  (reset! config-warned? false))

(defn- slurp-env-path [k]
  (some-> (System/getenv k) not-empty slurp str/trim not-empty))

(defn- env-config
  "Resolve publish config from the environment, following the repo's existing R2
   conventions (credential paths + account id) with a campaign-specific bucket."
  []
  {:bucket     (not-empty (System/getenv "R2_CAMPAIGN_BUCKET"))
   :account-id (not-empty (System/getenv "R2_ACCOUNT_ID"))
   :access-key (slurp-env-path "R2_ACCESS_KEY_ID_PATH")
   :secret-key (slurp-env-path "R2_SECRET_ACCESS_KEY_PATH")})

(defn- config-ready?
  [{:keys [bucket account-id access-key secret-key]}]
  (and bucket account-id access-key secret-key))

(defn- make-s3-client
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

(defn- invoke-put!
  "Put one object. Runs the AWS call in a future and enforces a hard timeout so
   a stuck network call cannot stall the caller. Returns :ok, :timeout, or an
   anomaly/error map/result; never throws."
  [s3-client bucket key body]
  (let [op :PutObject
        request {:Bucket bucket
                 :Key key
                 :Body (.getBytes (str body) "UTF-8")
                 :ContentType "application/json"}
        result (deref (future
                        (try
                          (aws/invoke s3-client {:op op :request request})
                          (catch Throwable t {:publish/error t})))
                      put-timeout-ms
                      ::timeout)]
    (cond
      (= result ::timeout) ::timeout
      (:publish/error result) result
      (:cognitect.anomalies/category result) result
      :else :ok)))

(defn- make-put-fn
  [config]
  (let [client (make-s3-client config)
        bucket (:bucket config)]
    (fn [slug body]
      (invoke-put! client bucket (str "campaign/" slug ".json") body))))

;; ---------------------------------------------------------------------------
;; Recent — newest-first membership slice, capped at 20.
;; campaigns/progress owns the contract minus recent; this namespace derives the
;; recent list from the same window/membership rules without reaching into its
;; private helpers.
;; ---------------------------------------------------------------------------

(def ^:private message-query
  '[:find (pull ?e [:db/id
                    :boostagram/message
                    :boostagram/zaprite_order_id
                    :invoice/identifier])
    :in $ ?since ?until
    :where
    [?e :boostagram/message _]
    [?e :invoice/creation_date ?cd]
    [(>= ?cd ?since)]
    [(<= ?cd ?until)]])

(defn- message-match? [message tag]
  (and (some? message) (some? tag) (not (str/blank? (str tag)))
       (str/includes? (str/lower-case (str message)) (str/lower-case (str tag)))))

(defn- payment-ids [oid iid entity]
  (cond-> #{(str "entity:" entity)}
    (not (str/blank? (str oid))) (conj (str "zaprite:" oid))
    (not (str/blank? (str iid))) (conj (str "invoice:" iid))))

(defn- tagged-ids [conn from to tag]
  (if (str/blank? (str tag))
    #{}
    (->> (d/q message-query (d/db conn) (long from) (long to))
         (map (fn [[m]] m))
         (filter #(message-match? (:boostagram/message %) tag))
         (into #{} (mapcat #(payment-ids (:boostagram/zaprite_order_id %)
                                         (:invoice/identifier %)
                                         (:db/id %)))))))

(defn- episode-matches? [want episode]
  (let [want (str want)
        ep (str episode)]
    (or (= want ep)
        (str/starts-with? ep (str want ":"))
        (str/starts-with? ep (str want " ")))))

(defn- member? [tagged also-episode contribution]
  (or (contains? tagged (:contribution/payment-id contribution))
      (and (some? also-episode)
           (not (str/blank? (str also-episode)))
           (episode-matches? also-episode (:contribution/episode contribution)))))

(defn- shows-regex [campaign]
  (->> (get-in campaign [:campaign/scope :shows])
       (keep #(-> % shows/resolve-show :regex))
       (str/join "|")))

(defn- round-to [x places]
  (let [scale (Math/pow 10.0 (double places))]
    (/ (Math/round (* (double x) scale)) scale)))

(defn- usd-value [sats cents btc-usd]
  (+ (/ (* (double (long (or sats 0))) (double btc-usd)) 1e8)
     (/ (double (long (or cents 0))) 100.0)))

(defn- value-of [policy contribution]
  (let [{:keys [:contribution/type :contribution/sats :contribution/fiat-cents]} contribution
        fiat? (get policy :count-fiat? true)]
    {:sats (if (= :sat type) (long (or sats 0)) 0)
     :cents (cond
              (and (= :fiat type) fiat?) (long (or fiat-cents 0))
              (and (= :member-free type)
                   fiat?
                   (get policy :count-member-free? false)) (long (or fiat-cents 0))
              :else 0)}))

(defn- counted-contributions [conn campaign]
  (let [{:keys [opens closes]} (:campaign/window campaign)
        to (dec (long closes))
        policy (:campaign/policy campaign)
        all (contributions/contributions
             conn (cond-> {:from (long opens)
                           :to to
                           :actions (get policy :actions #{:boost})
                           :exclude-self? (get policy :exclude-self? true)}
                    (seq (get-in campaign [:campaign/scope :shows]))
                    (assoc :show-regex (shows-regex campaign))))
        tagged (tagged-ids conn (long opens) to (:campaign/tag campaign))
        also-episode (:campaign/also-episode campaign)]
    (filterv #(member? tagged also-episode %) all)))

(defn recent-entries
  "Newest-first recent contributions for a campaign, capped at 20. Uses the
   BTC/USD rate from the computed progress map so per-entry USD matches the
   contract's conversion. Message text is read per entity; sender is the
   contribution handle, or \"anonymous\" for the anonymity bucket."
  [conn campaign progress-map]
  (let [campaign (campaigns/find-campaign campaign)
        btc-usd (double (get-in progress-map [:rate :btc_usd]))
        policy (:campaign/policy campaign)
        counted (counted-contributions conn campaign)]
    (->> counted
         (sort-by :contribution/occurred-at #(compare %2 %1))
         (take 20)
         (mapv (fn [c]
                 (let [{:keys [sats cents]} (value-of policy c)
                       sender (let [h (:contribution/handle c)]
                                (if (= :anonymous (:contribution/handle-class c))
                                  "anonymous"
                                  (or h "anonymous")))
                       msg (:boostagram/message (d/pull (d/db conn)
                                                        [:boostagram/message]
                                                        (:contribution/entity c)))]
                   {:sats (long sats)
                    :usd (round-to (usd-value sats cents btc-usd) 2)
                    :sender sender
                    :at (:contribution/occurred-at c)
                    :message (or msg "")}))))))

(defn- publish-one!
  "Compute and publish a single campaign. Returns a result map describing what
   happened; never throws."
  [conn campaign now {:keys [put-fn log-fn]}]
  (let [slug (:campaign/slug campaign)
        log-fn (or log-fn println)]
    (try
      (let [progress (campaigns/progress conn campaign now)]
        (cond
          (= :no-rate (:status progress))
          (do (log-fn (str "Campaign publish skipped (no-rate), keeping last good object: " slug))
              {:slug slug :result :skipped-no-rate})

          (:status progress)
          (do (log-fn (str "Campaign publish skipped (" (name (:status progress)) "): " slug))
              {:slug slug :result (:status progress)})

          :else
          (let [recent (recent-entries conn campaign progress)
                payload (assoc progress :recent recent)
                body (json/generate-string payload)
                put-result (try (put-fn slug body) (catch Throwable t {:publish/error t}))]
            (cond
              (= :ok put-result)
              {:slug slug :result :published}

              (= ::timeout put-result)
              (do (log-fn (str "Campaign publish timed out after " put-timeout-ms "ms: " slug))
                  {:slug slug :result :timeout})

              (:publish/error put-result)
              (do (log-fn (str "Campaign publish failed for " slug ": "
                               (.getMessage ^Throwable (:publish/error put-result))))
                  {:slug slug :result :error})

              (:cognitect.anomalies/category put-result)
              (do (log-fn (str "Campaign publish failed for " slug ": "
                               (:cognitect.anomalies/category put-result)))
                  {:slug slug :result :error})

              :else
              (do (log-fn (str "Campaign publish returned unexpected result for " slug ": " (pr-str put-result)))
                  {:slug slug :result :error})))))
      (catch Throwable t
        (log-fn (str "Campaign publish error for " slug ": " (.getMessage t)))
        {:slug slug :result :error}))))

(defn publish-campaigns!
  "Publish every configured campaign's contract to R2.

   Arity:
   - (publish-campaigns! conn) — reads env config and uses wall-clock time.
   - (publish-campaigns! conn opts) — opts may supply :now, :config, :put-fn,
     :campaigns, :log-fn for testing/injection.

   If config is unset or incomplete, logs once and publishes nothing. Each
   campaign is handled independently: :no-rate skips that campaign's put (the
   previous object stays), and any failed/timed-out put is logged and swallowed.
   Never throws into the caller."
  ([conn] (publish-campaigns! conn {}))
  ([conn {:keys [now config put-fn campaigns log-fn]
          :or {now (quot (System/currentTimeMillis) 1000)}}]
   (let [log-fn (or log-fn println)
         config (or config (env-config))
         campaigns (or campaigns (campaigns/campaigns))]
     ;; Readiness gates publishing even when a put-fn is injected: an injected
     ;; put is a test seam for the write, not a licence to publish unconfigured.
     (if-not (and (seq campaigns) (config-ready? config))
       (do
         (when (and (seq campaigns) (not (config-ready? config)))
           (warn-once! log-fn "Campaign publishing disabled: set R2_CAMPAIGN_BUCKET and R2_ACCOUNT_ID/R2_ACCESS_KEY_ID_PATH/R2_SECRET_ACCESS_KEY_PATH"))
         [])
       (let [put-fn (or put-fn (make-put-fn config))]
         (mapv #(publish-one! conn % now {:put-fn put-fn :log-fn log-fn})
               campaigns))))))