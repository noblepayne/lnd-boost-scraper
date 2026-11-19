(ns boost-scraper.contributions-test
  (:require [boost-scraper.contributions :as c]
            [boost-scraper.db :as db]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-contrib-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

(def T0 1767254400)   ; 2026-01-01 00:00 LA
(def T1 (+ T0 86400)) ; a day later

(defn- boost
  [{:keys [sender sats type cents action episode oid iid epoch]
    :or   {sender "alice" sats 1000 type :sat action "boost"
           episode "700" epoch (inc T0)
           iid (str "inv-" (java.util.UUID/randomUUID))}}]
  (cond-> {:invoice/identifier iid
           :invoice/creation_date epoch
           :boostagram/action action
           :boostagram/podcast "LINUX Unplugged"
           :boostagram/episode episode
           :boostagram/sender_name_normalized sender
           :boostagram/type type
           :boostagram/value_sat_total sats}
    cents (assoc :boostagram/amount_fiat_cents cents)
    oid (assoc :boostagram/zaprite_order_id oid)))

(defn- insert! [& rows] (d/transact! *conn* (vec rows)))

(defn- all-contribs [opts]
  (c/contributions *conn* (merge {:from T0 :to T1} opts)))

(deftest test-window-is-required
  (testing "an unbounded window is refused — sums must be reproducible"
    (is (thrown? clojure.lang.ExceptionInfo
                 (c/contributions *conn* {:from T0})))))

(deftest test-basic-contribution-shape
  (insert! (boost {:sender "alice" :sats 1500 :episode "700"}))
  (let [[x :as cs] (all-contribs {})]
    (testing "one contribution, integer sats, no floats"
      (is (= 1 (count cs)))
      (is (= :boost (:contribution/action x)))
      (is (= :sat (:contribution/type x)))
      (is (= 1500 (:contribution/sats x)))
      (is (nil? (:contribution/fiat-cents x)))
      (is (= "alice" (:contribution/handle x)))
      (is (= "700" (:contribution/episode x))))))

(deftest test-sats-and-cents-never-conflated
  (insert! (boost {:sender "alice" :sats 1500})
           (boost {:sender "bob" :sats 0 :type :fiat :cents 2000 :iid "fiat-1"}))
  (let [t (c/totals (all-contribs {}))]
    (testing "totals keep the two money streams separate"
      (is (= 1500 (:sats t)))
      (is (= 2000 (:fiat-cents t)))
      (is (= 2 (:count t))))
    (testing "a fiat contribution never adds sats"
      (is (= 0 (->> (all-contribs {}) (filter #(= :fiat (:contribution/type %))) first :contribution/sats))))))

(deftest test-payment-identity-dedupe
  (testing "same zaprite order id counted once"
    (insert! (boost {:sender "alice" :sats 500 :oid "od_dup" :iid "a"})
             (boost {:sender "alice" :sats 500 :oid "od_dup" :iid "b" :epoch (+ T0 60)}))
    (let [cs (all-contribs {})]
      (is (= 1 (count cs)) "duplicate payment collapsed")
      (is (= 500 (:contribution/sats (first cs))))))
  (testing "same invoice identifier counted once"
    (insert! (boost {:sender "bob" :sats 700 :iid "inv-dup" :epoch (+ T0 120)})
             (boost {:sender "bob" :sats 700 :iid "inv-dup" :epoch (+ T0 180)}))
    (let [bobs (filter #(= "bob" (:contribution/handle %)) (all-contribs {}))]
      (is (= 1 (count bobs))))))

(deftest test-bare-invoice-shells-are-not-contributions
  (testing "an untyped entity with no value/sender is not support
            (the live shape of a web-boost LND invoice alongside its fiat order)"
    (d/transact! *conn* [{:invoice/identifier "343910"
                          :invoice/creation_date (inc T0)
                          :boostagram/action "boost"
                          :boostagram/podcast "This Week in Bitcoin"
                          :boostagram/value_sat_total 0}])
    (is (empty? (all-contribs {})))))

(deftest test-self-and-auto-handles
  (insert! (boost {:sender "alice" :sats 100})
           (boost {:sender "chrislas" :sats 9999 :iid "self-1"})
           (boost {:sender "user183420225038226" :sats 50 :iid "auto-1"}))
  (testing "self-boosts excluded by default"
    (is (= #{"alice" "user183420225038226"}
           (into #{} (map :contribution/handle) (all-contribs {})))))
  (testing "exclude-self? false opts them back in"
    (is (= #{"alice" "chrislas" "user183420225038226"}
           (into #{} (map :contribution/handle) (all-contribs {:exclude-self? false})))))
  (testing "include-auto? false drops machine handles"
    (is (= #{"alice"} (into #{} (map :contribution/handle)
                            (all-contribs {:include-auto? false}))))))

(deftest test-actions-and-show-filters
  (insert! (boost {:sender "alice" :sats 100})
           (boost {:sender "alice" :sats 5 :action "stream" :iid "s-1"})
           (assoc (boost {:sender "bob" :sats 300 :iid "c-1"})
                  :boostagram/podcast "Coder Radio"))
  (testing "boosts only by default"
    (is (= #{:boost} (into #{} (map :contribution/action) (all-contribs {})))))
  (testing "streams available when asked"
    (is (= #{:boost :stream} (into #{} (map :contribution/action)
                                   (all-contribs {:actions #{:boost :stream}})))))
  (testing "show regex scopes the set"
    (is (= #{"alice"} (into #{} (map :contribution/handle)
                            (all-contribs {:show-regex "(?i).*unplugged.*"}))))))

(deftest test-by-handle-ranking
  (insert! (boost {:sender "alice" :sats 100 :iid "1"})
           (boost {:sender "alice" :sats 300 :iid "2"})
           (boost {:sender "bob" :sats 900 :iid "3"}))
  (let [rows (c/by-handle (all-contribs {}))]
    (testing "ranked by sats, aggregated per handle"
      (is (= ["bob" "alice"] (mapv :handle rows)))
      (is (= 400 (:sats (second rows))))
      (is (= 2 (:count (second rows)))))))

(deftest test-deterministic-ordering
  (insert! (boost {:sender "b" :sats 1 :iid "z" :epoch (+ T0 10)})
           (boost {:sender "a" :sats 1 :iid "y" :epoch (+ T0 10)}))
  (testing "same input yields the same order"
    (is (= (all-contribs {}) (all-contribs {})))))
