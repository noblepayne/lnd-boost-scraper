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
            [cognitect.aws.credentials :as credentials]))

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
          (let [recent (campaigns/recent conn (campaigns/find-campaign campaign) (:rate progress))
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