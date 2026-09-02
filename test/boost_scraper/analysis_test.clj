(ns boost-scraper.analysis-test
  (:require [boost-scraper.analysis :as analysis]
            [boost-scraper.db :as db]
            [boost-scraper.query-proxy :as qp]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d])
  (:import [java.time DayOfWeek]))

;; ============================================================================
;; Fixtures — fresh DB per test
;; ============================================================================

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-analysis-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn]
        (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

;; ============================================================================
;; Test data helpers
;; ============================================================================

(defn- make-boost
  [{:keys [sender podcast episode type sats fiat-cents fiat-usd
           app source epoch identifier]
    :or   {type :sat, app "Fountain", source "lnd"
           identifier (str "test-" (java.util.UUID/randomUUID))}}]
  (cond-> {:boostagram/action "boost"
           :boostagram/sender_name_normalized sender
           :boostagram/podcast podcast
           :boostagram/episode (or episode "Test Episode")
           :boostagram/type type
           :boostagram/app_name app
           :scraper/source source
           :invoice/creation_date epoch
           :invoice/identifier identifier}
    sats (assoc :boostagram/value_sat_total sats)
    fiat-cents (assoc :boostagram/amount_fiat_cents fiat-cents)
    fiat-usd (assoc :boostagram/fiat_value fiat-usd)))

(defn- insert [boosts]
  (d/transact! *conn* (vec boosts)))

(def lup-regex analysis/lup-regex)

;; ============================================================================
;; query-proxy: safe-read-edn
;; ============================================================================

(deftest test-safe-read-edn-valid
  (testing "parses valid EDN"
    (let [result (qp/safe-read-edn "{:find [?e] :where [[?e :db/id]]}")]
      (is (nil? (:error result)))
      (is (= '{:find [?e] :where [[?e :db/id]]} (:ok result))))))

(deftest test-safe-read-edn-invalid
  (testing "rejects invalid EDN"
    (let [result (qp/safe-read-edn "{:find [?e] :where}")]
      (is (= :read-error (:error result)))
      (is (string? (:detail result))))))

(deftest test-safe-read-edn-dangerous
  (testing "rejects reader tags"
    (let [result (qp/safe-read-edn "#java.util.Date")]
      (is (= :read-error (:error result))))))

;; ============================================================================
;; query-proxy: execute-query
;; ============================================================================

(deftest test-execute-query-valid
  (testing "executes a simple query"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged"
                          :sats 1000 :epoch 1000000})])
    (let [result (qp/execute-query *conn*
                                   "{:find [?s] :where [[?e :boostagram/sender_name_normalized ?s]]}"
                                   {})]
      (is (= :ok (:status result)))
      (is (seq (:results result)))
      (is (boolean? (:truncated result)))
      (is (pos? (:elapsed_ms result))))))

(deftest test-execute-query-invalid
  (testing "rejects query without :find"
    (let [result (qp/execute-query *conn*
                                   "{:where [[?e :db/id]]}"
                                   {})]
      (is (= :error (:status result)))
      (is (= "Query must be a map with :find and :where keys" (:detail result))))))

(deftest test-execute-query-truncation
  (testing "truncates results when limit exceeded"
    (insert (mapv (fn [i]
                    (make-boost {:sender (str "user-" i)
                                 :podcast "LINUX Unplugged"
                                 :sats 100
                                 :epoch (+ 1000000 i)}))
                  (range 20)))
    (let [result (qp/execute-query *conn*
                                   "{:find [?s] :where [[?e :boostagram/sender_name_normalized ?s]]}"
                                   {:limit 5})]
      (is (= :ok (:status result)))
      (is (= 5 (count (:results result))))
      (is (:truncated result)))))

;; ============================================================================
;; query-proxy: allowlist walker (RCE defense — 2026-09-01)
;; The 0.9.13 embedded resolver falls through to (resolve sym) → arbitrary
;; code exec + dot-form reflection. validate-query-fns must reject these.
;; ============================================================================

(deftest test-allowlist-rejects-load-string
  (testing "load-string is rejected (RCE vector)"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(clojure.core/load-string \"(+ 1 2)\") ?x]]}"
                                   {})]
      (is (= :error (:status result)))
      (is (re-find #"(?i)load-string" (:detail result))))))

(deftest test-allowlist-rejects-slurp
  (testing "slurp is rejected (file-read vector)"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(clojure.core/slurp \"/etc/hostname\") ?x]]}"
                                   {})]
      (is (= :error (:status result)))
      (is (re-find #"(?i)slurp" (:detail result))))))

(deftest test-allowlist-rejects-shell
  (testing "clojure.java.shell/sh is rejected"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(clojure.java.shell/sh \"echo\" \"hi\") ?x]]}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-rejects-eval
  (testing "eval is rejected"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(clojure.core/eval \"(foo)\") ?x]]}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-rejects-dot-form
  (testing "dot-form reflection is rejected"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(.getClass ?x) _]]}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-rejects-rule-binding
  (testing "rule bindings (% / %%) are rejected"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :in [$ %] :where [(rule ?x)]}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-rejects-apply
  (testing "apply is rejected (registry escape)"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[_ :boostagram/value_sat_total _] [(apply str \"a\") ?x]]}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-rejects-unknown-key
  (testing "unknown top-level query keys are rejected (strict)"
    (let [result (qp/execute-query *conn*
                                   "{:find [?x] :where [[?e :boostagram/value_sat_total ?x]] :having true}"
                                   {})]
      (is (= :error (:status result))))))

(deftest test-allowlist-allows-legit
  (testing "benign registry fns pass: get-else, re-matches, str, <, sum, count-distinct"
    (let [result (qp/execute-query *conn*
                                   "{:find [(count-distinct ?s)] :with [?e]
                                     :where [[?e :boostagram/sender_name_normalized ?s]
                                             [(get-else $ ?e :boostagram/episode \"x\") ?ep]
                                             [(str ?ep \"y\") ?z]
                                             [(< 0 1)]]}"
                                   {})]
      (is (= :ok (:status result))))))

(deftest test-allowlist-rejects-large-query
  (testing "oversized queries are rejected"
    (let [big-message (apply str (repeat 70000 "a"))
          result (qp/execute-query *conn*
                                   (str "{:find [?x] :where [[_ :boostagram/message \"" big-message "\"]]}")
                                   {})]
      (is (= :error (:status result))))))

;; ============================================================================
;; analysis: top-boosters
;; ============================================================================

(deftest test-top-boosters-basic
  (testing "returns top boosters sorted by total"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 5000 :epoch 1000000})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :sats 10000 :epoch 1000001})
             (make-boost {:sender "charlie" :podcast "LINUX Unplugged" :sats 2000 :epoch 1000002})])
    (let [result (analysis/top-boosters *conn* lup-regex 999999 1000010)]
      (is (= 3 (count result)))
      (is (= "bob" (first (first result))))
      (is (>= (second (first result)) (second (second result)))))))

(deftest test-top-boosters-with-limit
  (testing "respects limit parameter"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 5000 :epoch 1000000})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :sats 10000 :epoch 1000001})
             (make-boost {:sender "charlie" :podcast "LINUX Unplugged" :sats 2000 :epoch 1000002})])
    (let [result (analysis/top-boosters *conn* lup-regex 999999 1000010 2)]
      (is (= 2 (count result))))))

(deftest test-top-boosters-fiat-type
  (testing "filters by fiat boost type"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 5000 :epoch 1000000})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :type :fiat
                          :fiat-cents 2000 :fiat-usd 20.0 :sats 0 :epoch 1000001})])
    (let [result (analysis/top-boosters *conn* lup-regex 999999 1000010 nil :fiat)]
      (is (= 1 (count result)))
      (is (= "bob" (first (first result)))))))

(deftest test-top-boosters-time-range
  (testing "respects time range"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 5000 :epoch 1000000})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :sats 10000 :epoch 2000000})])
    (let [result (analysis/top-boosters *conn* lup-regex 999999 1000001)]
      (is (= 1 (count result)))
      (is (= "alice" (first (first result)))))))

(deftest test-top-boosters-episode-match
  (testing "matches via episode when podcast doesn't match regex"
    (insert [(make-boost {:sender "alice" :podcast "Some Other Show"
                          :episode "LINUX Unplugged Ep 100"
                          :sats 500 :epoch 1000000})])
    (let [result (analysis/top-boosters *conn* lup-regex 999999 1000001)]
      (is (= 1 (count result)))
      (is (= "alice" (first (first result)))))))

;; ============================================================================
;; analysis: boost-counts-by-day-of-week
;; ============================================================================

(deftest test-boost-counts-by-dow
  (testing "returns day-of-week frequencies"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 1000 :epoch 1775458800})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :sats 1000 :epoch 1775458800})])
    (let [result (analysis/boost-counts-by-day-of-week *conn* lup-regex)]
      (is (map? result))
      (is (contains? result DayOfWeek/MONDAY))
      (is (= 2 (get result DayOfWeek/MONDAY))))))

(deftest test-boost-counts-by-dow-with-type
  (testing "filters by boost type"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 1000 :epoch 1775458800})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :type :fiat
                          :fiat-cents 500 :fiat-usd 5.0 :sats 0 :epoch 1775458800})])
    (let [result (analysis/boost-counts-by-day-of-week *conn* lup-regex :sat)]
      (is (= 1 (get result DayOfWeek/MONDAY))))))

;; ============================================================================
;; analysis: monday-boost-summary
;; ============================================================================

(deftest test-monday-boost-summary
  (testing "returns summary with correct structure"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 1000 :epoch 1775458800})])
    (let [result (analysis/monday-boost-summary *conn* lup-regex)]
      (is (contains? result :per-day-of-week))
      (is (contains? result :total-boosts))
      (is (contains? result :total-weeks))
      (is (contains? result :weeks-with-monday))
      (is (contains? result :weeks-without-monday))
      (is (= 1 (:total-boosts result)))
      (is (= 1 (:weeks-with-monday result))))))

;; ============================================================================
;; analysis: top-booster-per-month
;; ============================================================================

(deftest test-top-booster-per-month
  (testing "returns monthly leaderboard"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :sats 5000 :epoch 1775458800})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :sats 10000 :epoch 1775458801})])
    (let [result (analysis/top-booster-per-month *conn* lup-regex)]
      (is (map? result))
      (is (contains? result "2026-04"))
      (let [[sender total] (get result "2026-04")]
        (is (= "bob" sender))
        (is (= 10000 total))))))

(deftest test-top-booster-per-month-fiat
  (testing "aggregates fiat cents"
    (insert [(make-boost {:sender "alice" :podcast "LINUX Unplugged" :type :fiat
                          :fiat-cents 1000 :fiat-usd 10.0 :sats 0 :epoch 1775458800})
             (make-boost {:sender "bob" :podcast "LINUX Unplugged" :type :fiat
                          :fiat-cents 3000 :fiat-usd 30.0 :sats 0 :epoch 1775458801})])
    (let [result (analysis/top-booster-per-month *conn* lup-regex :fiat)]
      (is (contains? result "2026-04"))
      (let [[sender total] (get result "2026-04")]
        (is (= "bob" sender))
        (is (= 3000 total))))))

;; ============================================================================
;; analysis: app-percentages
;; ============================================================================

(deftest test-app-percentages
  (testing "returns app distribution"
    (insert [(make-boost {:sender "a" :podcast "LINUX Unplugged" :sats 100 :epoch 1000000 :app "Fountain"})
             (make-boost {:sender "b" :podcast "LINUX Unplugged" :sats 100 :epoch 1000001 :app "Fountain"})
             (make-boost {:sender "c" :podcast "LINUX Unplugged" :sats 100 :epoch 1000002 :app "Podverse"})])
    (let [result (analysis/app-percentages *conn*)]
      (is (seq result))
      (let [fountain-pct (second (first (filter #(= "Fountain" (first %)) result)))]
        (is (< 60 fountain-pct 70))))))

;; ============================================================================
;; analysis: empty DB
;; ============================================================================

(deftest test-empty-db
  (testing "all functions handle empty DB gracefully"
    (is (= [] (analysis/top-boosters *conn* lup-regex 0 999999999)))
    (is (= {} (analysis/boost-counts-by-day-of-week *conn* lup-regex)))
    (is (= {} (:per-day-of-week (analysis/monday-boost-summary *conn* lup-regex))))
    (is (= {} (analysis/top-booster-per-month *conn* lup-regex)))
    (is (= [] (analysis/app-percentages *conn*)))))
