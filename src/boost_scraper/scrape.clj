(ns boost-scraper.scrape
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [babashka.cli :as cli]
            [clojure.string :as str]
            [clojure.pprint]
            [clojure.edn]))

(def alby-incoming-invoices-url "https://api.getalby.com/invoices/incoming")
(def alby-token-refresh-url "https://api.getalby.com/oauth/token")

(defn get-new-auth-token [basic-auth-secret refresh-token]
  (-> alby-token-refresh-url
      (http/post
       {:headers {:authorization (str "Basic " basic-auth-secret)}
        :form-params {"refresh_token" refresh-token
                      "grant_type" "refresh_token"}})
      :body
      (json/parse-string true)))

(defn get-boosts [{:keys [:token :items :page :after]}]
  (let [query-params {:items items :page page}
        query-params (if after
                       (assoc query-params
                              "q[created_at_gt]"
                              (/ (.getTime after) 1000))
                       query-params)
        data (-> alby-incoming-invoices-url
                 (http/get {:headers {:authorization (str "Bearer " token)}
                            :query-params query-params})
                 :body
                 (json/parse-string true))]
    {:next {:token token
            :items items
            :page (inc page)
            :after after}
     :data data}))


(defn get-all-boosts [token items-per-page]
  (iteration get-boosts
             {:somef (comp seq :data)
              :vf :data
              :kf :next
              :initk {:token token
                      :items items-per-page
                      :page 1
                      #_(comment :after last-lup)}}))

(defn get-n-boosts [token n]
  (into []
        (comp cat
              (filter #(= "boost" (-> % :boostagram :action)))
              (map #(assoc (:boostagram %) :creation_date (:creation_date %)))
              (take n))
        (get-all-boosts token 100)))


(defn format-date-old-n-busted [unix_time]
  (let [date (java.util.Date. (* 1000 unix_time))
        formatter (java.text.SimpleDateFormat. "MM/dd/yyyy h:mm:ss aa zzz")
        timezone (.getTimeZone java.util.TimeZone "America/Los_Angeles")
        _ (.setTimeZone formatter timezone)]
    (.format formatter date)))

(defn format-date [unix-time]
  (-> (java.time.Instant/ofEpochSecond unix-time)
      (.atZone (java.time.ZoneId/of "America/Los_Angeles"))
      (.format (java.time.format.DateTimeFormatter/ofPattern "MM/dd/yyyy h:mm:ss a zzz"))))

(defn int-comma [n] (clojure.pprint/cl-format nil "~:d" n))

(defn format-boost [{:keys [podcast
                            sender_name
                            value_msat_total
                            ;; :ts
                            message
                            episode
                            app_name
                            creation_date]}]
  (let [sats (int-comma (/ value_msat_total 1000))
        date (format-date creation_date)]
    (str
     "### From: " (or sender_name "no name provided :(")
     "\n" "+ " sats " sats"
     "\n" "+ " podcast
     "\n" "+ "  episode
     "\n" "+ " app_name
     "\n" "+ " date
     "\n\n" "> " (or message "no message provided :(")
     "\n\n")))

(defn fetch-and-format-boosts [token number-of-boosts]
  (->> number-of-boosts
       (get-n-boosts token)
       (map format-boost)
       str/join
       print))

(defn autoscrape []
  (let [{:keys [basic-auth-secret refresh-token]}      
        (clojure.edn/read-string (slurp "decrypted_secrets"))
        ;; _ (println basic-auth-secret "" refresh-token)
        {:keys [refresh_token access_token]} (get-new-auth-token basic-auth-secret refresh-token)]
    ;; (println refresh_token access_token)
    (spit "decrypted_secrets" {:basic-auth-secret basic-auth-secret :refresh-token refresh_token})
    (fetch-and-format-boosts access_token 10)))

(def cli-opts
  {:boosts {:alias :n
            :desc "Number of boosts to fetch."
            :default 10
            :coerce :int}
   :token {:alias :t
           :desc "Alby oauth token."
           :require true
           :coerce :string}})

(defn -main [& _]
  (try
    (let [opts (cli/parse-opts *command-line-args* {:spec cli-opts :args->opts [:token :boosts]})]
      (fetch-and-format-boosts (:token opts) (:boosts opts)))
    (catch Exception e
      (if (= :org.babashka/cli (-> e bean :data :type))
        (do
          (println
           (first (str/split (str e) #"\{")) "\n")

          (println "Help:")

          (println
           (cli/format-opts
            {:spec cli-opts :order [:token :boosts]})))
        (println (.getMessage e))))))

(comment
  (def test-token "...")
  (def last-lup #inst "2023-03-27T12-07:00")

  (-> (get-boosts {:token test-token :items 100 :page 2 :after last-lup}) :data count)

  (->> 1 (get-n-boosts test-token) first clojure.pprint/pprint)

  (->> 2 (fetch-and-format-boosts test-token) str/join print)
  
  (autoscrape)
  )
