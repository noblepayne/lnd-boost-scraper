(ns boost-scraper.web-test
  (:require [boost-scraper.campaigns :as campaigns]
            [boost-scraper.db :as db]
            [boost-scraper.price-feed :as price-feed]
            [boost-scraper.reconcile :as rec]
            [boost-scraper.test-utils :as test-utils]
            [boost-scraper.web :as web]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

(defn- find-handler
  "Extract the handler for [method path] from the reitit route table."
  [routes method path]
  (let [route (first (filter #(= path (first %)) routes))]
    (get-in route [1 method :handler])))

(defn- boost-entities
  [conn]
  (d/q '[:find [?e ...] :where [?e :boostagram/action "boost"]] (d/db conn)))

(deftest test-reconcile-routes
  (let [tmpdir (str "/tmp/test-web-reconcile-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (d/transact! conn
                   [{:invoice/identifier "325043"
                     :invoice/memo "Payment for Web Boost: TWIB 108 — debitcoinkoers.eu"
                     :invoice/value 10000
                     :invoice/settled true
                     :invoice/settle_date "2026-06-13T13:34:50Z"}
                    {:invoice/identifier "342724"
                     :invoice/memo "Payment for Web Boost: LUP 670 — Memphis"
                     :invoice/value 2222
                     :invoice/settled true
                     :invoice/settle_date "2026-08-07T03:36:14Z"
                     :invoice/creation_date (.getEpochSecond (rec/parse-rfc3339 "2026-08-07T02:40:37Z"))}])
      (let [routes (web/routes conn)
            preview (find-handler routes :get "/api/v1/reconcile/preview")
            backfill (find-handler routes :post "/api/v1/reconcile/backfill")
            orders [{:id "od_bofDf6orSH"
                     :currency "BTC"
                     :totalAmount 10000
                     :label "Web Boost: TWIB 108 — debitcoinkoers.eu"
                     :metadata {:app "web-boost" :username "debitcoinkoers.eu"}}]]
        (testing "preview returns detection and never writes (write flag on)"
          (with-redefs [web/zaprite-api-key (fn [] "k")
                        web/reconcile-write-enabled? (fn [] true)
                        rec/fetch-unified-orders (fn [_] orders)]
            (let [resp (preview {})
                  body (json/parse-string (:body resp) true)]
              (is (= 200 (:status resp)))
              (is (true? (:write-enabled body)))
              (is (= 1 (count (:orphans body))))
              (is (zero? (count (boost-entities conn)))))))
        (testing "backfill is gated by the write flag"
          (with-redefs [web/zaprite-api-key (fn [] "k")
                        web/reconcile-write-enabled? (fn [] false)
                        rec/fetch-unified-orders (fn [_] orders)]
            (let [resp (backfill {})]
              (is (= 403 (:status resp)))
              (is (zero? (count (boost-entities conn)))))))
        (testing "backfill with write flag on writes exactly the HIGH-confidence set"
          (with-redefs [web/zaprite-api-key (fn [] "k")
                        web/reconcile-write-enabled? (fn [] true)
                        rec/fetch-unified-orders (fn [_] orders)]
            (let [resp (backfill {})
                  body (json/parse-string (:body resp) true)]
              (is (= 200 (:status resp)))
              (is (= 1 (:written body)))
              (is (= 0 (:skipped body)))
              (is (= 1 (count (boost-entities conn)))))))
        (testing "backfill is idempotent across requests"
          (with-redefs [web/zaprite-api-key (fn [] "k")
                        web/reconcile-write-enabled? (fn [] true)
                        rec/fetch-unified-orders (fn [_] orders)]
            (let [body (json/parse-string (:body (backfill {})) true)]
              (is (= 0 (:written body)))
              (is (= 1 (:skipped body)))
              (is (= 1 (count (boost-entities conn)))))))
        (let [resolve (find-handler routes :post "/api/v1/reconcile/resolve")]
          (testing "resolve is gated by the write flag"
            (with-redefs [web/zaprite-api-key (fn [] "k")
                          web/reconcile-write-enabled? (fn [] false)]
              (let [resp (resolve {:body-params {:identifier "325043"}})]
                (is (= 403 (:status resp))))))
          (testing "resolve requires an identifier"
            (with-redefs [web/zaprite-api-key (fn [] "k")
                          web/reconcile-write-enabled? (fn [] true)]
              (let [resp (resolve {:body-params {}})]
                (is (= 400 (:status resp))))))
          (testing "resolve writes an unknown-invoice 404"
            (with-redefs [web/zaprite-api-key (fn [] "k")
                          web/reconcile-write-enabled? (fn [] true)]
              (let [resp (resolve {:body-params {:identifier "999999"}})]
                (is (= 404 (:status resp))))))
          (testing "resolve writes the settled invoice when the flag is on"
            (with-redefs [web/zaprite-api-key (fn [] "k")
                          web/reconcile-write-enabled? (fn [] true)]
              (let [resp (resolve {:body-params {:identifier "342724"}})
                    body (json/parse-string (:body resp) true)]
                (is (= 200 (:status resp)))
                (is (= "ok" (:status body)))
                (is (= 2222 (:boostagram/value_sat_total (:entity body)))
                    "entity carries the settled amount")
                (is (= "memphis" (:boostagram/sender_name_normalized (:entity body))))
                (is (= 2 (count (boost-entities conn)))
                    "one backfill boost + one resolved boost"))))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

;; ---------------------------------------------------------------------------
;; Campaign routes (read-only meter surface)
;; ---------------------------------------------------------------------------

(defn- with-campaign-conn
  "Temp Datalevin conn for the campaign route tests."
  [f]
  (let [tmpdir (str "/tmp/test-web-campaign-" (java.util.UUID/randomUUID))
        conn (d/get-conn tmpdir db/schema)]
    (try
      (f conn)
      (finally
        (d/close conn)
        (Thread/sleep 50)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))

(defn- boost
  "A boost entity in the shape the accounting core reads."
  [{:keys [sender sats epoch msg episode podcast iid]
    :or   {sender "alice" sats 1000 epoch 0 podcast "LINUX Unplugged"
           episode "695" iid (str "inv-" (java.util.UUID/randomUUID))}}]
  (cond-> {:invoice/identifier iid
           :invoice/creation_date epoch
           :boostagram/action "boost"
           :boostagram/podcast podcast
           :boostagram/episode episode
           :boostagram/sender_name_normalized sender
           :boostagram/type :sat
           :boostagram/value_sat_total sats}
    msg (assoc :boostagram/message msg)))

(defn- campaign-window
  "The shipped campaign's [opens closes], so the tests exercise real config."
  []
  (let [c (campaigns/find-campaign "lup-700-brent")]
    [(get-in c [:campaign/window :opens])
     (get-in c [:campaign/window :closes])]))

(deftest test-campaign-list-states
  (let [routes (web/routes nil)          ; the list route never touches the DB
        list-handler (find-handler routes :get "/api/v1/campaigns")
        [opens closes] (campaign-window)
        entry (fn [now]
                (with-redefs [web/epoch-now (fn [] now)]
                  (let [resp (list-handler {})]
                    (is (= 200 (:status resp)))
                    (-> (:body resp)
                        (json/parse-string true)
                        :campaigns
                        first))))]
    (testing "state is derived from the window alone"
      (is (= "scheduled" (:state (entry (dec opens)))) "before opens")
      (is (= "active" (:state (entry opens))) "opens itself is inside")
      (is (= "active" (:state (entry (dec closes)))) "last second is inside")
      (is (= "closed" (:state (entry closes))) "closes itself is outside")
      (is (= "closed" (:state (entry (+ closes 86400)))) "after close"))
    (testing "the list carries no money, only the index fields"
      (is (= #{:slug :title :state} (set (keys (entry opens)))))
      (is (= "lup-700-brent" (:slug (entry opens)))))))

(deftest test-campaign-detail-contract
  (with-campaign-conn
    (fn [conn]
      (let [[opens _closes] (campaign-window)
            at (+ opens 3600)
            detail (find-handler (web/routes conn) :get "/api/v1/campaigns/:slug")
            fetch (fn [slug now]
                    (with-redefs [web/epoch-now (fn [] now)
                                  price-feed/get-btc-usd (fn [] {:rate 100000.0
                                                                 :source "test"})]
                      (detail {:path-params {:slug slug}})))]
        (d/transact! conn [(boost {:sender "alice" :sats 100000 :epoch at
                                   :msg "for the #700club" :iid "w2-1"})
                           ;; an untagged boost on the finale still belongs
                           (boost {:sender "bob" :sats 5000 :epoch (+ at 60)
                                   :episode "700: The Big One" :iid "w2-2"})
                           ;; a non-member must appear in neither total nor recent
                           (boost {:sender "dave" :sats 999999 :epoch (+ at 120)
                                   :msg "great show" :iid "w2-3"})])
        (testing "200 with exactly the contract keys plus recent"
          (let [resp (fetch "lup-700-brent" at)]
            (is (= 200 (:status resp)))
            (let [body (json/parse-string (:body resp) true)]
              (is (= #{:slug :title :subtitle :tag :goal :raised :remaining_usd
                       :percent :days_left :opens_at :closes_at :generated_at
                       :rate :cta :recent}
                     (set (keys body))))
              (is (= "lup-700-brent" (:slug body)))
              (is (= at (:generated_at body)))
              (is (= 100000.0 (get-in body [:rate :btc_usd])))
              (testing "the meter counts only members"
                (is (= 105000 (get-in body [:raised :sats])))
                (is (= 105.0 (get-in body [:raised :usd]))))
              (testing "recent is newest-first, per-boost, and capped"
                (is (= [{:sats 5000 :usd 5.0 :sender "bob" :at (+ at 60)}
                        {:sats 100000 :usd 100.0 :sender "alice" :at at}]
                       (:recent body))
                    "names are published; message text is not")))))
        (testing "unknown slug is a 404, not an empty meter"
          (let [resp (fetch "no-such-campaign" at)]
            (is (= 404 (:status resp)))
            (is (contains? (json/parse-string (:body resp) true) :error))))))))

(deftest test-campaign-detail-recent-is-bounded
  (with-campaign-conn
    (fn [conn]
      (let [[opens _closes] (campaign-window)
            at (+ opens 3600)
            detail (find-handler (web/routes conn) :get "/api/v1/campaigns/:slug")]
        (d/transact! conn
                     (mapv (fn [i]
                             (boost {:sender (str "u" i) :sats 1000
                                     :epoch (+ opens 100 i)
                                     :msg "#700club" :iid (str "cap-" i)}))
                           (range 25)))
        (with-redefs [web/epoch-now (fn [] at)
                      price-feed/get-btc-usd (fn [] {:rate 100000.0 :source "test"})]
          (let [body (json/parse-string (:body (detail {:path-params
                                                        {:slug "lup-700-brent"}}))
                                        true)
                recent (:recent body)]
            (is (= 20 (count recent)) "capped regardless of how many qualify")
            (is (apply > (map :at recent)) "newest first, strictly descending")
            (is (= (+ opens 124) (:at (first recent))))
            (is (= 25 (get-in body [:raised :contributions]))
                "the cap is a display bound, not an accounting one")))))))

(deftest test-campaign-detail-refuses-without-a-rate
  (with-campaign-conn
    (fn [conn]
      (let [[opens _closes] (campaign-window)
            at (+ opens 3600)
            detail (find-handler (web/routes conn) :get "/api/v1/campaigns/:slug")]
        (d/transact! conn [(boost {:sender "alice" :sats 100000 :epoch at
                                   :msg "#700club" :iid "nr-1"})])
        (testing "no fresh and no stale rate => 503, never a zero-rate payload"
          (with-redefs [web/epoch-now (fn [] at)
                        price-feed/get-btc-usd (fn [] nil)]
            (let [resp (detail {:path-params {:slug "lup-700-brent"}})
                  body (json/parse-string (:body resp) true)]
              (is (= 503 (:status resp)))
              (is (= "no-rate" (:status body)))
              (is (string? (:error body)))
              (is (not (contains? body :raised)) "no partial contract leaks out")
              (is (not (contains? body :recent))))))))))