(ns boost-scraper.upstreams.lnd
  (:require [boost-scraper.utils :as utils]
            [babashka.http-client :as http]
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
    (println "still going!" offset)
    (let [query-params {:reversed true}
          query-params (if offset (assoc query-params :index_offset offset) query-params)
          data (-> "https://100.115.78.27:8080/v1/invoices"
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
      #_(reset! get-boosts-state (:next next_))
      next_)))

(defn get-all-lnd-boosts [macaroon & get-boost-args]
  (iteration get-lnd-boosts
             {:somef (comp seq :data)
              :vf :data
              :kf :next
              :initk (into {:macaroon macaroon}
                           (apply hash-map get-boost-args))}))

(comment

  (def macaroon (read-macaroon "/home/wes/src/lnd_workdir/admin.macaroon"))

  (get-lnd-boosts {:macaroon macaroon})
  (get-all-lnd-boosts {:macaroon macaroon :wait 2000})

    ;; TODO: docs on the property we need to set for this to work
  (http/get "https://100.115.78.27:8080/v1/invoices"
            {:client (http/client {:ssl-context {:insecure true}})
             :headers {"Grpc-Metadata-macaroon" macaroon}
             :query-params {"reversed" "true"}}))