(ns boost-scraper.reports
  (:require [boost-scraper.utils :as utils]
            [datalevin.core :as d]
            [clojure.instant]
            [clojure.string :as str]
            [clojure.pprint :as pprint]))

(defn get-boost-summary-for-report' [conn show-regex last-seen-timestamp]
  (d/q '[:find (d/pull ?e [:db/id :invoice/creation_date :boostagram/content_id :boostagram/value_sat_total])
         :in $ ?regex' ?last-seen-timestamp'
         :where
         [?e :invoice/creation_date ?creation_date]
         [(< ?last-seen-timestamp' ?creation_date)]
         ;; filter out those troublemakers
         (not [?e :boostagram/sender_name_normalized "chrislas"])
         (not [?e :boostagram/sender_name_normalized "noblepayne"])
         [?e :boostagram/action "boost"]
         ;; match our particular show
         [?e :boostagram/podcast ?podcast]
         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
         (or [(re-matches ?regex' ?podcast) _]
             [(re-matches ?regex' ?episode) _])]
       (d/db conn) show-regex last-seen-timestamp))

(defn get-boost-summary-for-report [conn show-regex last-seen-timestamp]
  (d/q '[:find [?ballers ?boosts ?thanks ?summary ?stream_summary ?total_summary ?last_seen_id]
         :in $ ?regex ?last-seen-timestamp
         :where
         ;; find all invoices since last-seen for show-regex
         [(datalevin.core/q
           [:find ?e
            :in $ ?regex' ?last-seen-timestamp'
            :where
            [?e :invoice/creation_date ?creation_date]
                ;; !!! FIXME: why does using `<` not work sometimes? Should be faster than using core fn
            [(< ?last-seen-timestamp' ?creation_date)]
                ;; filter out those troublemakers
            (not [?e :boostagram/sender_name_normalized "chrislas"])
            (not [?e :boostagram/sender_name_normalized "noblepayne"])
                ;; temp filters
            (not [?e :boostagram/sender_name_normalized "noblepaine"])
            (not [?e :boostagram/sender_name_normalized "testwes3"])
            (not [?e :boostagram/sender_name_normalized "testwes4"])
            (not [?e :boostagram/sender_name_normalized "testwes5"])
            (not [?e :boostagram/sender_name_normalized "not_quite_noblepayne"])
            (not [?e :boostagram/sender_name_normalized "noblepayne'"])
            (not [?e :boostagram/sender_name_normalized "noblepayne''"])
            (not [?e :boostagram/sender_name_normalized "noblepayne'''"])
            (not [?e :boostagram/sender_name_normalized "never_noblepayne"])
            (not [?e :boostagram/sender_name_normalized "noblepain"])
            (not [?e :boostagram/sender_name_normalized "testwes"])
            (not [?e :boostagram/sender_name_normalized "testwes2"])
            (not [?e :boostagram/sender_name_normalized "testwes6"])
            (not [?e :boostagram/sender_name_normalized "testwes7"])
            (not [?e :boostagram/sender_name_normalized "testwes8"])
            (not [?e :boostagram/sender_name_normalized "testwes9"])
            (not [?e :boostagram/sender_name_normalized "testwes10"])
            (not [?e :boostagram/sender_name_normalized "testwes11"])
            (not [?e :boostagram/sender_name_normalized "testwes12"])
            (not [?e :boostagram/sender_name_normalized "noblepayne-nope"])
            (not [?e :boostagram/sender_name_normalized "noblepayne-test"])
            (not [?e :boostagram/sender_name_normalized "breezywes"])
                ;; match our particular show
            [?e :boostagram/podcast ?podcast]
            [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
            (or [(re-matches ?regex' ?podcast) _]
                [(re-matches ?regex' ?episode) _])]
           $ ?regex ?last-seen-timestamp)
          ?valid_eids]
         ;; find max boost creation_date
         [(datalevin.core/q
           [:find [(max ?cd')]
            :in $ [[?e'] ...]
            :where
            [?e' :boostagram/action "boost"]
            [?e' :invoice/creation_date ?cd']]
           $ ?valid_eids)
          ?maxcd]
         [(first ?maxcd) ?last_seen_id]
         ;; limit eids by max boost creation_date
         [(datalevin.core/q
           [:find ?e
            :in $ [[?e] ...] ?maxcd'
            :where
            [?e :invoice/creation_date ?cd]
            [(<= ?cd ?maxcd')]]
           $ ?valid_eids ?last_seen_id)
          ?valid_eids_before_maxcd]
         ;; aggregate boosts by sender_name_normalized
         [(datalevin.core/q
           [:find ?sender_name_normalized (sum ?sats) (count ?e) (min ?d) (distinct ?e)
            :in $ [[?e] ...]
            :where
            [?e :boostagram/action "boost"]
            [?e :boostagram/sender_name_normalized ?sender_name_normalized]
            [?e :boostagram/value_sat_total ?sats]
            [?e :invoice/creation_date ?d]]
           $ ?valid_eids_before_maxcd)
          ?sats_by_eid]
         ;; pull individual boost data for each sender
         [(datalevin.core/q
           [:find ?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts
            :in $ [[?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boost_ids] ...]
            :where
            [(datalevin.core/q
              [:find [(d/pull ?e' [:boostagram/sender_name_normalized
                                   :boostagram/value_sat_total
                                   :boostagram/podcast
                                   :boostagram/episode
                                   :boostagram/app_name
                                   :invoice/created_at
                                   :invoice/creation_date
                                   :invoice/identifier
                                   :boostagram/message]) ...]
               :in $ [?e' ...]]
              $ ?boost_ids)
             ?boosts]]
           $ ?sats_by_eid)
          ?sats_by_eid_with_deets]
         ;;;; filter by report section
         ;; ballers
         [(datalevin.core/q
           [:find ?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'
            :in $ [[?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'] ...]
            :where [(<= 20000 ?sat_total')]]
           $ ?sats_by_eid_with_deets)
          ?ballers]
         ;; boosts
         [(datalevin.core/q
           [:find ?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'
            :in $ [[?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'] ...]
            :where
            [(<= 2000 ?sat_total')]
            [(< ?sat_total' 20000)]]
           $ ?sats_by_eid_with_deets)
          ?boosts]
         ;; thanks
         [(datalevin.core/q
           [:find ?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'
            :in $ [[?sender_name_normalized' ?sat_total' ?boost_count' ?first_boost' ?boosts'] ...]
            :where
            [(< ?sat_total' 2000)]]
           $ ?sats_by_eid_with_deets)
          ?thanks]
         ;; boost summary
         [(datalevin.core/q
           [:find (sum ?sats) (count ?e) (count-distinct ?sender)
            :in $ [[?e] ...]
            :where
                ;; boost only
            [?e :boostagram/action "boost"]
                ;; bind our vars to aggregate
            [?e :boostagram/value_sat_total ?sats]
            [?e :boostagram/sender_name_normalized ?sender]]
           $ ?valid_eids_before_maxcd)
          ?summary']
         ;; handle empty results. having a nil here short circuits the whole query
         [(or (first ?summary') [0 0 0]) ?summary]
         ;; stream summary
         [(datalevin.core/q
           [:find (sum ?sats) (count ?e) (count-distinct ?sender)
            :in $ [[?e] ...]
            :where
                ;; streams only
            [?e :boostagram/action "stream"]
                ;; bind our vars to aggregate
            [?e :boostagram/value_sat_total ?sats]
            [?e :boostagram/sender_name_normalized ?sender]]
           $ ?valid_eids_before_maxcd)
          ?stream_summary']
         ;; handle empty results. having a nil here short circuits the whole query
         [(or (first ?stream_summary') [0 0 0]) ?stream_summary]
         ;; total summary
         [(datalevin.core/q
           [:find (sum ?sats) (count ?e) (count-distinct ?sender)
            :in $ [[?e] ...]
            :where
                ;; bind our vars to aggregate
            [?e :boostagram/value_sat_total ?sats]
            [?e :boostagram/sender_name_normalized ?sender]]
           $ ?valid_eids_before_maxcd)
          ?total_summary']
         ;; handle empty results. having a nil here short circuits the whole query
         [(or (first ?total_summary') [0 0 0]) ?total_summary]]
       (d/db conn) show-regex last-seen-timestamp))

(defn sort-report
  [[ballers
    boosts
    thanks
    [boost_total_sats boost_total_boosts boost_total_boosters]
    [stream_total_sats stream_total_streams stream_total_streamers]
    [total_sats total_invoices total_unique_boosters]
    last_seen_id]]
  (letfn [(sort-boosts [[sender total count mindate boosts]]
            {:sender sender
             :total total
             :count count
             :mindate mindate
             :boosts (sort-by :invoice/created_at boosts)})]
    {:ballers (sort-by :total #(compare %2 %1) (map sort-boosts ballers))
     :boosts (sort-by :mindate (map sort-boosts boosts))
     :thanks (sort-by :mindate (map sort-boosts thanks))
     :boost-summary {:boost_total_sats boost_total_sats
                     :boost_total_boosts boost_total_boosts
                     :boost_total_boosters boost_total_boosters}
     :stream-summary {:stream_total_sats stream_total_sats
                      :stream_total_streams stream_total_streams
                      :stream_total_streamers stream_total_streamers}
     :summary {:total_sats total_sats
               :total_invoices total_invoices
               :last_seen_id last_seen_id
               :total_unique_boosters total_unique_boosters}}))

(defn int-comma [n] (clojure.pprint/cl-format nil "~:d"  (float (or n 0))))

(defn format-boost-batch-details [[boost & batch]]
  (str/join
   "\n"
   (concat
    (let [{:keys [boostagram/message
                  boostagram/value_sat_total
                  boostagram/podcast
                  boostagram/episode
                  boostagram/app_name
                  #_invoice/identifier
                  invoice/creation_date]} boost]
      [(str "+ " podcast "\n"
            "+ " episode "\n"
            "+ " app_name "\n"
            #_("+ " identifier "\n")
            "\n"
            "+ " (utils/format-date creation_date) " (" creation_date ")" "\n"
            "+ " (int-comma value_sat_total) " sats\n"
            (str/join "\n" (map #(str "> " %) (str/split-lines (or message "No Message Found :(")))))])
    (for [{:keys [boostagram/message boostagram/value_sat_total
                  invoice/creation_date
                  #_invoice/identifier]} batch]
      (str "\n"
           #_("+ " identifier "\n")
           "+ " (utils/format-date creation_date) " (" creation_date ")" "\n"
           "+ " (int-comma value_sat_total) " sats\n"
           (str/join "\n" (map #(str "> " %) (str/split-lines (or message "No Message Found :(")))))))))

(defn format-boost-batch [{:keys [sender total count boosts]}]
  (str "### From: " sender "\n"
       "+ " (int-comma total) " sats\n"
       "+ " (int-comma count) " boosts\n"
       (format-boost-batch-details boosts)
       "\n"))

(defn format-boost-section [boosts]
  (str/join "\n" (map format-boost-batch boosts)))

(defn format-sorted-report
  [{:keys [ballers boosts thanks boost-summary stream-summary summary]}]
  (str "## Baller Boosts\n"
       (format-boost-section ballers) "\n"
       "## Boosts\n"
       (format-boost-section boosts) "\n"
       "## Thanks\n"
       (format-boost-section thanks)
       "\n## Boost Summary"
       "\n+ Total Boosted Sats: " (int-comma (:boost_total_sats boost-summary))
       "\n+ Total Boosts: " (int-comma (:boost_total_boosts boost-summary))
       "\n+ Total Boosters: " (int-comma (:boost_total_boosters boost-summary))
       "\n"
       "\n## Stream Summary"
       "\n+ Total Streamed Sats: " (int-comma (:stream_total_sats stream-summary))
       "\n+ Total Streams: " (int-comma (:stream_total_streams stream-summary))
       "\n+ Total Streamers: " (int-comma (:stream_total_streamers stream-summary))
       "\n"
       "\n## Summary"
       "\n+ Total Sats: " (int-comma (:total_sats summary))
       "\n+ Total Invoices: " (int-comma (:total_invoices summary))
       "\n+ Total Unique Senders: " (int-comma (:total_unique_boosters summary))
       "\n"
       "\n## Last Seen"
       "\n+ Last seen ID: " (:last_seen_id summary)
       "\n"))

(defn boost-report [conn show-regex last-seen-id]
  (->> (get-boost-summary-for-report conn show-regex last-seen-id)
       sort-report
       format-sorted-report))

