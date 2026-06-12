(ns boost-scraper.analysis
  "Ad-hoc analysis queries for the boost scraper database.
   Run from REPL — does not touch any existing code or data.
   Uses query patterns matching boosties.clj and reports.clj."
  (:require [boost-scraper.db :as db]
            [boost-scraper.shows :as shows]
            [datalevin.core :as d])
  (:import [java.time Instant ZoneId DayOfWeek]
           [java.time.temporal IsoFields]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(def all-regex (re-pattern ".*"))

;; ---------------------------------------------------------------------------
;; Private query builders — data-driven, not form-duplicated
;; ---------------------------------------------------------------------------

(defn- or-clause
  "Build the (or ...) clause for podcast/episode matching."
  []
  (list 'or
        '[(re-matches ?regex ?podcast) _]
        '[(re-matches ?regex ?episode) _]))

(defn- base-in
  "Build the :in vector for Datalog queries."
  [time-range? boost-type]
  (cond-> '[$ ?regex]
    time-range? (into '[?start ?end])
    boost-type (conj '?boost-type)))

(defn- base-params
  "Build the runtime params vector matching the :in vector."
  [regex start end boost-type]
  (cond-> [regex]
    (some? start) (into [start end])
    (some? boost-type) (conj boost-type)))

(defn- build-sender-totals-query
  "Build query map for sender totals. Dispatches on boost-type for aggregation."
  [boost-type time-range?]
  (let [find-clause (case boost-type
                      :fiat '[?sender (sum ?cents)]
                      :member-free '[?sender (count ?e)]
                      '[?sender (sum ?sats)])
        amount-clause (case boost-type
                        :fiat '[?e :boostagram/amount_fiat_cents ?cents]
                        :member-free nil
                        '[?e :boostagram/value_sat_total ?sats])
        type-clause (when boost-type '[?e :boostagram/type ?boost-type])
        where (cond-> '[[?e :boostagram/action "boost"]
                        [?e :boostagram/podcast ?podcast]
                        [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]]
                true (conj (or-clause))
                true (conj '[?e :invoice/creation_date ?cd])
                time-range? (conj '[(<= ?start ?cd ?end)])
                amount-clause (conj amount-clause)
                true (conj '[(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender])
                type-clause (conj type-clause))]
    {:find find-clause
     :in (base-in time-range? boost-type)
     :where where}))

(defn- build-monthly-query
  "Build query for per-month leaderboard. Returns [cd sender amount] triples."
  [boost-type]
  (let [find-clause (case boost-type
                      :fiat '[?cd ?sender ?cents]
                      :member-free '[?cd ?sender ?e]
                      '[?cd ?sender ?sats])
        amount-clause (case boost-type
                        :fiat '[?e :boostagram/amount_fiat_cents ?cents]
                        :member-free nil
                        '[?e :boostagram/value_sat_total ?sats])
        type-clause (when boost-type '[?e :boostagram/type ?boost-type])
        where (cond-> '[[?e :boostagram/action "boost"]
                        [?e :boostagram/podcast ?podcast]
                        [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]]
                true (conj (or-clause))
                true (conj '[?e :invoice/creation_date ?cd])
                amount-clause (conj amount-clause)
                true (conj '[(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender])
                type-clause (conj type-clause))]
    {:find find-clause
     :in (base-in false boost-type)
     :where where}))

;; ---------------------------------------------------------------------------
;; Private helpers
;; ---------------------------------------------------------------------------

(defn- boost-timestamps
  "Returns sequence of :invoice/creation_date for boosts matching regex.
   boost-type: nil (all), :sat, :fiat, :member-free"
  ([conn regex]
   (boost-timestamps conn regex nil))
  ([conn regex boost-type]
   (let [or-c (or-clause)
         type-c (when boost-type '[?e :boostagram/type ?boost-type])
         where (cond-> '[[?e :boostagram/action "boost"]
                         [?e :boostagram/podcast ?podcast]
                         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]]
                 true (conj or-c)
                 true (conj '[?e :invoice/creation_date ?cd])
                 type-c (conj type-c))
         in (base-in false boost-type)
         query {:find '[?cd] :with '[?e] :in in :where where}
         params (base-params regex nil nil boost-type)]
     (->> (apply d/q query (d/db conn) params)
          (map first)))))

(defn- boost-sender-totals
  "Returns sequence of [sender-name total] for boosts matching regex.
   Aggregation: sat/nil → sats, fiat → cents, member-free → count."
  ([conn regex]
   (boost-sender-totals conn regex nil nil nil))
  ([conn regex start end]
   (boost-sender-totals conn regex start end nil))
  ([conn regex start end boost-type]
   (let [query (build-sender-totals-query boost-type (some? start))
         params (base-params regex start end boost-type)]
     (apply d/q query (d/db conn) params))))

(defn- epoch->dow [epoch-seconds]
  (.getDayOfWeek (.atZone (Instant/ofEpochSecond epoch-seconds) la-zone)))

;; ---------------------------------------------------------------------------
;; Per-show top boosters
;; ---------------------------------------------------------------------------

(defn top-boosters
  "Returns top N boosters for show-regex within [start end] epoch range
   as sorted [[sender-name total] ...]. n nil = no limit.
   boost-type: nil (all), :sat, :fiat, :member-free"
  ([conn show-regex start end]
   (top-boosters conn show-regex start end nil nil))
  ([conn show-regex start end n]
   (top-boosters conn show-regex start end n nil))
  ([conn show-regex start end n boost-type]
   (let [sorted (->> (boost-sender-totals conn show-regex start end boost-type)
                     (sort-by second >))]
     (if n (take n sorted) sorted))))

;; ---------------------------------------------------------------------------
;; Monday / day-of-week analysis
;; ---------------------------------------------------------------------------

(defn boost-counts-by-day-of-week
  "Returns map of DayOfWeek -> boost count for boosts matching regex."
  ([conn regex]
   (boost-counts-by-day-of-week conn regex nil))
  ([conn regex boost-type]
   (->> (boost-timestamps conn regex boost-type)
        (map epoch->dow)
        frequencies)))

(defn monday-boost-summary
  "Returns {:per-day-of-week ... :total-weeks N :weeks-with-monday N :weeks-without N
            :weeks-without-monday-list [...]} for boosts matching regex."
  ([conn regex]
   (monday-boost-summary conn regex nil))
  ([conn regex boost-type]
   (letfn [(epoch->iso-week [epoch-seconds]
             (let [ld (.toLocalDate (.atZone (Instant/ofEpochSecond epoch-seconds) la-zone))]
               [(.get ld IsoFields/WEEK_BASED_YEAR)
                (.get ld IsoFields/WEEK_OF_WEEK_BASED_YEAR)]))
           (iso-week-label [[year week]]
             (str year "-W" (format "%02d" week)))]
     (let [timestamps (boost-timestamps conn regex boost-type)
           per-dow (->> timestamps
                        (map epoch->dow)
                        frequencies
                        (sort-by (fn [[dow _]] (.getValue dow))))
           weeks (->> timestamps
                      (reduce (fn [acc epoch]
                                (let [dow   (epoch->dow epoch)
                                      label (iso-week-label (epoch->iso-week epoch))]
                                  (update-in acc [label :days] (fnil conj #{}) dow)))
                              {}))
           [with-monday without-monday] ((juxt filter remove)
                                         (fn [[_ {:keys [days]}]]
                                           (contains? days DayOfWeek/MONDAY))
                                         weeks)]
       {:per-day-of-week      (into (sorted-map-by #(compare (.getValue %1) (.getValue %2))) per-dow)
        :total-boosts         (count timestamps)
        :total-weeks          (count weeks)
        :weeks-with-monday    (count with-monday)
        :weeks-without-monday (count without-monday)
        :weeks-without-monday-list (sort (map first without-monday))}))))

(defn print-monday-summary
  "Pretty-print Monday boost analysis. Use: (print-monday-summary conn lup-regex)"
  ([conn regex]
   (print-monday-summary conn regex nil))
  ([conn regex boost-type]
   (let [{:keys [per-day-of-week total-boosts total-weeks
                 weeks-with-monday weeks-without-monday
                 weeks-without-monday-list]} (monday-boost-summary conn regex boost-type)]
     (println "=== Boost Distribution by Day of Week ===")
     (doseq [[dow cnt] per-day-of-week]
       (println (format "  %-10s %d" (str dow) cnt)))
     (println)
     (println (format "Total boosts: %d" total-boosts))
     (println (format "Total weeks in dataset: %d" total-weeks))
     (println (format "Weeks with Monday boosts: %d  (%.1f%%)"
                      weeks-with-monday
                      (if (pos? total-weeks) (* 100.0 (/ weeks-with-monday total-weeks)) 0.0)))
     (println (format "Weeks WITHOUT Monday boosts: %d  (%.1f%%)"
                      weeks-without-monday
                      (if (pos? total-weeks) (* 100.0 (/ weeks-without-monday total-weeks)) 0.0)))
     (when (seq weeks-without-monday-list)
       (println)
       (println "Weeks with zero Monday boosts:")
       (doseq [w weeks-without-monday-list]
         (println "  " w))))))

;; ---------------------------------------------------------------------------
;; Per-month leaderboard
;; ---------------------------------------------------------------------------

(defn- aggregate-monthly-boosts
  "Aggregate sender totals for a month's worth of [cd sender amount] triples.
   sat/nil → sum sats, fiat → sum cents, member-free → count."
  [boost-type boosts]
  (->> boosts
       (group-by second)
       (reduce-kv
        (fn [m sender entries]
          (let [total (case boost-type
                        :member-free (count entries)
                        (reduce + (map (fn [e] (nth e 2)) entries)))]
            (assoc m sender total)))
        {})))

(defn top-booster-per-month
  "Returns sorted-map of \"YYYY-MM\" -> [sender total] for each month.
   Aggregation: sat/nil → sats, fiat → cents, member-free → count."
  ([conn regex]
   (top-booster-per-month conn regex nil))
  ([conn regex boost-type]
   (let [query (build-monthly-query boost-type)
         params (base-params regex nil nil boost-type)
         raw (apply d/q query (d/db conn) params)]
     (->> raw
          (group-by (fn [[cd _ _]]
                      (let [ld (.toLocalDate (.atZone (Instant/ofEpochSecond cd) la-zone))]
                        (str (.getYear ld) "-" (format "%02d" (.getMonthValue ld))))))
          (reduce-kv
           (fn [acc month boosts]
             (let [totals (aggregate-monthly-boosts boost-type boosts)
                   top    (first (sort-by val > totals))]
               (assoc acc month top)))
           (sorted-map))))))

;; ---------------------------------------------------------------------------
;; App percentages
;; ---------------------------------------------------------------------------

(defn app-percentages
  "Returns [[app-name percentage] ...] for all boosts, sorted descending."
  [conn]
  (let [raw (d/q '[:find ?app (count ?e)
                   :where
                   [?e :boostagram/action "boost"]
                   [?e :boostagram/app_name ?app]]
                 (d/db conn))
        total (reduce + (map second raw))]
    (->> raw
         (map (fn [[app cnt]]
                [app (if (pos? total) (* 100.0 (/ cnt total)) 0.0)]))
         (sort-by second >))))

;; ---------------------------------------------------------------------------
;; Convenience: show-specific regexes from the registry
;; ---------------------------------------------------------------------------

(def lup-regex
  (re-pattern (shows/regex-for "lup")))

(def twib-regex
  (re-pattern (shows/regex-for "twib")))

(def launch-regex
  (re-pattern (shows/regex-for "launch")))

;; ---------------------------------------------------------------------------
;; REPL usage
;; ---------------------------------------------------------------------------
(comment
  (require '[boost-scraper.analysis :as analysis] :reload)

  (defn ->epoch [inst] (-> inst .toInstant .getEpochSecond))

  (def conn (d/get-conn "/dev/shm/nodecan" db/schema))

  ;; Top 5 for LUP in April 2026 (all types)
  (analysis/top-boosters conn analysis/lup-regex
                         (->epoch #inst "2026-04-01T07:00:00Z")
                         (->epoch #inst "2026-05-01T06:59:00Z")
                         5)

  ;; Top 5 fiat boosters for LUP in April 2026
  (analysis/top-boosters conn analysis/lup-regex
                         (->epoch #inst "2026-04-01T07:00:00Z")
                         (->epoch #inst "2026-05-01T06:59:00Z")
                         5 :fiat)

  ;; Top 5 sat boosters for LUP
  (analysis/top-boosters conn analysis/lup-regex
                         (->epoch #inst "2026-04-01T07:00:00Z")
                         (->epoch #inst "2026-05-01T06:59:00Z")
                         5 :sat)

  ;; Monday analysis for Launch (all types)
  (analysis/print-monday-summary conn analysis/launch-regex)

  ;; Monday analysis for Launch (fiat only)
  (analysis/print-monday-summary conn analysis/launch-regex :fiat)

  ;; Per-month leaderboard for all shows
  (analysis/top-booster-per-month conn (re-pattern ".*"))

  ;; Per-month leaderboard, fiat only
  (analysis/top-booster-per-month conn analysis/lup-regex :fiat)

  ;; App percentages
  (analysis/app-percentages conn)

  (d/close conn))
