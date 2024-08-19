(ns boost-scraper.upstreams.alby
  (:require [boost-scraper.utils :as utils]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(def alby-incoming-invoices-url "https://api.getalby.com/invoices/incoming")

(def scrape-can-run (atom true))

(defn get-boosts [{:keys [:token :items :page :wait :after :since] :as last_}]
  (when @scrape-can-run
    (println "still going!" page)
    (let [query-params {:items (or items 100) :page page}
          query-params (if after
                         (assoc query-params
                                "q[created_at_gt]"
                                #_"q[created_at_lt]"
                                (/ (.getTime after) 1000))
                         query-params)
          query-params (if since
                         (assoc query-params "q[since]" since)
                         query-params)
          data (-> alby-incoming-invoices-url
                   (http/get {:headers {:authorization (str "Bearer " token)}
                              :query-params query-params})
                   :body
                   (json/parse-string true))
          next_ {:next (into last_ {:token token
                                    :items items
                                    :page (inc page)})

                 :data data}]
      (try
        (->> data
             (filter :creation_date)
             (map :creation_date)
             sort
             first
             (#(println "stamp: " % "   " (utils/format-date (or % 0)))))
        (catch Exception e (println "OH NO: " e)))
      (when wait
        (Thread/sleep wait))
      #_(reset! get-boosts-state (:next next_))
      next_)))

(defn get-all-boosts [token #_items-per-page & get-boost-args]
  (map :data
       (iteration get-boosts
                  {:somef (comp seq :data)
                   :kf :next
                   :initk (into {:token token
                                 #_:items #_items-per-page
                                 :page 1}
                                (apply hash-map get-boost-args))})))

(defn get-all-boosts-until-epoch [token epoch & get-boost-args]
  (->> {:somef (comp seq :data)
        :kf :next
        :initk (into {:token token
                      :page 1}
                     (apply hash-map get-boost-args))}
       (iteration get-boosts)
       (map :data)
       (take-while
        (fn [boost-batch]
          (let [filtered-batch (filter :creation_date boost-batch)
                creation_dates (map :creation_date filtered-batch)
                first_creation_date (apply max creation_dates)]
            (<= epoch first_creation_date))))))

(comment

  (def test-token (or (System/getenv "ALBY_ACCESS_CODE") ""))

  (get-boosts {:token test-token :items 2
               :page 1 :after #inst "2024-08-01T11:10:00"

               #_:since #_"txjbH2VGZJ7Z9MzCtAaQWhJ4"})

  (first (get-all-boosts test-token :items 2 :wait 2000))
  (def _
    (into []
          (get-all-boosts-until-epoch
           test-token
           (boost-scraper.core/->epoch #inst "2024-08-12T20:00")
           :wait 3000))))