(ns boost-scraper.boosties-test
  (:require [boost-scraper.boosties :as boosties]
            [boost-scraper.db :as db]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

;; ============================================================================
;; Fixtures
;; ============================================================================

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
  [{:keys [sender podcast action sats epoch identifier]
    :or   {podcast "LINUX Unplugged" action "boost" epoch 1767225600
           identifier (str "t-" (java.util.UUID/randomUUID))}}]
  {:invoice/identifier identifier
   :invoice/creation_date epoch
   :boostagram/action action
   :boostagram/podcast podcast
   :boostagram/sender_name_normalized sender
   :boostagram/value_sat_total sats
   :boostagram/type :sat
   :scraper/source "lnd"})

(defn- insert! [rows]
  (d/transact! *conn* (mapv make-boost rows)))

;; ============================================================================
;; leaderboard
;; ============================================================================

(deftest test-leaderboard-basic
  (insert! [{:sender "alice" :sats 1000}
            {:sender "alice" :sats 500}
            {:sender "bob" :sats 300}])
  (let [rows (boosties/leaderboard *conn* {:regex "(?i).*unplugged.*"})]
    (testing "aggregates sats and counts per sender"
      (is (= {"alice" {:sender "alice" :count 2 :sats 1500}
              "bob" {:sender "bob" :count 1 :sats 300}}
             (into {} (map (juxt :sender identity) rows)))))))

(deftest test-leaderboard-excludes-self-and-other-shows
  (insert! [{:sender "alice" :sats 1000}
            {:sender "chrislas" :sats 9999}
            {:sender "noblepayne" :sats 9999}
            {:sender "alice" :sats 500 :podcast "Coder Radio"}])
  (testing "self-boosts dropped by default"
    (is (= #{"alice"}
           (into #{} (map :sender)
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
            {:sender "new" :sats 200 :epoch 1767225600}])
  (testing "window filters on creation epoch"
    (is (= ["new"]
           (mapv :sender (boosties/leaderboard *conn*
                                               {:regex "(?i).*unplugged.*"
                                                :since-epoch 1735689600}))))))

(deftest test-leaderboard-action-separation
  (insert! [{:sender "alice" :sats 1000 :action "boost"}
            {:sender "alice" :sats 25 :action "stream"}])
  (testing "boost and stream are distinct boards"
    (is (= 1000 (:sats (first (boosties/leaderboard *conn*
                                                    {:regex "(?i).*unplugged.*"
                                                     :action "boost"})))))
    (is (= 25 (:sats (first (boosties/leaderboard *conn*
                                                  {:regex "(?i).*unplugged.*"
                                                   :action "stream"})))))))

;; ============================================================================
;; payload
;; ============================================================================

(deftest test-payload-shape
  (insert! [{:sender "alice" :sats 1000}
            {:sender "alice" :sats 500}
            {:sender "bob" :sats 900}
            {:sender "carol" :sats 10 :action "stream"}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 5})]
    (testing "front runner is the sats leader"
      (is (= "alice" (get-in p [:front-runner :sender])))
      (is (= 1500 (get-in p [:front-runner :sats]))))
    (testing "both orderings present"
      (is (= ["alice" "bob"] (mapv :sender (:by-sats p))))
      (is (= ["alice" "bob"] (mapv :sender (:by-count p)))))
    (testing "streams reported separately"
      (is (= ["carol"] (mapv :sender (get-in p [:streams :by-sats])))))
    (testing "totals roll up correctly"
      (is (= {:events 3 :sats 2400 :people 2} (:boosts (:totals p))))
      (is (= {:events 1 :sats 10 :people 1} (:streams (:totals p))))
      (is (= {:sats 2410 :events 4 :people 3} (:combined (:totals p)))))))

(deftest test-payload-limit-applies-to-boards-not-totals
  (insert! (for [i (range 10)] {:sender (str "user" i) :sats (inc i)}))
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*" :limit 3})]
    (testing "boards are truncated"
      (is (= 3 (count (:by-sats p))))
      (is (= 3 (count (:by-count p)))))
    (testing "totals cover everyone"
      (is (= 10 (get-in p [:totals :boosts :people])))
      (is (= 55 (get-in p [:totals :boosts :sats]))))))

(deftest test-payload-defaults
  (insert! [{:sender "alice" :sats 100}])
  (let [p (boosties/payload *conn* {:regex "(?i).*unplugged.*"})]
    (testing "defaults: since 2025-01-01, limit 10"
      (is (= boosties/default-since (:since p)))
      (is (= 10 (:limit p))))))
