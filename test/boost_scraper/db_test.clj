(ns boost-scraper.db-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.test-utils :as test-utils]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

(deftest test-normalize-name
  (testing "lowercases and trims"
    (is (= "wes" (db/normalize-name "Wes")))
    (is (= "wes" (db/normalize-name "  wes  ")))
    (is (= "john doe" (db/normalize-name "John Doe"))))
  (testing "strips leading @"
    (is (= "wes" (db/normalize-name "@Wes")))
    (is (= "cj" (db/normalize-name "@CJ")))
    (is (= "@nested" (db/normalize-name "@@Nested"))))
  (testing "empty and whitespace"
    (is (= "" (db/normalize-name "")))
    (is (= "" (db/normalize-name "  "))))
  (testing "handles edge cases"
    (is (= "test_user" (db/normalize-name "Test_User")))
    (is (= "user@host" (db/normalize-name "User@Host")))))

(deftest test-remove-empty-vals
  (testing "removes nil and empty string values"
    (is (= {:b 1 :d "hello"}
           (db/remove-empty-vals {:a nil :b 1 :c "" :d "hello"}))))
  (testing "preserves false and 0"
    (is (= {:a false :b 0}
           (db/remove-empty-vals {:a false :b 0 :c nil :d ""}))))
  (testing "empty input"
    (is (= {} (db/remove-empty-vals {}))))
  (testing "all nil/empty"
    (is (= {} (db/remove-empty-vals {:a nil :b ""})))))

(deftest test-sha256
  (testing "produces consistent hex hash"
    (is (= (db/sha256 "hello") (db/sha256 "hello")))
    (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
           (db/sha256 "hello"))))
  (testing "different inputs produce different hashes"
    (is (not= (db/sha256 "hello") (db/sha256 "world")))))

(deftest test-non-divisible-msats
  (testing "non-divisible msats truncates to integer sats (quot behavior)"
    (let [result (db/coerce-invoice-vals
                  {:boostagram/value_msat_total 1234})]
      (is (instance? Long (:boostagram/value_sat_total result)))
      (is (= 1 (:boostagram/value_sat_total result)))))
  (testing "1 msat rounds down to 0 sats"
    (let [result (db/coerce-invoice-vals
                  {:boostagram/value_msat_total 1})]
      (is (= 0 (:boostagram/value_sat_total result))))))

(deftest test-coerce-invoice-vals
  (testing "LND format: string add_index becomes identifier + int add_index"
    (let [result (db/coerce-invoice-vals
                  {:invoice/add_index "453978"
                   :invoice/creation_date "1722901411"
                   :boostagram/value_msat_total 5000000
                   :boostagram/sender_name "Wes"})]
      (is (= "453978" (:invoice/identifier result)))
      (is (= 453978 (:invoice/add_index result)))
      (is (= 1722901411 (:invoice/creation_date result)))
      (is (= 5000 (:boostagram/value_sat_total result)))
      (is (= "wes" (:boostagram/sender_name_normalized result)))
      (is (some? (:boostagram/content_id result)))))
  (testing "Alby format: created_at string becomes Date"
    (let [result (db/coerce-invoice-vals
                  {:invoice/created_at "2026-05-26T12:00:00Z"
                   :boostagram/value_msat_total 1000000
                   :boostagram/sender_name "@User"})]
      (is (instance? java.util.Date (:invoice/created_at result)))
      (is (= 1000 (:boostagram/value_sat_total result)))
      (is (= "user" (:boostagram/sender_name_normalized result)))))
  (testing "Alby format with timezone offset string"
    (let [result (db/coerce-invoice-vals
                  {:invoice/created_at "2026-05-26T14:00:00+02:00"
                   :boostagram/value_msat_total 1000000})]
      (is (instance? java.util.Date (:invoice/created_at result)))
      (is (= 1000 (:boostagram/value_sat_total result)))))
  (testing "minimal input produces defaults"
    (let [result (db/coerce-invoice-vals
                  {:boostagram/value_msat_total 50000})]
      (is (= 50 (:boostagram/value_sat_total result)))
      (is (some? (:boostagram/content_id result)))
      (is (nil? (:invoice/identifier result)))
      (is (nil? (:invoice/created_at result)))))
  (testing "creation_date without created_at derives created_at"
    (let [result (db/coerce-invoice-vals
                  {:invoice/creation_date 1722901411
                   :boostagram/value_msat_total 100000})]
      (is (instance? java.util.Date (:invoice/created_at result)))
      (is (= 100 (:boostagram/value_sat_total result)))))
  (testing "no sender_name — no sender_name_normalized"
    (let [result (db/coerce-invoice-vals
                  {:boostagram/value_msat_total 1000})]
      (is (nil? (:boostagram/sender_name_normalized result)))))
  (testing "content_id determinism — same input, same hash"
    (let [input {:boostagram/action "boost"
                 :boostagram/app_name "TestApp"
                 :boostagram/podcast "Test Show"
                 :boostagram/episode "Test Ep"
                 :boostagram/sender_name "wes"
                 :boostagram/value_msat_total 1000000
                 :boostagram/message "hello"
                 :boostagram/ts 1722901411}
          a (db/coerce-invoice-vals input)
          b (db/coerce-invoice-vals input)]
      (is (= (:boostagram/content_id a) (:boostagram/content_id b)))))
  (testing "content_id differs for different actions"
    (let [boost-input {:boostagram/action "boost" :boostagram/value_msat_total 1000}
          stream-input {:boostagram/action "stream" :boostagram/value_msat_total 1000}
          boost-result (db/coerce-invoice-vals boost-input)
          stream-result (db/coerce-invoice-vals stream-input)]
      (is (not= (:boostagram/content_id boost-result)
                (:boostagram/content_id stream-result))))))

(deftest test-decode-boost
  (testing "decodes known Base64 boostagram"
    (let [raw-bytes (.getBytes (json/generate-string
                                {:action "boost"
                                 :app_name "TestApp"
                                 :podcast "Test Show"
                                 :episode "Test Ep"
                                 :sender_name "wes"
                                 :value_msat_total 1000000
                                 :message "hello"
                                 :ts 1722901411})
                               "UTF-8")
          encoded (String. (.encode (java.util.Base64/getEncoder) raw-bytes))
          result (db/decode-boost encoded "test")]
      (is (= "boost" (:boostagram/action result)))
      (is (= "TestApp" (:boostagram/app_name result)))
      (is (= "Test Show" (:boostagram/podcast result)))
      (is (= "wes" (:boostagram/sender_name result)))
      (is (= 1000000 (:boostagram/value_msat_total result)))
      (is (= "hello" (:boostagram/message result)))
      (is (= 1722901411 (:boostagram/ts result)))))
  (testing "returns empty map on invalid base64"
    (is (= {} (db/decode-boost "not-valid-base64!!!" "test"))))
  (testing "returns empty map on valid base64 but not JSON"
    (is (= {} (db/decode-boost (String. (.encode (java.util.Base64/getEncoder)
                                                 (.getBytes "not-json" "UTF-8")))
                               "test")))))

(deftest test-decode-keysend
  (testing "decodes keysend custom record"
    (let [raw-bytes (.getBytes "some-keysend-data" "UTF-8")
          encoded (String. (.encode (java.util.Base64/getEncoder) raw-bytes))
          result (db/decode-keysend encoded "test")]
      (is (= "some-keysend-data" (:invoice/keysend result)))))
  (testing "returns empty map on invalid base64"
    (is (= {} (db/decode-keysend "not-valid!" "test")))))

(deftest test-namespace-invoice-keys
  (testing "moves non-map values under namespace key (vectors included)"
    (let [result (db/namespace-invoice-keys :invoice
                                            {:add_index "123"
                                             :creation_date "1722901411"
                                             :htlcs [{:custom_records {"7629169" "abc"}}]})]
      (is (= {:add_index "123"
              :creation_date "1722901411"
              :htlcs [{:custom_records {"7629169" "abc"}}]}
             (:invoice result)))
      (is (nil? (:htlcs result)))))
  (testing "empty input"
    (is (= {} (db/namespace-invoice-keys :invoice {})))))

(deftest test-flatten-paths
  (testing "flattens nested keys with separator"
    (is (= {:invoice/add_index "123"
            :invoice/creation_date "1722901411"}
           (db/flatten-paths "/"
                             {:invoice {:add_index "123"
                                        :creation_date "1722901411"}}))))
  (testing "empty nested map preserves key with empty value"
    (is (= {:invoice {}}
           (db/flatten-paths "/" {:invoice {}}))))
  (testing "mixed depth"
    (is (= {:a/x 1 :a/y 2 :b 3}
           (db/flatten-paths "/" {:a {:x 1 :y 2} :b 3})))))

(deftest test-process-batch
  (testing "processes a realistic LND invoice with boostagram"
    (let [raw-bytes (.getBytes (json/generate-string
                                {:action "boost"
                                 :app_name "TestApp"
                                 :podcast "Test Show"
                                 :episode "Test Ep"
                                 :sender_name "wes"
                                 :value_msat_total 5000000
                                 :message "hello"
                                 :ts 1722901411})
                               "UTF-8")
          encoded (String. (.encode (java.util.Base64/getEncoder) raw-bytes))
          invoice {:add_index "123"
                   :creation_date "1722901411"
                   :value_msat 5000000
                   :memo "test"
                   :settled true
                   :htlcs [{:custom_records {(keyword "7629169") encoded
                                             (keyword "34349334") (String. (.encode
                                                                            (java.util.Base64/getEncoder)
                                                                            (.getBytes "test-keysend" "UTF-8")))}}]
                   :features {:some "feature"}
                   :amp_invoice_state {:some "state"}
                   :metadata {:some "meta"}
                   :custom_records {:some "custom"}}
          result (db/process-batch [invoice])
          boost (first result)]
      ;; dissoc removed the unwanted keys
      (is (not (contains? boost :features)))
      (is (not (contains? boost :amp_invoice_state)))
      (is (not (contains? boost :metadata)))
      (is (not (contains? boost :custom_records)))
      (is (not (contains? boost :htlcs)))
      ;; namespace + flatten + coerce produced expected fields
      (is (= "123" (:invoice/identifier boost)))
      (is (= 123 (:invoice/add_index boost)))
      (is (= 1722901411 (:invoice/creation_date boost)))
      (is (= 5000 (:boostagram/value_sat_total boost)))
      (is (= "wes" (:boostagram/sender_name boost)))
      (is (= "wes" (:boostagram/sender_name_normalized boost)))
      (is (= "boost" (:boostagram/action boost)))
      (is (= "TestApp" (:boostagram/app_name boost)))
      (is (= "Test Show" (:boostagram/podcast boost)))
      (is (= "Test Ep" (:boostagram/episode boost)))
      (is (= "hello" (:boostagram/message boost)))
      (is (= 1722901411 (:boostagram/ts boost)))
      (is (some? (:boostagram/content_id boost)))
      (is (= :sat (:boostagram/type boost))))))

(deftest test-backfill-boost-type!
  (let [tmpdir (str "/tmp/test-backfill-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn [{:invoice/identifier "b1" :boostagram/action "boost" :boostagram/content_id "c1"}
                         {:invoice/identifier "b2" :boostagram/action "stream" :boostagram/content_id "c2"}])
      (db/backfill-boost-type! conn)
      (let [db (d/db conn)
            types (d/q '[:find ?id ?t
                         :where [?e :invoice/identifier ?id]
                         [?e :boostagram/type ?t]]
                       db)]
        (is (= #{["b1" :sat] ["b2" :sat]} (into #{} types))))
      ;; idempotent — second run makes no changes
      (db/backfill-boost-type! conn)
      (let [db (d/db conn)
            types (d/q '[:find ?id ?t
                         :where [?e :invoice/identifier ?id]
                         [?e :boostagram/type ?t]]
                       db)]
        (is (= #{["b1" :sat] ["b2" :sat]} (into #{} types))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(deftest test-backfill-empty-db
  (testing "no entities at all — backfill is a no-op"
    (let [tmpdir (str "/tmp/test-backfill-empty-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              all-eids (d/q '[:find [?e ...] :where [?e :boostagram/type _]] db)]
          (is (empty? all-eids)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-backfill-all-typed
  (testing "all entities already have :boostagram/type — no changes"
    (let [tmpdir (str "/tmp/test-backfill-typed-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "t1" :boostagram/action "boost"
                       :boostagram/type :sat :boostagram/content_id "c1"}
                      {:invoice/identifier "t2" :boostagram/action "stream"
                       :boostagram/type :sat :boostagram/content_id "c2"}
                      {:invoice/identifier "t3" :boostagram/action "boost"
                       :boostagram/type :fiat :boostagram/content_id "c3"}])
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              types (d/q '[:find ?id ?t
                           :where [?e :invoice/identifier ?id]
                           [?e :boostagram/type ?t]]
                         db)]
          (is (= #{["t1" :sat] ["t2" :sat] ["t3" :fiat]} (into #{} types))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-backfill-mixed-types
  (testing "some already typed, some untyped — only untyped get type"
    (let [tmpdir (str "/tmp/test-backfill-mixed-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "pre-typed" :boostagram/action "boost"
                       :boostagram/type :fiat :boostagram/amount_fiat_cents 1000
                       :boostagram/content_id "c1"}
                      {:invoice/identifier "pre-member" :boostagram/action "boost"
                       :boostagram/type :member-free :boostagram/payment_rail "member-free"
                       :boostagram/content_id "c2"}
                      {:invoice/identifier "legacy-1" :boostagram/action "boost"
                       :boostagram/content_id "c3"}
                      {:invoice/identifier "legacy-2" :boostagram/action "stream"
                       :boostagram/content_id "c4"}])
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              types (d/q '[:find ?id ?t
                           :where [?e :invoice/identifier ?id]
                           [?e :boostagram/type ?t]]
                         db)]
          (is (= #{["pre-typed" :fiat]
                   ["pre-member" :member-free]
                   ["legacy-1" :sat]
                   ["legacy-2" :sat]}
                 (into #{} types))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-backfill-no-action
  (testing "entities without :boostagram/action are not touched"
    (let [tmpdir (str "/tmp/test-backfill-noact-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:sync-cursor/key "zaprite" :sync-cursor/value "2026-01-01"}
                      {:sync-cursor/key "r2-member" :sync-cursor/value "key-abc"}])
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              typed (d/q '[:find [?e ...] :where [?e :boostagram/type _]] db)]
          (is (empty? typed))
          (let [cursors (d/q '[:find ?k ?v
                               :where [?e :sync-cursor/key ?k]
                               [?e :sync-cursor/value ?v]]
                             db)]
            (is (= #{["zaprite" "2026-01-01"] ["r2-member" "key-abc"]}
                   (into #{} cursors)))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-backfill-existing-type-preserved
  (testing "entity with :boostagram/action and existing non-sat type keeps its type"
    (let [tmpdir (str "/tmp/test-backfill-preserve-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "fiat-boost" :boostagram/action "boost"
                       :boostagram/type :fiat :boostagram/amount_fiat_cents 2500
                       :boostagram/content_id "c1"}
                      {:invoice/identifier "free-boost" :boostagram/action "boost"
                       :boostagram/type :member-free :boostagram/payment_rail "member-free"
                       :boostagram/memberful_member_id "42"
                       :boostagram/content_id "c2"}])
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              types (d/q '[:find ?id ?t
                           :where [?e :invoice/identifier ?id]
                           [?e :boostagram/type ?t]]
                         db)]
          (is (= #{["fiat-boost" :fiat] ["free-boost" :member-free]}
                 (into #{} types))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-backfill-large-batch
  (testing "100 untyped entities all get :sat in one backfill"
    (let [tmpdir (str "/tmp/test-backfill-large-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)
          entities (vec (for [i (range 100)]
                          {:invoice/identifier (str "legacy-" i)
                           :boostagram/action (if (odd? i) "boost" "stream")
                           :boostagram/value_sat_total (rand-int 50000)
                           :boostagram/content_id (str "c" i)
                           :scraper/source "lnd"}))]
      (try
        (d/transact! conn entities)
        (db/backfill-boost-type! conn)
        (let [db (d/db conn)
              types (d/q '[:find ?id ?t
                           :where [?e :invoice/identifier ?id]
                           [?e :boostagram/type ?t]]
                         db)]
          (is (= 100 (count types)))
          (is (every? (fn [[_id t]] (= :sat t)) types)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-datalevin-schema-roundtrip
  (let [tmpdir (str "/tmp/test-schema-roundtrip-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)
        boost {:invoice/identifier "roundtrip-1"
               :invoice/creation_date 1722901411
               :boostagram/sender_name_normalized "testuser"
               :boostagram/value_sat_total 1000
               :boostagram/podcast "Test Show"
               :boostagram/episode "Test Ep"
               :boostagram/app_name "TestApp"
               :boostagram/action "boost"
               :boostagram/content_id "content-id-1"
               :scraper/source "test"}]
    (try
      (d/transact! conn [boost])
      (let [db (d/db conn)
            results (d/q '[:find ?v ?id ?c
                           :where [?e :boostagram/value_sat_total ?v]
                           [?e :invoice/identifier ?id]
                           [?e :boostagram/content_id ?c]]
                         db)]
        (is (= #{[1000 "roundtrip-1" "content-id-1"]} (into #{} results))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(deftest test-cursor-destructuring
  (testing "cursor read with [:find [?value]] — works with nil and populated"
    (let [tmpdir (str "/tmp/test-cursor-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (let [[cursor] (d/q '[:find [?value]
                              :where [?e :sync-cursor/key "test"]
                              [?e :sync-cursor/value ?value]]
                            (d/db conn))]
          (is (nil? cursor) "no cursor returns nil"))
        (d/transact! conn [{:sync-cursor/key "test"
                            :sync-cursor/value "some-cursor-value"}])
        (let [[cursor] (d/q '[:find [?value]
                              :where [?e :sync-cursor/key "test"]
                              [?e :sync-cursor/value ?value]]
                            (d/db conn))]
          (is (= "some-cursor-value" cursor) "cursor reads correctly"))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))
