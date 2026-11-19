(ns boost-scraper.boosties
  "Boosties — leaderboard lens over the accounting core.

   Built on `contributions` (see docs/campaign-goal-spec.md §2) rather than its
   own queries, so the segment can never disagree with the report:
   sats, fiat and member boosts are kept as distinct money streams and the
   endpoint says which is which.

   Boards:
   - sats    : on-chain/Lightning sat boosts (integer sats)
   - fiat    : dollar-denominated web boosts (integer cents, shown as USD)
   - members : member-free boosts (presence, no per-boost value)
   - streams : ambient listen-through (sats)
   - combined: all sats-bearing events (boosts + streams), for the segment's
               'total v4v' line — never mixes fiat into sats."
  (:require [boost-scraper.contributions :as c]
            [clojure.string :as str]))

(def default-since
  "2026-01-01T00:00 America/Los_Angeles (= 2026-01-01T08:00Z). The Boosties
   window is the current year in the show's timezone: everything from Jan 1
   inclusive, i.e. nothing from 2025-12-31T23:59:59-08:00 or earlier."
  1767254400)

(def ^:private week-seconds (* 7 86400))

(defn- week-bucket [epoch] (quot (long epoch) week-seconds))

(defn- window [since-epoch]
  {:from (long (or since-epoch default-since))
   :to (quot (System/currentTimeMillis) 1000)})

(defn- with-usd [row]
  (assoc row :usd (/ (double (:fiat-cents row)) 100.0)))

(defn- aggregate
  "Group contributions into per-handle rows:
   {:handle :count :sats :fiat-cents :weeks}"
  [cs]
  (->> cs
       (group-by :contribution/handle)
       (map (fn [[handle xs]]
              {:handle handle
               :count (count xs)
               :sats (reduce + 0 (map :contribution/sats xs))
               :fiat-cents (reduce + 0 (remove nil? (map :contribution/fiat-cents xs)))
               :weeks (count (into #{} (map (comp week-bucket :contribution/occurred-at) xs)))}))
       vec))

(defn- rank
  [rows k limit]
  (take limit (sort-by (juxt k :handle) #(compare %2 %1) rows)))

(defn- summary
  "Public-facing totals shape (events/sats/fiat-cents/people)."
  [cs]
  (let [t (c/totals cs)]
    {:events (:count t)
     :sats (:sats t)
     :fiat-cents (:fiat-cents t)
     :people (:people t)}))

(defn leaderboard
  "Convenience lens: sat boosts aggregated per handle, highest sats first.
   Kept for callers/tests that want the original shape."
  [conn {:keys [regex since-epoch include-self?]}]
  (let [{:keys [from to]} (window since-epoch)]
    (->> (c/contributions conn {:from from :to to
                                :show-regex regex
                                :actions #{:boost}
                                :exclude-self? (not include-self?)})
         (filter #(= :sat (:contribution/type %)))
         aggregate
         (sort-by (juxt (comp - :sats) :handle))
         (map (fn [r] {:sender (:handle r) :count (:count r) :sats (:sats r)})))))

(defn payload
  "The full Boosties payload.

   opts :: {:regex :since-epoch :limit :include-self?}

   Sats and fiat are reported separately and never summed together; USD is
   cents/100 exactly (no price-feed conversion here, so the segment cannot
   drift with the BTC rate)."
  [conn {:keys [limit since-epoch include-self?] :or {limit 10} :as opts}]
  (let [limit (long limit)
        {:keys [from to]} (window since-epoch)
        cs (c/contributions conn {:from from :to to
                                  :show-regex (:regex opts)
                                  :actions #{:boost :stream}
                                  :exclude-self? (not include-self?)})
        by-action (group-by :contribution/action cs)
        boosts (get by-action :boost [])
        streams (get by-action :stream [])
        sat-boosts (filter #(= :sat (:contribution/type %)) boosts)
        fiat-boosts (filter #(= :fiat (:contribution/type %)) boosts)
        member-boosts (filter #(= :member-free (:contribution/type %)) boosts)
        sat-streams (filter #(= :sat (:contribution/type %)) streams)
        sat-rows (aggregate sat-boosts)
        fiat-rows (map with-usd (aggregate fiat-boosts))
        member-rows (aggregate member-boosts)
        stream-rows (aggregate sat-streams)
        t-summary summary
        sat-t (t-summary sat-boosts)
        fiat-t (t-summary fiat-boosts)
        member-t (t-summary member-boosts)
        stream-t (t-summary sat-streams)]
    {:show-regex (str (:regex opts ".*"))
     :since from
     :limit limit
     :front-runner (first (rank sat-rows :sats limit))
     :by-sats (vec (rank sat-rows :sats limit))
     :by-count (vec (rank sat-rows :count limit))
     :fiat {:by-amount (vec (rank fiat-rows :fiat-cents limit))
            :by-count (vec (rank fiat-rows :count limit))}
     :members {:by-count (vec (rank member-rows :count limit))
               :by-weeks (vec (rank member-rows :weeks limit))}
     :streams {:by-sats (vec (rank stream-rows :sats limit))
               :by-count (vec (rank stream-rows :count limit))}
     :totals {:boosts sat-t
              :fiat fiat-t
              :members member-t
              :streams stream-t
              ;; "total v4v" = every sats-bearing event; fiat stays separate
              :combined {:sats (+ (:sats sat-t) (:sats stream-t))
                         :events (+ (:events sat-t) (:events stream-t))
                         :people (:people (t-summary (concat sat-boosts sat-streams)))}}}))
