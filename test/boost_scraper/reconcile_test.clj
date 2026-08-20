(ns boost-scraper.reconcile-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.reconcile :as rec]
            [boost-scraper.test-utils :as test-utils]
            [boost-scraper.upstream.zaprite :as zaprite]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

(def adam-order
  {:id "od_nVJ3uLtbZz"
   :currency "BTC"
   :totalAmount 88888
   :label "Web Boost: TWIB 118 — Adam Curry"
   :metadata {:app "web-boost"
              :username "Adam Curry"
              :podcastName "This Week in Bitcoin"
              :slug "twib"
              :episodeTitle "TWIB 118"
              :episodeNumber "118"
              :episodeGuid "guid-118"
              :message "Great show keep it up"}})

(def memphis-orders
  [{:id "od_bY1at35Vl9"
    :currency "BTC"
    :totalAmount 2222
    :label "Web Boost: LUP 670 — Memphis"
    :metadata {:app "web-boost" :username "memphis"}}
   {:id "od_vff0Bfkh8g"
    :currency "BTC"
    :totalAmount 2222
    :label "Web Boost: LUP 670 — Memphis"
    :metadata {:app "web-boost" :username "Memphis"}}])

(deftest test-parse-web-boost-memo
  (testing "canonical LND memo with em dash"
    (is (= {:show-slug "twib" :show-ep "118" :username "Adam Curry"
            :label "Payment for Web Boost: TWIB 118 — Adam Curry"}
           (rec/parse-web-boost-memo "Payment for Web Boost: TWIB 118 — Adam Curry"))))
  (testing "bare form (no prefix) observed in some DB entities"
    (is (= {:show-slug "twib" :show-ep "108" :username "debitcoinkoers.eu"
            :label "TWIB 108 — debitcoinkoers.eu"}
           (rec/parse-web-boost-memo "TWIB 108 — debitcoinkoers.eu"))))
  (testing "Zaprite label form"
    (is (= {:show-slug "twib" :show-ep "118" :username "Adam Curry"}
           (select-keys (rec/parse-web-boost-memo "Web Boost: TWIB 118 — Adam Curry")
                        [:show-slug :show-ep :username]))))
  (testing "separator variants: en dash and hyphen"
    (is (= "Adam" (:username (rec/parse-web-boost-memo "TWIB 118 – Adam"))))
    (is (= "Bob" (:username (rec/parse-web-boost-memo "LUP 12 - Bob")))))
  (testing "hyphenated slug"
    (is (= {:show-slug "the-launch" :show-ep "39" :username "Zap Fan"}
           (select-keys (rec/parse-web-boost-memo "THE-LAUNCH 39 — Zap Fan")
                        [:show-slug :show-ep :username]))))
  (testing "username may contain dashes, emoji, and spaces"
    (is (= "CronkADonkTheDoggo" (:username (rec/parse-web-boost-memo
                                            "Web Boost: TWIB 109 — CronkADonkTheDoggo"))))
    (is (= "Mr. Dash-Name ⚡" (:username (rec/parse-web-boost-memo "LUP 1 — Mr. Dash-Name ⚡")))))
  (testing "non-web-boost memos reject"
    (doseq [memo [nil ""
                  "Web Zap The Launch!"
                  "Launch 39 Web Zap ⚡"
                  "Texas Linux Festival Trip Support"
                  "Payment for Web Boost: TWIB"
                  "Payment for Web Boost: 118 — Adam Curry"]]
      (is (nil? (rec/parse-web-boost-memo memo)) (pr-str memo)))
    (testing "foundational boostagram memo is not a web-boost memo"
      (is (nil? (rec/parse-web-boost-memo "boost: TWIB 118 — Adam Curry"))))))

(deftest test-order-sats
  (testing "BTC order amount parses"
    (is (= 88888 (rec/order-sats adam-order))))
  (testing "string amount coerces"
    (is (= 2222 (rec/order-sats (assoc adam-order :totalAmount "2222")))))
  (testing "non-BTC is nil"
    (is (nil? (rec/order-sats (assoc adam-order :currency "USD" :totalAmount 30)))))
  (testing "missing amount is nil"
    (is (nil? (rec/order-sats (dissoc adam-order :totalAmount))))))

(deftest test-candidate-id
  (testing "metadata username preferred"
    (is (= "adam curry" (rec/candidate-id adam-order))))
  (testing "label parse fallback when metadata missing"
    (is (= "adam curry" (rec/candidate-id (assoc-in adam-order [:metadata :username] nil))))))

(deftest test-euid-guard
  (let [euid-order (assoc adam-order
                          :externalUniqId
                          "web-boost:twib:8c07b3da-bcc6-43ee-b3ee-11c323ea4768:BTC:88888:6223c93d-c8da-4d77-b424-60868f4c7c03:17819f4e")
        target {:show-slug "twib" :show-ep "118" :username "adam curry" :sats 88888}]
    (testing "prefix parts parse"
      (is (= {:slug "twib" :currency "BTC" :amount "88888"}
             (rec/euid-parts euid-order))))
    (testing "non-web-boost euid rejected"
      (is (nil? (rec/euid-parts (assoc euid-order :externalUniqId "other:thing:1")))))
    (testing "missing euid skips the guard (legacy orders)"
      (is (true? (rec/euid-matches-target? adam-order target))))
    (testing "matching euid passes"
      (is (true? (rec/euid-matches-target? euid-order target))))
    (testing "euid slug mismatch fails even when label matches"
      (is (false? (rec/euid-matches-target?
                   (assoc euid-order :externalUniqId
                          "web-boost:lup:7c961d88-db34-466f-a68a-37e39de64352:BTC:88888:abc:def")
                   target))))
    (testing "euid amount mismatch fails"
      (is (false? (rec/euid-matches-target?
                   (assoc euid-order :externalUniqId
                          "web-boost:twib:8c07b3da-bcc6-43ee-b3ee-11c323ea4768:BTC:99999:abc:def")
                   target))))
    (testing "euid guard excludes from match (label agrees, euid disagrees)"
      (let [wrong-euid (assoc euid-order :externalUniqId
                              "web-boost:lup:7c961d88-db34-466f-a68a-37e39de64352:BTC:88888:abc:def")
            result (rec/match-order-candidates [wrong-euid] target)]
        (is (= :none (:confidence result)))))
    (testing "euid guard passes -> high confidence"
      (is (= :high (:confidence (rec/match-order-candidates [euid-order] target))))))
  (testing "memphis pair: identical euid prefix but distinct tails -> manual review"
    (let [pair (map (fn [o t]
                      (assoc o :externalUniqId
                             (str "web-boost:lup:7c961d88-db34-466f-a68a-37e39de64352"
                                  ":BTC:2222:" t)))
                    memphis-orders ["token-a:fpr-a" "token-b:fpr-b"])
          target {:show-slug "lup" :show-ep "670" :username "memphis" :sats 2222}
          result (rec/match-order-candidates pair target)]
      (is (= :manual-review (:confidence result)))
      (is (= 2 (count (:candidates result)))))))

(deftest test-match-order-candidates
  (let [target {:show-slug "twib" :show-ep "118" :username "adam curry" :sats 88888}]
    (testing "single high-confidence match"
      (let [result (rec/match-order-candidates [adam-order] target)]
        (is (= :high (:confidence result)))
        (is (= "od_nVJ3uLtbZz" (:id (:order result))))))
    (testing "username case-insensitive (metadata vs memo)"
      (let [result (rec/match-order-candidates [adam-order]
                                               (assoc target :username "ADAM CURRY"))]
        (is (= :high (:confidence result)))))
    (testing "amount mismatch excludes"
      (let [result (rec/match-order-candidates [adam-order]
                                               (assoc target :sats 88887))]
        (is (= :none (:confidence result)))))
    (testing "non-web-boost order excluded"
      (let [result (rec/match-order-candidates
                    [(assoc-in adam-order [:metadata :app] "other")] target)]
        (is (= :none (:confidence result)))))
    (testing "label-disagreement on show/ep excludes"
      (let [wrong-show (assoc adam-order :label "Web Boost: LUP 999 — Adam Curry")
            result (rec/match-order-candidates [wrong-show] target)]
        (is (= :none (:confidence result))))))
  (testing "duplicate-label pair (memphis) -> manual review, both candidates"
    (let [target {:show-slug "lup" :show-ep "670" :username "memphis" :sats 2222}
          result (rec/match-order-candidates memphis-orders target)]
      (is (= :manual-review (:confidence result)))
      (is (nil? (:order result)))
      (is (= 2 (count (:candidates result))))))
  (testing "amount disambiguates a same-label pair"
    (let [target {:show-slug "lup" :show-ep "670" :username "memphis" :sats 2222}
          other (assoc (first memphis-orders) :totalAmount 9999)
          result (rec/match-order-candidates [other (second memphis-orders)] target)]
      (is (= :high (:confidence result)))
      (is (= "od_vff0Bfkh8g" (:id (:order result))))))
  (testing "no candidates -> none"
    (is (= :none (:confidence
                  (rec/match-order-candidates [] {:username "nobody" :sats 1}))))))

(deftest test-build-boost-entity
  (let [parsed (rec/parse-web-boost-memo "Payment for Web Boost: TWIB 118 — Adam Curry")
        info {:invoice-id "344542"
              :sats 88888
              :settle-date "2026-08-13T03:22:01Z"
              :creation-date "2026-08-13T03:21:22Z"}
        entity (rec/build-boost-entity parsed info adam-order)]
    (testing "upserts onto existing invoice identity"
      (is (= "344542" (:invoice/identifier entity))))
    (testing "order id anchors idempotency"
      (is (= "od_nVJ3uLtbZz" (:boostagram/zaprite_order_id entity))))
    (testing "LND ground truth fields"
      (is (= "nodecan" (:scraper/source entity)))
      (is (= 88888 (:boostagram/value_sat_total entity)))
      (is (= :sat (:boostagram/type entity)))
      (is (= "boost" (:boostagram/action entity)))
      (is (= "lightning" (:boostagram/payment_rail entity))))
    (testing "field precedence: settle timestamp wins for creation/received"
      (is (= 1786591321 (:invoice/creation_date entity)))
      (is (some? (:invoice/created_at entity)))
      (is (= (java.util.Date/from (java.time.Instant/parse "2026-08-13T03:22:01Z"))
             (:boostagram/received_at entity))))
    (testing "zaprite metadata enrichment"
      (is (= "Adam Curry" (:boostagram/sender_name entity)))
      (is (= "adam curry" (:boostagram/sender_name_normalized entity)))
      (is (= "Great show keep it up" (:boostagram/message entity)))
      (is (= "twib" (:boostagram/podcast_slug entity)))
      (is (= "This Week in Bitcoin" (:boostagram/podcast entity)))
      (is (= "TWIB 118" (:boostagram/episode entity)))
      (is (= "guid-118" (:boostagram/episode_guid entity)))))
  (testing "sats string coercsion"
    (let [parsed (rec/parse-web-boost-memo "LUP 673 — mg")
          entity (rec/build-boost-entity
                  parsed
                  {:invoice-id "336940" :sats "11111" :settle-date "2026-07-19T17:38:07Z"}
                  {:id "od_iGW1TRf8Ph"
                   :currency "BTC"
                   :totalAmount 11111
                   :metadata {:app "web-boost" :username "mg" :podcastName "LINUX Unplugged"
                              :episodeTitle "LUP 673"}})]
      (is (= 11111 (:boostagram/value_sat_total entity)))))
  (testing "episode falls back to memo when metadata lacks titles"
    (let [parsed (rec/parse-web-boost-memo "TWIB 108 — debitcoinkoers.eu")
          order (-> adam-order
                    (assoc :id "od_bofDf6orSH")
                    (assoc-in [:metadata :username] "debitcoinkoers.eu")
                    (assoc-in [:metadata :podcastName] "This Week in Bitcoin")
                    (update :metadata dissoc :episodeTitle :episodeNumber :episodeGuid))
          entity (rec/build-boost-entity
                  parsed
                  {:invoice-id "325043" :sats 10000 :settle-date "2026-06-13T13:34:50Z"}
                  order)]
      (is (= "108" (:boostagram/episode entity)))
      (is (= "twib" (:boostagram/podcast_slug entity)))
      (is (= "This Week in Bitcoin" (:boostagram/podcast entity)))
      (is (= 10000 (:boostagram/value_sat_total entity)))))
  (testing "podcast name resolves from shows registry when metadata lacks it"
    (let [parsed (rec/parse-web-boost-memo "LUP 670 — Other")
          order (assoc-in adam-order [:metadata :username] "other")
          order (update order :metadata dissoc :podcastName)
          entity (rec/build-boost-entity
                  parsed
                  {:invoice-id "342724" :sats 2222 :settle-date "2026-08-07T03:36:14Z"}
                  order)]
      (is (= "LINUX Unplugged" (:boostagram/podcast entity)))))
  (testing "missing settle date falls back to creation date"
    (let [parsed (rec/parse-web-boost-memo "TWIB 118 — Adam Curry")
          entity (rec/build-boost-entity
                  parsed
                  {:invoice-id "344542" :sats 88888 :creation-date "2026-08-13T03:21:22Z"}
                  adam-order)]
      (is (= 1786591282 (:invoice/creation_date entity)))
      (is (= (java.util.Date/from (java.time.Instant/parse "2026-08-13T03:21:22Z"))
             (:boostagram/received_at entity))))))

(deftest test-epoch-to-rfc3339
  (testing "epoch converts"
    (is (= "2026-08-13T03:22:01Z" (rec/epoch-to-rfc3339 1786591321))))
  (testing "nil and non-integer input are nil"
    (is (nil? (rec/epoch-to-rfc3339 nil)))
    (is (nil? (rec/epoch-to-rfc3339 "not-a-number")))))

(deftest test-find-web-boost-invoices
  (let [tmpdir (str "/tmp/test-reconcile-find-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn
                   [{:invoice/identifier "325042"
                     :invoice/memo "Payment for Web Boost: TWIB 108 — NorthLakeTaHodl"
                     :invoice/value 12345
                     :invoice/settled true
                     :invoice/settle_date "2026-06-13T13:09:09Z"
                     :invoice/creation_date 1786591321}
                    {:invoice/identifier "342723"
                     :invoice/memo "Payment for Web Boost: LUP 670 — Memphis"
                     :invoice/value 2222
                     :invoice/settled false
                     :invoice/creation_date 1786591321}
                    {:invoice/identifier "999999"
                     :invoice/memo "boost: TWIB 1 — Someone"
                     :invoice/value 100
                     :invoice/settled true
                     :invoice/creation_date 1786591321}
                    {:invoice/identifier "458909"
                     :invoice/memo "LUP 677 — mg"
                     :invoice/value 11111
                     :invoice/settled true
                     :invoice/creation_date 1786591321}])
      (let [result (rec/find-web-boost-invoices conn)
            ids (into #{} (map :invoice/identifier result))
            northlake (some #(when (= "325042" (:invoice/identifier %)) %) result)]
        (testing "settled web-boost memos only (bare memo form accepted)"
          (is (= #{"325042" "458909"} ids)))
        (testing "fields survive the pull"
          (is (= 12345 (:invoice/value northlake)))
          (is (= "2026-06-13T13:09:09Z" (:invoice/settle_date northlake)))
          (is (true? (:invoice/settled northlake))))
        (testing "settled falsy and non-web-boost memos excluded"
          (is (not (contains? ids "342723")))
          (is (not (contains? ids "999999")))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(deftest test-find-boosted-keys
  (let [tmpdir (str "/tmp/test-reconcile-keys-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn
                   [{:invoice/identifier "111" :boostagram/action "boost"}
                    {:invoice/identifier "222" :boostagram/zaprite_order_id "od_abc"}
                    {:invoice/identifier "333" :boostagram/action "stream"}
                    {:invoice/identifier "444"}])
      (let [keys (rec/find-boosted-keys conn)]
        (testing "identifiers only from entities with action=boost"
          (is (= #{"111"} (:identifiers keys))))
        (testing "order ids captured independently"
          (is (= #{"od_abc"} (:order-ids keys)))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(deftest test-fetch-pending-orders
  (testing "status=PENDING single param (array form is silently ignored by Zaprite)"
    (is (= {"status" "PENDING" "page" "3"}
           (rec/pending-orders-query 3)))
    (is (nil? (get (rec/pending-orders-query 1) "status[]"))))
  (testing "single page"
    (let [calls (atom [])
          fetch-fn (fn [key page]
                     (swap! calls conj [key page])
                     {:items [{:id "a"} {:id "b"}] :meta {:pagesCount 1}})]
      (is (= ["a" "b"] (mapv :id (rec/fetch-pending-orders* fetch-fn "k"))))
      (is (= [["k" 1]] @calls))))
  (testing "multi page accumulates"
    (let [fetch-fn (fn [_ page]
                     {:items [{:id page}]
                      :meta {:pagesCount 3}})]
      (is (= [1 2 3] (mapv :id (rec/fetch-pending-orders* fetch-fn "k"))))))
  (testing "nil response terminates early"
    (let [calls (atom 0)
          fetch-fn (fn [_ _] (swap! calls inc) nil)]
      (is (empty? (rec/fetch-pending-orders* fetch-fn "k")))
      (is (= 1 @calls))))
  (testing "missing pagesCount treated as single page"
    (let [fetch-fn (fn [_ page]
                     (when (= 1 page) {:items [{:id "only"}]}))]
      (is (= ["only"] (mapv :id (rec/fetch-pending-orders* fetch-fn "k"))))))
  (testing "empty items on multi-page still pages through"
    (let [fetch-fn (fn [_ _]
                     {:items [] :meta {:pagesCount 2}})]
      (is (empty? (rec/fetch-pending-orders* fetch-fn "k"))))))

(deftest test-detect-orphans
  (let [invoices [{:invoice/identifier "325043"
                   :invoice/memo "Payment for Web Boost: TWIB 108 — debitcoinkoers.eu"
                   :invoice/value 10000
                   :invoice/settle_date "2026-06-13T13:34:50Z"}
                  {:invoice/identifier "325042"
                   :invoice/memo "Payment for Web Boost: TWIB 108 — NorthLakeTaHodl"
                   :invoice/value 12345
                   :invoice/settle_date "2026-06-13T13:09:09Z"}
                  {:invoice/identifier "342724"
                   :invoice/memo "Payment for Web Boost: LUP 670 — Memphis"
                   :invoice/value 2222
                   :invoice/settle_date "2026-08-07T03:36:14Z"}
                  {:invoice/identifier "440000"
                   :invoice/memo "Payment for Web Boost: LUP 673 — okaygroovy"
                   :invoice/value 1000
                   :invoice/settle_date "2026-07-04T14:35:39Z"}]
        orders [{:id "od_bofDf6orSH" :currency "BTC" :totalAmount 10000
                 :label "Web Boost: TWIB 108 — debitcoinkoers.eu"
                 :metadata {:app "web-boost" :username "debitcoinkoers.eu"}}
                {:id "od_bY1at35Vl9" :currency "BTC" :totalAmount 2222
                 :label "Web Boost: LUP 670 — Memphis"
                 :metadata {:app "web-boost" :username "memphis"}}
                {:id "od_vff0Bfkh8g" :currency "BTC" :totalAmount 2222
                 :label "Web Boost: LUP 670 — Memphis"
                 :metadata {:app "web-boost" :username "Memphis"}}]
        boosted {:identifiers #{"325042"} :order-ids #{"od_done"}}
        result (rec/detect-orphans invoices orders boosted)]
    (testing "counts"
      (is (= 4 (:scanned result)))
      (is (= 1 (:already-boosted result)))
      (is (= 1 (count (:orphans result))))
      (is (= 1 (count (:manual-review result))))
      (is (= 1 (count (:unmatched result)))))
    (testing "sats accounting"
      (is (= 12345 (:total-sats-skipped result)))
      (is (= 13222 (:total-sats-orphaned result))))
    (testing "high-confidence orphan carries the ready entity"
      (let [orphan (first (:orphans result))]
        (is (= "od_bofDf6orSH" (:order-id orphan)))
        (is (= 10000 (:sats orphan)))
        (is (= "twib" (:show-slug orphan)))
        (is (= "debitcoinkoers.eu" (:username orphan)))
        (is (some? (:entity orphan)))
        (is (= "325043" (:invoice/identifier (:entity orphan))))))
    (testing "manual review carries both memphis candidates"
      (let [review (first (:manual-review result))]
        (is (= #{"od_bY1at35Vl9" "od_vff0Bfkh8g"}
               (into #{} (:candidates review))))))
    (testing "unmatched carries the invoice"
      (is (= "440000" (:identifier (first (:unmatched result)))))))
  (testing "order already reconciled via zaprite_order_id is skipped"
    (let [result (rec/detect-orphans
                  [{:invoice/identifier "344542"
                    :invoice/memo "Payment for Web Boost: TWIB 118 — Adam Curry"
                    :invoice/value 88888
                    :invoice/settle_date "2026-08-13T03:22:01Z"}]
                  [adam-order]
                  {:identifiers #{} :order-ids #{"od_nVJ3uLtbZz"}})]
      (is (= 1 (:already-boosted result)))
      (is (empty? (:orphans result)))))
  (testing "empty inputs"
    (let [result (rec/detect-orphans [] [] {:identifiers #{} :order-ids #{}})]
      (is (= 0 (:scanned result)))
      (is (empty? (:orphans result)))
      (is (= 0 (:total-sats-orphaned result))))))

(deftest test-reconcile-report
  (testing "includes summary, orphan rows, and unmatched"
    (let [detection {:scanned 2 :already-boosted 1
                     :orphans [{:identifier "325043" :username "debitcoinkoers.eu"
                                :show-slug "twib" :show-ep "108" :sats 10000
                                :settle-date "2026-06-13T13:34:50Z"
                                :order-id "od_bofDf6orSH"}]
                     :manual-review []
                     :unmatched [{:identifier "440000"
                                  :memo "Payment for Web Boost: LUP 673 — okaygroovy"
                                  :sats 1000}]
                     :total-sats-skipped 12345
                     :total-sats-orphaned 11000}
          md (rec/reconcile-report detection)]
      (is (str/includes? md "Orphans: 1 (11000 sats)"))
      (is (str/includes? md "`325043`"))
      (is (str/includes? md "od_bofDf6orSH"))
      (is (str/includes? md "`440000`"))))
  (testing "empty detection renders cleanly"
    (let [md (rec/reconcile-report {:scanned 0 :already-boosted 0
                                    :orphans [] :manual-review []
                                    :unmatched []
                                    :total-sats-skipped 0
                                    :total-sats-orphaned 0})]
      (is (str/includes? md "Orphans: 0 (0 sats)"))
      (is (str/includes? md "Unmatched: 0")))))

(deftest test-sync-web-boost-reconcile
  (let [tmpdir (str "/tmp/test-reconcile-sync-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn
                   [{:invoice/identifier "325043"
                     :invoice/memo "Payment for Web Boost: TWIB 108 — debitcoinkoers.eu"
                     :invoice/value 10000
                     :invoice/settled true
                     :invoice/settle_date "2026-06-13T13:34:50Z"}])
      (let [orders [{:id "od_bofDf6orSH"
                     :currency "BTC"
                     :totalAmount 10000
                     :label "Web Boost: TWIB 108 — debitcoinkoers.eu"
                     :metadata {:app "web-boost" :username "debitcoinkoers.eu"}}]
            broadcasts (atom [])
            run (fn []
                  (rec/sync-web-boost-reconcile!
                   conn "k"
                   {:allow-write? true
                    :fetch-pending (fn [_] orders)
                    :broadcast-fn (fn [payload] (swap! broadcasts conj payload))}))]
        (let [r1 (run)]
          (testing "first run writes the high-confidence orphan"
            (is (= 1 (:written r1)))
            (is (= 1 (count (:orphans r1))))
            (is (= 0 (:already-boosted r1)))))
        (testing "second run is idempotent"
          (let [r2 (run)]
            (is (= 0 (:written r2)))
            (is (= 1 (:already-boosted r2)))))
        (testing "exactly one boost entity exists, keyed by the LND identifier"
          (let [entities (d/q '[:find [?e ...] :where [?e :boostagram/action "boost"]]
                              (d/db conn))]
            (is (= 1 (count entities)))
            (let [ent (into {} (d/entity (d/db conn) (first entities)))]
              (is (= "325043" (:invoice/identifier ent)))
              (is (= "od_bofDf6orSH" (:boostagram/zaprite_order_id ent)))
              (is (= 10000 (:boostagram/value_sat_total ent))))))
        (testing "each write broadcasts the process-order key set"
          (is (= 1 (count @broadcasts)))
          (let [payload (first @broadcasts)]
            (is (= 10000 (:boostagram/value_sat_total payload)))
            (is (= "Zaprite" (:boostagram/app_name payload)))
            (is (= #{:boostagram/sender_name_normalized
                     :boostagram/value_sat_total
                     :boostagram/app_name
                     :boostagram/podcast
                     :boostagram/episode
                     :invoice/creation_date}
                   (set (keys payload)))
                "message is dropped here (empty in this fixture), matching process-order"))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(deftest test-dual-producer-merge
  (let [tmpdir (str "/tmp/test-reconcile-merge-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn
                   [{:invoice/identifier "325043"
                     :invoice/memo "Payment for Web Boost: TWIB 108 — debitcoinkoers.eu"
                     :invoice/value 10000
                     :invoice/settled true
                     :invoice/settle_date "2026-06-13T13:34:50Z"}])
      (let [order-id "od_bofDf6orSH"
            fetched-order {:id order-id
                           :currency "BTC"
                           :totalAmount 10000
                           :label "Web Boost: TWIB 108 — debitcoinkoers.eu"
                           :metadata {:app "web-boost" :username "debitcoinkoers.eu"}}
            complete-order (assoc fetched-order
                                  :paidAt "2026-06-13T13:35:00Z"
                                  :metadata (merge (:metadata fetched-order)
                                                   {:podcastName "This Week in Bitcoin"
                                                    :slug "twib"
                                                    :episodeTitle "TWIB 108"
                                                    :episodeGuid "guid-108"}))]
        (testing "reconcile write + later normal Zaprite sync converge on ONE entity
                  (probe-pinned; the unique zaprite_order_id is the merge point)"
          (rec/sync-web-boost-reconcile!
           conn "k"
           {:allow-write? true
            :fetch-pending (fn [_] [fetched-order])})
          (d/transact! conn [(db/remove-empty-vals (zaprite/process-order complete-order))])
          (let [entities (d/q '[:find [?e ...] :where [?e :boostagram/action "boost"]]
                              (d/db conn))]
            (is (= 1 (count entities)) "later sync must not create a second entity")
            (let [ent (into {} (d/entity (d/db conn) (first entities)))]
              (is (= (str "zaprite-" order-id) (:invoice/identifier ent))
                  "identifier re-keys to zaprite-<id> (harmless; order-id still anchors)")
              (is (= order-id (:boostagram/zaprite_order_id ent)))
              (is (= 10000 (:boostagram/value_sat_total ent)))
              (is (= "boost" (:boostagram/action ent)))
              (is (= :sat (:boostagram/type ent)))
              (is (= "Zaprite" (:boostagram/app_name ent)))))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))