(ns boost-scraper.schema-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.schema :as schema]
            [boost-scraper.test-utils :as test-utils]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-schema-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

(defn- seed! []
  (d/transact! *conn*
               [{:invoice/identifier "s1"
                 :invoice/creation_date 1780272000
                 :boostagram/action "boost"
                 :boostagram/type :sat
                 :boostagram/podcast "LINUX Unplugged"
                 :boostagram/sender_name_normalized "alice"
                 :boostagram/value_sat_total 1000
                 ;; deliberately NOT in the schema — the sender_id case
                 :boostagram/sender_id "npub1abc"}]))

(deftest test-describe-covers-declared-attributes
  (seed!)
  (let [result (schema/describe *conn*)
        by-name (into {} (map (juxt :attribute identity) (:attributes result)))
        podcast (get by-name ":boostagram/podcast")]
    (testing "declared attributes are reported with their schema type"
      (is (some? podcast))
      (is (true? (:declared podcast)))
      (is (= "db.type/string" (:type podcast))))
    (testing "examples come from real data"
      (is (= ["LINUX Unplugged"] (:examples podcast))))
    (testing "entity counts are opt-in (they cost ~1s/attribute live)"
      (is (not (contains? podcast :entities)))
      (let [with-counts (into {} (map (juxt :attribute identity)
                                      (:attributes (schema/describe *conn* {:counts? true}))))
            p2 (get with-counts ":boostagram/podcast")]
        (is (pos? (:entities p2)))))))

(deftest test-describe-discovers-undeclared-attributes
  (seed!)
  (let [result (schema/describe *conn*)
        by-name (into {} (map (juxt :attribute identity) (:attributes result)))
        sender-id (get by-name ":boostagram/sender_id")]
    (testing "an attribute present in data but absent from the schema is surfaced"
      (is (some? sender-id) "undeclared attrs are discoverable, not invisible")
      (is (false? (:declared sender-id)))
      (is (= "db.type/string" (:type sender-id)) "type inferred from a sample")
      (is (= ["npub1abc"] (:examples sender-id))))
    (testing "counts split declared vs undeclared"
      (is (pos? (get-in result [:counts :declared])))
      (is (pos? (get-in result [:counts :undeclared])))
      (is (= (get-in result [:counts :total])
             (+ (get-in result [:counts :declared])
                (get-in result [:counts :undeclared])))))))

(deftest test-filter-by-substring
  (seed!)
  (let [result (schema/filtered *conn* {:q "podcast"})
        names (mapv :attribute (:attributes result))]
    (testing "only matching attributes survive the filter"
      (is (seq names))
      (is (every? #(re-find #"podcast" %) names)))
    (testing "unfiltered returns everything"
      (is (> (count (:attributes (schema/filtered *conn* {}))) (count names))))))

(deftest test-cache-is-returned
  (seed!)
  (let [a (schema/cached *conn* {})
        b (schema/cached *conn* {})]
    (testing "repeat calls hit the cache"
      (is (identical? a b)))))
