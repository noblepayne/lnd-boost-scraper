(ns boost-scraper.reports-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.reports :as reports]
            [boost-scraper.test-utils :as test-utils]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

;; ============================================================================
;; sort-report: 9-element tuple processing
;; ============================================================================

(deftest test-sort-report-all-positions
  (testing "all 9 tuple positions fully populated"
    (let [sat-epoch 2000000000
          fiat-epoch 2000000010
          free-epoch 2000000020
          now (java.util.Date.)
          baller-boost {:boostagram/sender_name_normalized "big-spender"
                        :boostagram/value_sat_total 50000
                        :boostagram/podcast "LINUX Unplugged"
                        :boostagram/episode "Baller Ep"
                        :boostagram/app_name "Fountain"
                        :boostagram/ts 1000
                        :boostagram/message "Baller boost!"
                        :invoice/created_at now
                        :invoice/creation_date sat-epoch
                        :invoice/identifier "inv-1"
                        :scraper/source "lnd"}
          boost-boost {:boostagram/sender_name_normalized "mid-spender"
                       :boostagram/value_sat_total 10000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Boost Ep"
                       :boostagram/app_name "Podverse"
                       :boostagram/message "Mid boost!"
                       :invoice/created_at now
                       :invoice/creation_date 2000000005
                       :invoice/identifier "inv-2"
                       :scraper/source "lnd"}
          thanks-boost {:boostagram/sender_name_normalized "small-spender"
                        :boostagram/value_sat_total 500
                        :boostagram/podcast "LINUX Unplugged"
                        :boostagram/episode "Thanks Ep"
                        :boostagram/app_name "Alby"
                        :boostagram/message "Thanks!"
                        :invoice/created_at now
                        :invoice/creation_date 2000000008
                        :invoice/identifier "inv-3"
                        :scraper/source "lnd"}
          fiat-boost {:boostagram/sender_name_normalized "fiat-user"
                      :boostagram/value_sat_total 0
                      :boostagram/amount_fiat_cents 2000
                      :boostagram/amount_fiat_currency "USD"
                      :boostagram/payment_rail "card"
                      :boostagram/podcast "LINUX Unplugged"
                      :boostagram/episode "Fiat Ep"
                      :boostagram/app_name "Zaprite"
                      :boostagram/message "Card payment!"
                      :invoice/created_at now
                      :invoice/creation_date fiat-epoch
                      :scraper/source "zaprite"}
          member-free {:boostagram/sender_name_normalized "free-user"
                       :boostagram/value_sat_total 0
                       :boostagram/payment_rail "member-free"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Free Ep"
                       :boostagram/app_name "Memberful (Free)"
                       :boostagram/message "Love the show"
                       :invoice/created_at now
                       :invoice/creation_date free-epoch
                       :scraper/source "r2-member"}
          raw [[["big-spender" 50000 1 sat-epoch [baller-boost]]]
               [["mid-spender" 10000 1 2000000005 [boost-boost]]]
               [["small-spender" 500 1 2000000008 [thanks-boost]]]
               [["fiat-user" 2000 1 [fiat-boost]]]
               [["free-user" 1 [member-free]]]
               [60500 3 3]
               [0 0 0]
               [60500 5 5]
               42
               [] [] []]
          result (reports/sort-report raw)]
      (is (= 1 (count (:ballers result))))
      (is (= "big-spender" (-> result :ballers first :sender)))
      (is (= 50000 (-> result :ballers first :total)))
      (is (= 1 (-> result :ballers first :count)))
      (is (= 1 (count (:boosts result))))
      (is (= "mid-spender" (-> result :boosts first :sender)))
      (is (= 1 (count (:thanks result))))
      (is (= "small-spender" (-> result :thanks first :sender)))
      (is (= 1 (count (:fiat-boosts result))))
      (is (= "fiat-user" (-> result :fiat-boosts first :sender)))
      (is (= 2000 (-> result :fiat-boosts first :total)))
      (is (= 1 (count (:member-free-boosts result))))
      (is (= "free-user" (-> result :member-free-boosts first :sender)))
      (is (= 1 (-> result :member-free-boosts first :count)))
      (is (= 60500 (:boost_total_sats (:boost-summary result))))
      (is (= 3 (:boost_total_boosts (:boost-summary result))))
      (is (= 3 (:boost_total_boosters (:boost-summary result))))
      (is (= 0 (:stream_total_sats (:stream-summary result))))
      (is (= 60500 (:total_sats (:summary result))))
      (is (= 5 (:total_invoices (:summary result))))
      (is (= 5 (:total_unique_boosters (:summary result))))
      (is (= 42 (:last_seen_id (:summary result)))))))

(deftest test-sort-report-empty-fiat-and-member-free
  (testing "fiat-by-sender and member-free-by-sender positions are empty vectors"
    (let [now (java.util.Date.)
          baller-boost {:boostagram/sender_name_normalized "big-spender"
                        :boostagram/value_sat_total 50000
                        :boostagram/podcast "LINUX Unplugged"
                        :boostagram/episode "Baller Ep"
                        :boostagram/app_name "Fountain"
                        :boostagram/message "Baller boost!"
                        :invoice/created_at now
                        :invoice/creation_date 2000000000
                        :invoice/identifier "inv-1"
                        :scraper/source "lnd"}
          raw [[["big-spender" 50000 1 2000000000 [baller-boost]]] ;; ballers
               [] ;; boosts (empty)
               [] ;; thanks (empty)
               [] ;; fiat-by-sender (empty)
               [] ;; member-free-by-sender (empty)
               [50000 1 1] ;; boost-summary
               [0 0 0] ;; stream-summary
               [50000 1 1] ;; total-summary
               nil ;; last-seen-id
               [] [] []]
          result (reports/sort-report raw)]
      (is (= 1 (count (:ballers result))))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:fiat-boosts result)))
      (is (empty? (:member-free-boosts result)))
      (is (= 50000 (:boost_total_sats (:boost-summary result)))))))

(deftest test-sort-report-only-fiat
  (testing "only fiat boosts present -- no sat-level ballers/boosts/thanks"
    (let [now (java.util.Date.)
          fiat-boost {:boostagram/sender_name_normalized "fiat-user"
                      :boostagram/value_sat_total 0
                      :boostagram/amount_fiat_cents 1500
                      :boostagram/amount_fiat_currency "USD"
                      :boostagram/payment_rail "ach"
                      :boostagram/podcast "LINUX Unplugged"
                      :boostagram/episode "Fiat Ep"
                      :boostagram/app_name "Zaprite"
                      :boostagram/message "ACH payment"
                      :invoice/created_at now
                      :invoice/creation_date 2000000010
                      :scraper/source "zaprite"}
          raw [[] [] []
               [["fiat-user" 1500 1 [fiat-boost]]] ;; fiat-by-sender
               [] ;; member-free empty
               [0 0 0] ;; boost-summary (zero -- no sat boosts)
               [0 0 0] ;; stream-summary
               [0 1 1] ;; total-summary
               nil
               [] [] []]
          result (reports/sort-report raw)]
      (is (empty? (:ballers result)))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:member-free-boosts result)))
      (is (= 1 (count (:fiat-boosts result))))
      (is (= "fiat-user" (-> result :fiat-boosts first :sender)))
      (is (= 1500 (-> result :fiat-boosts first :total)))
      (is (= 0 (:boost_total_sats (:boost-summary result))))
      (is (= 0 (:total_sats (:summary result))))
      (is (= 1 (:total_invoices (:summary result)))))))

(deftest test-sort-report-only-member-free
  (testing "only member-free boosts present -- no sat or fiat boosts"
    (let [now (java.util.Date.)
          mfree-boost {:boostagram/sender_name_normalized "free-user"
                       :boostagram/value_sat_total 0
                       :boostagram/payment_rail "member-free"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Free Ep"
                       :boostagram/app_name "Memberful (Free)"
                       :boostagram/message "Free boost!"
                       :invoice/created_at now
                       :invoice/creation_date 2000000010
                       :scraper/source "r2-member"}
          raw [[] [] [] []
               [["free-user" 1 [mfree-boost]]] ;; member-free-by-sender
               [0 0 0] ;; boost-summary
               [0 0 0] ;; stream-summary
               [0 1 1] ;; total-summary
               nil
               [] [] []]
          result (reports/sort-report raw)]
      (is (empty? (:ballers result)))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:fiat-boosts result)))
      (is (= 1 (count (:member-free-boosts result))))
      (is (= "free-user" (-> result :member-free-boosts first :sender)))
      (is (= 1 (-> result :member-free-boosts first :count)))
      (is (empty? (:fiat-boosts result))))))

(deftest test-sort-report-empty-all
  (testing "entirely empty result -- all sections empty, nil last-seen-id"
    (let [raw [[] [] [] [] [] [0 0 0] [0 0 0] [0 0 0] nil [] [] []]
          result (reports/sort-report raw)]
      (is (empty? (:ballers result)))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:fiat-boosts result)))
      (is (empty? (:member-free-boosts result)))
      (is (= 0 (:boost_total_sats (:boost-summary result))))
      (is (= 0 (:stream_total_sats (:stream-summary result))))
      (is (= 0 (:total_sats (:summary result))))
      (is (nil? (:last_seen_id (:summary result)))))))

;; ============================================================================
;; sort-report: fiat detail shape [sender total_cents count [boosts]]
;; ============================================================================

(deftest test-sort-report-fiat-detail-shape
  (testing "multiple fiat senders sorted by total descending, boosts by created_at"
    (let [fiat-a-1 {:boostagram/sender_name_normalized "fiat-user-a"
                    :boostagram/amount_fiat_cents 1000
                    :boostagram/amount_fiat_currency "USD"
                    :boostagram/payment_rail "card"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "First card boost"
                    :invoice/created_at (java.util.Date. 1000000)
                    :invoice/creation_date 2000000010
                    :scraper/source "zaprite"}
          fiat-a-2 {:boostagram/sender_name_normalized "fiat-user-a"
                    :boostagram/amount_fiat_cents 500
                    :boostagram/amount_fiat_currency "USD"
                    :boostagram/payment_rail "card"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "Second card boost"
                    :invoice/created_at (java.util.Date. 2000000)
                    :invoice/creation_date 2000000020
                    :scraper/source "zaprite"}
          fiat-b-1 {:boostagram/sender_name_normalized "fiat-user-b"
                    :boostagram/amount_fiat_cents 2000
                    :boostagram/amount_fiat_currency "USD"
                    :boostagram/payment_rail "ach"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "ACH boost"
                    :invoice/created_at (java.util.Date. 1500000)
                    :invoice/creation_date 2000000015
                    :scraper/source "zaprite"}
          raw [[] [] []
               [["fiat-user-a" 1500 2 [fiat-a-1 fiat-a-2]]
                ["fiat-user-b" 2000 1 [fiat-b-1]]]
               []
               [0 0 0] [0 0 0] [0 3 2] nil
               [] [] []]
          result (reports/sort-report raw)]
      (is (= 2 (count (:fiat-boosts result))))
      (is (= "fiat-user-b" (-> result :fiat-boosts first :sender)))
      (is (= 2000 (-> result :fiat-boosts first :total)))
      (is (= "fiat-user-a" (-> result :fiat-boosts second :sender)))
      (is (= 1500 (-> result :fiat-boosts second :total)))
      (let [boosts-a (-> result :fiat-boosts second :boosts)]
        (is (= 2 (count boosts-a)))
        (is (= "First card boost" (:boostagram/message (first boosts-a))))
        (is (= "Second card boost" (:boostagram/message (second boosts-a))))))))

;; ============================================================================
;; sort-report: member-free detail shape [sender count [boosts]]
;; ============================================================================

(deftest test-sort-report-member-free-detail-shape
  (testing "multiple member-free senders sorted by count descending"
    (let [free-a-1 {:boostagram/sender_name_normalized "free-user-a"
                    :boostagram/payment_rail "member-free"
                    :boostagram/memberful_member_id "100"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "First free"
                    :invoice/created_at (java.util.Date. 1000000)
                    :invoice/creation_date 2000000010
                    :scraper/source "r2-member"}
          free-b-1 {:boostagram/sender_name_normalized "free-user-b"
                    :boostagram/payment_rail "member-free"
                    :boostagram/memberful_member_id "200"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "Free B-1"
                    :invoice/created_at (java.util.Date. 1000000)
                    :invoice/creation_date 2000000020
                    :scraper/source "r2-member"}
          free-b-2 {:boostagram/sender_name_normalized "free-user-b"
                    :boostagram/payment_rail "member-free"
                    :boostagram/memberful_member_id "200"
                    :boostagram/podcast "LINUX Unplugged"
                    :boostagram/episode "Ep 1"
                    :boostagram/message "Free B-2"
                    :invoice/created_at (java.util.Date. 2000000)
                    :invoice/creation_date 2000000030
                    :scraper/source "r2-member"}
          raw [[] [] [] []
               [["free-user-b" 2 [free-b-1 free-b-2]]
                ["free-user-a" 1 [free-a-1]]]
               [0 0 0] [0 0 0] [0 3 2] nil
               [] [] []]
          result (reports/sort-report raw)]
      (is (= 2 (count (:member-free-boosts result))))
      (is (= "free-user-b" (-> result :member-free-boosts first :sender)))
      (is (= 2 (-> result :member-free-boosts first :count)))
      (is (= "free-user-a" (-> result :member-free-boosts second :sender)))
      (is (= 1 (-> result :member-free-boosts second :count)))
      (let [boosts-b (-> result :member-free-boosts first :boosts)]
        (is (= "Free B-1" (:boostagram/message (nth boosts-b 0))))
        (is (= "Free B-2" (:boostagram/message (nth boosts-b 1))))))))

;; ============================================================================
;; sort-report: multiple sat senders in each bucket
;; ============================================================================

(deftest test-sort-report-sat-detail-shape
  (testing "ballers sorted by total descending, boosts/thanks by mindate ascending"
    (let [now (java.util.Date.)
          baller-a {:boostagram/sender_name_normalized "baller-a"
                    :boostagram/value_sat_total 60000
                    :boostagram/podcast "Test"
                    :boostagram/episode "Ep"
                    :boostagram/app_name "App"
                    :boostagram/message "Biggest baller"
                    :invoice/created_at now
                    :invoice/creation_date 1000
                    :scraper/source "lnd"}
          baller-b {:boostagram/sender_name_normalized "baller-b"
                    :boostagram/value_sat_total 30000
                    :boostagram/podcast "Test"
                    :boostagram/episode "Ep"
                    :boostagram/app_name "App"
                    :boostagram/message "Smaller baller"
                    :invoice/created_at now
                    :invoice/creation_date 2000
                    :scraper/source "lnd"}
          boost-a {:boostagram/sender_name_normalized "boost-a"
                   :boostagram/value_sat_total 5000
                   :boostagram/podcast "Test"
                   :boostagram/episode "Ep"
                   :boostagram/app_name "App"
                   :boostagram/message "Boost A"
                   :invoice/created_at now
                   :invoice/creation_date 3000
                   :scraper/source "lnd"}
          boost-b {:boostagram/sender_name_normalized "boost-b"
                   :boostagram/value_sat_total 3000
                   :boostagram/podcast "Test"
                   :boostagram/episode "Ep"
                   :boostagram/app_name "App"
                   :boostagram/message "Boost B"
                   :invoice/created_at now
                   :invoice/creation_date 4000
                   :scraper/source "lnd"}
          thanks-a {:boostagram/sender_name_normalized "thanks-a"
                    :boostagram/value_sat_total 500
                    :boostagram/podcast "Test"
                    :boostagram/episode "Ep"
                    :boostagram/app_name "App"
                    :boostagram/message "Thanks A"
                    :invoice/created_at now
                    :invoice/creation_date 5000
                    :scraper/source "lnd"}
          thanks-b {:boostagram/sender_name_normalized "thanks-b"
                    :boostagram/value_sat_total 100
                    :boostagram/podcast "Test"
                    :boostagram/episode "Ep"
                    :boostagram/app_name "App"
                    :boostagram/message "Thanks B"
                    :invoice/created_at now
                    :invoice/creation_date 6000
                    :scraper/source "lnd"}
          raw [[["baller-a" 60000 1 1000 [baller-a]]
                ["baller-b" 30000 1 2000 [baller-b]]]
               [["boost-a" 5000 1 3000 [boost-a]]
                ["boost-b" 3000 1 4000 [boost-b]]]
               [["thanks-a" 500 1 5000 [thanks-a]]
                ["thanks-b" 100 1 6000 [thanks-b]]]
               [] []
               [98500 6 6] [0 0 0] [98500 6 6] 6000
               [] [] []]
          result (reports/sort-report raw)]
      (is (= "baller-a" (-> result :ballers (nth 0) :sender)))
      (is (= "baller-b" (-> result :ballers (nth 1) :sender)))
      (is (= "boost-a" (-> result :boosts (nth 0) :sender)))
      (is (= "boost-b" (-> result :boosts (nth 1) :sender)))
      (is (= "thanks-a" (-> result :thanks (nth 0) :sender)))
      (is (= "thanks-b" (-> result :thanks (nth 1) :sender))))))

;; ============================================================================
;; format-sorted-report: rendering
;; ============================================================================

(deftest test-format-sorted-report-all-sections
  (testing "renders all sections with data"
    (let [now (java.util.Date.)
          creation 2000000000
          sorted {:ballers [{:sender "big-spender"
                             :total 50000
                             :count 1
                             :mindate creation
                             :boosts [{:boostagram/sender_name_normalized "big-spender"
                                       :boostagram/value_sat_total 50000
                                       :boostagram/podcast "LINUX Unplugged"
                                       :boostagram/episode "Baller Ep"
                                       :boostagram/app_name "Fountain"
                                       :boostagram/ts 1000
                                       :boostagram/message "Baller message!"
                                       :invoice/creation_date creation
                                       :invoice/created_at now
                                       :scraper/source "lnd"}]}]
                  :boosts [{:sender "mid-spender"
                            :total 10000
                            :count 1
                            :mindate 2000000005
                            :boosts [{:boostagram/value_sat_total 10000
                                      :boostagram/message "Mid message"
                                      :invoice/creation_date 2000000005
                                      :invoice/created_at now}]}]
                  :thanks [{:sender "small-spender"
                            :total 500
                            :count 1
                            :mindate 2000000008
                            :boosts [{:boostagram/value_sat_total 500
                                      :boostagram/message "Thanks!"
                                      :invoice/creation_date 2000000008
                                      :invoice/created_at now}]}]
                  :fiat-boosts [{:sender "fiat-user"
                                 :total 1000
                                 :count 1
                                 :boosts [{:boostagram/sender_name_normalized "fiat-user"
                                           :boostagram/amount_fiat_cents 1000
                                           :boostagram/amount_fiat_currency "USD"
                                           :boostagram/payment_rail "card"
                                           :boostagram/podcast "LINUX Unplugged"
                                           :boostagram/episode "Fiat Ep"
                                           :boostagram/app_name "Zaprite"
                                           :boostagram/message "Card payment!"
                                           :invoice/creation_date 2000000015
                                           :invoice/created_at now
                                           :scraper/source "zaprite"}]}]
                  :member-free-boosts [{:sender "free-user"
                                        :count 1
                                        :boosts [{:boostagram/sender_name_normalized "free-user"
                                                  :boostagram/payment_rail "member-free"
                                                  :boostagram/memberful_member_id "42"
                                                  :boostagram/podcast "LINUX Unplugged"
                                                  :boostagram/episode "Free Ep"
                                                  :boostagram/message "Free boost!"
                                                  :invoice/creation_date 2000000020
                                                  :invoice/created_at now
                                                  :scraper/source "r2-member"}]}]
                  :boost-summary {:boost_total_sats 60500
                                  :boost_total_boosts 3
                                  :boost_total_boosters 3}
                  :stream-summary {:stream_total_sats 0
                                   :stream_total_streams 0
                                   :stream_total_streamers 0}
                  :summary {:total_sats 60500
                            :total_invoices 5
                            :total_unique_boosters 5
                            :last_seen_id 42}}
          output (reports/format-sorted-report sorted)]
      (is (str/includes? output "## Baller Boosts"))
      (is (str/includes? output "## Boosts"))
      (is (str/includes? output "## Thanks"))
      (is (str/includes? output "## Fiat Boosts"))
      (is (str/includes? output "## Member Free Boosts"))
      (is (str/includes? output "## Boost Summary"))
      (is (str/includes? output "## Stream Summary"))
      (is (str/includes? output "## Summary"))
      (is (str/includes? output "## Last Seen"))
      (is (str/includes? output "big-spender"))
      (is (str/includes? output "50,000 sats"))
      (is (str/includes? output "fiat-user"))
      (is (str/includes? output "$10.00 (card)"))
      (is (str/includes? output "free-user"))
      (is (str/includes? output "Free Member Boost"))
      (is (str/includes? output "Total Boosted Sats: 60,500"))
      (is (str/includes? output "Total Sats: 60,500"))
      (is (str/includes? output "Total Fiat Boosts: 1 (1 booster)"))
      (is (str/includes? output "Total Member Free Boosts: 1 (1 member)"))
      (is (str/includes? output "Last seen ID: 42")))))

(deftest test-format-sorted-report-empty-sections
  (testing "empty fiat-boosts and member-free-boosts omit their sections"
    (let [sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts []
                  :member-free-boosts []
                  :boost-summary {:boost_total_sats 0
                                  :boost_total_boosts 0
                                  :boost_total_boosters 0}
                  :stream-summary {:stream_total_sats 0
                                   :stream_total_streams 0
                                   :stream_total_streamers 0}
                  :summary {:total_sats 0
                            :total_invoices 0
                            :total_unique_boosters 0
                            :last_seen_id nil}}
          output (reports/format-sorted-report sorted)]
      (is (str/includes? output "## Baller Boosts"))
      (is (str/includes? output "## Boosts"))
      (is (str/includes? output "## Thanks"))
      (is (str/includes? output "## Boost Summary"))
      (is (str/includes? output "## Stream Summary"))
      (is (str/includes? output "## Summary"))
      (is (str/includes? output "## Last Seen"))
      (is (not (str/includes? output "## Fiat Boosts")))
      (is (not (str/includes? output "## Member Free Boosts")))
      (is (not (str/includes? output "Total Fiat Boosts")))
      (is (not (str/includes? output "Total Member Free Boosts")))
      (is (str/includes? output "Total Boosted Sats: 0"))
      (is (str/includes? output "Last seen ID: ")))))

(deftest test-format-sorted-report-partial-sections
  (testing "fiat-boosts present, member-free absent (and vice versa)"
    (let [now (java.util.Date.)
          fiat-result (reports/format-sorted-report
                       {:ballers []
                        :boosts []
                        :thanks []
                        :fiat-boosts [{:sender "fiat-only"
                                       :total 500
                                       :count 1
                                       :boosts [{:boostagram/sender_name_normalized "fiat-only"
                                                 :boostagram/amount_fiat_cents 500
                                                 :boostagram/amount_fiat_currency "USD"
                                                 :boostagram/payment_rail "card"
                                                 :boostagram/message "Fiat only"
                                                 :invoice/creation_date 2000000000
                                                 :invoice/created_at now}]}]
                        :member-free-boosts []
                        :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                        :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                        :summary {:total_sats 0 :total_invoices 1 :total_unique_boosters 1 :last_seen_id 1}})
          free-result (reports/format-sorted-report
                       {:ballers []
                        :boosts []
                        :thanks []
                        :fiat-boosts []
                        :member-free-boosts [{:sender "free-only"
                                              :count 1
                                              :boosts [{:boostagram/sender_name_normalized "free-only"
                                                        :boostagram/payment_rail "member-free"
                                                        :boostagram/message "Free only"
                                                        :invoice/creation_date 2000000000
                                                        :invoice/created_at now}]}]
                        :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                        :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                        :summary {:total_sats 0 :total_invoices 1 :total_unique_boosters 1 :last_seen_id 2}})]
      (is (str/includes? fiat-result "## Fiat Boosts"))
      (is (not (str/includes? fiat-result "## Member Free Boosts")))
      (is (str/includes? fiat-result "Total Fiat Boosts: 1 (1 booster)"))
      (is (not (str/includes? fiat-result "Total Member Free Boosts")))
      (is (not (str/includes? free-result "## Fiat Boosts")))
      (is (str/includes? free-result "## Member Free Boosts"))
      (is (not (str/includes? free-result "Total Fiat Boosts")))
      (is (str/includes? free-result "Total Member Free Boosts: 1 (1 member)")))))

(deftest test-format-sorted-report-summary-lines
  (testing "pluralization and counts with multiple senders"
    (let [now (java.util.Date.)
          sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts [{:sender "fiat1" :total 1000 :count 2
                                 :boosts [{:boostagram/sender_name_normalized "fiat1"
                                           :boostagram/amount_fiat_cents 500
                                           :boostagram/amount_fiat_currency "USD"
                                           :boostagram/payment_rail "card"
                                           :invoice/creation_date 2000000000
                                           :invoice/created_at now}
                                          {:boostagram/sender_name_normalized "fiat1"
                                           :boostagram/amount_fiat_cents 500
                                           :boostagram/payment_rail "card"
                                           :invoice/creation_date 2000000001
                                           :invoice/created_at now}]}
                                {:sender "fiat2" :total 500 :count 1
                                 :boosts [{:boostagram/sender_name_normalized "fiat2"
                                           :boostagram/amount_fiat_cents 500
                                           :boostagram/amount_fiat_currency "USD"
                                           :boostagram/payment_rail "card"
                                           :invoice/creation_date 2000000002
                                           :invoice/created_at now}]}
                                {:sender "fiat3" :total 250 :count 1
                                 :boosts [{:boostagram/sender_name_normalized "fiat3"
                                           :boostagram/amount_fiat_cents 250
                                           :boostagram/amount_fiat_currency "USD"
                                           :boostagram/payment_rail "card"
                                           :invoice/creation_date 2000000003
                                           :invoice/created_at now}]}]
                  :member-free-boosts [{:sender "free1" :count 3
                                        :boosts (vec (repeat 3
                                                             {:boostagram/sender_name_normalized "free1"
                                                              :boostagram/payment_rail "member-free"
                                                              :invoice/creation_date 2000000010
                                                              :invoice/created_at now}))}
                                       {:sender "free2" :count 2
                                        :boosts (vec (repeat 2
                                                             {:boostagram/sender_name_normalized "free2"
                                                              :boostagram/payment_rail "member-free"
                                                              :invoice/creation_date 2000000020
                                                              :invoice/created_at now}))}]
                  :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                  :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                  :summary {:total_sats 0 :total_invoices 9 :total_unique_boosters 5 :last_seen_id 1}}
          output (reports/format-sorted-report sorted)]
      (is (str/includes? output "Total Fiat Boosts: 4 (3 boosters)"))
      (is (str/includes? output "Total Member Free Boosts: 5 (2 members)"))))
  (testing "single sender uses singular form"
    (let [now (java.util.Date.)
          sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts [{:sender "lonely" :total 100 :count 1
                                 :boosts [{:boostagram/sender_name_normalized "lonely"
                                           :boostagram/amount_fiat_cents 100
                                           :boostagram/amount_fiat_currency "USD"
                                           :boostagram/payment_rail "card"
                                           :invoice/creation_date 2000000000
                                           :invoice/created_at now}]}]
                  :member-free-boosts [{:sender "solo" :count 1
                                        :boosts [{:boostagram/sender_name_normalized "solo"
                                                  :boostagram/payment_rail "member-free"
                                                  :invoice/creation_date 2000000010
                                                  :invoice/created_at now}]}]
                  :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                  :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                  :summary {:total_sats 0 :total_invoices 2 :total_unique_boosters 2 :last_seen_id 1}}
          output (reports/format-sorted-report sorted)]
      (is (str/includes? output "Total Fiat Boosts: 1 (1 booster)"))
      (is (str/includes? output "Total Member Free Boosts: 1 (1 member)"))))
  (testing "nil fiat-boosts and member-free-boosts — no crash, no lines"
    (let [sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts nil
                  :member-free-boosts nil
                  :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                  :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                  :summary {:total_sats 0 :total_invoices 0 :total_unique_boosters 0 :last_seen_id nil}}
          output (reports/format-sorted-report sorted)]
      (is (not (str/includes? output "Total Fiat Boosts")))
      (is (not (str/includes? output "Total Member Free Boosts"))))))

;; ============================================================================
;; format-value-line edge cases
;; ============================================================================

(deftest test-format-value-line-edge-cases
  (testing "large sats values get comma-separated"
    (is (= "1,000,000 sats"
           (reports/format-value-line {:boostagram/value_sat_total 1000000}))))
  (testing "nil value_sat_total defaults to 0 sats"
    (is (= "0 sats" (reports/format-value-line {}))))
  (testing "0 fiat cents falls through to sats display"
    (is (= "5,000 sats"
           (reports/format-value-line {:boostagram/amount_fiat_cents 0
                                       :boostagram/value_sat_total 5000}))))
  (testing "fiat with nil payment_rail shows unknown"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 999
                 :boostagram/payment_rail nil})]
      (is (str/includes? line "$9.99"))
      (is (str/includes? line "unknown"))))
  (testing "fiat takes priority even with high sats value"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 2500
                 :boostagram/value_sat_total 100000})]
      (is (str/includes? line "$25.00"))
      (is (not (str/includes? line "sats")))))
  (testing "fiat value uses String/format (no comma separators)"
    (let [line (reports/format-value-line
                {:boostagram/amount_fiat_cents 1234567
                 :boostagram/payment_rail "ach"})]
      (is (str/includes? line "$12345.67"))
      (is (str/includes? line "(ach)"))))
  (testing "fiat precision at boundaries"
    (is (str/includes?
         (reports/format-value-line {:boostagram/amount_fiat_cents 1
                                     :boostagram/payment_rail "card"})
         "$0.01"))
    (is (str/includes?
         (reports/format-value-line {:boostagram/amount_fiat_cents 99
                                     :boostagram/payment_rail "card"})
         "$0.99"))))

;; ============================================================================
;; normalize-report: ReportsSchema with missing keys
;; ============================================================================

(deftest test-normalize-report-missing-keys
  (testing "empty map returns defaults for vector keys, nil for map keys"
    (let [result (reports/normalize-report {})]
      ;; Vector keys have :default [] -- populated even when missing
      (is (= [] (:ballers result)))
      (is (= [] (:boosts result)))
      (is (= [] (:thanks result)))
      (is (= [] (:fiat-boosts result)))
      (is (= [] (:member-free-boosts result)))
      ;; Top-level map keys have no :default -- return nil when absent
      (is (nil? (:boost-summary result)))
      (is (nil? (:stream-summary result)))
      (is (nil? (:summary result)))))

  (testing "partial keys preserved, missing sub-keys get defaults within provided maps"
    (let [result (reports/normalize-report
                  {:ballers [{:sender "test" :total 50000}]
                   :boost-summary {:boost_total_sats 5000}
                   :summary {:total_sats 5000 :last_seen_id 42}})]
      (is (= 1 (count (:ballers result))))
      (is (empty? (:boosts result)))
      (is (empty? (:thanks result)))
      (is (empty? (:fiat-boosts result)))
      (is (empty? (:member-free-boosts result)))
      ;; Provided map keys are preserved
      (is (= 5000 (:boost_total_sats (:boost-summary result))))
      ;; Missing sub-keys within provided maps get defaults via schema
      (is (= 0 (:boost_total_boosts (:boost-summary result))))
      ;; stream-summary key not present in partial input
      (is (nil? (:stream-summary result)))
      ;; Provided summary map
      (is (= 5000 (:total_sats (:summary result))))
      (is (= 0 (:total_invoices (:summary result))))
      (is (= 42 (:last_seen_id (:summary result))))))

  (testing "boost-report output (all keys present) roundtrips through normalize"
    (let [sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts []
                  :member-free-boosts []
                  :boost-summary {:boost_total_sats 100 :boost_total_boosts 2 :boost_total_boosters 2}
                  :stream-summary {:stream_total_sats 50 :stream_total_streams 1 :stream_total_streamers 1}
                  :summary {:total_sats 150 :total_invoices 3 :total_unique_boosters 3 :last_seen_id 99}}
          normalized (reports/normalize-report sorted)]
      (is (= 100 (:boost_total_sats (:boost-summary normalized))))
      (is (= 50 (:stream_total_sats (:stream-summary normalized))))
      (is (= 150 (:total_sats (:summary normalized))))
      (is (= 99 (:last_seen_id (:summary normalized)))))))

;; ============================================================================
;; boost-report integration: full pipeline (query -> sort -> normalize -> format)
;; These tests exercise the full pipeline with a real Datalevin connection.
;; NOTE: get-boost-summary-for-report uses nested datalevin.core/q calls
;; within the query. The basic path is verified below.
;; ============================================================================

(deftest test-boost-report-integration
  (testing "full pipeline with real Datalevin query produces formatted report"
    (let [tmpdir (str "/tmp/test-boost-report-int-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)
          sat-epoch 2000000000
          fiat-epoch 2000000001
          free-epoch 2000000002]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "big-spender"
                       :boostagram/value_sat_total 50000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Test Ep"
                       :boostagram/app_name "Fountain"
                       :boostagram/message "Baller boost!"
                       :boostagram/ts 1000
                       :invoice/creation_date sat-epoch
                       :invoice/created_at (java.util.Date. 1000000)
                       :boostagram/content_id "c1"
                       :scraper/source "lnd"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/type :fiat
                       :boostagram/sender_name_normalized "fiat-user"
                       :boostagram/value_sat_total 0
                       :boostagram/amount_fiat_cents 2000
                       :boostagram/amount_fiat_currency "USD"
                       :boostagram/payment_rail "card"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Test Ep"
                       :boostagram/app_name "Zaprite"
                       :boostagram/message "Card boost"
                       :invoice/creation_date fiat-epoch
                       :invoice/created_at (java.util.Date. 2000000)
                       :boostagram/content_id "c2"
                       :scraper/source "zaprite"}
                      {:invoice/identifier "b3"
                       :boostagram/action "boost"
                       :boostagram/type :member-free
                       :boostagram/sender_name_normalized "free-user"
                       :boostagram/value_sat_total 0
                       :boostagram/payment_rail "member-free"
                       :boostagram/memberful_member_id "42"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Test Ep"
                       :boostagram/app_name "Memberful (Free)"
                       :boostagram/message "Free boost"
                       :invoice/creation_date free-epoch
                       :invoice/created_at (java.util.Date. 3000000)
                       :boostagram/content_id "c3"
                       :scraper/source "r2-member"}])
        (let [report-str (reports/boost-report conn #"LINUX Unplugged" 0)]
          (is (str/includes? report-str "## Baller Boosts"))
          (is (str/includes? report-str "## Fiat Boosts"))
          (is (str/includes? report-str "## Member Free Boosts"))
          (is (str/includes? report-str "## Boost Summary"))
          (is (str/includes? report-str "## Stream Summary"))
          (is (str/includes? report-str "## Summary"))
          (is (str/includes? report-str "## Last Seen"))
          (is (str/includes? report-str "big-spender"))
          (is (str/includes? report-str "50,000 sats"))
          (is (str/includes? report-str "$20.00 (card)"))
          (is (str/includes? report-str "Free Member Boost"))
          (is (str/includes? report-str "Total Boosted Sats: 50,000"))
          (is (str/includes? report-str "Total Boosts: 1"))
          (is (str/includes? report-str "Total Streamed Sats: 0"))
          (is (str/includes? report-str "Last seen ID: 2000000002")))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-boost-report-integration-empty
  (testing "empty report when no boosts match regex"
    (let [tmpdir (str "/tmp/test-boost-report-int-empty-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user"
                       :boostagram/value_sat_total 1000
                       :boostagram/podcast "Other Show"
                       :boostagram/episode "Ep"
                       :boostagram/message "Wont match"
                       :invoice/creation_date 1000
                       :boostagram/content_id "c1"
                       :scraper/source "lnd"}])
        (let [report-str (reports/boost-report conn #"NONEXISTENT" 0)]
          (is (str/includes? report-str "## Baller Boosts"))
          (is (str/includes? report-str "## Boost Summary"))
          (is (str/includes? report-str "Total Boosted Sats: 0"))
          (is (not (str/includes? report-str "## Fiat Boosts")))
          (is (not (str/includes? report-str "## Member Free Boosts"))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-boost-report-integration-regex-matching
  (testing "report matches by episode or podcast using re-matches (full string match)"
    (let [tmpdir (str "/tmp/test-boost-report-regex-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     ;; re-matches requires FULL string match.  Use exact values.
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user-a"
                       :boostagram/value_sat_total 50000
                       :boostagram/podcast "nope"
                       :boostagram/episode "LUP 500" ;; exact match for #"LUP 500"
                       :boostagram/app_name "Fountain"
                       :boostagram/message "Episode match!"
                       :invoice/creation_date 1000
                       :boostagram/content_id "c1"
                       :scraper/source "lnd"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user-b"
                       :boostagram/value_sat_total 3000
                       :boostagram/podcast "LINUX Unplugged" ;; exact match for #"LINUX Unplugged"
                       :boostagram/episode "nope"
                       :boostagram/app_name "Podverse"
                       :boostagram/message "Podcast match!"
                       :invoice/creation_date 2000
                       :boostagram/content_id "c2"
                       :scraper/source "lnd"}
                      {:invoice/identifier "b3"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user-c"
                       :boostagram/value_sat_total 1000
                       :boostagram/podcast "Other Show"
                       :boostagram/episode "Other Ep"
                       :boostagram/app_name "Alby"
                       :boostagram/message "Excluded"
                       :invoice/creation_date 3000
                       :boostagram/content_id "c3"
                       :scraper/source "lnd"}])
        (let [report-str (reports/boost-report conn #"LUP 500|LINUX Unplugged" 0)]
          (is (str/includes? report-str "user-a") "episode-matched sender")
          (is (str/includes? report-str "user-b") "podcast-matched sender")
          (is (not (str/includes? report-str "user-c")) "non-matching sender excluded")
          (is (str/includes? report-str "50,000 sats"))
          (is (str/includes? report-str "3,000 sats")))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

;; ============================================================================
;; first-booster: DB-dependent, finds earliest stream sender for an episode
;; ============================================================================

(deftest test-first-booster
  (testing "returns earliest stream sender for an episode"
    (let [tmpdir (str "/tmp/test-first-booster-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "s1"
                       :boostagram/action "stream"
                       :boostagram/episode "Ep1"
                       :boostagram/sender_name "Alice"
                       :boostagram/value_sat_total 1000
                       :invoice/creation_date 100
                       :boostagram/content_id "c1"
                       :scraper/source "test"}
                      {:invoice/identifier "s2"
                       :boostagram/action "stream"
                       :boostagram/episode "Ep1"
                       :boostagram/sender_name "Bob"
                       :boostagram/value_sat_total 2000
                       :invoice/creation_date 200
                       :boostagram/content_id "c2"
                       :scraper/source "test"}
                      ;; boost for same episode - must be excluded
                      {:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/episode "Ep1"
                       :boostagram/sender_name "Diana"
                       :boostagram/value_sat_total 5000
                       :invoice/creation_date 150
                       :boostagram/content_id "c3"
                       :scraper/source "test"}
                      ;; stream for different episode - must be excluded
                      {:invoice/identifier "s3"
                       :boostagram/action "stream"
                       :boostagram/episode "Ep2"
                       :boostagram/sender_name "Eve"
                       :boostagram/value_sat_total 4000
                       :invoice/creation_date 50
                       :boostagram/content_id "c4"
                       :scraper/source "test"}])
        (let [result (reports/first-booster conn "Ep1")]
          (is (some? result))
          (is (= "Alice" (:boostagram/sender_name result))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-first-booster-empty
  (testing "returns empty result when no streams match"
    (let [tmpdir (str "/tmp/test-first-booster-empty-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "s1"
                       :boostagram/action "stream"
                       :boostagram/episode "Ep1"
                       :boostagram/sender_name "Alice"
                       :boostagram/value_sat_total 1000
                       :invoice/creation_date 100
                       :boostagram/content_id "c1"
                       :scraper/source "test"}])
        (let [result (reports/first-booster conn "NonExistentEp")]
          (is (nil? result)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

;; ============================================================================
;; podcast-app-percentages: DB-dependent, calculates app share of boosts
;; ============================================================================

(deftest test-podcast-app-percentages
  (testing "calculates correct percentages with and without app_name"
    (let [tmpdir (str "/tmp/test-podcast-pct-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/app_name "Fountain"
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 1000
                       :boostagram/content_id "c1"
                       :scraper/source "test"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/app_name "Fountain"
                       :boostagram/sender_name_normalized "user2"
                       :boostagram/value_sat_total 2000
                       :boostagram/content_id "c2"
                       :scraper/source "test"}
                      ;; no app_name key at all -> get-else should return "unknown_app"
                      {:invoice/identifier "b3"
                       :boostagram/action "boost"
                       :boostagram/sender_name_normalized "user3"
                       :boostagram/value_sat_total 3000
                       :boostagram/content_id "c3"
                       :scraper/source "test"}
                      ;; no app_name key at all -> get-else should return "unknown_app"
                      {:invoice/identifier "b4"
                       :boostagram/action "boost"
                       :boostagram/sender_name_normalized "user4"
                       :boostagram/value_sat_total 4000
                       :boostagram/content_id "c4"
                       :scraper/source "test"}])
        (let [result (reports/podcast-app-percentages conn)
              result-set (into #{} result)]
          (is (= #{["Fountain" 50] ["unknown_app" 50]} result-set))
          (is (= 2 (count result))))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-podcast-app-percentages-all-same-app
  (testing "all boosts have the same app_name"
    (let [tmpdir (str "/tmp/test-podcast-pct-same-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/app_name "Fountain"
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 1000
                       :boostagram/content_id "c1"
                       :scraper/source "test"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/app_name "Fountain"
                       :boostagram/sender_name_normalized "user2"
                       :boostagram/value_sat_total 2000
                       :boostagram/content_id "c2"
                       :scraper/source "test"}])
        (let [result (reports/podcast-app-percentages conn)]
          (is (= [["Fountain" 100]] result)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-podcast-app-percentages-all-unknown
  (testing "all boosts lack app_name"
    (let [tmpdir (str "/tmp/test-podcast-pct-unk-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 1000
                       :boostagram/content_id "c1"
                       :scraper/source "test"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/sender_name_normalized "user2"
                       :boostagram/value_sat_total 2000
                       :boostagram/content_id "c2"
                       :scraper/source "test"}])
        (let [result (reports/podcast-app-percentages conn)]
          (is (= [["unknown_app" 100]] result)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-podcast-app-percentages-only-streams
  (testing "only stream records exist - no boosts at all"
    (let [tmpdir (str "/tmp/test-podcast-pct-streams-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "s1"
                       :boostagram/action "stream"
                       :boostagram/app_name "Fountain"
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 1000
                       :boostagram/content_id "c1"
                       :scraper/source "test"}])
        (let [result (reports/podcast-app-percentages conn)]
          (is (empty? result)))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-add-fiat-to-total
  (testing "nil rate — no conversion"
    (let [report {:fiat-boosts [{:sender "user" :total 1500 :count 1 :boosts []}]
                  :summary {:total_sats 0 :total_invoices 1 :total_unique_boosters 1}}
          result (reports/add-fiat-to-total report nil)]
      (is (= 0 (:total_sats (:summary result))))
      (is (nil? (:fiat-converted result)))
      (is (nil? (:fiat-skipped result)))))
  (testing "nil rate with fiat — sets fiat-skipped"
    (let [report {:fiat-boosts [{:sender "user" :total 1500 :count 1 :boosts []}]
                  :summary {:total_sats 1000 :total_invoices 1 :total_unique_boosters 1}}
          result (reports/add-fiat-to-total report {})]
      (is (= 1000 (:total_sats (:summary result))))
      (is (true? (:fiat-skipped result)))
      (is (nil? (:fiat-converted result)))))
  (testing "no fiat boosts — no conversion, no skipped"
    (let [report {:fiat-boosts []
                  :summary {:total_sats 1000 :total_invoices 1 :total_unique_boosters 1}}
          result (reports/add-fiat-to-total report {:rate 50000 :source "test"})]
      (is (= 1000 (:total_sats (:summary result))))
      (is (nil? (:fiat-converted result)))
      (is (nil? (:fiat-skipped result)))))
  (testing "converts fiat cents to sats with source metadata"
    (let [report {:fiat-boosts [{:sender "user" :total 1500 :count 1 :boosts []}]
                  :summary {:total_sats 0 :total_invoices 1 :total_unique_boosters 1}}
          result (reports/add-fiat-to-total report {:rate 50000 :source "test"})]
      (is (= 750000 (:total_sats (:summary result))))
      (is (= "test" (-> result :fiat-converted :source)))
      (is (= 50000 (-> result :fiat-converted :rate)))))
  (testing "adds to existing sats total"
    (let [report {:fiat-boosts [{:sender "user" :total 500 :count 1 :boosts []}]
                  :summary {:total_sats 100000 :total_invoices 2 :total_unique_boosters 2}}
          result (reports/add-fiat-to-total report {:rate 50000 :source "test"})]
      (is (= 350000 (:total_sats (:summary result))))))
  (testing "multiple fiat senders summed"
    (let [report {:fiat-boosts [{:sender "a" :total 1000 :count 1 :boosts []}
                                {:sender "b" :total 2000 :count 1 :boosts []}]
                  :summary {:total_sats 0 :total_invoices 2 :total_unique_boosters 2}}
          result (reports/add-fiat-to-total report {:rate 20000 :source "test"})]
      (is (= 600000 (:total_sats (:summary result)))))))

;; ============================================================================
;; source-breakdown: per-source aggregation
;; ============================================================================

(deftest test-sort-report-source-breakdown
  (testing "source breakdown merges sat/fiat/member-free results per source"
    (let [sat-results [["lnd" 50000 3] ["nodecan" 10000 1]]
          fiat-results [["zaprite" 3000 2]]
          member-results [["r2-member" 5]]
          raw [[] [] [] [] []
               [0 0 0] [0 0 0] [0 0 0] nil
               sat-results fiat-results member-results]
          result (reports/sort-report raw)
          sb (:source-breakdown result)]
      (is (= 4 (count sb)))
      (is (= 3 (:count (get sb "lnd"))))
      (is (= 50000 (:sats (get sb "lnd"))))
      (is (= 0 (:fiat-cents (get sb "lnd"))))
      (is (= 1 (:count (get sb "nodecan"))))
      (is (= 10000 (:sats (get sb "nodecan"))))
      (is (= 2 (:count (get sb "zaprite"))))
      (is (= 3000 (:fiat-cents (get sb "zaprite"))))
      (is (= 0 (:sats (get sb "zaprite"))))
      (is (= 5 (:count (get sb "r2-member")))))))

(deftest test-format-sorted-report-source-breakdown
  (testing "renders source breakdown section with counts, sats, and fiat"
    (let [sorted {:ballers []
                  :boosts []
                  :thanks []
                  :fiat-boosts []
                  :member-free-boosts []
                  :boost-summary {:boost_total_sats 0 :boost_total_boosts 0 :boost_total_boosters 0}
                  :stream-summary {:stream_total_sats 0 :stream_total_streams 0 :stream_total_streamers 0}
                  :summary {:total_sats 0 :total_invoices 0 :total_unique_boosters 0 :last_seen_id 1}
                  :source-breakdown {"lnd" {:count 5 :sats 60000 :fiat-cents 0}
                                     "zaprite" {:count 2 :sats 0 :fiat-cents 3500}
                                     "r2-member" {:count 1 :sats 0 :fiat-cents 0}}}
          output (reports/format-sorted-report sorted)]
      (is (str/includes? output "## Source Breakdown"))
      (is (str/includes? output "lnd: 5 boosts — 60,000 sats"))
      (is (str/includes? output "zaprite: 2 boosts — 0 sats, $35.00 fiat"))
      (is (str/includes? output "r2-member: 1 boost — 0 sats")))))

(deftest test-boost-report-integration-source-breakdown
  (testing "full pipeline with multiple sources produces source breakdown in report"
    (let [tmpdir (str "/tmp/test-boost-report-src-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 50000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Fountain"
                       :boostagram/message "Sat boost"
                       :invoice/creation_date 2000000000
                       :invoice/created_at (java.util.Date. 1000000)
                       :boostagram/content_id "c1"
                       :scraper/source "lnd"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user2"
                       :boostagram/value_sat_total 3000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Alby"
                       :boostagram/message "Sat boost 2"
                       :invoice/creation_date 2000000001
                       :invoice/created_at (java.util.Date. 2000000)
                       :boostagram/content_id "c2"
                       :scraper/source "nodecan"}
                      {:invoice/identifier "b3"
                       :boostagram/action "boost"
                       :boostagram/type :fiat
                       :boostagram/sender_name_normalized "user3"
                       :boostagram/value_sat_total 0
                       :boostagram/amount_fiat_cents 5000
                       :boostagram/amount_fiat_currency "USD"
                       :boostagram/payment_rail "card"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Zaprite"
                       :boostagram/message "Fiat boost"
                       :invoice/creation_date 2000000002
                       :invoice/created_at (java.util.Date. 3000000)
                       :boostagram/content_id "c3"
                       :scraper/source "zaprite"}])
        (let [report-str (reports/boost-report conn #"LINUX Unplugged" 0)]
          (is (str/includes? report-str "## Source Breakdown"))
          (is (str/includes? report-str "lnd:"))
          (is (str/includes? report-str "nodecan:"))
          (is (str/includes? report-str "zaprite:")))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))

(deftest test-source-breakdown-excludes-streams
  (testing "source breakdown only counts boosts, not streams"
    (let [tmpdir (str "/tmp/test-src-breakdown-streams-" (java.util.UUID/randomUUID))
          conn (d/get-conn tmpdir db/schema)]
      (try
        (d/transact! conn
                     ;; 2 sat boosts from lnd
                     [{:invoice/identifier "b1"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user1"
                       :boostagram/value_sat_total 50000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Fountain"
                       :boostagram/message "Boost 1"
                       :invoice/creation_date 2000000000
                       :invoice/created_at (java.util.Date. 1000000)
                       :boostagram/content_id "c1"
                       :scraper/source "lnd"}
                      {:invoice/identifier "b2"
                       :boostagram/action "boost"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "user2"
                       :boostagram/value_sat_total 3000
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Fountain"
                       :boostagram/message "Boost 2"
                       :invoice/creation_date 2000000001
                       :invoice/created_at (java.util.Date. 2000000)
                       :boostagram/content_id "c2"
                       :scraper/source "lnd"}
                      ;; 500 streams from lnd — must NOT appear in source breakdown
                      {:invoice/identifier "s1"
                       :boostagram/action "stream"
                       :boostagram/type :sat
                       :boostagram/sender_name_normalized "streamer1"
                       :boostagram/value_sat_total 100
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Fountain"
                       :invoice/creation_date 2000000002
                       :invoice/created_at (java.util.Date. 3000000)
                       :boostagram/content_id "c3"
                       :scraper/source "lnd"}
                      ;; 1 fiat boost from zaprite
                      {:invoice/identifier "b3"
                       :boostagram/action "boost"
                       :boostagram/type :fiat
                       :boostagram/sender_name_normalized "user3"
                       :boostagram/value_sat_total 0
                       :boostagram/amount_fiat_cents 5000
                       :boostagram/amount_fiat_currency "USD"
                       :boostagram/payment_rail "card"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Zaprite"
                       :boostagram/message "Fiat boost"
                       :invoice/creation_date 2000000003
                       :invoice/created_at (java.util.Date. 4000000)
                       :boostagram/content_id "c4"
                       :scraper/source "zaprite"}
                      ;; 1 member-free boost from r2-member
                      {:invoice/identifier "b4"
                       :boostagram/action "boost"
                       :boostagram/type :member-free
                       :boostagram/sender_name_normalized "user4"
                       :boostagram/value_sat_total 0
                       :boostagram/payment_rail "member-free"
                       :boostagram/podcast "LINUX Unplugged"
                       :boostagram/episode "Ep1"
                       :boostagram/app_name "Memberful (Free)"
                       :boostagram/message "Free boost"
                       :invoice/creation_date 2000000005
                       :invoice/created_at (java.util.Date. 6000000)
                       :boostagram/content_id "c6"
                       :scraper/source "r2-member"}])
        (let [report-str (reports/boost-report conn #"LINUX Unplugged" 0)]
          ;; lnd: 2 sat boosts (streams excluded by NOT filter)
          (is (re-find #"lnd: 2 boosts" report-str)
              "lnd source should count only sat boosts, not streams")
          ;; zaprite: 1 fiat boost (no action filter on fiat — no streams in fiat)
          (is (re-find #"zaprite: 1 boost" report-str)
              "zaprite source should count fiat boosts")
          ;; r2-member: 1 member-free boost (no action filter — no streams in member-free)
          (is (re-find #"r2-member: 1 boost" report-str)
              "r2-member source should count member-free boosts")
          ;; total line present
          (is (re-find #"Total: 4 boosts" report-str)
              "total line sums all sources"))
        (finally
          (d/close conn)
          (test-utils/delete-dir-recursively (io/file tmpdir)))))))


