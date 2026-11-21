(ns boost-scraper.reindex-test
  (:require [boost-scraper.db :as db]
            [boost-scraper.reindex :as reindex]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]))

(defn- tmp-dir []
  (str "/tmp/test-reindex-" (java.util.UUID/randomUUID)))

(deftest test-reindex-applies-the-index
  (let [dir (tmp-dir)]
    (try
      (let [conn (d/get-conn dir db/schema)]
        (d/transact! conn [{:invoice/creation_date 1700000000
                            :boostagram/content_id "a"}
                           {:invoice/creation_date 1700000001
                            :boostagram/content_id "b"}])
        (d/close conn))

      (testing "the migration runs and verifies itself"
        (is (pos? (reindex/reindex-one! dir))))

      (testing "data survives and the query still answers"
        (let [conn (d/get-conn dir db/schema)
              rows (d/q '[:find ?cid :in $ ?since ?stop
                          :where [?e :invoice/creation_date ?cd]
                                 [?e :boostagram/content_id ?cid]
                                 [(< ?since ?cd ?stop)]]
                        (d/db conn) 1699999999 1700000002)]
          (is (= 2 (count rows)))
          (is (:db/index (get (d/schema conn) :invoice/creation_date)))
          (d/close conn)))
      (finally
        (dorun (map #(.delete %) (reverse (file-seq (java.io.File. dir)))))))))
