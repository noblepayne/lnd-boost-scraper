(ns boost-scraper.boosties
  (:require [datalevin.core :as d]))

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

(defn boosts-by-total-amount [conn]
  (->> (boosties-v1 conn "boost")
       (sort-by #(nth % 3) #(compare %2 %1))))

(defn boosts-by-number [conn]
  (->> (boosties-v1 conn "boost")
       (sort-by #(nth % 2) #(compare %2 %1))))

(defn streams-by-total-amount [conn]
  (->> (boosties-v1 conn "stream")
       (sort-by #(nth % 3) #(compare %2 %1))))

(defn streams-by-number [conn]
  (->> (boosties-v1 conn "stream")
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
  (boosties-v1 conn "boost")

  (println "sent us the most sats")
  (take 15 (boosts-by-total-amount conn))
  (println "sent us the most boosts")
  (take 15 (boosts-by-number conn))
  (println "sent us the most streamed sats")
  (take 15 (streams-by-total-amount conn))
  (println "sent us the most streams")
  (take 15 (streams-by-number conn))

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
  (count-of-boosts (streams-by-total-amount conn)))