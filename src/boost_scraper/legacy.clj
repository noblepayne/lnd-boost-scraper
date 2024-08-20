(ns boost-scraper.legacy
  (:require [datalevin.core :as d]))

(def base-boost-q
  '[;; find last seen boost
    [?last-e :invoice/identifier ?last-seen]
     ;; get its creation_date
    [?last-e :invoice/creation_date ?last-creation_date]
     ;; for every boost, get its creation date
    [?e :invoice/creation_date ?creation_date]
     ;; it must be after last creation date
    [(< ?last-creation_date ?creation_date)]
     ;; it should be a boost
    [?e :boostagram/action "boost"]
     ;; bind podcasts name
    [?e :boostagram/podcast ?podcast]
     ;; bind search pattern
    [(re-pattern ".*Unplugged.*") ?regex]
     ;; match show
    (or [(re-matches ?regex ?podcast)]
        [?e :boostagram/podcast "LINUX Unplugged"])])

(defn get-ballers [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (> (:boostagram/value_sat_total x1)
              (:boostagram/value_sat_total x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(<= 20000 ?ms)]])}
             (d/db conn)
             last-seen)))

(defn get-normal-boosts [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(<=  2000 ?ms)]
                                          [(< ?ms 20000)]])}
             (d/db conn)
             last-seen)))

(defn get-thanks [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(< ?ms 2000)]])}
             (d/db conn)
             last-seen)))

(defn get-summary [conn last-seen]
  (d/q {:find '[(sum ?s) (count ?e) (count-distinct ?b)]
        :in '[$ ?last-seen]
        :where (into base-boost-q '[[?e :boostagram/value_sat_total ?s]
                                    [?e :boostagram/sender_name_normalized ?b]])}
       (d/db conn)
       last-seen))

(defn get-stream-summary [conn show-regex last-seen]
  (into [] cat
        (d/q {:find '[(sum ?s) (count ?e) (count-distinct ?b) (distinct ?b)]
              :in '[$ ?regex ?last-seen]
              :where '[[?last-e :invoice/identifier ?last-seen]
                       [?last-e :invoice/creation_date ?last-creation_date]
                       [?e :invoice/creation_date ?creation_date]
                       [(< ?last-creation_date ?creation_date)]
                       [?e :boostagram/action "stream"]
                       [?e :boostagram/podcast ?podcast]
                       [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                       (or
                        [(re-matches ?regex ?episode) _]
                        [(re-matches ?regex ?podcast) _])
                       [?e :boostagram/value_sat_total ?s]
                       [?e :boostagram/sender_name_normalized ?b]]}
             (d/db conn)
             show-regex
             last-seen)))

(defn get-all-boosts-since [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                :boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at
                                :invoice/creation_date])]
              :in '[$ ?last-seen]
              :where base-boost-q}
             (d/db conn)
             last-seen)))

(defn get-lnd-boosts-from-db [conn show-regex last-seen-idx]
  (into (sorted-set-by (fn [& args] (apply compare (reverse (map :invoice/creation_date args)))))
        (comp cat
              #_(take 10)
                ;; sort keys of each boost
              (map #(into (sorted-map) %)))
        (d/q '[:find (d/pull ?e #_[:*] [:boostagram/app_name
                                        :boostagram/podcast
                                        :boostagram/episode
                                        :boostagram/sender_name_normalized
                                        :boostagram/value_msat_total
                                        :boostagram/value_sat_total
                                        :boostagram/message
                                        :invoice/identifier
                                        :invoice/created_at
                                        :invoice/creation_date])
               :in $ ?regex ?last-seen-idx
               :where
                 ;; only find boosts
               [?e :boostagram/action "boost"]
                 ;; with creation_date's after our "since" marker
               [?e :invoice/creation_date ?cd]
               [?e0 :invoice/identifier ?last-seen-idx]
               [?e0 :invoice/creation_date ?cd0]
               [(< ?cd0 ?cd)]
               #_[(< ?last-seen-idx ?cd)]
                 ;; match podcast and episode to find all episodes of `show`
               [?e :boostagram/podcast ?podcast]
               [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
               (or
                [(re-matches ?regex ?podcast) _]
                [(re-matches ?regex ?episode) _])]
             (d/db conn)
             show-regex
             last-seen-idx)))

(defn v2->v1 [v2-boosts]
  (->> v2-boosts
         ;; remove namespaces from keys
       (mapv
        (fn [boost]
          (into {}
                (map (fn [[k v]]
                       [(keyword (name k)) v]))
                boost)))
         ;; rename :sender_name_normalized -> :sender_name
       (mapv
        (fn [boost]
          (into {}
                (map (fn [[k v]]
                       [(if (= k :sender_name_normalized) :sender_name k) v]))
                boost)))))

#_(defn boost-report-v2 [conn show-regex last-seen-index]
    (->> (get-lnd-boosts-from-db conn show-regex last-seen-index)
         v2->v1
         v1/boost-report
         #_(#(with-out-str (clojure.pprint/pprint %)))
         (spit "/tmp/new_boosts.md")))

#_(defn make-stream-summary [conn show-regex last-seen]
    (let [[sats streams streamers] (get-stream-summary conn show-regex last-seen)]
      (str "### Stream Totals\n"
           "+ Total Sats: " (v1/int-comma sats) "\n"
           "+ Total Streams: " (v1/int-comma streams) "\n"
           "+ Unique Streamers: " (v1/int-comma streamers) "\n")))

(comment

  (boost-report-v2 conn #"(?i).*linux.*" (-> #inst "2024-07-01T00:00Z" (#(.getTime %)) (/ 1000)))
  (boost-report-v2 conn #"(?i).*self.*" "443250")

  (boost-report-v2 conn #"(?i).*linux.*" "450565")

  (make-stream-summary conn #"(?i).*self.*" "443250"))
