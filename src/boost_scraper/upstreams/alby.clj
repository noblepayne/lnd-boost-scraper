(ns boost-scraper.upstreams.alby
  (:require [boost-scraper.utils :as utils]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

(def alby-incoming-invoices-url "https://api.getalby.com/invoices/incoming")

(def scrape-can-run (atom true))

(defn get-boosts [{:keys [:token :items :page :wait :after :since] :as last_}]
  (when @scrape-can-run
    (println "still going!" page)
    (let [query-params {:items items :page page}
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

(defn get-all-boosts [token items-per-page & get-boost-args]
  (iteration get-boosts
             {:somef (comp seq :data)
              :kf :next
              :initk (into {:token token
                            :items items-per-page
                            :page 1}
                           (apply hash-map get-boost-args))}))

(comment

  (def test-token (or (System/getenv "ALBY_ACCESS_TOKEN") ""))

  (get-boosts {:token test-token :items 2
               :page 1 :after #inst "2024-08-01T11:10:00"

               #_:since #_"txjbH2VGZJ7Z9MzCtAaQWhJ4"}))