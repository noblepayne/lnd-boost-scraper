(ns boost-scraper.upstreams.lnd
  (:require [babashka.http-client :as http]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]))

(def scrape-can-run (atom true))

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

(defn get-lnd-boosts [{:keys [macaroon offset wait] :as last_}]
  (when @scrape-can-run
    (println "still going!" offset (get last_ :url))
    (let [query-params {:reversed true}
          query-params (if offset (assoc query-params :index_offset offset) query-params)
          data (-> (get last_ :url "https://100.115.78.27:8080/v1/invoices")
                   (http/get {:headers {"Grpc-Metadata-macaroon" macaroon}
                              :client (http/client {:ssl-context {:insecure true}})
                              :query-params query-params})
                   :body
                   (json/parse-string true))
          next_ {:next (into last_ {:macaroon  macaroon
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
        (println "waiting...")
        (Thread/sleep wait)
        (println "DONE WAITING"))
      next_)))

(defn get-all-boosts [macaroon & get-boost-args]
  (iteration get-lnd-boosts
             {:somef (comp seq :data)
              :vf :data
              :kf :next
              :initk (into {:macaroon macaroon}
                           (apply hash-map get-boost-args))}))

(defn get-all-boosts-until-epoch [macaroon epoch & get-boost-args]
  (->> {:somef (comp seq :data)
        :vf :data
        :kf :next
        :initk (into {:macaroon macaroon}
                     (apply hash-map get-boost-args))}
       (iteration get-lnd-boosts)
       (take-while
        (fn [boost-batch]
          (let [filtered-batch (filter :creation_date boost-batch)
                creation_dates (map (comp #(Integer/parseInt %) :creation_date) filtered-batch)
                first_creation_date (apply max creation_dates)]
            ;; TODO: tests; <= or <?
            (<= epoch first_creation_date))))))

(comment
  (require '[boost-scraper.core])
  (def macaroon (read-macaroon "/home/wes/src/lnd_workdir/admin.macaroon"))
  (def nodecan-macaroon (read-macaroon "/home/wes/src/lnd_workdir/nodecan/nodecan.macaroon"))

  (get-lnd-boosts {:macaroon macaroon})
  (def _ (into [] (get-all-boosts-until-epoch
                   macaroon
                   (boost-scraper.core/->epoch #inst "2024-08-12T20:00")
                   :wait 500)))

    ;; TODO: docs on the property we need to set for this to work
  (http/get "https://100.115.78.27:8080/v1/invoices"
            {:client (http/client {:ssl-context {:insecure true}})
             :headers {"Grpc-Metadata-macaroon" macaroon}
             :query-params {"reversed" "true"}}))