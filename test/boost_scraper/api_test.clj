(ns boost-scraper.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(when-let [base-url (System/getenv "TEST_BASE_URL")]
  (defn- get-client-states []
    (-> (http/get (str base-url "/api/v1/client-states"))
        :body
        (json/parse-string true)
        :client-states))

  (defn- delete-client-state [client show]
    (http/delete (str base-url "/api/v1/client-states")
                 {:query-params {:client client :show show}}))

  (deftest client-state-lifecycle-test
    (let [client (str "test-client-" (System/currentTimeMillis))
          show "lup"]

      (testing "Initial state: client does not exist"
        (let [states (get-client-states)]
          (is (not (some #(= (:client %) client) states)))))

      (testing "First request: creates client state"
        (let [resp (http/get (str base-url "/boosts")
                             {:query-params {:json "true" :show show :client client :since "1775604435"}})
              states (get-client-states)
              client-state (first (filter #(= (:client %) client) states))]
          (is (= 200 (:status resp)))
          (is (some? client-state))
          (is (= show (:show client-state)))
          (is (integer? (:last-seen-tx client-state)))))

      (testing "Second request (incremental): uses stored timestamp"
        (let [states-before (get-client-states)
              client-state-before (first (filter #(= (:client %) client) states-before))
              last-seen-before (:last-seen-tx client-state-before)

              resp (http/get (str base-url "/boosts")
                             {:query-params {:json "true" :show show :client client}})

              states-after (get-client-states)
              client-state-after (first (filter #(= (:client %) client) states-after))]

          (is (= 200 (:status resp)))
          (is (>= (:last-seen-tx client-state-after) last-seen-before))))

      (testing "Delete client state"
        (let [resp (delete-client-state client show)
              states (get-client-states)]
          (is (= 200 (:status resp)))
          (is (not (some #(= (:client %) client) states))))))))
