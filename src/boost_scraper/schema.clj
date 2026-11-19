(ns boost-scraper.schema
  "Self-description for the database: what a caller can query, without reading
   source.

   WHY: the query API (`POST /api/v1/query`) lets a bot ask arbitrary Datalog,
   but until now it had to learn attribute names by reading `.clj` files. That
   is exactly the friction that kept the query API unused for months.

   KEY INSIGHT (measured): Datalevin auto-registers attributes it has seen, so
   `d/schema` lists everything — declared and undeclared alike — from memory,
   instantly. Discovery therefore needs no datom sampling at all. The earlier
   sampling approach cost 44s per call; this one is effectively free.

   'declared' here means *in the application schema* (`boost-scraper.db/schema`),
   not merely present in Datalevin's own schema — otherwise every attribute
   would look declared and the interesting ones would stay invisible.
   `:boostagram/sender_id` is the canonical example: 353,204 rows, never
   declared, and now our strongest identity signal.

   Cost model (live, 98 attributes):
     names/types/declared   — free (schema is in memory)
     example values         — opt-in, ~0.4s/attribute  (?examples=true)
     entity counts          — opt-in, ~1s/attribute    (?counts=true)"
  (:require [boost-scraper.db :as db]
            [clojure.string :as str]
            [datalevin.core :as d]))

(def ^:private cache-ttl-ms 3600000)        ; an hour: attributes change rarely
(defonce ^:private cache (atom {}))

(defn- type-name
  "Render a :db.type/* keyword as \"db.type/string\" (no leading colon) so JSON
   callers get a stable, readable value."
  [kw]
  (when kw (subs (str kw) 1)))

(defn- infer-type
  "Best-effort type from a sample value, for attributes Datalevin did not type."
  [v]
  (cond
    (nil? v) nil
    (string? v) :db.type/string
    (integer? v) :db.type/long
    (float? v) (if (instance? Double v) :db.type/double :db.type/float)
    (keyword? v) :db.type/keyword
    (boolean? v) :db.type/boolean
    (inst? v) :db.type/instant
    :else nil))

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
  "Entity count for an attribute (index-backed, but ~1s each live)."
  [db attr]
  (try (d/count-datoms db nil attr nil)
       (catch Throwable _ nil)))

(defn- attr-entry
  [db props app-attrs attr opts]
  (let [{:keys [examples? counts?]} opts
        samples (when examples? (sample-values db attr 3))
        value-type (or (:db/valueType props)
                       (some-> samples first infer-type))]
    (cond-> {:attribute (str attr)
             :declared (contains? app-attrs attr)
             :type (type-name value-type)}
      examples? (assoc :examples samples)
      counts? (assoc :entities (count-entities db attr))
      (:db/cardinality props) (assoc :cardinality (type-name (:db/cardinality props)))
      (:db/unique props) (assoc :unique (type-name (:db/unique props))))))

(defn describe
  "Describe every attribute the database knows about.

   opts :: {:q substring      filter on attribute name
            :examples? bool   include sample values (slow-ish)
            :counts? bool     include entity counts (slow)

   Returns {:attributes [...] :counts {...} :notes {...}}."
  ([conn] (describe conn {}))
  ([conn {:keys [q examples? counts?]}]
   (let [db (d/db conn)
         dal-schema (d/schema conn)
         app-attrs (set (keys db/schema))
         entries (->> (keys dal-schema)
                      (filter (fn [attr]
                                (or (not (seq q))
                                    (str/includes? (str/lower-case (str attr))
                                                   (str/lower-case q)))))
                      (map #(attr-entry db (get dal-schema %) app-attrs % 
                                        {:examples? examples? :counts? counts?}))
                      (sort-by (juxt #(if (:declared %) 0 1) :attribute)))]
     {:attributes (vec entries)
      :counts {:declared (count (filter :declared entries))
               :undeclared (count (remove :declared entries))
               :total (count entries)}
      :notes {:declared "In the application schema (boost-scraper.db/schema)."
              :undeclared "Known to Datalevin but absent from the application schema — queryable, and worth knowing about (:boostagram/sender_id, :boostagram/boost_link, :boostagram/name were each found this way)."
              :examples (if examples? "included" "omitted; add ?examples=true for sample values")
              :entity-counts (if counts? "included" "omitted; add ?counts=true (slow: ~1s/attribute)")}})))

(defn cached
  "describe with an hourly cache. The key includes every option, so the
   expensive variants never poison the fast one."
  [conn opts]
  (let [k [(str (:q opts)) (boolean (:examples? opts)) (boolean (:counts? opts))]
        now (System/currentTimeMillis)
        hit (get @cache k)
        [at v] hit]
    (if (and hit (< (- now at) cache-ttl-ms))
      v
      (let [d (describe conn opts)]
        (swap! cache assoc k [now d])
        d))))
