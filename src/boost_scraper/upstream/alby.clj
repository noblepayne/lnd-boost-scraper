(ns boost-scraper.upstream.alby
  (:require [babashka.http-client :as http]
            [boost-scraper.upstream :as upstream]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def alby-incoming-invoices-url "https://api.getalby.com/invoices/incoming")

(defn load-key [path]
  (-> (slurp path)
      (str/trim)))

(defrecord Scraper []
  upstream/IBoostScrape
  (get-boosts [_ {:keys [token offset wait items after since] :as last_}]
    (when @upstream/scrape
      (println "Alby still going!" offset)
      (let [offset (or offset (or (:page last_) 1))
            query-params {:items (or items 100) :page offset}
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
                                      :page (inc offset)})
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
        next_))))

(comment
  (def alby-token (or (load-key (System/getenv "ALBY_TOKEN_PATH")) "")))