(ns boost-scraper.analysis
  "Ad-hoc analysis queries for the boost scraper database.
   Run from REPL — does not touch any existing code or data.
   Uses query patterns matching boosties.clj and reports.clj."
  (:require [boost-scraper.core :as core]
            [boost-scraper.db :as db]
            [boost-scraper.shows :as shows]
            [datalevin.core :as d])
  (:import [java.time Instant ZoneId DayOfWeek]
           [java.time.temporal IsoFields]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

;; ---------------------------------------------------------------------------
;; Base query helpers — all per-show queries compose from here
;; ---------------------------------------------------------------------------

(defn- boost-timestamps
  "Returns sequence of :invoice/creation_date for boosts matching `regex`.
   Used by all downstream analysis functions."
  [conn regex]
  (->> (d/q '[:find ?cd
              :in $ ?regex
              :where
              [?e :boostagram/action "boost"]
              [?e :boostagram/podcast ?podcast]
              [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
              (or [(re-matches ?regex ?podcast) _]
                  [(re-matches ?regex ?episode) _])
              [?e :invoice/creation_date ?cd]]
            (d/db conn) regex)
       (map first)))

(defn- boost-sender-totals
  "Returns sequence of [sender-name total-sats] for boosts matching `regex`
   within the optional time range [`start` `end`] (epoch seconds)."
  ([conn regex]
   (d/q '[:find ?sender (sum ?sats)
          :in $ ?regex
          :where
          [?e :boostagram/action "boost"]
          [?e :boostagram/podcast ?podcast]
          [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
          (or [(re-matches ?regex ?podcast) _]
              [(re-matches ?regex ?episode) _])
          [?e :invoice/creation_date ?cd]
          [?e :boostagram/value_sat_total ?sats]
          [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]]
        (d/db conn) regex))
  ([conn regex start end]
   (d/q '[:find ?sender (sum ?sats)
          :in $ ?regex ?start ?end
          :where
          [?e :boostagram/action "boost"]
          [?e :boostagram/podcast ?podcast]
          [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
          (or [(re-matches ?regex ?podcast) _]
              [(re-matches ?regex ?episode) _])
          [?e :invoice/creation_date ?cd]
          [(<= ?start ?cd ?end)]
          [?e :boostagram/value_sat_total ?sats]
          [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]]
        (d/db conn) regex start end)))

;; ---------------------------------------------------------------------------
;; Time helpers
;; ---------------------------------------------------------------------------

(defn- epoch->dow [epoch-seconds]
  (.getDayOfWeek (.atZone (Instant/ofEpochSecond epoch-seconds) la-zone)))

;; ---------------------------------------------------------------------------
;; Per-show top boosters
;; ---------------------------------------------------------------------------

(defn top-boosters
  "Returns top N boosters for `show-regex` within [`start` `end`] epoch range
   as sorted [[sender-name total-sats] ...]. No limit = all boosters."
  ([conn show-regex start end]
   (->> (boost-sender-totals conn show-regex start end)
        (sort-by second >)))
  ([conn show-regex start end n]
   (take n (top-boosters conn show-regex start end))))

;; ---------------------------------------------------------------------------
;; Monday / day-of-week analysis
;; ---------------------------------------------------------------------------

(defn boost-counts-by-day-of-week
  "Returns map of DayOfWeek -> boost count for all boosts matching `regex`."
  [conn regex]
  (->> (boost-timestamps conn regex)
       (map epoch->dow)
       frequencies))

(defn monday-boost-summary
  "Returns {:per-day-of-week ... :total-weeks N :weeks-with-monday N :weeks-without N
            :weeks-without-monday-list [...]} for boosts matching `regex`."
  [conn regex]
  (letfn [(epoch->iso-week [epoch-seconds]
            (let [ld (.toLocalDate (.atZone (Instant/ofEpochSecond epoch-seconds) la-zone))]
              [(.get ld IsoFields/WEEK_BASED_YEAR)
               (.get ld IsoFields/WEEK_OF_WEEK_BASED_YEAR)]))
          (iso-week-label [[year week]]
            (str year "-W" (format "%02d" week)))]
    (let [timestamps (boost-timestamps conn regex)
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
       :weeks-without-monday-list (sort (map first without-monday))})))

(defn print-monday-summary
  "Pretty-print Monday boost analysis. Use: (print-monday-summary conn lup-regex)"
  [conn regex]
  (let [{:keys [per-day-of-week total-boosts total-weeks
                weeks-with-monday weeks-without-monday
                weeks-without-monday-list]} (monday-boost-summary conn regex)]
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
        (println "  " w)))))

;; ---------------------------------------------------------------------------
;; Per-month leaderboard
;; ---------------------------------------------------------------------------

(defn top-booster-per-month
  "Returns sorted-map of \"YYYY-MM\" -> [sender total-sats] for each month
   with boosts matching `regex`."
  [conn regex]
  (let [raw (d/q '[:find ?cd ?sender ?sats
                   :in $ ?regex
                   :where
                   [?e :boostagram/action "boost"]
                   [?e :boostagram/podcast ?podcast]
                   [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                   (or [(re-matches ?regex ?podcast) _]
                       [(re-matches ?regex ?episode) _])
                   [?e :invoice/creation_date ?cd]
                   [?e :boostagram/value_sat_total ?sats]
                   [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]]
                 (d/db conn) regex)]
    (->> raw
         (group-by (fn [[cd _ _]]
                     (let [ld (.toLocalDate (.atZone (Instant/ofEpochSecond cd) la-zone))]
                       (str (.getYear ld) "-" (format "%02d" (.getMonthValue ld))))))
         (reduce-kv (fn [acc month boosts]
                      (let [totals (->> boosts
                                        (group-by #(nth % 1))
                                        (reduce-kv (fn [m sender entries]
                                                     (assoc m sender (reduce + (map #(nth % 2) entries)))) {}))
                            top (first (sort-by val > totals))]
                        (assoc acc month top)))
                    (sorted-map)))))

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

  (def conn (d/get-conn "/dev/shm/nodecan" db/schema))

  ;; Top 5 for LUP in April 2026
  (analysis/top-boosters conn analysis/lup-regex
                         (core/->epoch #inst "2026-04-01T07:00:00Z")
                         (core/->epoch #inst "2026-05-01T06:59:00Z")
                         5)

  ;; Full leaderboard for TWIB in April 2026
  (analysis/top-boosters conn analysis/twib-regex
                         (core/->epoch #inst "2026-04-01T07:00:00Z")
                         (core/->epoch #inst "2026-05-01T06:59:00Z"))

  ;; Monday analysis for Launch
  (analysis/print-monday-summary conn analysis/launch-regex)

  ;; Per-month leaderboard for all shows
  (analysis/top-booster-per-month conn (re-pattern ".*"))

  (d/close conn))
