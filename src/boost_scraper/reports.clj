(ns boost-scraper.reports
  (:require [boost-scraper.utils :as utils]
            [clojure.instant]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.transform :as mt]))

(def ReportSchema
  [:map
   [:ballers {:default []} [:vector [:map {:closed false}]]]
   [:boosts {:default []} [:vector [:map {:closed false}]]]
   [:thanks {:default []} [:vector [:map {:closed false}]]]
   [:fiat-boosts {:default []} [:vector [:map {:closed false}]]]
   [:member-free-boosts {:default []} [:vector [:map {:closed false}]]]
   [:boost-summary
    [:map
     [:boost_total_sats {:default 0} :int]
     [:boost_total_boosts {:default 0} :int]
     [:boost_total_boosters {:default 0} :int]]]
   [:stream-summary
    [:map
     [:stream_total_sats {:default 0} :int]
     [:stream_total_streams {:default 0} :int]
     [:stream_total_streamers {:default 0} :int]]]
   [:summary
    [:map
     [:total_sats {:default 0} :int]
     [:total_invoices {:default 0} :int]
     [:total_unique_boosters {:default 0} :int]
     [:last_seen_id [:maybe :int]]]]])

(def report-transformer
  (mt/transformer
   (mt/default-value-transformer)
   (mt/string-transformer)))

(defn normalize-report
  "Normalize report data using ReportSchema to ensure consistent defaults."
  [data]
  (m/decode ReportSchema data report-transformer))

(defn get-boost-summary-for-report' [conn show-regex last-seen-timestamp]
  (d/q '[:find (d/pull ?e [:db/id
                           :invoice/identifier
                           :invoice/created_at
                           :invoice/creation_date
                           :boostagram/content_id
                           :boostagram/value_sat_total
                           :boostagram/sender_name_normalized
                           :boostagram/podcast
                           :boostagram/episode
                           :boostagram/app_name
                           :boostagram/message
                           :boostagram/ts
                           :boostagram/amount_fiat_cents
                           :boostagram/amount_fiat_currency
                           :boostagram/payment_rail])
         :in $ ?regex' ?last-seen-timestamp'
         :where
         [?e :invoice/creation_date ?creation_date]
         [(< ?last-seen-timestamp' ?creation_date)]
         ;; filter out those troublemakers
         (not [?e :boostagram/sender_name_normalized "chrislas"])
         (not [?e :boostagram/sender_name_normalized "noblepayne"])
         [?e :boostagram/action "boost"]
          ;; match our particular show
         [(get-else $ ?e :boostagram/podcast "Unknown Podcast") ?podcast]
         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
         (or [(re-matches ?regex' ?podcast) _]
             [(re-matches ?regex' ?episode) _])]
       (d/db conn) show-regex last-seen-timestamp))

(defn get-boost-summary-for-report [conn show-regex last-seen-timestamp]
  (d/q '[:find [?ballers ?boosts ?thanks ?fiat_by_sender ?member_free_by_sender ?summary ?stream_summary ?total_summary ?last_seen_id ?source_sat ?source_fiat ?source_member]
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
            #_(not [?e :boostagram/sender_name_normalized "noblepayne"])
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
            ;; TODO: support no podcast being specified
            [(get-else $ ?e :boostagram/podcast "Unknown Podcast") ?podcast]
            [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
            (or [(re-matches ?regex' ?podcast) _]
                [(re-matches ?regex' ?episode) _])]
           $ ?regex ?last-seen-timestamp)
          ?valid_eids]
         ;; NOTE: ?valid_eids intentionally includes fiat and member-free
         ;; entities — no [:boostagram/type :sat] filter here. This means
         ;; ?last_seen_id (max creation_date) spans all boost types, which
         ;; is correct: we want the cursor to advance past every processed
         ;; entity regardless of type. The type-specific aggregation rules
         ;; below filter by :sat / :fiat / :member-free respectively.
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
            [?e :boostagram/type :sat]
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
                                   :boostagram/ts
                                   :invoice/created_at
                                   :invoice/creation_date
                                   :invoice/identifier
                                   :boostagram/message
                                   :scraper/source
                                   :boostagram/amount_fiat_cents
                                   :boostagram/amount_fiat_currency
                                   :boostagram/payment_rail]) ...]
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
          ;; fiat: aggregate cents by sender
         [(datalevin.core/q
           [:find ?sender (sum ?cents) (count ?e) (distinct ?e)
            :in $ [[?e] ...]
            :where
            [?e :boostagram/type :fiat]
            [?e :boostagram/sender_name_normalized ?sender]
            [?e :boostagram/amount_fiat_cents ?cents]]
           $ ?valid_eids_before_maxcd)
          ?fiat_raw]
          ;; fiat: pull boost details per sender
         [(datalevin.core/q
           [:find ?sender' ?cent_total' ?boost_count' ?boosts
            :in $ [[?sender' ?cent_total' ?boost_count' ?boost_ids] ...]
            :where
            [(datalevin.core/q
              [:find [(d/pull ?e [:boostagram/sender_name_normalized
                                  :boostagram/amount_fiat_cents
                                  :boostagram/amount_fiat_currency
                                  :boostagram/payment_rail
                                  :boostagram/podcast
                                  :boostagram/episode
                                  :boostagram/message
                                  :invoice/creation_date
                                  :invoice/created_at
                                  :scraper/source]) ...]
               :in $ [?e ...]]
              $ ?boost_ids)
             ?boosts]]
           $ ?fiat_raw)
          ?fiat_by_sender]
          ;; member-free: count by sender
         [(datalevin.core/q
           [:find ?sender (count ?e) (distinct ?e)
            :in $ [[?e] ...]
            :where
            [?e :boostagram/type :member-free]
            [?e :boostagram/sender_name_normalized ?sender]]
           $ ?valid_eids_before_maxcd)
          ?member_free_raw]
          ;; member-free: pull boost details per sender
         [(datalevin.core/q
           [:find ?sender' ?boost_count' ?boosts
            :in $ [[?sender' ?boost_count' ?boost_ids] ...]
            :where
            [(datalevin.core/q
              [:find [(d/pull ?e [:boostagram/sender_name_normalized
                                  :boostagram/payment_rail
                                  :boostagram/memberful_member_id
                                  :boostagram/podcast
                                  :boostagram/episode
                                  :boostagram/message
                                  :invoice/creation_date
                                  :invoice/created_at
                                  :scraper/source]) ...]
               :in $ [?e ...]]
              $ ?boost_ids)
             ?boosts]]
           $ ?member_free_raw)
          ?member_free_by_sender]
          ;; boost summary (sat only)
         [(datalevin.core/q
           [:find (sum ?sats) (count ?e) (count-distinct ?sender)
            :in $ [[?e] ...]
            :where
                 ;; boost only
            [?e :boostagram/action "boost"]
            [?e :boostagram/type :sat]
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
         [(or (first ?total_summary') [0 0 0]) ?total_summary]
         ;; source breakdown: sat boosts grouped by :scraper/source
         ;; NOT filter: exclude streams but include boosts even if action field is missing
         [(datalevin.core/q
           [:find ?source (sum ?sats) (count ?e)
            :in $ [[?e] ...]
            :where
            (not [?e :boostagram/action "stream"])
            [?e :boostagram/type :sat]
            [?e :boostagram/value_sat_total ?sats]
            [(get-else $ ?e :scraper/source "unknown") ?source]]
           $ ?valid_eids_before_maxcd)
          ?source_sat]
         ;; source breakdown: fiat boosts grouped by :scraper/source
         ;; no action filter — fiat has no concept of streams
         [(datalevin.core/q
           [:find ?source (sum ?cents) (count ?e)
            :in $ [[?e] ...]
            :where
            [?e :boostagram/type :fiat]
            [?e :boostagram/amount_fiat_cents ?cents]
            [(get-else $ ?e :scraper/source "unknown") ?source]]
           $ ?valid_eids_before_maxcd)
          ?source_fiat]
         ;; source breakdown: member-free boosts grouped by :scraper/source
         ;; no action filter — member-free has no concept of streams
         [(datalevin.core/q
           [:find ?source (count ?e)
            :in $ [[?e] ...]
            :where
            [?e :boostagram/type :member-free]
            [(get-else $ ?e :scraper/source "unknown") ?source]]
           $ ?valid_eids_before_maxcd)
          ?source_member]]
       (d/db conn) show-regex last-seen-timestamp))

(defn sort-report
  [[ballers boosts thanks fiat-by-sender member-free-by-sender
    [boost_total_sats boost_total_boosts boost_total_boosters]
    [stream_total_sats stream_total_streams stream_total_streamers]
    [total_sats total_invoices total_unique_boosters]
    last_seen_id
    source_sat source_fiat source_member]]
  (letfn [(sort-boosts [[sender total count mindate boosts]]
            {:sender sender
             :total total
             :count count
             :mindate mindate
             :boosts (sort-by :invoice/created_at boosts)})
          (sort-fiat [[sender total count boosts]]
            {:sender sender
             :total total
             :count count
             :boosts (sort-by :invoice/created_at boosts)})
          (sort-free [[sender count boosts]]
            {:sender sender
             :count count
             :boosts (sort-by :invoice/created_at boosts)})
          (merge-source-breakdown [sat-results fiat-results member-results]
            (let [sat-map (into {} (for [[src sats cnt] sat-results]
                                     [src {:sats (or sats 0) :count (or cnt 0)}]))
                  fi-map (into {} (for [[src cents cnt] fiat-results]
                                    [src {:fiat-cents (or cents 0) :count (or cnt 0)}]))
                  mem-map (into {} (for [[src cnt] member-results]
                                     [src {:count (or cnt 0)}]))
                  all-sources (distinct (concat (keys sat-map) (keys fi-map) (keys mem-map)))]
              (into (sorted-map)
                    (for [src all-sources
                          :let [sat (get-in sat-map [src :sats] 0)
                                sat-cnt (get-in sat-map [src :count] 0)
                                fi-cents (get-in fi-map [src :fiat-cents] 0)
                                fi-cnt (get-in fi-map [src :count] 0)
                                mem-cnt (get-in mem-map [src :count] 0)]]
                      [src {:count (+ sat-cnt fi-cnt mem-cnt)
                            :sats sat
                            :fiat-cents fi-cents}]))))]
    {:ballers (sort-by :total #(compare %2 %1) (map sort-boosts ballers))
     :boosts (sort-by :mindate (map sort-boosts boosts))
     :thanks (sort-by :mindate (map sort-boosts thanks))
     :fiat-boosts (sort-by :total #(compare %2 %1) (map sort-fiat fiat-by-sender))
     :member-free-boosts (sort-by :count #(compare %2 %1) (map sort-free member-free-by-sender))
     :boost-summary {:boost_total_sats (or boost_total_sats 0)
                     :boost_total_boosts (or boost_total_boosts 0)
                     :boost_total_boosters (or boost_total_boosters 0)}
     :stream-summary {:stream_total_sats (or stream_total_sats 0)
                      :stream_total_streams (or stream_total_streams 0)
                      :stream_total_streamers (or stream_total_streamers 0)}
     :summary {:total_sats (or total_sats 0)
               :total_invoices (or total_invoices 0)
               :last_seen_id last_seen_id
               :total_unique_boosters (or total_unique_boosters 0)}
     :source-breakdown (merge-source-breakdown source_sat source_fiat source_member)}))

(defn int-comma [n] (clojure.pprint/cl-format nil "~:d" (or n 0)))

(defn format-value-line
  "Format a boost's value line.
   Fiat: '$5.00 (card)'. Member-free: 'Free'. Sats: '5,000 sats'."
  [b]
  (cond
    (and (:boostagram/amount_fiat_cents b)
         (pos? (:boostagram/amount_fiat_cents b)))
    (let [dollars (/ (:boostagram/amount_fiat_cents b) 100.0)
          rail (or (:boostagram/payment_rail b) "unknown")]
      (format "$%.2f (%s)" (double dollars) rail))
    (= "member-free" (:boostagram/payment_rail b))
    "Free Member Boost"
    :else
    (str (int-comma (:boostagram/value_sat_total b)) " sats")))

(defn score-metadata
  "Score a boost by richness of metadata.
   Higher score = more info available."
  [b]
  (+ (if (:boostagram/podcast b) 5 0)
     (if (:boostagram/episode b) 4 0)
     (if (or (:boostagram/ts b) (:boostagram/time b)) 3 0)
     (if (:boostagram/app_name b) 2 0)
     (if (:scraper/source b) 1 0)))

(defn best-metadata
  "Returns the boost with the 'best' (richest) metadata from the batch.
   Uses scoring: podcast(5) > episode(4) > ts/time(3) > app_name(2) > source(1)"
  [boosts]
  (if (seq boosts)
    (apply max-key score-metadata boosts)
    nil))

(defn format-boost-batch-details [boosts]
  (let [best (best-metadata boosts)
        others (if best
                 (remove #(identical? % best) boosts)
                 boosts)]
    (str/join
     "\n"
     (concat
      (let [{:keys [boostagram/message
                    boostagram/podcast
                    boostagram/episode
                    boostagram/app_name
                    boostagram/ts
                    boostagram/time
                    #_invoice/identifier
                    invoice/creation_date
                    scraper/source]} best]
        [(str
          (when podcast (str "+ " podcast "\n"))
          (when episode (str "+ " episode "\n"))
          (when app_name (str "+ " app_name "\n"))
          (when source (str "+ " source "\n"))
          (when (or ts time)
            (let [display-ts (or ts time)]
              (str "+ at " (if (string? display-ts) display-ts (utils/format-seconds display-ts)) "\n")))
          "\n"
          "+ " (utils/format-date creation_date) " (" creation_date ")" "\n"
          "+ " (format-value-line best) "\n"
          (str/join "\n" (map #(str "> " %) (str/split-lines (or message "No Message Found :(")))))])
      (for [{:keys [boostagram/message boostagram/value_sat_total
                    invoice/creation_date
                    #_invoice/identifier
                    boostagram/amount_fiat_cents
                    boostagram/amount_fiat_currency
                    boostagram/payment_rail]} others]
        (str "\n"
             #_("+ " identifier "\n")
             "+ " (utils/format-date creation_date) " (" creation_date ")" "\n"
             "+ " (format-value-line {:boostagram/amount_fiat_cents amount_fiat_cents
                                      :boostagram/amount_fiat_currency amount_fiat_currency
                                      :boostagram/payment_rail payment_rail
                                      :boostagram/value_sat_total value_sat_total}) "\n"
             (str/join "\n" (map #(str "> " %) (str/split-lines (or message "No Message Found :("))))))))))

(defn format-boost-batch [{:keys [sender total count boosts]}]
  (str "### From: " sender "\n"
       "+ " (int-comma total) " sats\n"
       "+ " (int-comma count) " boosts\n"
       (format-boost-batch-details boosts)
       "\n"))

(defn format-fiat-boost-batch [{:keys [sender total count boosts]}]
  (str "### From: " sender "\n"
       "+ " (format "%.2f" (float (/ (or total 0) 100))) " total fiat\n"
       "+ " (int-comma count) " boost(s)\n"
       (format-boost-batch-details boosts)
       "\n"))

(defn format-member-free-boost-batch [{:keys [sender count boosts]}]
  (str "### From: " sender "\n"
       "+ " (int-comma count) " free member boost(s)\n"
       (format-boost-batch-details boosts)
       "\n"))

(defn format-boost-section [boosts]
  (str/join "\n" (map format-boost-batch boosts)))

(defn format-fiat-section [boosts]
  (str/join "\n" (map format-fiat-boost-batch boosts)))

(defn format-member-free-section [boosts]
  (str/join "\n" (map format-member-free-boost-batch boosts)))

(defn format-sorted-report
  [{:keys [ballers boosts thanks fiat-boosts member-free-boosts
           boost-summary stream-summary summary fiat-converted fiat-skipped
           source-breakdown]}]
  (str "## Baller Boosts\n"
       (format-boost-section ballers) "\n"
       "## Boosts\n"
       (format-boost-section boosts) "\n"
       (when (seq fiat-boosts)
         (str "\n## Fiat Boosts\n"
              (format-fiat-section fiat-boosts)))
       (when (seq member-free-boosts)
         (str "\n## Member Free Boosts\n"
              (format-member-free-section member-free-boosts)))
       "\n## Thanks\n"
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
       (cond
         fiat-converted
         (str " (incl. " (int-comma (:fiat-sats fiat-converted))
              " fiat @" (int-comma (:rate fiat-converted)) " sats/USD"
              ", via " (:source fiat-converted) ")")
         fiat-skipped
         " (rate unavailable — fiat excluded)")
       "\n+ Total Invoices: " (int-comma (:total_invoices summary))
       "\n+ Total Unique Senders: " (int-comma (:total_unique_boosters summary))
       (let [fiat-count (apply + (map :count fiat-boosts))
             fiat-senders (count fiat-boosts)
             free-count (apply + (map :count member-free-boosts))
             free-senders (count member-free-boosts)]
         (str
          (when (pos? fiat-count)
            (str "\n+ Total Fiat Boosts: " (int-comma fiat-count)
                 " (" (int-comma fiat-senders) " booster"
                 (when (not= 1 fiat-senders) "s") ")"))
          (when (pos? free-count)
            (str "\n+ Total Member Free Boosts: " (int-comma free-count)
                 " (" (int-comma free-senders) " member"
                 (when (not= 1 free-senders) "s") ")"))))
       (when (seq source-breakdown)
         (let [sorted (reverse (sort-by (fn [[_ v]] (:count v)) source-breakdown))
               total-count (reduce + (map (fn [[_ v]] (:count v)) source-breakdown))
               total-sats (reduce + (map (fn [[_ v]] (:sats v)) source-breakdown))
               total-fiat (reduce + (map (fn [[_ v]] (:fiat-cents v)) source-breakdown))]
           (str "\n\n## Source Breakdown\n"
                (str/join "\n"
                          (for [[src {:keys [count sats fiat-cents]}] sorted]
                            (str "+ " src ": " (int-comma count) " boost"
                                 (when (not= 1 count) "s")
                                 " — " (int-comma sats) " sats"
                                 (when (pos? fiat-cents)
                                   (str ", $" (format "%.2f" (/ fiat-cents 100.0)) " fiat")))))
                "\n+ Total: " (int-comma total-count) " boosts — " (int-comma total-sats) " sats"
                (when (pos? total-fiat)
                  (str ", $" (format "%.2f" (/ total-fiat 100.0)) " fiat")))))
       "\n"
       "\n## Last Seen"
       "\n+ Last seen ID: " (:last_seen_id summary)
       "\n"))

(defn add-fiat-to-total
  "Convert fiat boost totals to sats and add to summary total.
   fiat-sats-rate = {:rate N :source NAME} where rate is sats per 1 USD,
   nil if no feed configured, or a map with nil rate if feed failed."
  [report fiat-sats-rate]
  (let [rate (:rate fiat-sats-rate)]
    (cond
      (and rate (seq (:fiat-boosts report)))
      (let [fiat-cents (apply + (map :total (:fiat-boosts report)))
            fiat-sats (int (/ (* fiat-cents (long rate)) 100))]
        (-> report
            (assoc :fiat-converted {:fiat-cents fiat-cents
                                    :fiat-sats fiat-sats
                                    :rate rate
                                    :source (:source fiat-sats-rate)})
            (update-in [:summary :total_sats] + fiat-sats)))
      (and (map? fiat-sats-rate) (nil? rate) (seq (:fiat-boosts report)))
      (assoc report :fiat-skipped true)
      :else report)))

(defn boost-report [conn show-regex last-seen-id & {:keys [fiat-sats-rate]}]
  (->> (get-boost-summary-for-report conn show-regex last-seen-id)
       sort-report
       normalize-report
       (#(add-fiat-to-total % fiat-sats-rate))
       format-sorted-report))

(defn first-booster [conn episode]
  (let [db (d/db conn)
        [min-ts]
        (d/q '[:find [(min ?timestamp)]
               :in $ ?episode
               :where
               [?e :boostagram/action "stream"]
               [?e :boostagram/episode ?episode]
               [?e :invoice/creation_date ?timestamp]]
             db episode)]
    (when min-ts
      (first
       (d/q '[:find [(pull ?e [:boostagram/sender_name
                               :invoice/created_at
                               :boostagram/app_name
                               :boostagram/value_sat_total])]
              :in $ ?episode ?min-ts
              :where
              [?e :boostagram/action "stream"]
              [?e :boostagram/episode ?episode]
              [?e :invoice/creation_date ?min-ts]]
            db episode min-ts)))))

(defn podcast-app-percentages [conn]
  (let [db (d/db conn)
        ;; Query all boosts with their app_name (get-else for missing)
        app-counts (d/q '[:find ?app (count ?e)
                          :in $
                          :where
                          [?e :boostagram/action "boost"]
                          [(get-else $ ?e :boostagram/app_name "unknown_app") ?app]]
                        db)
        total (apply + (map second app-counts))]
    (if (pos? total)
      (sort-by second #(compare %2 %1)
               (map (fn [[app cnt]]
                      [app (long (Math/round (* (/ cnt total) 100.0)))])
                    app-counts))
      [])))
