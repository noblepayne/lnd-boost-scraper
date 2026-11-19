(ns boost-scraper.schema
  "Self-description for the database: what a caller can query, without reading
   source.

   WHY: the query API (`POST /api/v1/query`) lets a bot ask arbitrary Datalog,
   but until now it had to learn attribute names by reading `.clj` files. That
   is exactly the friction that kept the query API unused for months. This
   namespace makes the data model discoverable from the API itself.

   It reports BOTH kinds of attribute:
   - declared (in the Datalevin schema), with type/uniqueness/cardinality;
   - observed-but-undeclared, discovered by sampling real entities. This
     matters here: `:boostagram/sender_id` sat unnoticed on 353,204 rows
     because nothing declared it, and that is now our strongest identity signal."
  (:require [boost-scraper.db :as db]
            [clojure.string :as str]
            [datalevin.core :as d]))

(def ^:private cache-ttl-ms 3600000)        ; an hour: attributes change rarely
(defonce ^:private cache (atom {}))

(def ^:private sample-attrs
  "Attributes with modest cardinality used to discover undeclared attributes.
   Chosen to span ingest paths: core boosts, typed rows, web-boost orders,
   member boosts, and raw invoices."
  [:boostagram/action
   :boostagram/type
   :boostagram/message
   :boostagram/zaprite_order_id
   :boostagram/payment_rail
   :invoice/identifier])

(def ^:private sample-entities-per-attr 40)

(defn- infer-type
  "Best-effort type from a sample value (only needed for undeclared attrs —
   declared ones carry :db/valueType)."
  [v]
  (cond
    (nil? v) :unknown
    (string? v) :db.type/string
    (integer? v) :db.type/long
    (float? v) (if (instance? Double v) :db.type/double :db.type/float)
    (keyword? v) :db.type/keyword
    (boolean? v) :db.type/boolean
    (inst? v) :db.type/instant
    :else (keyword "db.type" (str/lower-case (str (type v))))))

(defn- sample-values
  "Up to n distinct values for an attribute, via the AVE index."
  [db attr n]
  (->> (d/datoms db :ave attr)
       (map d/datom-v)
       (remove nil?)
       (distinct)
       (take n)
       vec))

(defn- count-entities
  "Entity count for an attribute (index-backed, but still the expensive part of
   a full description — ~1s/attribute live). Only computed when asked for."
  [db attr]
  (try (d/count-datoms db nil attr nil)
       (catch Throwable _ nil)))

(defn- observed-attrs
  "Attributes actually present on sampled entities (declared or not)."
  [db]
  (->> sample-attrs
       (mapcat (fn [attr]
                 (->> (d/datoms db :ave attr)
                      (map d/datom-e)
                      (distinct)
                      (take sample-entities-per-attr))))
       (distinct)
       (mapcat (fn [e] (keys (into {} (d/entity db e)))))
       (distinct)
       vec))

(defn- type-name
  "Render a :db.type/* keyword as \"db.type/string\" (no leading colon) so JSON
   callers get a stable, readable value."
  [kw]
  (when kw (subs (str kw) 1)))

(defn- attr-entry
  "One attribute description. `declared?` means \"in the application schema\"
   (boost-scraper.db/schema) — NOT merely present in Datalevin's own schema,
   because Datalevin auto-registers attributes it has seen, which would make
   every attribute look declared."
  [db dal-schema app-attrs attr counts?]
  (let [props (get dal-schema attr)
        value-type (or (:db/valueType props)
                       (some-> (sample-values db attr 1) first infer-type))]
    (cond-> {:attribute (str attr)
             :declared (contains? app-attrs attr)
             :type (type-name value-type)
             :examples (sample-values db attr 3)}
      counts? (assoc :entities (count-entities db attr))
      props (assoc :cardinality (type-name (:db/cardinality props))
                   :unique (type-name (:db/unique props))))))

(defn describe
  "Full schema description:
   {:attributes [{:attribute :declared :type :examples ...}]
    :counts {:declared N :undeclared M :total T}
    :notes {...}}

   opts :: {:counts? false}  — entity counts cost ~1s per attribute live
                              (54 attributes ≈ 1m45s), so they are opt-in."
  ([conn] (describe conn {}))
  ([conn {:keys [counts?]}]
   (let [db (d/db conn)
         dal-schema (d/schema conn)
         app-attrs (set (keys db/schema))
         observed (observed-attrs db)
         all-attrs (into (set (keys dal-schema)) observed)
         entries (->> all-attrs
                      (map #(attr-entry db dal-schema app-attrs % counts?))
                      (sort-by (juxt #(if (:declared %) 0 1) :attribute)))]
     {:attributes (vec entries)
      :counts {:declared (count (filter :declared entries))
               :undeclared (count (remove :declared entries))
               :total (count entries)}
      :notes {:declared "In the application schema (boost-scraper.db/schema)."
              :undeclared "Observed in the data but absent from the application schema — queryable, and worth knowing about (e.g. :boostagram/sender_id, :boostagram/boost_link, :boostagram/name were each found this way)."
              :entity-counts (if counts? "included" "omitted; add ?counts=true (slow: ~1s/attribute)")
              :sampling (str "Undeclared attributes are discovered from up to "
                             sample-entities-per-attr " entities per sample attribute: "
                             (pr-str sample-attrs))}})))

(defn filtered
  "describe, optionally narrowed by a substring match on the attribute name."
  [conn {:keys [q counts?]}]
  (let [d (describe conn {:counts? counts?})]
    (if (seq q)
      (update d :attributes (fn [as] (filterv #(str/includes?
                                                (str/lower-case (:attribute %))
                                                (str/lower-case q))
                                              as)))
      d)))

(defn cached
  "describe with an hourly cache. Cache key includes the filter and the
   counts flag, so the expensive variant never poisons the fast one."
  [conn opts]
  (let [k [(str (:q opts)) (boolean (:counts? opts))]
        now (System/currentTimeMillis)
        hit (get @cache k)
        [at v] hit]
    (if (and hit (< (- now at) cache-ttl-ms))
      v
      (let [d (filtered conn opts)]
        (swap! cache assoc k [now d])
        d))))
