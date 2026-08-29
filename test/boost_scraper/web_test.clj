(ns boost-scraper.web-test
  (:require [boost-scraper.db :as db]
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
                     :invoice/settle_date "2026-06-13T13:34:50Z"}])
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
              (is (= 1 (count (boost-entities conn))))))))
      (finally
        (d/close conn)
        (test-utils/delete-dir-recursively (io/file tmpdir))))))