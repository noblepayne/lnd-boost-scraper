(ns boost-scraper.boosties
  "Boosties — leaderboard queries for the periodic booster segment.

   Historically a REPL-only scratch namespace (see the comment block at the
   bottom retained as history). Now a small parameterized library behind
   `GET /api/v1/boosties`, so fresh numbers are one curl away instead of a
   local Clojure session.

   Conventions, matching the pre-existing segment numbers:
   - self-boosts (chrislas / noblepayne) are excluded by default;
   - the window starts at `since` epoch seconds, default 2026-01-01 (LA);
   - boosts and streams are reported separately (they are different acts) and
     also summed into a combined v4v total;
   - amounts come from `:boostagram/value_sat_total` (integer sats)."
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

(def default-since
  "2026-01-01T00:00 America/Los_Angeles (= 2026-01-01T08:00Z). The Boosties
   window is the current year in the show's timezone: everything from Jan 1
   inclusive, i.e. nothing from 2025-12-31T23:59:59-08:00 or earlier."
  1767254400)

(def self-boosters
  "Hosts who boost their own show; excluded from public leaderboards."
  #{"chrislas" "noblepayne"})

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(def ^:private leaderboard-query
  "Per-sender event count and sat total for one action within the window.

   Plain patterns only — no get-else — so this rides indexes instead of paying
   the per-row function cost we measured at ~40x on full scans."
  '[:find ?sender (count ?e) (sum ?sats)
    :in $ ?action ?regex ?since
    :where
    [?e :boostagram/action ?action]
    [?e :invoice/creation_date ?cd]
    [(>= ?cd ?since)]
    [?e :boostagram/podcast ?podcast]
    [(re-matches ?regex ?podcast) _]
    [?e :boostagram/sender_name_normalized ?sender]
    [?e :boostagram/value_sat_total ?sats]])

(defn- normalize-regex
  "Accept a compiled Pattern or a string pattern."
  [regex]
  (cond (instance? java.util.regex.Pattern regex) regex
        (string? regex) (re-pattern regex)
        :else (re-pattern "(?i).*")))

(defn leaderboard
  "Rows of {:sender :count :sats} for `action` (\"boost\" or \"stream\"),
   since `since-epoch`, for shows whose podcast name matches `regex`.
   Self-boosts are dropped unless `include-self?`."
  [conn {:keys [regex action since-epoch include-self?]
         :or   {action "boost" include-self? false}}]
  (let [rows (d/q leaderboard-query
                  (d/db conn)
                  action
                  (normalize-regex regex)
                  (long (or since-epoch default-since)))]
    (->> rows
         (map (fn [[sender cnt sats]]
                {:sender sender :count (long cnt) :sats (long sats)}))
         (remove (fn [{:keys [sender]}]
                   (and (not include-self?)
                        (contains? self-boosters (some-> sender str/lower-case)))))
         (filter (fn [{:keys [sender]}] (not= sender "N/A"))))))

(defn- rank
  "Top `limit` rows by `key`, descending."
  [rows k limit]
  (take limit (sort-by k #(compare %2 %1) rows)))

(defn- totals
  [rows]
  {:events (reduce + 0 (map :count rows))
   :sats (reduce + 0 (map :sats rows))
   :people (count rows)})

(defn payload
  "The full Boosties payload used by the segment and the HTTP endpoint.

   opts :: {:regex :since-epoch :limit :include-self?}

   Returns
   {:show-regex :since :limit
    :front-runner {:sender :sats :count}
    :by-sats [...] :by-count [...]
    :streams {:by-sats [...] :by-count [...]}
    :totals {:boosts {...} :streams {...} :combined {...}}}"
  [conn {:keys [limit since-epoch] :or {limit 10} :as opts}]
  (let [limit (long limit)
        boosts (leaderboard conn (assoc opts :action "boost"))
        streams (leaderboard conn (assoc opts :action "stream"))
        by-sats (rank boosts :sats limit)
        by-count (rank boosts :count limit)
        stream-by-sats (rank streams :sats limit)
        stream-by-count (rank streams :count limit)
        boost-totals (totals boosts)
        stream-totals (totals streams)
        ;; Combined people: a person who both boosts and streams is one person.
        combined (->> (concat boosts streams)
                      (group-by :sender)
                      (reduce-kv (fn [acc sender xs]
                                   (assoc acc sender
                                          {:sats (reduce + 0 (map :sats xs))
                                           :count (reduce + 0 (map :count xs))}))
                                 {})
                      (map (fn [[sender v]] (assoc v :sender sender))))]
    {:show-regex (str (normalize-regex (:regex opts)))
     :since (long (or since-epoch default-since))
     :limit limit
     :front-runner (first by-sats)
     :by-sats (vec by-sats)
     :by-count (vec by-count)
     :streams {:by-sats (vec stream-by-sats)
               :by-count (vec stream-by-count)}
     :totals {:boosts boost-totals
              :streams stream-totals
              :combined {:sats (+ (:sats boost-totals) (:sats stream-totals))
                         :events (+ (:events boost-totals) (:events stream-totals))
                         :people (count combined)}}}))

(comment
  ;; Historical REPL scratch (kept for the original segment definitions).
  ;; Superseded by `payload` + GET /api/v1/boosties.
  ;;
  ;;   (def conn core/nodecan-conn)
  ;;   (payload conn {:regex "(?i).*unplugged.*" :since-epoch 1735689600 :limit 10})
  ;;
  ;; Original ad-hoc queries lived here (boosties-v1/v2, by-total-amount,
  ;; by-number, total-v4v, boosties-clients). Their semantics are folded into
  ;; `leaderboard` + `payload`; the numbers they produced are reproduced by:
  ;;   boosts  -> leaderboard conn {:action "boost"}
  ;;   streams -> leaderboard conn {:action "stream"})
  )
