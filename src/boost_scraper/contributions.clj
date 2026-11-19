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
  "One pull pass instead of per-attribute get-else: measured ~10x faster on the
   stream-heavy windows that dominate the DB."
  '[:find (pull ?e [:db/id
                    :boostagram/action
                    :boostagram/podcast
                    :boostagram/episode
                    :boostagram/sender_name_normalized
                    :boostagram/value_sat_total
                    :boostagram/type
                    :boostagram/amount_fiat_cents
                    :boostagram/zaprite_order_id
                    :invoice/identifier
                    :invoice/creation_date
                    :scraper/source
                    :boostagram/app_name])
    :in $ ?since ?until ?regex ?actions
    :where
    [?e :boostagram/action ?action]
    [(contains? ?actions ?action)]
    [?e :invoice/creation_date ?cd]
    [(>= ?cd ?since)]
    [(<= ?cd ?until)]
    [?e :boostagram/podcast ?podcast]
    [(re-matches ?regex ?podcast) _]
    [?e :boostagram/sender_name_normalized ?sender]])

(defn- payment-id
  "Canonical dedupe key: the Zaprite order when present, else the invoice
   identifier, else the entity (no dedupe possible)."
  [oid iid e]
  (cond (not (str/blank? (str oid))) (str "zaprite:" oid)
        (not (str/blank? (str iid))) (str "invoice:" iid)
        :else (str "entity:" e)))

(def ^:private aggregate-query
  "Server-side rollup for a high-volume action (streams). Returns per-handle
   count + sats without materializing 38k entity maps."
  '[:find ?sender (count ?e) (sum ?sats)
    :in $ ?since ?until ?regex ?action
    :where
    [?e :boostagram/action ?action]
    [?e :invoice/creation_date ?cd]
    [(>= ?cd ?since)]
    [(<= ?cd ?until)]
    [?e :boostagram/podcast ?podcast]
    [(re-matches ?regex ?podcast) _]
    [?e :boostagram/sender_name_normalized ?sender]
    [?e :boostagram/value_sat_total ?sats]])

(defn aggregate-by-handle
  "Per-handle rollup for one action, computed in the query engine.

   Exists so lenses over high-volume actions (streams: ~38k rows/window) do not
   pay to materialize every contribution. Uses the same field and filters as
   `contributions`, so the money rule is unchanged."
  [conn {:keys [from to show-regex action exclude-self? include-auto?]
         :or   {action :stream exclude-self? true include-auto? true}}]
  (let [regex (cond (instance? java.util.regex.Pattern show-regex) show-regex
                    (string? show-regex) (re-pattern show-regex)
                    :else (re-pattern ".*"))]
    (->> (d/q aggregate-query (d/db conn) (long from) (long to) regex (name action))
         (map (fn [[handle cnt sats]]
                {:handle handle :count (long cnt) :sats (long (or sats 0))}))
         (remove (fn [{:keys [handle]}]
                   (and (not include-auto?) (auto-generated-handle? handle))))
         (remove (fn [{:keys [handle]}]
                   (and exclude-self?
                        (contains? self-boosters (some-> handle str/lower-case)))))
         vec)))

(defn- ->contribution
  "Normalize one pulled entity map into a contribution."
  [m]
  (let [e (:db/id m)
        action (:boostagram/action m)
        cd (:invoice/creation_date m)
        sats (long (or (:boostagram/value_sat_total m) 0))
        cents (long (or (:boostagram/amount_fiat_cents m) 0))]
    {:contribution/entity e
     :contribution/payment-id (payment-id (:boostagram/zaprite_order_id m)
                                          (:invoice/identifier m)
                                          e)
     :contribution/action (keyword action)
     :contribution/type (or (:boostagram/type m) :none)
     :contribution/handle (:boostagram/sender_name_normalized m)
     :contribution/sats sats
     :contribution/fiat-cents (when (pos? cents) cents)
     :contribution/occurred-at (when cd (long cd))
     :contribution/podcast (:boostagram/podcast m)
     :contribution/episode (:boostagram/episode m)
     :contribution/source (:scraper/source m)
     :contribution/app (:boostagram/app_name m)}))

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
         (map (fn [[m]] (->contribution m)))   ; pull-in-find yields [entity-map]
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
