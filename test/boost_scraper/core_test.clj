(ns boost-scraper.core-test
  (:require [boost-scraper.core :as core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [boost_scraper.upstream IBoostScrape]))

(deftest test-get-all-boosts-until-epoch
  (testing "stops before batch max creation_date < epoch"
    (let [batch-1 [{:creation_date 200} {:creation_date 100}]
          batch-2 [{:creation_date 50} {:creation_date 80}]
          responses (atom [{:data batch-1 :next :page-2}
                           {:data batch-2 :next nil}])
          scraper (reify IBoostScrape
                    (get-boosts [_ _]
                      (if-let [resp (first @responses)]
                        (do (swap! responses rest) resp)
                        {:data [] :next nil})))
          result (doall (core/get-all-boosts-until-epoch scraper :test-token 150))]
      (is (empty? @responses))
      (is (= 1 (count result)))
      (is (= 2 (count (first result))))))
  (testing "epoch at exact boundary (<= behavior)"
    (let [batch [{:creation_date 100}]
          calls (atom [])
          scraper (reify IBoostScrape
                    (get-boosts [_ {:keys [token]}]
                      (swap! calls conj token)
                      {:data batch :next nil}))
          result (doall (core/get-all-boosts-until-epoch scraper :test-token 100))]
      (is (= 1 (count result)))
      (is (= 100 (:creation_date (first (first result)))))))
  (testing "no batches — returns empty"
    (let [scraper (reify IBoostScrape
                    (get-boosts [_ _]
                      {:data [] :next nil}))]
      (is (empty? (core/get-all-boosts-until-epoch scraper :test-token 100)))))
  (testing "mixed batch — records without creation_date filtered from max, but kept in output"
    (let [batch [{:creation_date 200} {:no-creation-date "foo"}]
          scraper (reify IBoostScrape
                    (get-boosts [_ _]
                      {:data batch :next nil}))
          result (doall (core/get-all-boosts-until-epoch scraper :test-token 100))]
      (is (= 1 (count result)))
      (is (= 2 (count (first result))))))
  (testing "all records lack creation_date — batch excluded (take-while returns nil)"
    (let [batch [{:no-date 1} {:also-no-date 2}]
          scraper (reify IBoostScrape
                    (get-boosts [_ _]
                      {:data batch :next nil}))]
      (is (empty? (doall (core/get-all-boosts-until-epoch scraper :test-token 100)))))))

(deftest test-credential-gating
  (testing "not-empty makes empty string nil (falsy)"
    (is (nil? (not-empty "")))
    (is (nil? (not-empty nil)))
    (is (= "foo" (not-empty "foo"))))
  (testing "some-> nil / empty / empty-file chain returns nil"
    (is (nil? (some-> nil not-empty slurp str/trim not-empty)))
    (is (nil? (some-> "" not-empty slurp str/trim not-empty)))
    (is (nil? (some-> "/dev/null" not-empty slurp str/trim not-empty))))
  (testing "and gating — all five must be truthy"
    (is (nil? (and nil "a" "b" "c" "d")))
    (is (nil? (and "a" nil "b" "c" "d")))
    (is (nil? (and "a" "b" nil "c" "d")))
    (is (nil? (and "a" "b" "c" nil "d")))
    (is (nil? (and "a" "b" "c" "d" nil)))
    (is (= "e" (and "a" "b" "c" "d" "e")))))
