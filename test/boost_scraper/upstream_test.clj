(ns boost-scraper.upstream-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [boost-scraper.db :as db]
            [boost-scraper.reports :as reports]
            [boost-scraper.upstream.zaprite :as zaprite]
            [boost-scraper.upstream.r2 :as r2]))

(deftest test-format-value-line
  (testing "sats-based boost"
    (is (= "1,234 sats"
           (reports/format-value-line {:boostagram/value_sat_total 1234}))))
  (testing "nil value_sat_total defaults to 0 sats"
    (is (= "0 sats"
           (reports/format-value-line {}))))
  (testing "fiat boost (USD card)"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 500
                 :boostagram/amount_fiat_currency "USD"
                 :boostagram/payment_rail "card"})]
      (is (str/includes? line "$5.00"))
      (is (str/includes? line "card"))))
  (testing "fiat boost (ACH)"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 1999
                 :boostagram/amount_fiat_currency "USD"
                 :boostagram/payment_rail "ach"})]
      (is (str/includes? line "$19.99"))
      (is (str/includes? line "ach"))))
  (testing "member-free boost"
    (is (= "Free Member Boost"
           (reports/format-value-line
            {:boostagram/payment_rail "member-free"
             :boostagram/value_sat_total 0}))))
  (testing "fiat takes priority over sats"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 1000
                 :boostagram/amount_fiat_currency "USD"
                 :boostagram/payment_rail "card"
                 :boostagram/value_sat_total 50000})]
      (is (str/includes? line "$10.00"))
      (is (not (str/includes? line "sats")))))
  (testing "0-cent fiat treated as sats (consistent with boost-type)"
    (is (= "0 sats"
           (reports/format-value-line
            {:boostagram/amount_fiat_cents 0
             :boostagram/payment_rail "card"
             :boostagram/value_sat_total 0})))))

(deftest test-infer-payment-rail
  (testing "lightning"
    (is (= "lightning" (zaprite/infer-payment-rail {:transactions [{:method "LIGHTNING"}]}))))
  (testing "card"
    (is (= "card" (zaprite/infer-payment-rail {:transactions [{:method "CARD"}]}))))
  (testing "apple pay maps to card"
    (is (= "card" (zaprite/infer-payment-rail {:transactions [{:method "APPLEPAY"}]}))))
  (testing "unknown method lowercased"
    (is (= "crypto" (zaprite/infer-payment-rail {:transactions [{:method "CRYPTO"}]}))))
  (testing "no transactions returns unknown"
    (is (= "unknown" (zaprite/infer-payment-rail {})))))

(deftest test-process-order
  (testing "BTC order"
    (let [order {:id "ord-123"
                 :totalAmount 10000
                 :currency "BTC"
                 :paidAt "2026-05-26T12:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost" :podcastName "LINUX Unplugged" :episodeTitle "Test Episode" :username "testuser" :message "Great show!" :memberId 42 :slug "lup"}}
          entity (zaprite/process-order order)]
      (is (= "zaprite-ord-123" (:invoice/identifier entity)))
      (is (= "LINUX Unplugged" (:boostagram/podcast entity)))
      (is (= 10000 (:boostagram/value_sat_total entity)))
      (is (nil? (:boostagram/amount_fiat_cents entity)))
      (is (nil? (:boostagram/amount_fiat_currency entity)))
      (is (= "lightning" (:boostagram/payment_rail entity)))
      (is (= "zaprite" (:scraper/source entity)))
      (is (= "testuser" (:boostagram/sender_name entity)))
      (is (= "42" (:boostagram/memberful_member_id entity)))
      (is (= "lup" (:boostagram/podcast_slug entity)))
      (is (= :sat (:boostagram/type entity)))))
  (testing "USD order via card"
    (let [order {:id "ord-456"
                 :totalAmount 500
                 :currency "USD"
                 :paidAt "2026-05-26T13:00:00.000Z"
                 :transactions [{:method "CARD"}]
                 :metadata {:app "web-boost"
                            :podcastName "Self-Hosted"
                            :episodeTitle "Test Episode 2"
                            :username "fiat-user"
                            :message "Paying with card!"}}
          entity (zaprite/process-order order)]
      (is (= "zaprite-ord-456" (:invoice/identifier entity)))
      (is (= 0 (:boostagram/value_sat_total entity)))
      (is (= 500 (:boostagram/amount_fiat_cents entity)))
      (is (= "USD" (:boostagram/amount_fiat_currency entity)))
      (is (= "card" (:boostagram/payment_rail entity)))
      (is (= "zaprite" (:scraper/source entity)))
      (is (= :fiat (:boostagram/type entity)))))
  (testing "no metadata (non-web-boost) — still processes with defaults"
    (let [order {:id "ord-789"
                 :totalAmount 2000
                 :currency "BTC"
                 :paidAt "2026-05-26T14:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]}
          entity (zaprite/process-order order)]
      (is (= "zaprite-ord-789" (:invoice/identifier entity)))
      (is (nil? (:boostagram/podcast entity)))
      (is (= "" (:boostagram/message entity)))))
  (testing "missing paidAt — gracefully handles nil date"
    (let [order {:id "ord-nil-date"
                 :totalAmount 5000
                 :currency "BTC"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost"}}
          entity (zaprite/process-order order)]
      (is (= "zaprite-ord-nil-date" (:invoice/identifier entity)))
      (is (nil? (:invoice/creation_date entity)))
      (is (nil? (:invoice/created_at entity)))
      (is (nil? (:boostagram/received_at entity))))))

(deftest test-process-order-slug-fallback
  (testing "podcastSlug used when slug not present in metadata"
    (let [order {:id "ord-slug-fallback"
                 :totalAmount 1000
                 :currency "BTC"
                 :paidAt "2026-05-26T20:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost"
                            :podcastName "Self-Hosted"
                            :podcastSlug "selfh"
                            :episodeTitle "Test"
                            :username "user"
                            :message "hi"}}
          entity (zaprite/process-order order)]
      (is (= "selfh" (:boostagram/podcast_slug entity)))
      (is (= "Self-Hosted" (:boostagram/podcast entity)))))
  (testing "slug takes priority over podcastSlug"
    (let [order {:id "ord-slug-priority"
                 :totalAmount 1000
                 :currency "BTC"
                 :paidAt "2026-05-26T21:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost"
                            :podcastName "Test Show"
                            :slug "winner"
                            :podcastSlug "loser"
                            :episodeTitle "Test"
                            :username "user"
                            :message "hi"}}
          entity (zaprite/process-order order)]
      (is (= "winner" (:boostagram/podcast_slug entity)))))
  (testing "neither slug nor podcastSlug — nil"
    (let [order {:id "ord-no-slug"
                 :totalAmount 1000
                 :currency "BTC"
                 :paidAt "2026-05-26T22:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost"}}
          entity (zaprite/process-order order)]
      (is (nil? (:boostagram/podcast_slug entity))))))

(deftest test-process-record
  (testing "member free boost with full 10-field record"
    (let [record {:boostId "boost-abc"
                  :username "free-user"
                  :message "Thanks for the show!"
                  :createdAt "2026-05-26T14:00:00.000Z"
                  :memberId 9012
                  :podcastSlug "lup"
                  :podcastName "Linux Unplugged"
                  :episodeGuid "guid-456"
                  :episodeTitle "The Config Episode"
                  :amountFiatCents 0
                  :amountFiatCurrency "USD"}
          object-key "member-boosts/v1/r/boost-abc.json"
          entity (r2/process-record record object-key)]
      (is (= object-key (:boostagram/r2_object_key entity)))
      (is (= "member-r2-boost-abc" (:invoice/identifier entity)))
      (is (= 0 (:boostagram/value_sat_total entity)))
      (is (= "member-free" (:boostagram/payment_rail entity)))
      (is (= "9012" (:boostagram/memberful_member_id entity)))
      (is (= "Memberful (Free)" (:boostagram/app_name entity)))
      (is (= "r2-member" (:scraper/source entity)))
      (is (= "lup" (:boostagram/podcast_slug entity)))
      (is (= "guid-456" (:boostagram/episode_guid entity)))
      (is (= 0 (:boostagram/amount_fiat_cents entity)))
      (is (= "USD" (:boostagram/amount_fiat_currency entity)))
      (is (= "free-user" (:boostagram/sender_name entity)))
      (is (= "Linux Unplugged" (:boostagram/podcast entity)))
      (is (= "The Config Episode" (:boostagram/episode entity)))
      (is (= :member-free (:boostagram/type entity)))))
  (testing "missing memberId — nil when absent"
    (let [record {:boostId "boost-no-member"
                  :username "user"
                  :createdAt "2026-05-26T15:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-0"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (nil? (:boostagram/memberful_member_id entity)))))
  (testing "non-zero fiat fields"
    (let [record {:boostId "boost-paid"
                  :username "paying-user"
                  :message "worth it"
                  :createdAt "2026-05-26T16:00:00.000Z"
                  :memberId 555
                  :podcastSlug "premium"
                  :episodeGuid "guid-paid"
                  :amountFiatCents 999
                  :amountFiatCurrency "USD"}
          entity (r2/process-record record "key.json")]
      (is (= 999 (:boostagram/amount_fiat_cents entity)))
      (is (= "USD" (:boostagram/amount_fiat_currency entity)))))
  (testing "missing createdAt — nil date fields"
    (let [record {:boostId "boost-no-date"
                  :username "user"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (nil? (:invoice/creation_date entity)))
      (is (nil? (:invoice/created_at entity)))
      (is (nil? (:boostagram/received_at entity)))))
  (testing "missing senderName — defaults to empty string"
    (let [record {:boostId "boost-no-user"
                  :createdAt "2026-05-26T17:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (= "" (:boostagram/sender_name_normalized entity)))
      (is (= "" (:boostagram/message entity)))))
  (testing "nil memberId (from JSON key missing) — nil attribute"
    (let [record {:boostId "boost-no-member-id"
                  :username "user"
                  :createdAt "2026-05-26T18:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (nil? (:boostagram/memberful_member_id entity)))))
  (testing "default currency when absent"
    (let [record {:boostId "boost-no-currency"
                  :username "user"
                  :createdAt "2026-05-26T19:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (= "USD" (:boostagram/amount_fiat_currency entity))))))

(deftest test-process-record-missing-podcast-episode
  (testing "missing podcastName and episodeTitle — nil when absent"
    (let [record {:boostId "boost-no-podcast"
                  :username "user"
                  :createdAt "2026-05-26T20:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"
                  :amountFiatCents 0}
          entity (r2/process-record record "key.json")]
      (is (nil? (:boostagram/podcast entity)))
      (is (nil? (:boostagram/episode entity))))))

(deftest test-sort-report-with-fiat
  (testing "fiat and sat boosts are separated into correct sections"
    ;; Simulate the raw query output shape:
    ;; [ballers boosts thanks [boost-summary] [stream-summary] [total-summary] last-seen-id]
    (let [sat-boost {:boostagram/sender_name_normalized "big-spender"
                     :boostagram/value_sat_total 50000
                     :boostagram/podcast "LINUX Unplugged"
                     :boostagram/episode "Test Ep"
                     :boostagram/app_name "SomeApp"
                     :invoice/creation_date 2000000000
                     :scraper/source "lnd"}
          fiat-boost {:boostagram/sender_name_normalized "fiat-user"
                      :boostagram/value_sat_total 0
                      :boostagram/amount_fiat_cents 1000
                      :boostagram/amount_fiat_currency "USD"
                      :boostagram/payment_rail "card"
                      :boostagram/podcast "LINUX Unplugged"
                      :boostagram/episode "Test Ep"
                      :boostagram/app_name "Zaprite"
                      :invoice/creation_date 2000000001
                      :scraper/source "zaprite"}
          member-free {:boostagram/sender_name_normalized "free-user"
                       :boostagram/value_sat_total 0
                       :boostagram/payment_rail "member-free"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Test Ep"
                       :boostagram/app_name "Memberful (Free)"
                       :invoice/creation_date 2000000002
                       :scraper/source "r2-member"}
          raw-result [[["big-spender" 50000 1 2000000000 [sat-boost]]]  ;; ballers
                      []  ;; boosts
                      []  ;; thanks
                      [["fiat-user" 1000 1 [fiat-boost]]]  ;; fiat-by-sender
                      [["free-user" 1 [member-free]]]  ;; member-free-by-sender
                      [50000 1 1]  ;; boost-summary
                      [0 0 0]  ;; stream-summary
                      [50000 3 3]  ;; total-summary
                      nil]  ;; last-seen-id
          result (reports/sort-report raw-result)]
      (is (= 1 (count (:ballers result))))
      (is (= "big-spender" (-> result :ballers first :sender)))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (= 1 (count (:fiat-boosts result))))
      (is (= "fiat-user" (-> result :fiat-boosts first :sender)))
      (is (= 1000 (-> result :fiat-boosts first :total)))
      (is (= 1 (count (:member-free-boosts result))))
      (is (= "free-user" (-> result :member-free-boosts first :sender))))))

(deftest test-process-order-keyword-roundtrip
  (testing "json/parse-string true → process-order (production code path)"
    (let [order-map {:id "ord-json-1" :totalAmount 5000 :currency "USD"
                     :paidAt "2026-05-26T17:00:00.000Z"
                     :transactions [{:method "CARD"}]
                     :metadata {:app "web-boost" :podcastName "Test Show"
                                :episodeTitle "Test Ep" :username "json-user"
                                :message "from JSON"}}
          json-string (json/generate-string order-map)
          parsed (json/parse-string json-string true)
          entity (zaprite/process-order parsed)]
      (is (= "zaprite-ord-json-1" (:invoice/identifier entity)))
      (is (= "Test Show" (:boostagram/podcast entity)))
      (is (= 0 (:boostagram/value_sat_total entity)))
      (is (= 5000 (:boostagram/amount_fiat_cents entity)))
      (is (= "USD" (:boostagram/amount_fiat_currency entity)))
      (is (= "card" (:boostagram/payment_rail entity))))))

(deftest test-process-record-keyword-roundtrip
  (testing "json/parse-string true → process-record (production code path)"
    (let [record-map {:boostId "boost-json-1"
                      :username "json-user"
                      :message "from JSON"
                      :createdAt "2026-05-26T18:00:00.000Z"
                      :memberId 777
                      :podcastSlug "lup"
                      :podcastName "Linux Unplugged"
                      :episodeGuid "guid-json"
                      :episodeTitle "The Config Episode"
                      :amountFiatCents 0
                      :amountFiatCurrency "USD"}
          json-string (json/generate-string record-map)
          parsed (json/parse-string json-string true)
          entity (r2/process-record parsed "member-boosts/v1/r/boost-json-1.json")]
      (is (= "member-r2-boost-json-1" (:invoice/identifier entity)))
      (is (= "json-user" (:boostagram/sender_name entity)))
      (is (= "777" (:boostagram/memberful_member_id entity)))
      (is (= "lup" (:boostagram/podcast_slug entity)))
      (is (= "Linux Unplugged" (:boostagram/podcast entity)))
      (is (= "The Config Episode" (:boostagram/episode entity))))))

(deftest test-sort-report-boundaries
  (testing "2000 sats lands in boosts section (not thanks)"
    (let [now (java.util.Date.)
          boost {:boostagram/sender_name_normalized "boundary-user"
                 :boostagram/value_sat_total 2000
                 :boostagram/podcast "Test Show"
                 :boostagram/episode "Test Ep"
                 :boostagram/app_name "App"
                 :invoice/creation_date 2000000000
                 :invoice/created_at now
                 :scraper/source "lnd"}
           raw [[]
                [["boundary-user" 2000 1 2000000000 [boost]]]
                []
                []
                []
                [2000 1 1]
                [0 0 0]
                [2000 1 1]
                nil]
          result (reports/sort-report raw)]
      (is (empty? (:ballers result)))
      (is (= 1 (count (:boosts result))))
      (is (empty? (:thanks result)))))
  (testing "1999 sats lands in thanks section"
    (let [now (java.util.Date.)
          boost {:boostagram/sender_name_normalized "thanks-user"
                 :boostagram/value_sat_total 1999
                 :boostagram/podcast "Test Show"
                 :boostagram/episode "Test Ep"
                 :boostagram/app_name "App"
                 :invoice/creation_date 2000000000
                 :invoice/created_at now
                 :scraper/source "lnd"}
           raw [[]  ;; ballers
                []  ;; boosts
                [["thanks-user" 1999 1 2000000000 [boost]]]  ;; thanks
                []  ;; fiat-by-sender
                []  ;; member-free-by-sender
                [1999 1 1]  ;; boost-summary
                [0 0 0]  ;; stream-summary
                [1999 1 1]  ;; total-summary
                nil]  ;; last-seen-id
          result (reports/sort-report raw)]
      (is (empty? (:boosts result)))
      (is (= 1 (count (:thanks result))))))
  (testing "20000 sats lands in ballers section"
    (let [now (java.util.Date.)
          boost {:boostagram/sender_name_normalized "baller-user"
                 :boostagram/value_sat_total 20000
                 :boostagram/podcast "Test Show"
                 :boostagram/episode "Test Ep"
                 :boostagram/app_name "App"
                 :invoice/creation_date 2000000000
                 :invoice/created_at now
                 :scraper/source "lnd"}
          raw [[["baller-user" 20000 1 2000000000 [boost]]]
               []
               []
               []
               []
               [20000 1 1]
               [0 0 0]
               [20000 1 1]
               nil]
          result (reports/sort-report raw)]
      (is (= 1 (count (:ballers result))))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))))
  (testing "empty results — all sections empty"
    (let [raw [[] [] [] [] [] [0 0 0] [0 0 0] [0 0 0] nil]
          result (reports/sort-report raw)]
      (is (empty? (:ballers result)))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:fiat-boosts result)))
      (is (empty? (:member-free-boosts result))))))

(deftest test-process-record-nil-free
  (testing "process-record with missing amountFiatCents — no nil values after remove-empty-vals"
    (let [record {:boostId "boost-no-fiat"
                  :username "user"
                  :createdAt "2026-05-26T18:00:00.000Z"
                  :podcastSlug "test"
                  :episodeGuid "guid-nil"}
          entity (db/remove-empty-vals (r2/process-record record "key.json"))
          nil-vals (filter (comp nil? val) entity)]
      (is (empty? nil-vals) (str "nil values leaked: " nil-vals))))
  (testing "process-record with all fields present — no nil values"
    (let [record {:boostId "boost-full" :username "user" :message "hello"
                  :createdAt "2026-05-26T18:00:00.000Z" :memberId 123
                  :podcastSlug "test" :episodeGuid "guid-full"
                  :podcastName "Test Show" :episodeTitle "Test Ep"
                  :amountFiatCents 500 :amountFiatCurrency "USD"}
          entity (db/remove-empty-vals (r2/process-record record "key.json"))
          nil-vals (filter (comp nil? val) entity)]
      (is (empty? nil-vals) (str "nil values leaked: " nil-vals)))))

(deftest test-process-order-nil-free
  (testing "BTC order — no nil values after remove-empty-vals"
    (let [order {:id "ord-btc" :totalAmount 10000 :currency "BTC"
                 :paidAt "2026-05-26T12:00:00.000Z"
                 :transactions [{:method "LIGHTNING"}]
                 :metadata {:app "web-boost" :podcastName "Test" :episodeTitle "Ep"
                            :username "u" :message "m"}}
          entity (db/remove-empty-vals (zaprite/process-order order))
          nil-vals (filter (comp nil? val) entity)]
      (is (empty? nil-vals) (str "nil values leaked: " nil-vals))))
  (testing "USD order — no nil values after remove-empty-vals"
    (let [order {:id "ord-usd" :totalAmount 500 :currency "USD"
                 :paidAt "2026-05-26T12:00:00.000Z"
                 :transactions [{:method "CARD"}]
                 :metadata {:app "web-boost" :podcastName "Test" :episodeTitle "Ep"
                            :username "u" :message "m"}}
          entity (db/remove-empty-vals (zaprite/process-order order))
          nil-vals (filter (comp nil? val) entity)]
      (is (empty? nil-vals) (str "nil values leaked: " nil-vals)))))
