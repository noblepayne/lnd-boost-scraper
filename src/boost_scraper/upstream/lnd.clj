(ns boost-scraper.upstream.lnd
  (:require [babashka.http-client :as http]
            [boost-scraper.upstream :as upstream]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]))

;; macaroon -> hex
(defn- get-path [pathstr]
  (let [args (into-array String [])
        default-fs (java.nio.file.FileSystems/getDefault)]
    (.getPath default-fs pathstr args)))

(defn read-macaroon [path]
  (-> (get-path path)
      java.nio.file.Files/readAllBytes
      javax.xml.bind.DatatypeConverter/printHexBinary
      (#(.toLowerCase %))))

(defrecord Scraper []
  upstream/IBoostScrape
  (get-boosts [_ {:keys [token wait offset url] :as last_}]
    (when @upstream/scrape
      (println "still going!" offset url)
      (let [query-params {:reversed true}
            query-params (if offset (assoc query-params :index_offset offset) query-params)
            data (-> (or url "https://100.115.78.27:8080/v1/invoices")
                     (http/get {:headers {"Grpc-Metadata-macaroon" token}
                                :client (http/client {:ssl-context {:insecure true}})
                                :query-params query-params})
                     :body
                     (json/parse-string true))
            next_ {:next (into last_ {:macaroon token
                                      :offset (get data :first_index_offset)})

                   :data (:invoices data)}]
        (try
          (->> data
               :invoices
               (filter :creation_date)
               (map :creation_date)
               sort
             ;; FIXME: cleanup/better observability
               #_first
               (#(vector (first %) (last %)))
               (#(do (println "stamp: " (peek %) "   " (utils/format-date (Integer/parseInt (or (peek %) "0")))) %))
               (#(do (println "stamp: " (first %) "   " (utils/format-date (Integer/parseInt (or (first %) "0")))) %)))
          (catch Exception e (println "OH NO: " e)))
        (when wait
          (println "LND waiting...")
          (Thread/sleep wait)
          (println "LND DONE WAITING"))
        next_))))

(comment
  (def macaroon (read-macaroon "/home/wes/src/lnd_workdir/admin.macaroon"))
  (def nodecan-macaroon (read-macaroon "/home/wes/src/lnd_workdir/nodecan/nodecan.macaroon"))

    ;; TODO: docs on the property we need to set for this to work
  (http/get "https://100.115.78.27:8080/v1/invoices"
            {:client (http/client {:ssl-context {:insecure true}})
             :headers {"Grpc-Metadata-macaroon" macaroon}
             :query-params {"reversed" "true"}}))