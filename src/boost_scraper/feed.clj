(ns boost-scraper.feed
  "Feed view queries — entity-level boost fetching for the Helipad-style feed."
  (:require [datalevin.core :as d]))

(defn get-boosts-for-feed-v2
  "Fetch boosts for the feed view. Returns vector of boost maps sorted by time desc.
   show-regex: regex for podcast/episode matching
   podcast: optional exact podcast name filter
   since: epoch seconds — fetch boosts newer than this
   before-time: epoch seconds — cursor time (exclusive)
   before-index: entity index — cursor index for same-timestamp tiebreak
   limit: max results (hard cap 200)"
  ([conn show-regex]
   (get-boosts-for-feed-v2 conn show-regex nil nil nil nil 100))
  ([conn show-regex podcast]
   (get-boosts-for-feed-v2 conn show-regex podcast nil nil nil 100))
  ([conn show-regex podcast since]
   (get-boosts-for-feed-v2 conn show-regex podcast since nil nil 100))
  ([conn show-regex podcast since before-time]
   (get-boosts-for-feed-v2 conn show-regex podcast since before-time nil 100))
  ([conn show-regex podcast since before-time before-index]
   (get-boosts-for-feed-v2 conn show-regex podcast since before-time before-index 100))
  ([conn show-regex podcast since before-time before-index limit]
   (let [cap (min (or limit 100) 200)
         base-where '[[?e :boostagram/action "boost"]
                      [?e :boostagram/podcast ?podcast]
                      [?e :invoice/creation_date ?cd]
                      [(re-matches ?regex ?podcast)]
                      [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                      [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]
                      [(get-else $ ?e :boostagram/value_sat_total 0) ?sats]
                      [(get-else $ ?e :boostagram/app_name "Unknown") ?app]
                      [(get-else $ ?e :boostagram/message "") ?message]
                      [(get-else $ ?e :invoice/add_index 0) ?idx]]
         podcast-cond (when (and podcast (seq podcast))
                        '[(= ?podcast ?pod-filter)])
         ;; Composite cursor: older than (before-time, before-index)
         cursor-cond (when (and before-time before-index)
                       '[(or (< ?cd ?bt) (and (= ?cd ?bt) (< ?idx ?bi)))])
         since-cond (when since
                      '[(<= ?start ?cd)])
         where (cond-> base-where
                 podcast-cond (conj podcast-cond)
                 cursor-cond (conj cursor-cond)
                 since-cond (conj since-cond))
         find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx]
         in-clause (cond-> '[$ ?regex]
                     podcast (conj '?pod-filter)
                     since (conj '?start)
                     (and before-time before-index) (into '[?bt ?bi]))
         params (cond-> [show-regex]
                  podcast (conj podcast)
                  since (conj since)
                  (and before-time before-index) (into [before-time before-index]))
         query {:find find-clause
                :in in-clause
                :where where}]
     (->> (apply d/q query (d/db conn) params)
          (sort-by first >)
          (take cap)
          (mapv (fn [[cd sender sats app podcast episode message idx]]
                  {:time cd
                   :sender sender
                   :sats sats
                   :app app
                   :podcast podcast
                   :episode episode
                   :message message
                   :index idx}))))))

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
                     [(get-else $ ?e :invoice/add_index 0) ?idx]]
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
        find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx]
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
         (sort-by first >)
         (mapv (fn [[cd sender sats app podcast episode message idx]]
                 {:time cd
                  :sender sender
                  :sats sats
                  :app app
                  :podcast podcast
                  :episode episode
                  :message message
                  :index idx})))))

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
