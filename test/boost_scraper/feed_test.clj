(ns boost-scraper.feed-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.feed :as feed]
            [boost-scraper.test-utils :as test-utils]
            [boost-scraper.ws :as ws]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

;; Helpers
(defn- transact-boosts! [conn boosts]
  (d/transact! conn boosts))

(deftest test-feed-pagination-same-time-identifier-tie-break
  (testing "same time, different identifiers paginated via before_id"
    (let [tmpdir (str "/tmp/test-feed-pagination-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (transact-boosts! conn
                          [{:invoice/identifier "a-1"
                            :invoice/creation_date 1000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-a"
                            :boostagram/value_sat_total 100
                            :scraper/source "zaprite"}
                           {:invoice/identifier "b-1"
                            :invoice/creation_date 1000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-b"
                            :boostagram/value_sat_total 200
                            :scraper/source "zaprite"}
                           {:invoice/identifier "c-1"
                            :invoice/creation_date 999
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-c"
                            :boostagram/value_sat_total 300
                            :scraper/source "zaprite"}])
        (let [page1 (feed/get-boosts-for-feed-v2 conn #".*" nil nil 1000 "a-1" 10)]
          ;; With old code (idx=0 tie) page1 would be empty or duplicate.
          ;; New code should return b-1 (same time, identifier > a-1) then c-1.
          (is (some #(= "b-1" (:identifier %)) page1)
              "b-1 should appear when paginating after a-1 same time")
          (is (not (some #(= "a-1" (:identifier %)) page1))
              "a-1 should not reappear"))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-feed-dedup-by-content-id
  (testing "duplicate content_id with different identifiers deduped to one"
    (let [tmpdir (str "/tmp/test-feed-dedup-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (transact-boosts! conn
                          [{:invoice/identifier "dup-a"
                            :invoice/creation_date 1000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "same-cid"
                            :boostagram/value_sat_total 100
                            :scraper/source "lnd"}
                           {:invoice/identifier "dup-b"
                            :invoice/creation_date 1000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "same-cid"
                            :boostagram/value_sat_total 100
                            :scraper/source "zaprite"}])
        (let [results (feed/get-boosts-for-feed-v2 conn #".*" nil nil nil nil 10)
              cids (map :content_id results)]
          (is (= 1 (count results)) "should dedup to one per content_id")
          (is (= 1 (count (filter #(= "same-cid" %) cids)))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-feed-sorts-time-desc-identifier-asc
  (testing "stable sort time desc, identifier asc"
    (let [tmpdir (str "/tmp/test-feed-sort-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (transact-boosts! conn
                          [{:invoice/identifier "b"
                            :invoice/creation_date 2000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-b"
                            :scraper/source "zaprite"}
                           {:invoice/identifier "a"
                            :invoice/creation_date 2000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-a"
                            :scraper/source "zaprite"}
                           {:invoice/identifier "c"
                            :invoice/creation_date 3000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c-c"
                            :scraper/source "zaprite"}])
        (let [results (feed/get-boosts-for-feed-v2 conn #".*" nil nil nil nil 10)
              order (mapv :identifier results)]
          (is (= ["c" "a" "b"] order) "3000 first, then 2000 asc by identifier"))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-ws-normalize-includes-identifier
  (testing "normalize-boost includes stable identifiers"
    (let [entity {:invoice/creation_date 1000
                  :invoice/identifier "my-id"
                  :boostagram/content_id "my-cid"
                  :invoice/add_index 123
                  :boostagram/sender_name_normalized "wes"
                  :boostagram/value_sat_total 1000
                  :boostagram/app_name "Fountain"
                  :boostagram/podcast "LINUX Unplugged"
                  :boostagram/episode "Ep"
                  :boostagram/message "hi"
                  :boostagram/amount_fiat_cents 0
                  :boostagram/payment_rail "lightning"
                  :boostagram/amount_fiat_currency "USD"}
          normalized (ws/normalize-boost entity)]
      (is (= "my-id" (:identifier normalized)) "should include identifier")
      (is (= "my-cid" (:content_id normalized)) "should include content_id")
      (is (= 123 (:index normalized)) "should include index"))))

(deftest test-feed-cursor-legacy-before-index-still-works
  (testing "legacy before_index pagination still returns correct next page"
    (let [tmpdir (str "/tmp/test-feed-legacy-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (transact-boosts! conn
                          [{:invoice/identifier "100"
                            :invoice/add_index 100
                            :invoice/creation_date 2000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c100"
                            :scraper/source "lnd"}
                           {:invoice/identifier "50"
                            :invoice/add_index 50
                            :invoice/creation_date 2000
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c50"
                            :scraper/source "lnd"}
                           {:invoice/identifier "10"
                            :invoice/add_index 10
                            :invoice/creation_date 1999
                            :boostagram/action "boost"
                            :boostagram/podcast "LINUX Unplugged"
                            :boostagram/content_id "c10"
                            :scraper/source "lnd"}])
        ;; Old API: before_time 2000, before_index 100 should return 50 then 10 (idx < 100)
        (let [page (feed/get-boosts-for-feed-v2 conn #".*" nil nil 2000 100 10)]
          ;; With new code, legacy idx path should still work
          (is (some #(= "50" (:identifier %)) page))
          (is (not (some #(= "100" (:identifier %)) page))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))
