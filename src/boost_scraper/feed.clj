(ns boost-scraper.feed
  "Feed view queries — entity-level boost fetching for the Helipad-style feed."
  (:require [datalevin.core :as d]))

(defn get-boosts-for-feed
  "Fetch boosts for the feed view. Returns vector of boost maps sorted by time desc.
   show-regex: regex pattern for podcast/episode matching
   before-timestamp: epoch seconds — fetch boosts older than this (cursor-based pagination)
   limit: max results (hard cap 200)"
  ([conn show-regex]
   (get-boosts-for-feed conn show-regex nil 100))
  ([conn show-regex before-timestamp]
   (get-boosts-for-feed conn show-regex before-timestamp 100))
  ([conn show-regex before-timestamp limit]
   (let [cap (min (or limit 100) 200)
         where (cond-> '[[?e :boostagram/action "boost"]
                         [?e :boostagram/podcast ?podcast]
                         [?e :invoice/creation_date ?cd]
                         [(re-matches ?regex ?podcast)]
                         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                         [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]
                         [(get-else $ ?e :boostagram/value_sat_total 0) ?sats]
                         [(get-else $ ?e :boostagram/app_name "Unknown") ?app]
                         [(get-else $ ?e :boostagram/message "") ?message]
                         [?e :invoice/add_index ?idx]]
                 (some? before-timestamp) (conj '[(< ?cd ?before)]))
         find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx]
         in-clause (if (some? before-timestamp)
                     '[$ ?regex ?before]
                     '[$ ?regex])
         params (if (some? before-timestamp)
                  [show-regex before-timestamp]
                  [show-regex])
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

(defn get-boosts-for-feed-v2
  "Alternative implementation using explicit parameter binding.
   show-regex: regex for podcast/episode matching
   since: epoch seconds — fetch boosts newer than this
   before: epoch seconds — fetch boosts older than this (cursor)
   limit: max results"
  ([conn show-regex]
   (get-boosts-for-feed-v2 conn show-regex nil nil 100))
  ([conn show-regex since]
   (get-boosts-for-feed-v2 conn show-regex since nil 100))
  ([conn show-regex since before]
   (get-boosts-for-feed-v2 conn show-regex since before 100))
  ([conn show-regex since before limit]
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
                      [?e :invoice/add_index ?idx]]
         time-cond (cond
                     (and since before) '[(<= ?start ?cd ?end)]
                     since '[(<= ?start ?cd)]
                     before '[(<= ?cd ?end)]
                     :else nil)
         where (cond-> base-where
                 time-cond (conj time-cond))
         find-clause '[?cd ?sender ?sats ?app ?podcast ?episode ?message ?idx]
         in-clause (cond
                     (and since before) '[$ ?regex ?start ?end]
                     since '[$ ?regex ?start]
                     before '[$ ?regex ?end]
                     :else '[$ ?regex])
         params (cond
                  (and since before) [show-regex since before]
                  since [show-regex since]
                  before [show-regex before]
                  :else [show-regex])
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
