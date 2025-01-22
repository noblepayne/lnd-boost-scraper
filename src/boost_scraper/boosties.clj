(ns boost-scraper.boosties
  (:require [datalevin.core :as d]
            [boost-scraper.core :as core]
            [clojure.string :as str]
            [boost-scraper.reports :as reports]))

(defn boosties-v1 [conn action]
  (d/q
   '[:find #_?podcast #_?episode ?sender ?action (count ?tx) (sum ?amount)
     :in $ ?action
     :where
     [?tx :invoice/created_at ?created_at]
     [(>= ?created_at #inst "2024-01-01T00:00:00")]
     [?tx :boostagram/action ?action]
     [(get-else $ ?tx :boostagram/sender_name_normalized "N/A") ?sender]
     [?tx :boostagram/value_msat_total ?_amount]
     [(/ ?_amount 1000) ?amount]
     #_[?tx :boostagram/action ?action]
     [?tx :boostagram/episode ?episode]
     [?tx :boostagram/podcast ?podcast]
     [(re-pattern ".*Unplugged.*") ?regex]
     (or [(re-matches ?regex ?episode)]
         [?tx :boostagram/podcast "LINUX Unplugged"])]
   (d/db conn)
   action))

(defn boosties-v1-no-action-filter [conn]
  (d/q
   '[:find ?sender ?action (count ?tx) (sum ?amount)
     :in $
     :where
     [?tx :invoice/created_at ?created_at]
     [(>= ?created_at #inst "2023-01-01T00:00:00")]
     [?tx :boostagram/action ?action]
     [(get-else $ ?tx :boostagram/sender_name_normalized "N/A") ?sender]
     [?tx :boostagram/value_msat_total ?_amount]
     [(/ ?_amount 1000) ?amount]
     [?tx :boostagram/action ?action]
     [?tx :boostagram/episode ?episode]
     [?tx :boostagram/podcast ?podcast]
     [(re-pattern ".*Unplugged.*") ?regex]
     (or [(re-matches ?regex ?episode)]
         [?tx :boostagram/podcast "LINUX Unplugged"])]
   (d/db conn)))

(defn boosties-v2 [conn regex action start]
  (d/q '[:find ?sender (count ?e) (sum ?amount)
         :in $ ?regex ?action ?start
         :where
         [?e :boostagram/action ?action]
         [?e :invoice/created_at ?created_at]
         [(<= ?start ?created_at)]
         [?e :boostagram/podcast ?podcast]
         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
         (or [(re-matches ?regex ?podcast) _]
             [(re-matches ?regex ?episode) _])
         (not [?e :boostagram/sender_name_normalized "chrislas"])
         (not [?e :boostagram/sender_name_normalized "noblepayne"])
         [?e :boostagram/value_msat_total ?amount']
         [(/ ?amount' 1000.0) ?amount]
         [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]]
       (d/db conn)
       (or regex #"(?i).*li.*unplugged.*")
       action
       (or start #inst "2024-01-01T00:00Z")))

(defn boosties-v2-no-action [conn regex start]
  (d/q '[:find ?sender (count ?e) (sum ?amount)
         :in $ ?regex ?start
         :where
         [?e :invoice/created_at ?created_at]
         [(<= ?start ?created_at)]
         [?e :boostagram/podcast ?podcast]
         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
         (or [(re-matches ?regex ?podcast) _]
             [(re-matches ?regex ?episode) _])
         (not [?e :boostagram/sender_name_normalized "chrislas"])
         (not [?e :boostagram/sender_name_normalized "noblepayne"])
         [?e :boostagram/value_msat_total ?amount']
         [(/ ?amount' 1000.0) ?amount]
         [(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]]
       (d/db conn)
       (or regex #"(?i).*li.*unplugged.*")
       (or start #inst "2024-01-01T00:00Z")))

(defn boosts-by-total-amount [conn]
  (->> (boosties-v2 conn nil "boost" nil)
       (sort-by #(nth % 2) #(compare %2 %1))))

(defn boosts-by-number [conn]
  (->> (boosties-v2 conn nil "boost" nil)
       (sort-by #(nth % 1) #(compare %2 %1))))

(defn streams-by-total-amount [conn]
  (->> (boosties-v2 conn nil "stream" nil)
       (sort-by #(nth % 2) #(compare %2 %1))))

(defn streams-by-number [conn]
  (->> (boosties-v2 conn nil "stream" nil)
       (sort-by #(nth % 1) #(compare %2 %1))))

(defn total-v4v [conn]
  (->> (boosties-v2-no-action conn nil #_#".*" nil)
       (sort-by #(nth % 2) #(compare %2 %1))))

(defn sum-of-boosts [boosts]
  (reduce
   (fn [xs x] (+ xs (bigint (peek x))))
   0N
   boosts))

(defn count-of-boosts [boosts]
  (reduce
   (fn [xs x] (+ xs (bigint (peek (pop x)))))
   0N
   boosts))

(comment
  (require '[boost-scraper.core :as core])
  (def conn core/nodecan-conn)

  (boosties-v1 conn "boost")

  (println
   (str
    "Sent us the most sats"
    "\n"
    (str/join
     "\n"
     (for [[sender _ sent] (reverse (take 5 (boosts-by-total-amount conn)))]
       (str sender " " (boost-scraper.reports/int-comma (clojure.math/round sent)))))
    "\n"
    "\n"
    "Sent us the most boosts"
    "\n"
    (str/join
     "\n"
     (for [[sender sent] (reverse (take 5 (boosts-by-number conn)))]
       (str sender " " (boost-scraper.reports/int-comma (clojure.math/round sent)))))
    "\n"
    "\n"
    "Sent us the most streamed sats"
    "\n"
    (str/join
     "\n"
     (for [[sender _ sent] (reverse (take 5 (streams-by-total-amount conn)))]
       (str sender " " (boost-scraper.reports/int-comma (clojure.math/round sent)))))
    "\n"
    "\n"
    "Sent us the most streams"
    "\n"
    (str/join
     "\n"
     (for [[sender sent] (reverse (take 5 (streams-by-number conn)))]
       (str sender " " (boost-scraper.reports/int-comma (clojure.math/round sent)))))))

  ;; total from boosts and streams
  (sum-of-boosts (total-v4v conn))
  ;; total amount of sats from boosts
  (sum-of-boosts (boosts-by-total-amount conn))
  ;; total number of boosters
  (count (boosts-by-total-amount conn))
  ;; total number of boosts
  (count-of-boosts (boosts-by-total-amount conn))
  ;; total amount of sats from streams
  (sum-of-boosts (streams-by-total-amount conn))
  ;; total number of streamers
  (count (streams-by-total-amount conn))
  ;; total number of streams
  (count-of-boosts (streams-by-total-amount conn))

  (boosties-v2 conn #"(?i).*li.*unplugged.*" "boost" #inst "2024-01-01T00:00Z")
  (boosties-v2 conn nil "boost" nil))



(defn boosties-clients [conn regex start]
  (d/q '[:find ?client (count ?e)
         :in $ ?regex ?start
         :where
         [?e :invoice/created_at ?created_at]
         [?e :boostagram/action "stream"]
         [(<= ?start ?created_at)]
         [?e :boostagram/podcast ?podcast]
         [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
         (or [(re-matches ?regex ?podcast) _]
             [(re-matches ?regex ?episode) _])
         (not [?e :boostagram/sender_name_normalized "chrislas"])
         (not [?e :boostagram/sender_name_normalized "noblepayne"])
         [(get-else $ ?e :boostagram/app_name "N/A") ?client]]
       (d/db conn)
       (or regex #"(?i).*li.*unplugged.*")
       (or start #inst "2024-01-01T00:00Z")))