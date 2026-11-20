(ns boost-scraper.publish-test
  "Contract tests for campaign publishing.

   These tests exercise serialization, the :no-rate refusal, and failure/timeout
   isolation using injected stubs — no real R2 calls and no network."
  (:require [boost-scraper.campaigns :as campaigns]
            [boost-scraper.db :as db]
            [boost-scraper.publish :as publish]
            [boost-scraper.test-utils :as test-utils]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]))

(def ^:dynamic *conn* nil)

(defn- delete-dir [dir]
  (when (.exists (java.io.File. dir))
    (test-utils/delete-dir-recursively dir)))

(defn- each-test-db [f]
  (let [dir (str "/tmp/test-publish-" (java.util.UUID/randomUUID))
        conn (d/get-conn dir db/schema)]
    (try
      (binding [*conn* conn] (f))
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (delete-dir dir)))))

(use-fixtures :each each-test-db)

(def T0 1767254400)
(def OPEN T0)
(def CLOSE (+ T0 (* 19 86400)))
(def NOW (+ OPEN 3600))
(def RATE {:btc_usd 100000.0 :at NOW :source "test"})

(def test-campaign
  {:campaign/slug "test-camp"
   :campaign/title "Test Campaign"
   :campaign/subtitle "One line of copy."
   :campaign/window {:opens OPEN :closes CLOSE}
   :campaign/tag "#700club"
   :campaign/also-episode "700"
   :campaign/target {:fiat-cents 200000}
   :campaign/policy {:actions #{:boost}
                     :count-fiat? true
                     :count-member-free? false
                     :exclude-self? true}
   :campaign/cta {:label "Boost with #700club"
                  :url "https://example.test/"}})

(defn- boost
  [{:keys [sender sats type cents action msg episode epoch oid iid podcast]
    :or   {sender "alice" sats 1000 type :sat action "boost"
           epoch (inc OPEN) episode "695" podcast "LINUX Unplugged"
           iid (str "inv-" (java.util.UUID/randomUUID))}}]
  (cond-> {:invoice/identifier iid
           :invoice/creation_date epoch
           :boostagram/action action
           :boostagram/podcast podcast
           :boostagram/episode episode
           :boostagram/sender_name_normalized sender
           :boostagram/type type
           :boostagram/value_sat_total sats}
    msg (assoc :boostagram/message msg)
    cents (assoc :boostagram/amount_fiat_cents cents)
    oid (assoc :boostagram/zaprite_order_id oid)))

(defn- insert! [& rows] (d/transact! *conn* (vec rows)))

(defn- silent-log [& _] nil)

(deftest test-serializes-contract-with-recent-capped-and-newest-first
  (doseq [i (range 25)]
    (insert! (boost {:sender (str "user-" i)
                     :sats (+ 1000 i)
                     :msg "#700club"
                     :epoch (+ OPEN 10 i)
                     :iid (str "r-" i)})))
  (let [puts (atom [])
        put-fn (fn [slug body]
                 (swap! puts conj {:slug slug :body body})
                 :ok)
        result (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket "b" :account-id "a" :access-key "k" :secret-key "s"}
                                            :put-fn put-fn
                                            :campaigns [test-campaign]
                                            :log-fn silent-log})
        payload (json/parse-string (:body (first @puts)) true)]
    (testing "one put for the campaign, reported as published"
      (is (= 1 (count @puts)))
      (is (= "test-camp" (:slug (first @puts))))
      (is (= [{:slug "test-camp" :result :published}] result)))
    (testing "contract keys are present with recent"
      (is (= #{:slug :title :subtitle :tag :goal :raised :split :remaining_usd
               :percent :days_left :opens_at :closes_at :generated_at :rate :cta :recent}
             (set (keys payload))))
      (is (= "test-camp" (:slug payload)))
      (is (= 2000.0 (get-in payload [:goal :usd]))))
    (testing "recent is capped at 20 and newest-first"
      (is (= 20 (count (:recent payload))))
      (is (= (sort > (map :at (:recent payload))) (mapv :at (:recent payload))))
      (is (> (:at (first (:recent payload))) (:at (last (:recent payload)))))
      (is (= (+ OPEN 10 24) (:at (first (:recent payload))))))
    (testing "recent entries carry the contract fields"
      (is (every? #(= #{:sats :usd :sender :at} (set (keys %)))
                  (:recent payload))
          "names are published; message text is not")
      (is (= "user-24" (:sender (first (:recent payload)))))
      (is (= 1024 (:sats (first (:recent payload))))))))

(deftest test-no-rate-skips-put-and-does-not-throw
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "nr-1"}))
  (with-redefs [campaigns/progress (fn [& _] {:status :no-rate})]
    (let [puts (atom 0)
          logs (atom [])
          put-fn (fn [_slug _body] (swap! puts inc) :ok)
          result (publish/publish-campaigns! *conn*
                                             {:now NOW
                                              :config {:bucket "b" :account-id "a" :access-key "k" :secret-key "s"}
                                              :put-fn put-fn
                                              :campaigns [test-campaign]
                                              :log-fn #(swap! logs conj %)})]
      (is (= 0 @puts) "no put attempted when rate is missing")
      (is (= [{:slug "test-camp" :result :skipped-no-rate}] result))
      (is (some #(re-find #"no-rate" %) @logs) "skip is logged"))))

(deftest test-put-failure-is-swallowed-and-logged
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "pf-1"}))
  (let [logs (atom [])
        put-fn (fn [_slug _body] (throw (ex-info "boom" {})))
        result (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket "b" :account-id "a" :access-key "k" :secret-key "s"}
                                            :put-fn put-fn
                                            :campaigns [test-campaign]
                                            :log-fn #(swap! logs conj %)})]
    (is (= [{:slug "test-camp" :result :error}] result))
    (is (some #(re-find #"failed" %) @logs))
    (is (seq @logs))))

(deftest test-put-timeout-is-swallowed-and-logged
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "pt-1"}))
  (let [logs (atom [])
        put-fn (fn [_slug _body] :boost-scraper.publish/timeout)
        result (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket "b" :account-id "a" :access-key "k" :secret-key "s"}
                                            :put-fn put-fn
                                            :campaigns [test-campaign]
                                            :log-fn #(swap! logs conj %)})]
    (is (= [{:slug "test-camp" :result :timeout}] result))
    (is (some #(re-find #"timed out" %) @logs))))

(deftest test-unset-bucket-config-publishes-nothing-and-logs-once
  (#'publish/reset-config-warning!)
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "uc-1"}))
  (let [puts (atom 0)
        logs (atom [])]
    ;; the once-only warning is process-global by design; reset it so this test
    ;; observes the first warning rather than whatever ran before it
    (#'publish/reset-config-warning!)
    (with-redefs [campaigns/progress (fn [& _] (throw (ex-info "should not compute" {})))]
      (let [r1 (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket nil}
                                            :put-fn (fn [_ _] (swap! puts inc) :ok)
                                            :campaigns [test-campaign]
                                            :log-fn #(swap! logs conj %)})
            r2 (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket nil}
                                            :put-fn (fn [_ _] (swap! puts inc) :ok)
                                            :campaigns [test-campaign]
                                            :log-fn #(swap! logs conj %)})]
        (is (= [] r1))
        (is (= [] r2))
        (is (= 0 @puts))
        (is (= 1 (count @logs)) "unset config is reported once")))))

(deftest test-resolve-config-precedence
  (let [env {"R2_CAMPAIGN_BUCKET" "web-boost-campaigns"
             "R2_ACCOUNT_ID" "acct"
             "R2_CAMPAIGN_ACCESS_KEY_ID_PATH" "/camp/key"
             "R2_CAMPAIGN_SECRET_ACCESS_KEY_PATH" "/camp/secret"
             "R2_ACCESS_KEY_ID_PATH" "/shared/key"
             "R2_SECRET_ACCESS_KEY_PATH" "/shared/secret"}
        ;; read-path takes the ENV VAR NAME (it resolves the file itself),
        ;; not the path — same contract as the production reader.
        contents {"R2_CAMPAIGN_ACCESS_KEY_ID_PATH" "camp-key"
                  "R2_CAMPAIGN_SECRET_ACCESS_KEY_PATH" "camp-secret"
                  "R2_ACCESS_KEY_ID_PATH" "shared-key"
                  "R2_SECRET_ACCESS_KEY_PATH" "shared-secret"}
        get-env #(get env %) read-path #(get contents %)]
    (testing "campaign-scoped credentials win"
      (let [cfg (#'publish/resolve-config get-env read-path)]
        (is (= "web-boost-campaigns" (:bucket cfg)))
        (is (= "acct" (:account-id cfg)))
        (is (= "camp-key" (:access-key cfg)))
        (is (= "camp-secret" (:secret-key cfg)))))
    (testing "the shared token is only a fallback"
      (let [cfg (#'publish/resolve-config
                  (fn [k] (when-not (clojure.string/starts-with? k "R2_CAMPAIGN_ACCESS")
                            (get env k)))
                  (fn [k] (when-not (clojure.string/starts-with? k "R2_CAMPAIGN_")
                            (get contents k))))]
        (is (= "shared-key" (:access-key cfg)))
        (is (= "shared-secret" (:secret-key cfg)))))
    (testing "campaign account id overrides the shared one when present"
      (let [cfg (#'publish/resolve-config
                  (fn [k] (if (= k "R2_CAMPAIGN_ACCOUNT_ID") "camp-acct" (get env k)))
                  read-path)]
        (is (= "camp-acct" (:account-id cfg)))))))

(deftest test-successful-publish-emits-a-receipt-line
  (insert! (boost {:sender "alice" :sats 1000 :msg "#700club" :iid "ok-1"}))
  (let [logs (atom [])
        puts (atom [])
        result (publish/publish-campaigns! *conn*
                                           {:now NOW
                                            :config {:bucket "b" :account-id "a" :access-key "k" :secret-key "s"}
                                            :put-fn (fn [slug body] (swap! puts conj {:slug slug :body body}) :ok)
                                            :campaigns [test-campaign]
                                            :log-fn #(swap! logs conj %)})]
    (is (= [{:slug "test-camp" :result :published}] result))
    (is (= 1 (count @logs)) "success is no longer silent")
    (let [line (first @logs)
          payload (json/parse-string (:body (first @puts)) true)]
      (testing "the receipt names the campaign and agrees with the artifact it wrote"
        (is (re-find #"Campaign published test-camp" line))
        (is (re-find (re-pattern (str (get-in payload [:raised :sats]) " sats")) line))
        (is (re-find (re-pattern (str (get-in payload [:raised :contributions]) " contributions")) line))
        (is (re-find #"\$[0-9]+\.[0-9]{2} raised" line))
        (is (re-find #"USD/BTC" line))))))

(deftest test-publish-summary-cannot-fail-a-publish
  (testing "missing progress keys degrade the line instead of throwing"
    (is (string? (publish/publish-summary "x" {})))
    (is (string? (publish/publish-summary "x" {:raised nil :rate nil})))
    (is (re-find #"\$0\.00" (publish/publish-summary "x" {})))
    (is (re-find #"0 contributions" (publish/publish-summary "x" {}))))
  (testing "a rate source renders without keyword punctuation"
    (is (re-find #"\(mempool\.space\)"
                 (publish/publish-summary
                  "x" {:raised {:usd 0.87 :sats 1477 :contributions 2}
                       :rate {:btc_usd 59051 :source :mempool.space}})))))
