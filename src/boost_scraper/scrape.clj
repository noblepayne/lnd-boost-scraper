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

(def filter-boosts
  (comp (filter #(= "boost" (-> % :boostagram :action)))
        (map #(assoc (:boostagram %)
                     :creation_date (:creation_date %)
                     :identifier (:identifier %)))))

(defn get-new-boosts [token last-boost-id]
  (into []
        (comp cat
              filter-boosts
              (take-while #(not= last-boost-id (:identifier %))))
        (get-all-boosts token 100)))

(defn get-n-boosts [token n]
  (into []
        (comp cat
              #_(remove (comp empty? :memo))
              filter-boosts
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
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd h:mm:ss a zzz"))))

(defn int-comma [n] (clojure.pprint/cl-format nil "~:d"  (float n)))

(defn format-boost [{:keys [podcast
                            sender_name
                            value_msat_total
                            ;; :ts
                            message
                            episode
                            app_name
                            creation_date
                            identifier]}]
  (let [sats (int-comma (/ value_msat_total 1000))
        date (format-date creation_date)]
    (str
     "### From: " (or sender_name "no name provided :(")
     "\n" "+ " sats " sats"
     "\n" "+ " podcast
     "\n" "+ " episode
     "\n" "+ " app_name
     "\n" "+ " date
     "\n" "+ " identifier
     "\n\n" "> " (or message "no message provided :(")
     "\n\n")))

(defn fetch-and-format-boosts [token number-of-boosts]
  (->> number-of-boosts
       (get-n-boosts token)
       (filter #(or (= "LINUX Unplugged"  (:podcast %))
                    (str/includes? (get % :episode "") "LINUX Unplugged")))
       (map format-boost)
       str/join
       print))

(defn boosts-by-sender [boosts]
  (reduce
   (fn [state new]
     (let [sender_name (get new :sender_name "unknown_sender")
           current-value (get state sender_name [])
           new-value (conj current-value new)]
       (assoc state sender_name new-value)))
   {}
   boosts))

(defn summarize-boosts-by-sender [boosts]
  (into {}
        (map (fn [[k v]]
               [k {:count (count v)
                   :sats (/ (reduce
                             #(+ %1 (get %2 :value_msat_total 0))
                             0
                             v)
                            1000)
                   :boosts (sort-by :creation_date v)
                   :creation_date (apply min (map :creation_date v))}]))

        boosts))

(defn boost-report [token last-seen-boost-id]
  (let [new-boosts (->> last-seen-boost-id
                        (get-new-boosts token)
                        (filter #(or (= "LINUX Unplugged"  (:podcast %))
                                     (str/includes? (get % :episode "") "LINUX Unplugged")))
                        (sort-by :creation_date))
        new-last-id (->> new-boosts last :identifier)
        boosts-by-sender (summarize-boosts-by-sender
                          (boosts-by-sender new-boosts))
        total-sats (/ (reduce + (map :value_msat_total new-boosts)) 1000)
        ballers (->> boosts-by-sender
                     (filter #(<= 20000 (:sats (second %))))
                     (sort-by (comp (juxt :sats :creation_date) second)))
                     ;reverse)

        boosters (->> boosts-by-sender
                      (filter #(<= 2000 (:sats (second %)) 20000))
                      (sort-by (comp (juxt :creation_date :sats) second)))
                      ;reverse)
        thanks (->> boosts-by-sender
                    (filter #(> 2000 (:sats (second %))))
                    (sort-by (comp (juxt :creation_date :sats) second)))
                   ; reverse)

        formatted-ballers (->> ballers (mapcat #(-> % second :boosts)) (map format-boost) str/join)
        formatted-boosters (->> boosters (mapcat #(-> % second :boosts)) (map format-boost) str/join)
        formatted-thanks (->> thanks (mapcat #(-> % second :boosts)) (map format-boost) str/join)]
    (str
     "## Baller Boosts\n"
     formatted-ballers
     "## Boosts\n"
     formatted-boosters
     "## Thanks\n"
     formatted-thanks
     "### Total Sats\n"
     "+ Total Sats: " (int-comma total-sats) "\n\n"
     "### Last Seen Boost\n"
     "Last seen boost id: " new-last-id
     "\n\n")))


(defn autoscrape []
  (let [{:keys [basic-auth-secret refresh-token last-id]}
        (clojure.edn/read-string (slurp "decrypted_secrets"))
        ;; _ (println basic-auth-secret "" refresh-token)
        {:keys [refresh_token access_token]} (get-new-auth-token basic-auth-secret refresh-token)]
    ;; (println refresh_token access_token)
    (spit "decrypted_secrets" {:basic-auth-secret basic-auth-secret :refresh-token refresh_token})
    (println (boost-report access_token last-id))))



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
        (println (.getMessage e)))
      (System/exit 1))))

(comment

  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)


  (def test-token "")
  (def last-lup #inst "2023-03-27T12-07:00")

  (->> (get-boosts
        {:token test-token :items 50 :page 5 :after last-lup})
       :data
       (remove #(or (= "stream" (-> % :boostagram :action))
                    (= "streaming" (-> % :boostagram :action))))
       tap>)

  (->> 5 (get-n-boosts test-token) #_tap> clojure.pprint/pprint)
  (->> 1000 (get-n-boosts test-token) (filter #(= (:podcast %) "All Jupiter Broadcasting Shows")) tap>)

  (->> 200 (fetch-and-format-boosts test-token) str/join print)
  (->> (get-new-boosts test-token "") tap>)


  (spit "/tmp/new-boosts.md" (boost-report test-token ""))

  (def res (get-new-boosts test-token ""))

  (-> res boosts-by-sender summarize-boosts-by-sender)


  (boost-report test-token "")

  (autoscrape))


;;;;;
