(ns boost-scraper.db
  (:require [boost-scraper.ws :as ws]
            [cheshire.core :as json]
            [clojure.instant]
            [clojure.string :as str]
            [datalevin.core :as d]))

(def schema
  {:invoice/identifier {:db/valueType :db.type/string
                        :db/unique :db.unique/identity}
   :invoice/source {:db/valueType :db.type/string}
   :invoice/creation_date {:db/valueType :db.type/long}
   :invoice/creation_date_per_30 {:db/valueType :db.type/long}
   :invoice/created_at {:db/valueType :db.type/instant}
   :invoice/add_index {:db/valueType :db.type/long}
   :invoice/comment {:db/valueType :db.type/string}
   :invoice/keysend {:db/valueType :db.type/string}
   :invoice/memo {:db/valueType :db.type/string}
   :boostagram/sender_name {:db/valueType :db.type/string}
   :boostagram/sender_name_normalized {:db/valueType :db.type/string}
   :boostagram/episode {:db/valueType :db.type/string}
   :boostagram/podcast {:db/valueType :db.type/string}
   :boostagram/app_name {:db/valueType :db.type/string}
   :boostagram/action {:db/valueType :db.type/string}
   :boostagram/message {:db/valueType :db.type/string}
   :boostagram/value_msat_total {:db/valueType :db.type/long}
   :boostagram/value_sat_total {:db/valueType :db.type/long}
   :boostagram/ts {:db/valueType :db.type/long}
   :boostagram/time {:db/valueType :db.type/string}
   :scraper/source {:db/valueType :db.type/string}
   :boostagram/content_id {:db/valueType :db.type/string
                          ;; TODO: enable uniqueness after we're sure this is unique enough
                          ;; N.B. how does it work for streams? What is someone streams the same show twice?
                           #_:db/unique #_:db.unique/identity}
   ;; Client state for agent tracking
   :client-state/key {:db/valueType :db.type/string
                      :db/unique :db.unique/identity}
   :client-state/client-id {:db/valueType :db.type/string}
   :client-state/show-slug {:db/valueType :db.type/string}
   :client-state/last-seen-tx {:db/valueType :db.type/long}
   :client-state/last-accessed-tx {:db/valueType :db.type/long}
   ;;
   ;; New boost source fields
   :boostagram/payment_rail {:db/valueType :db.type/string}
   :boostagram/zaprite_order_id {:db/valueType :db.type/string
                                 :db/unique :db.unique/identity}
   :boostagram/r2_object_key {:db/valueType :db.type/string
                              :db/unique :db.unique/identity}
   :boostagram/memberful_member_id {:db/valueType :db.type/string}
   :boostagram/amount_fiat_cents {:db/valueType :db.type/long}
   :boostagram/amount_fiat_currency {:db/valueType :db.type/string}
   :boostagram/received_at {:db/valueType :db.type/instant}
   :boostagram/podcast_slug {:db/valueType :db.type/string}
   :boostagram/episode_guid {:db/valueType :db.type/string}

   :boostagram/type {:db/valueType :db.type/keyword}
   :boostagram/boost_id {:db/valueType :db.type/string}
   ;; Sync cursor tracking
   :sync-cursor/key {:db/valueType :db.type/string
                     :db/unique :db.unique/identity}
   :sync-cursor/value {:db/valueType :db.type/string}})

(defn remove-empty-vals [m]
  (->> m
       (remove
        (fn [[_ v]]
          (or (nil? v)
              (and (string? v)
                   (empty? v)))))
       (into {})))

(defn namespace-invoice-keys [toplvl-key m]
  (reduce
   (fn [xs [k v]]
     (if (map? v)
       (assoc xs k v)
       (let [toplvl (get xs toplvl-key {})
             toplvl (assoc toplvl k v)]
         (assoc xs toplvl-key toplvl))))
   {}
   m))

(defn flatten-paths
  "https://andersmurphy.com/2019/11/30/clojure-flattening-key-paths.html"
  [separator m]
  (letfn [(flatten-paths [m separator path]
            (lazy-seq
             (when-let [[[k v] & xs] (seq m)]
               (cond (and (map? v) (not-empty v))
                     (into (flatten-paths v separator (conj path k))
                           (flatten-paths xs separator path))
                     :else
                     (cons [(->> (conj path k)
                                 (map name)
                                 (clojure.string/join separator)
                                 keyword) v]
                           (flatten-paths xs separator path))))))]
    (into {} (flatten-paths m separator []))))

(defn normalize-name [name_]
  (-> name_
      str/trim
      str/lower-case
      ((fn [s] (if (str/starts-with? s "@")
                 (.substring s 1)
                 s)))))

(defn decode-boost [rawboost debug]
  (try
    (let [decoder (java.util.Base64/getDecoder)]
      (-> decoder
          (.decode rawboost)
          (#(java.lang.String. %))
          json/parse-string
          remove-empty-vals
          (#(namespace-invoice-keys :boostagram %))
          (#(flatten-paths "/" %))))
    (catch Exception e (println "EXCEPTION DECODING BOOST: " rawboost debug (bean e)) {})))

(defn decode-keysend [rawboost debug]
  (try
    (let [decoder (java.util.Base64/getDecoder)]
      (-> decoder
          (.decode rawboost)
          (#(java.lang.String. %))
          (#(hash-map :invoice/keysend %))))
    (catch Exception e (println "EXCEPTION DECODING KEYSEND: " rawboost debug (bean e)) {})))

(defn sha256 [string]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes string "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn- content-id [{:keys [:boostagram/action
                           :boostagram/app_name
                           :boostagram/episode
                           :boostagram/podcast
                           :boostagram/sender_name
                           :boostagram/value_msat_total
                           :boostagram/message
                           :boostagram/ts
                           :boostagram/time
                           :boostagram/guid
                           :boostagram/feedID
                           :boostagram/itemID
                           :boostagram/episode_guid
                           :boostagram/boost_uuid]}]
  (sha256 (str/join " " [action
                         episode
                         episode_guid
                         itemID
                         podcast
                         guid
                         feedID
                         sender_name
                         value_msat_total
                         boost_uuid
                         message
                         app_name
                         ts
                         time])))

;; FIXME: create common base and split out alby and lnd specifics
(defn coerce-invoice-vals [invoice]
  (let [;; From LND; if present use as string identifier.
        invoice (if-let [add_index (get invoice :invoice/add_index)]
                  (assoc
                   invoice
                   :invoice/identifier
                   add_index)
                  invoice)
        ;; From LND; keep a copy as an int for queries.
        invoice (if-let [add_index (get invoice :invoice/add_index)]
                  (assoc
                   invoice
                   :invoice/add_index
                   (Integer/parseInt add_index))
                  invoice)
        ;; Convert string creation_date (unix epoch) into int for indexes/queries.
        invoice (if-let [creation_date (get invoice :invoice/creation_date)]
                  (assoc
                   invoice
                   :invoice/creation_date
                   (if (string? creation_date) (Integer/parseInt creation_date) creation_date))
                  invoice)
        #_invoice #_(if-let [creation_date (get invoice :invoice/creation_date)]
                      (assoc
                       invoice
                       :invoice/creation_date_per_30
                       (if (= "boost" (get invoice :boostagram/action))
                         (quot creation_date 30)
                         (rand-int 1000000000)))
                      invoice)
        ;; From Alby; if present parse from string into Date.
        invoice (if-let [created_at (get invoice :invoice/created_at)]
                  (assoc
                   invoice
                   :invoice/created_at
                   (clojure.instant/read-instant-date created_at))
                  invoice)
        ;; If created_at is not present, create it form creation_date.
        invoice (if-not (contains? invoice :invoice/created_at)
                  (if-let [creation_date (get invoice :invoice/creation_date)]
                    (assoc
                     invoice
                     :invoice/created_at
                     (java.util.Date/from (java.time.Instant/ofEpochSecond creation_date)))
                    invoice)
                  invoice)
        invoice (if-let [[{custom_records :custom_records}] (get invoice :invoice/htlcs)]
                  (let [keysend (when-let [rawkeysend (get custom_records :34349334)]
                                  ;; From LND; if present parse into keysend data.
                                  (decode-keysend rawkeysend custom_records))
                        boost (when-let [rawboost (get custom_records :7629169)]
                                ;; From LND; if present parse into boostagram data.
                                (decode-boost rawboost custom_records))]
                    (reduce into invoice (remove empty? [keysend boost])))
                  invoice)
        invoice (dissoc invoice :invoice/htlcs)
        ;; Noramlize sender name.
        invoice (if-let [sender_name (get invoice :boostagram/sender_name)]
                  (assoc
                   invoice
                   :boostagram/sender_name_normalized
                   (normalize-name sender_name))
                  invoice)
        ;; Convert millisats to sats.
        invoice (if-let [msats (get invoice :boostagram/value_msat_total)]
                  (assoc
                   invoice
                   :boostagram/value_sat_total
                   (quot msats 1000))
                  invoice)
        ;; Add attempt at content-derived ID
        invoice (assoc invoice :boostagram/content_id (content-id invoice))
        ;; Classify as sat-based (all LND/Alby invoices)
        invoice (assoc invoice :boostagram/type :sat)]
    invoice))

(defn process-batch [batch]
  (into []
        (comp (map #(dissoc % :features :amp_invoice_state :metadata :custom_records))
              (map #(namespace-invoice-keys :invoice %1))
              (map #(flatten-paths "/" %1))
              (map remove-empty-vals)
              (map coerce-invoice-vals))
        #_(map #(into (sorted-map) %))
        batch))

(defn backfill-boost-type! [conn]
  (let [db (d/db conn)
        eids (d/q '[:find [?e ...]
                    :where
                    [?e :boostagram/action _]
                    (not [?e :boostagram/type _])]
                  db)
        txdata (mapv (fn [eid] {:db/id eid :boostagram/type :sat}) eids)]
    (when (seq txdata)
      (println "Backfilling :boostagram/type for" (count txdata) "entities")
      (d/transact! conn txdata)
      (println "Backfill complete."))))

(defn add-boosts [conn source-name boosts]
  (->> boosts
       (map process-batch)
       ;; TODO: integrate into process-batch
       (map (fn [batch] (map #(assoc % :scraper/source source-name) batch)))
       (run! (fn [boost-batch]
               (d/transact! conn boost-batch)
               (doseq [entity boost-batch]
                 (when (= "boost" (:boostagram/action entity))
                   (ws/broadcast! (select-keys entity [:boostagram/sender_name_normalized
                                                       :boostagram/value_sat_total
                                                       :boostagram/app_name
                                                       :boostagram/podcast
                                                       :boostagram/episode
                                                       :boostagram/message
                                                       :invoice/creation_date]))))))))