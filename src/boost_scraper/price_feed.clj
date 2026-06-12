(ns boost-scraper.price-feed
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def ^:private sources
  [{:name "mempool.space" :url "https://mempool.space/api/v1/prices"
    :parser (fn [body] (some-> body (json/parse-string true) :USD))}
   {:name "CoinGecko" :url "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd"
    :parser (fn [body] (some-> body (json/parse-string true) :bitcoin :usd))}
   {:name "blockchain.info" :url "https://blockchain.info/ticker"
    :parser (fn [body] (some-> body (json/parse-string true) :USD :last))}])

(defonce ^:private cache (atom {:rate nil :updated-at 0}))

(def ^:private ^:const cache-ttl-ms 300000)

(def ^:private ^:const http-timeout-ms 5000)

(defn- try-source
  [source]
  (try
    (let [resp (http/get (:url source)
                         {:as :string
                          :timeout http-timeout-ms
                          :headers {"User-Agent" "lnd-boost-scraper/1.0"
                                    "Accept" "application/json"}})
          body (:body resp)
          rate ((:parser source) body)]
      (if (and rate (pos? rate))
        (do (println "Price feed: got BTC/USD" rate "from" (:name source))
            {:rate rate :source (:name source)})
        (do (println "Price feed:" (:name source) "returned invalid rate:" rate)
            nil)))
    (catch Exception e
      (println "Price feed:" (:name source) "error:" (.getMessage e))
      nil)))

(defn- fetch-btc-usd
  []
  (some try-source sources))

(defn get-btc-usd
  "Return {:rate N :source NAME} with in-memory 5min cache.
   Falls back through multiple sources if one fails.
   Returns nil if all sources fail and no cached rate."
  []
  (let [{:keys [rate updated-at]} @cache
        now (System/currentTimeMillis)]
    (if (and rate (< (- now updated-at) cache-ttl-ms))
      rate
      (let [new-result (fetch-btc-usd)]
        (if new-result
          (do (swap! cache assoc :rate new-result :updated-at now)
              new-result)
          (do (if rate
                (println "Price feed: all sources failed, using stale rate" (:rate rate)
                         "from" (:source rate) "at" (str (java.time.Instant/ofEpochMilli updated-at)))
                (println "Price feed: all sources failed, no cached rate available"))
              rate))))))

(defn fiat-sats-rate
  "Return {:rate N :source NAME} where rate is sats per 1 USD.
   Returns nil if price unavailable."
  []
  (when-let [{:keys [rate source]} (get-btc-usd)]
    {:rate (int (/ 100000000 rate)) :source source}))