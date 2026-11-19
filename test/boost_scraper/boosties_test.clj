(ns boost-scraper.boosties-test
  (:require [boost-scraper.boosties :as boosties]
            [boost-scraper.db :as db]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-boosties-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

(defn- make-boost
  [{:keys [sender podcast action sats type cents epoch identifier]
    :or   {podcast "LINUX Unplugged" action "boost" type :sat
           ;; 2026-06-01T00:00Z — comfortably inside the default 2026 window
           epoch 1780272000
           identifier (str "t-" (java.util.UUID/randomUUID))}}]
  (cond-> {:invoice/identifier identifier
           :invoice/creation_date epoch
           :boostagram/action action
           :boostagram/podcast podcast
           :boostagram/sender_name_normalized sender
           :boostagram/value_sat_total (or sats 0)
           :boostagram/type type
           :scraper/source "lnd"}
    cents (assoc :boostagram/amount_fiat_cents cents)))

(defn- insert! [rows] (d/transact! *conn* (mapv make-boost rows)))

;; ---------------------------------------------------------------------------
;; leaderboard (compat lens)
;; ---------------------------------------------------------------------------

(deftest test-leaderboard-basic
  (insert! [{:sender "alice" :sats 1000}
            {:sender "alice" :sats 500}
            {:sender "bob" :sats 300}])
  (let [rows (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"})]
    (testing "aggregates sats and counts per handle"
      (is (= [["alice" 2 1500] ["bob" 1 300]]
             (mapv (juxt :sender :count :sats) rows))))))

(deftest test-leaderboard-excludes-self-and-other-shows
  (insert! [{:sender "alice" :sats 1000}
            {:sender "chrislas" :sats 9999}
            {:sender "noblepayne" :sats 9999}
            {:sender "alice" :sats 500 :podcast "Coder Radio"}])
  (testing "self-boosts dropped by default"
    (is (= #{"alice"} (into #{} (map :sender)
                            (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"})))))
  (testing "other shows excluded by the regex"
    (is (= 1000 (:sats (first (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"}))))))
  (testing "include-self? opts back in"
    (is (= #{"alice" "chrislas" "noblepayne"}
           (into #{} (map :sender)
                 (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"
                                               :include-self? true}))))))

(deftest test-leaderboard-since-window
  (insert! [{:sender "old" :sats 100 :epoch 1600000000}
            {:sender "last-year" :sats 150 :epoch 1767225600}   ; 2026-01-01 00:00Z = 2025-12-31 16:00 LA
            {:sender "new" :sats 200 :epoch 1780272000}])       ; 2026-06-01Z
  (testing "default window is 2026 in LA: New Year's Eve Pacific is excluded"
    (is (= ["new"] (mapv :sender (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"})))))
  (testing "an explicit since overrides the default"
    (is (= #{"last-year" "new"}
           (into #{} (map :sender)
                 (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"
                                               :since-epoch 1767225600}))))))

;; ---------------------------------------------------------------------------
;; payload — sats / fiat / members / streams are separate streams
;; ---------------------------------------------------------------------------

(deftest test-payload-sats-board
  (insert! [{:sender "alice" :sats 1000}
            {:sender "alice" :sats 500}
            {:sender "bob" :sats 900}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 5})]
    (testing "front runner is the sats leader"
      (is (= "alice" (get-in p [:front-runner :handle])))
      (is (= 1500 (get-in p [:front-runner :sats]))))
    (testing "both orderings present"
      (is (= ["alice" "bob"] (mapv :handle (:by-sats p))))
      (is (= ["alice" "bob"] (mapv :handle (:by-count p)))))
    (testing "totals"
      (is (= {:events 3 :sats 2400 :fiat-cents 0 :people 2}
             (:boosts (:totals p)))))))

(deftest test-payload-fiat-is-separate
  (insert! [{:sender "alice" :sats 1000}
            {:sender "fred" :type :fiat :cents 20000}
            {:sender "fred" :type :fiat :cents 5000}
            {:sender "gina" :type :fiat :cents 7500}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 5})]
    (testing "fiat ranked by amount, expressed in cents and USD"
      (is (= ["fred" "gina"] (mapv :handle (get-in p [:fiat :by-amount]))))
      (is (= 25000 (get-in p [:fiat :by-amount 0 :fiat-cents])))
      (is (= 250.0 (get-in p [:fiat :by-amount 0 :usd]))))
    (testing "fiat never leaks into the sats boards"
      (is (= ["alice"] (mapv :handle (:by-sats p))))
      (is (= 1000 (get-in p [:totals :boosts :sats])))
      (is (= 32500 (get-in p [:totals :fiat :fiat-cents]))))
    (testing "combined is sats-only (fiat stays separate by design)"
      (is (= 1000 (get-in p [:totals :combined :sats]))))))

(deftest test-payload-members-board
  (insert! [{:sender "mel" :type :member-free :sats 0 :epoch 1780272000}
            {:sender "mel" :type :member-free :sats 0 :epoch (+ 1780272000 604800)} ; next week
            {:sender "mel" :type :member-free :sats 0 :epoch (+ 1780272000 604800 60)}
            {:sender "ned" :type :member-free :sats 0 :epoch 1780272000}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 5})]
    (testing "members ranked by count and by consistency (distinct weeks)"
      (is (= ["mel" "ned"] (mapv :handle (get-in p [:members :by-count]))))
      (is (= 3 (get-in p [:members :by-count 0 :count])))
      (is (= 2 (get-in p [:members :by-weeks 0 :weeks]))))
    (testing "member boosts carry no sats and no fiat"
      (is (= 0 (get-in p [:totals :members :sats])))
      (is (= 0 (get-in p [:totals :members :fiat-cents]))))
    (testing "member people are counted"
      (is (= 2 (get-in p [:totals :members :people]))))))

(deftest test-payload-streams-separate
  (insert! [{:sender "alice" :sats 1000}
            {:sender "carol" :action "stream" :sats 10}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 5})]
    (testing "streams have their own board"
      (is (= ["carol"] (mapv :handle (get-in p [:streams :by-sats])))))
    (testing "combined v4v includes streams but not fiat"
      (is (= 1010 (get-in p [:totals :combined :sats])))
      (is (= 2 (get-in p [:totals :combined :events]))))))

(deftest test-payload-limit-applies-to-boards-not-totals
  (insert! (for [i (range 10)] {:sender (str "user" i) :sats (inc i)}))
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 3})]
    (testing "boards truncated"
      (is (= 3 (count (:by-sats p)))))
    (testing "totals cover everyone"
      (is (= 10 (get-in p [:totals :boosts :people])))
      (is (= 55 (get-in p [:totals :boosts :sats]))))))

(deftest test-payload-defaults
  (insert! [{:sender "alice" :sats 100}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*"})]
    (testing "defaults: since 2026-01-01 (LA), limit 10"
      (is (= boosties/default-since (:since p)))
      (is (= 10 (:limit p))))))
