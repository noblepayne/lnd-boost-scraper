(ns boost-scraper.campaigns
  "Campaign progress — the meter lens over `contributions`.

   DIVISION OF LABOUR: `contributions` owns what counts as a value movement and
   how it is deduped to a payment identity. This namespace adds only SELECTION
   (which contributions belong to one campaign) and PRESENTATION (the published
   contract). It never re-derives the accounting rules: sats and cents stay in
   separate integer fields until the single conversion point at the bottom.

   THE TWO REFUSALS — they are the load-bearing behaviour, not error handling:
   - no BTC/USD rate (neither fresh nor stale) => {:status :no-rate}. A meter
     with a made-up rate is worse than no meter, so we refuse to compute USD.
   - unknown slug => {:status :not-found}.

   The rate is an ARGUMENT. The 3-arity is the edge: it calls the price feed and
   stamps the emit time. Everything below it is deterministic under test — no
   clock, no network, no atoms."

  (:require [boost-scraper.contributions :as contributions]
            [boost-scraper.price-feed :as price-feed]
            [boost-scraper.shows :as shows]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]))

;; ---------------------------------------------------------------------------
;; Config — the EDN resource is the record of what a campaign is.
;; ---------------------------------------------------------------------------

(defn- read-campaigns []
  (let [resource (io/resource "campaigns.edn")]
    (when-not resource
      (throw (ex-info "campaigns.edn is not on the classpath (deps.edn :paths must include \"resources\")"
                      {})))
    (edn/read-string (slurp resource))))

(def ^:private campaigns*
  "Read once: the file is git-versioned config, not runtime state."
  (delay (read-campaigns)))

(defn campaigns
  "All configured campaigns, in file order."
  []
  (:campaigns @campaigns*))

(defn find-campaign
  "Resolve a campaign by slug (string or keyword) or pass a campaign map through
   unchanged. Returns nil when a slug does not resolve — callers must not guess."
  [slug-or-campaign]
  (cond
    (map? slug-or-campaign) slug-or-campaign
    (nil? slug-or-campaign) nil
    :else (let [slug (if (keyword? slug-or-campaign)
                       (name slug-or-campaign)
                       (str slug-or-campaign))]
            (first (filter #(= slug (:campaign/slug %)) (campaigns))))))

(defn- window
  "The flight window as longs. Inclusive of :opens, exclusive of :closes — the
   exclusion is applied at the query boundary in `counted-contributions`."
  [campaign]
  (let [{:keys [opens closes]} (:campaign/window campaign)]
    (when-not (and opens closes)
      (throw (ex-info "campaign requires :campaign/window with :opens and :closes"
                      {:slug (:campaign/slug campaign)})))
    {:opens (long opens) :closes (long closes)}))

;; ---------------------------------------------------------------------------
;; Selection — a boost belongs when it names the tag or lands on the finale.
;; ---------------------------------------------------------------------------

(def ^:private message-query
  "Messages are not part of the contribution pull (the accounting core has no
   reason to care what a boost says), so membership reads them separately and
   then joins on payment identity."
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

(defn- message-match?
  "Case-insensitive containment: the tag is the contract, and listeners type it
   by hand in whatever case their phone keyboard produces."
  [message tag]
  (and (some? message) (some? tag) (not (str/blank? (str tag)))
       (str/includes? (str/lower-case (str message)) (str/lower-case (str tag)))))

(defn- payment-ids
  "Every canonical id one entity could be deduped under. The prefixes are the
   contract of `contributions/payment-id`; aliasing all of them means a tagged
   duplicate still counts after dedupe keeps its untagged twin (and vice versa,
   which is the direction that quietly loses money)."
  [oid iid entity]
  (cond-> #{(str "entity:" entity)}
    (not (str/blank? (str oid))) (conj (str "zaprite:" oid))
    (not (str/blank? (str iid))) (conj (str "invoice:" iid))))

(defn- tagged-ids
  "Set of payment ids for every boost in the window whose message carries the tag."
  [conn from to tag]
  (if (str/blank? (str tag))
    #{}
    (->> (d/q message-query (d/db conn) (long from) (long to))
         (map (fn [[m]] m))
         (filter #(message-match? (:boostagram/message %) tag))
         (into #{} (mapcat #(payment-ids (:boostagram/zaprite_order_id %)
                                         (:invoice/identifier %)
                                         (:db/id %)))))))

(defn- episode-matches?
  "Episode labels in this DB read like `693: Two Cities, One Linux`, so an
   `also-episode` of `700` must match as a PREFIX, not by equality."
  [want episode]
  (let [want (str want)
        ep (str episode)]
    (or (= want ep)
        (str/starts-with? ep (str want ":"))
        (str/starts-with? ep (str want " ")))))

(defn- member?
  "Membership is two concrete facts, per docs/campaign-goal-spec.md §4.2."
  [tagged also-episode contribution]
  (or (contains? tagged (:contribution/payment-id contribution))
      (and (some? also-episode)
           (not (str/blank? (str also-episode)))
           (episode-matches? also-episode (:contribution/episode contribution)))))

(defn- shows-regex
  "Regex matching every show in :campaign/scope, so a campaign cannot count
   another show's episode. Unknown slugs are dropped; an empty scope means all."
  [campaign]
  (->> (get-in campaign [:campaign/scope :shows])
       (keep #(-> % shows/resolve-show :regex))
       (str/join "|")))

(defn- counted-contributions
  "Window-bounded, policy-filtered, membership-filtered contributions.
   Action and self filters are delegated to the accounting core; membership is
   ours. `:to` is `closes - 1` because the core's window is inclusive at both
   ends while a campaign window is exclusive at close (epoch-second granularity)."
  [conn campaign]
  (let [{:keys [opens closes]} (window campaign)
        to (dec closes)
        policy (:campaign/policy campaign)
        all (contributions/contributions
             conn (cond-> {:from opens
                           :to to
                           :actions (get policy :actions #{:boost})
                           :exclude-self? (get policy :exclude-self? true)}
                    (seq (get-in campaign [:campaign/scope :shows]))
                    (assoc :show-regex (shows-regex campaign))))
        tagged (tagged-ids conn opens to (:campaign/tag campaign))
        also-episode (:campaign/also-episode campaign)]
    (filterv #(member? tagged also-episode %) all)))

;; ---------------------------------------------------------------------------
;; Money — one conversion point, integer in, rounded out.
;; ---------------------------------------------------------------------------

(def ^:private sats-per-btc 1e8)

(defn- round-to [x places]
  (let [scale (Math/pow 10.0 (double places))]
    (/ (Math/round (* (double x) scale)) scale)))

(defn- usd-value
  "sats / 1e8 * btc_usd + cents / 100. The only place the two currencies meet."
  [sats cents btc-usd]
  (+ (/ (* (double (long (or sats 0))) (double btc-usd)) sats-per-btc)
     (/ (double (long (or cents 0))) 100.0)))

(defn- sats-value
  "Inverse: fiat cents at the same rate, so goal and raised are always converted
   by the identical number (a goal in sats that drifts from the goal in dollars
   would be the exact lie §4.1 of the spec forbids)."
  [cents btc-usd]
  (long (Math/round (/ (* (double (long cents)) 1e6) (double btc-usd)))))

(defn- value-of
  "What one counted contribution is worth under the campaign's policy. Presence
   and value are decided separately: a member-free boost is a person who showed
   up, not a dollar, unless the campaign says otherwise."
  [policy contribution]
  (let [{:keys [:contribution/type :contribution/sats :contribution/fiat-cents]} contribution
        fiat? (get policy :count-fiat? true)]
    {:sats (if (= :sat type) (long (or sats 0)) 0)
     :cents (cond
              (and (= :fiat type) fiat?) (long (or fiat-cents 0))
              (and (= :member-free type)
                   fiat?
                   (get policy :count-member-free? false)) (long (or fiat-cents 0))
              :else 0)}))

(defn- days-left
  "Ceiling, clamped at zero: a countdown that says 0 before the window closes
   reads as expired. Part of a day still counts as a day left."
  [closes now]
  (max 0 (long (Math/ceil (/ (double (- (long closes) (long now))) 86400.0)))))

(defn- meter
  "Pure assembly of the published contract (minus `recent`, which is W3's job)."
  [{:keys [campaign counted now rate]}]
  (let [{:keys [opens closes]} (window campaign)
        policy (:campaign/policy campaign)
        {people :people, anonymous :anonymous, n :count} (contributions/totals counted)
        value (reduce (fn [acc c]
                        (let [{:keys [sats cents]} (value-of policy c)]
                          (-> acc
                              (update :sats + sats)
                              (update :cents + cents)
                              (update :sats-count #(if (pos? sats) (inc %) %))
                              (update :fiat-count #(if (pos? cents) (inc %) %)))))
                      {:sats 0 :cents 0 :sats-count 0 :fiat-count 0}
                      counted)
        btc-usd (double (:btc_usd rate))
        goal-cents (long (get-in campaign [:campaign/target :fiat-cents]))
        goal-usd (double (/ goal-cents 100.0))
        ;; The two rails, valued separately and then summed. Rounding each part
        ;; first keeps the published invariant exact — split.sats.usd +
        ;; split.fiat.usd == raised.usd, to the cent — which is what makes the
        ;; split usable for reporting instead of merely decorative.
        sats-usd (round-to (/ (* (double (:sats value)) btc-usd) sats-per-btc) 2)
        fiat-usd (round-to (/ (double (:cents value)) 100.0) 2)
        raised-usd (round-to (+ sats-usd fiat-usd) 2)
        exact-usd (usd-value (:sats value) (:cents value) btc-usd)
        percent (if (pos? goal-usd)
                  (round-to (* 100.0 (/ exact-usd goal-usd)) 1)
                  0.0)
        cta (:campaign/cta campaign)]
    {:slug (:campaign/slug campaign)
     :title (:campaign/title campaign)
     ;; No editorial invention: a campaign without a subtitle publishes "" and
     ;; the card renders one line less.
     :subtitle (or (:campaign/subtitle campaign) "")
     :tag (:campaign/tag campaign)
     ;; Rendered only when the goal is met; published always so the card never
     ;; has to be redeployed to change one line of copy.
     :stretch (or (:campaign/stretch campaign) "")
     :goal {:usd (round-to goal-usd 2)
            :sats (sats-value goal-cents btc-usd)}
     :raised {:usd raised-usd
              :sats (long (:sats value))
              :contributions (long (or n 0))
              :people (long (or people 0))
              :anonymous (long (or anonymous 0))}
     ;; How the money actually arrived, rail by rail. `raised.sats` is the sats
     ;; rail only — it is NOT the total expressed in sats, and comparing it to
     ;; `goal.sats` is meaningless once fiat is in the mix. This is the object
     ;; that makes the mix legible.
     :split {:sats {:usd sats-usd
                    :sats (long (:sats value))
                    :contributions (long (:sats-count value))}
             :fiat {:usd fiat-usd
                    :sats 0
                    :contributions (long (:fiat-count value))}}
     ;; max(0, ...) — a campaign can close over goal; remaining must not go negative.
     :remaining_usd (round-to (max 0.0 (- goal-usd raised-usd)) 2)
     ;; Deliberately NOT clamped: clamping is a display concern (W5), and 118%
     ;; is information the publisher should not throw away.
     :percent percent
     :days_left (days-left closes now)
     :opens_at opens
     :closes_at closes
     :generated_at (long now)
     :rate {:btc_usd (double (:btc_usd rate))
            :at (:at rate)
            :source (:source rate)}
     :cta (select-keys cta [:label :url])}))

;; ---------------------------------------------------------------------------
;; Edge — the only place that knows about the network.
;; ---------------------------------------------------------------------------

(defn- fetch-rate
  "Snapshot of the cached feed, stamped with the emit moment.

   `:at` is the emit time, not the upstream fetch time: the contract's promise is
   'converted with the rate in force when this artifact was generated', and the
   price feed deliberately does not expose its cache timestamp. A stale rate is
   still a rate in force; it just makes `:at` a few minutes old in spirit."
  [now]
  (when-let [{:keys [rate source]} (price-feed/get-btc-usd)]
    {:btc_usd (double rate)
     :at (long now)
     :source source}))

(defn- normalize-rate
  "Accept either the contract shape {:btc_usd .. :at .. :source ..} or the raw
   feed shape {:rate .. :source ..}. A rate without a positive number is no rate."
  [rate now]
  (let [info (cond
               (nil? rate) nil
               (contains? rate :btc_usd) rate
               (contains? rate :rate) {:btc_usd (double (:rate rate))
                                       :at (or (:at rate) (long now))
                                       :source (:source rate)}
               :else nil)]
    (when (and info (number? (:btc_usd info)) (pos? (double (:btc_usd info))))
      info)))

;; ---------------------------------------------------------------------------
;; Public
;; ---------------------------------------------------------------------------

(defn progress
  "Progress toward one campaign, as the published contract minus `recent`.

   (progress conn campaign now)        ; edge: fetches the BTC/USD rate
   (progress conn campaign now rate)   ; deterministic: rate supplied

   `campaign` is a campaign map or a slug. `now` is epoch seconds. `rate` is
   either the contract rate map or the raw `price_feed/get-btc-usd` shape.

   Returns {:status :not-found} for an unknown slug and {:status :no-rate} when
   no rate is available — in both cases the caller must NOT publish."
  ([conn campaign now]
   (progress conn campaign now (fetch-rate now)))
  ([conn campaign now rate]
   (if-let [campaign (find-campaign campaign)]
     (if-let [rate (normalize-rate rate now)]
       (meter {:campaign campaign
               :now (long now)
               :rate rate
               :counted (counted-contributions conn campaign)})
       {:status :no-rate})
     {:status :not-found})))

(defn recent
  "Newest-first list of counted contributions for a campaign, capped at `n`.
   THIS IS THE ONE HOME for this projection — the HTTP route and the publisher
   both call it, so the published `recent` can never disagree with `raised`."
  ([conn campaign rate] (recent conn campaign rate 20))
  ([conn campaign rate n]
   (let [usd (fn [c] (usd-value (:contribution/sats c)
                                (:contribution/fiat-cents c)
                                (:btc_usd rate)))]
     (->> (counted-contributions conn campaign)
          (sort-by :contribution/occurred-at #(compare %2 %1))
          (take n)
          (mapv (fn [c]
                  {:sats (long (or (:contribution/sats c) 0))
                   :usd (round-to (usd c) 2)
                   :sender (or (:contribution/handle c) "anonymous")
                   :at (:contribution/occurred-at c)}))))))
