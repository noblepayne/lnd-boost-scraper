(ns boost-scraper.contributions
  "The accounting core — one definition of \"a counted value movement\".

   WHY THIS EXISTS: feed, reports, Boosties and (soon) campaign meters each
   recompute \"how much support came in\". When they disagree, the meter
   disagrees with the report on air. This namespace is the single answer;
   lenses add selection and presentation, never their own arithmetic.

   THE MODEL (see docs/campaign-goal-spec.md §2):
   - a contribution is one settled value movement, resolved to a payment
     identity and deduped to it;
   - sats and fiat cents are kept in separate integer fields and never summed
     together silently;
   - self-boosts and auto-generated handles are policy, not hardcode;
   - money is integer sats / integer cents. No floats here.

   VERIFIED AGAINST LIVE DATA (2026-11-19): a fiat web-boost order and its
   nodecan LND invoice do NOT double count — the invoice entity carries
   :boostagram/value_sat_total 0 and no sender/podcast, so it never becomes a
   contribution. The fiat order carries cents. That is why dedupe by payment
   identity is cheap and safe here, and why the known gap (fiat paid via
   lightning: real sats recorded only on the bare invoice) remains a deliberate
   policy question rather than an arithmetic accident."
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

(def self-boosters
  "Hosts whose own boosts are excluded from public lenses."
  #{"chrislas" "noblepayne"})

(defn- auto-generated-handle?
  "Handles that are machine-issued and must never be merged or treated as a
   named identity (`anonymous`, `user183420225038226`, `N/A`)."
  [handle]
  (let [h (some-> handle str str/lower-case (str/replace #"@.*$" "") str/trim)]
    (or (nil? h) (= "" h) (= "n/a" h) (= "anonymous" h)
        (boolean (re-matches #"user\d+.*" h)))))

;; ---------------------------------------------------------------------------
;; Query — one pull, then normalize in Clojure. Entity volume is small for
;; boosts (~6k); streams are window-bounded by the caller.
;; ---------------------------------------------------------------------------

(def ^:private base-query
  '[:find ?e ?action ?cd ?podcast ?episode ?sender ?sats ?type ?cents ?oid ?iid
    :in $ ?since ?until ?regex ?actions
    :where
    [?e :boostagram/action ?action]
    [(contains? ?actions ?action)]
    [?e :invoice/creation_date ?cd]
    [(>= ?cd ?since)]
    [(<= ?cd ?until)]
    [?e :boostagram/podcast ?podcast]
    [(re-matches ?regex ?podcast) _]
    [?e :boostagram/sender_name_normalized ?sender]
    [(get-else $ ?e :boostagram/episode "") ?episode]
    [(get-else $ ?e :boostagram/value_sat_total 0) ?sats]
    [(get-else $ ?e :boostagram/type :none) ?type]
    [(get-else $ ?e :boostagram/amount_fiat_cents 0) ?cents]
    [(get-else $ ?e :boostagram/zaprite_order_id "") ?oid]
    [(get-else $ ?e :invoice/identifier "") ?iid]])

(defn- payment-id
  "Canonical dedupe key: the Zaprite order when present, else the invoice
   identifier, else the entity (no dedupe possible)."
  [oid iid e]
  (cond (not (str/blank? (str oid))) (str "zaprite:" oid)
        (not (str/blank? (str iid))) (str "invoice:" iid)
        :else (str "entity:" e)))

(defn- ->contribution
  [[e action cd podcast episode sender sats type cents oid iid]]
  {:contribution/entity e
   :contribution/payment-id (payment-id oid iid e)
   :contribution/action (keyword action)
   :contribution/type type
   :contribution/handle sender
   :contribution/sats (long (or sats 0))
   :contribution/fiat-cents (let [c (long (or cents 0))] (when (pos? c) c))
   :contribution/occurred-at (long cd)
   :contribution/podcast podcast
   :contribution/episode episode})

(defn contributions
  "All counted value movements matching the request, deduped by payment id.

   opts :: {:from epoch         required window start (inclusive)
            :to   epoch         required window end (inclusive)
            :show-regex         Pattern or string; defaults to #\".*\"
            :actions            #{:boost} (default) — :stream available
            :exclude-self?      default true
            :include-auto?      default true (anonymous / user#### handles)}

   Returns contribution maps (see ->contribution). Deterministically ordered by
   occurred-at then payment id, so downstream sums and ties are reproducible."
  [conn {:keys [from to show-regex actions exclude-self? include-auto?]
         :or   {exclude-self? true include-auto? true actions #{:boost}}
         :as   opts}]
  (when-not (and from to)
    (throw (ex-info "contributions requires a bounded window (:from and :to)"
                    opts)))
  (let [regex (cond (instance? java.util.regex.Pattern show-regex) show-regex
                    (string? show-regex) (re-pattern show-regex)
                    :else (re-pattern ".*"))
        actions-str (into #{} (map name actions))
        rows (d/q base-query (d/db conn) (long from) (long to) regex actions-str)]
    (->> rows
         (map ->contribution)
         (remove (fn [{:keys [:contribution/type]}]
                   (= :none type)))            ; untyped = bare invoice shells
         (remove (fn [{:keys [:contribution/handle]}]
                   (and (not include-auto?) (auto-generated-handle? handle))))
         (remove (fn [{:keys [:contribution/handle]}]
                   (and exclude-self?
                        (contains? self-boosters (some-> handle str/lower-case)))))
         ;; Deterministic order BEFORE dedupe so "first sighting wins" is
         ;; reproducible across runs (query result order is not guaranteed).
         (sort-by (juxt :contribution/occurred-at
                        :contribution/payment-id
                        :contribution/entity))
         (reduce (fn [acc c]
                   (if (contains? (:seen acc) (:contribution/payment-id c))
                     acc
                     (-> acc
                         (update :seen conj (:contribution/payment-id c))
                         (update :out conj c))))
                 {:seen #{} :out []})
         :out
         vec)))

(defn totals
  "Roll up contributions. Sats and cents stay separate by design."
  [cs]
  {:count (count cs)
   :people (count (into #{} (map :contribution/handle) cs))
   :sats (reduce + 0 (map :contribution/sats cs))
   :fiat-cents (reduce + 0 (remove nil? (map :contribution/fiat-cents cs)))})

(defn by-handle
  "Group into [{:handle :count :sats :fiat-cents}] — the shape lenses rank."
  [cs]
  (->> cs
       (group-by :contribution/handle)
       (map (fn [[handle xs]]
              (assoc (totals xs) :handle handle)))
       (sort-by (juxt (comp - :sats) :handle))
       vec))
