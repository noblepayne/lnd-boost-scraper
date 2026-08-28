(ns boost-scraper.feed
  "Feed view queries — entity-level boost fetching for the Helipad-style feed."
  (:require [datalevin.core :as d]))

(def ^:private feed-default-limit 100)
(def ^:private feed-max-limit 200)

(defn- dedup-by-content-id
  "Dedup rows by content_id if present else identifier. If both empty, keep distinct via hash."
  [rows]
  (let [deduped (->> rows
                     (reduce (fn [acc row]
                               (let [id (nth row 8)
                                     cid (nth row 9)
                                     key (cond
                                           (seq cid) cid
                                           (seq id) id
                                           :else (str "row-" (hash row)))]
                                 (if (contains? acc key) acc (assoc acc key row))))
                             {})
                     vals)]
    (when (< (count deduped) (count rows))
      (println "feed dedup" {:total (count rows) :deduped (count deduped) :dropped (- (count rows) (count deduped))}))
    deduped))

(defn- feed-sort
  "Stable sort: time desc, identifier asc. Time at pos 0, identifier at pos 8."
  [rows]
  (sort (fn [a b]
          (let [c (compare (first b) (first a))]
            (if (not= c 0) c (compare (nth a 8) (nth b 8)))))
        rows))

(defn- row->boost
  [[cd sender sats app podcast episode message idx id cid fiat-cents rail fiat-currency]]
  {:time cd
   :sender sender
   :sats sats
   :app app
   :podcast podcast
   :episode episode
   :message message
   :index idx
   :identifier id
   :content_id cid
   :fiat_cents fiat-cents
   :payment_rail rail
   :fiat_currency fiat-currency})

(defn get-boosts-for-feed-v2
  "Fetch boosts for the feed view. Returns vector of boost maps sorted by time desc.
   show-regex: regex for podcast/episode matching
   podcast: optional exact podcast name filter
   since: epoch seconds — fetch boosts newer than this
   before-time: epoch seconds — cursor time (exclusive)
   before-id-or-index: entity identifier (string) or index (number) — cursor tie-break
   limit: max results (hard cap 200)"
  ([conn show-regex]
   (get-boosts-for-feed-v2 conn show-regex nil nil nil nil feed-default-limit))
  ([conn show-regex podcast]
   (get-boosts-for-feed-v2 conn show-regex podcast nil nil nil feed-default-limit))
  ([conn show-regex podcast since]
   (get-boosts-for-feed-v2 conn show-regex podcast since nil nil feed-default-limit))
  ([conn show-regex podcast since before-time]
   (get-boosts-for-feed-v2 conn show-regex podcast since before-time nil feed-default-limit))
  ([conn show-regex podcast since before-time before-id-or-index]
   (get-boosts-for-feed-v2 conn show-regex podcast since before-time before-id-or-index feed-default-limit))
  ([conn show-regex podcast since before-time before-id-or-index limit]
   (let [cap (min (or limit feed-default-limit) feed-max-limit)
         before-id (when (string? before-id-or-index) before-id-or-index)
         before-idx (when (number? before-id-or-index) before-id-or-index)
         base-where '[[?e :boostagram/action "boost"]
                      [?e :boostagram/podcast ?podcast]
                      [?e :invoice/creation_date ?cd]
                      [(re-matches ?regex ?podcast)]
                      [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                      [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]
                      [(get-else $ ?e :boostagram/value_sat_total 0) ?sats]
                      [(get-else $ ?e :boostagram/app_name "Unknown") ?app]
                      [(get-else $ ?e :boostagram/message "") ?message]
                      [(get-else $ ?e :invoice/add_index 0) ?idx]
                      [(get-else $ ?e :invoice/identifier "") ?id]
                      [(get-else $ ?e :boostagram/content_id "") ?cid]
                      [(get-else $ ?e :boostagram/amount_fiat_cents 0) ?fiat-cents]
                      [(get-else $ ?e :boostagram/payment_rail "") ?rail]
                      [(get-else $ ?e :boostagram/amount_fiat_currency "") ?fiat-currency]]
         podcast-cond (when (and podcast (seq podcast))
                        '[(= ?podcast ?pod-filter)])
         cursor-cond (cond
                       (and before-time (seq before-id))
                       '[(or (< ?cd ?bt) (and (= ?cd ?bt) (> ?id ?bid)))]
                       (and before-time (some? before-idx))
                       '[(or (< ?cd ?bt) (and (= ?cd ?bt) (< ?idx ?bi)))]
                       :else nil)
         since-cond (when since
                      '[(<= ?start ?cd)])
         where (cond-> base-where
                 podcast-cond (conj podcast-cond)
                 cursor-cond (conj cursor-cond)
                 since-cond (conj since-cond))
         find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx ?id ?cid ?fiat-cents ?rail ?fiat-currency]
         in-clause (cond-> '[$ ?regex]
                     podcast (conj '?pod-filter)
                     since (conj '?start)
                     (and before-time (seq before-id)) (into '[?bt ?bid])
                     (and before-time (some? before-idx) (empty? before-id)) (into '[?bt ?bi]))
         params (cond-> [show-regex]
                  podcast (conj podcast)
                  since (conj since)
                  (and before-time (seq before-id)) (into [before-time before-id])
                  (and before-time (some? before-idx) (empty? before-id)) (into [before-time before-idx]))
         query {:find find-clause
                :in in-clause
                :where where}]
     (->> (apply d/q query (d/db conn) params)
          dedup-by-content-id
          feed-sort
          (take cap)
          (mapv row->boost)))))

(defn get-boosts-for-csv
  "Fetch boosts for CSV export. No limit cap, returns all matching."
  [conn show-regex podcast since end]
  (let [base-where '[[?e :boostagram/action "boost"]
                     [?e :boostagram/podcast ?podcast]
                     [?e :invoice/creation_date ?cd]
                     [(re-matches ?regex ?podcast)]
                     [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                     [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]
                     [(get-else $ ?e :boostagram/value_sat_total 0) ?sats]
                     [(get-else $ ?e :boostagram/app_name "Unknown") ?app]
                     [(get-else $ ?e :boostagram/message "") ?message]
                     [(get-else $ ?e :invoice/add_index 0) ?idx]
                     [(get-else $ ?e :invoice/identifier "") ?id]
                     [(get-else $ ?e :boostagram/content_id "") ?cid]
                     [(get-else $ ?e :boostagram/amount_fiat_cents 0) ?fiat-cents]
                     [(get-else $ ?e :boostagram/payment_rail "") ?rail]
                     [(get-else $ ?e :boostagram/amount_fiat_currency "") ?fiat-currency]]
        podcast-cond (when (and podcast (seq podcast))
                       '[(= ?podcast ?pod-filter)])
        time-cond (cond
                    (and since end) '[(<= ?start ?cd ?end)]
                    since '[(<= ?start ?cd)]
                    end '[(<= ?cd ?end)]
                    :else nil)
        where (cond-> base-where
                podcast-cond (conj podcast-cond)
                time-cond (conj time-cond))
        find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx ?id ?cid ?fiat-cents ?rail ?fiat-currency]
        in-clause (cond-> '[$ ?regex]
                    podcast (conj '?pod-filter)
                    (and since end) (into '[?start ?end])
                    (and since (not end)) (conj '?start)
                    (and end (not since)) (conj '?end))
        params (cond-> [show-regex]
                 podcast (conj podcast)
                 (and since end) (into [since end])
                 (and since (not end)) (conj since)
                 (and end (not since)) (conj end))
        query {:find find-clause
               :in in-clause
               :where where}]
    (->> (apply d/q query (d/db conn) params)
         dedup-by-content-id
         feed-sort
         (mapv row->boost))))

(defn get-podcasts-for-feed
  "Get distinct podcast names matching show-regex."
  [conn show-regex]
  (let [query '{:find [?podcast]
                :in [$ ?regex]
                :where [[?e :boostagram/action "boost"]
                        [?e :boostagram/podcast ?podcast]
                        [(re-matches ?regex ?podcast)]]}]
    (->> (d/q query (d/db conn) show-regex)
         (mapv first)
         sort
         vec)))
