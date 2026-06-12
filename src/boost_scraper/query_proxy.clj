(ns boost-scraper.query-proxy
  "Safe Datalog query proxy for the boost scraper database.
   Read-only by construction — never imports d/transact!."
  (:require [clojure.edn :as edn]
            [datalevin.core :as d]))

(defn safe-read-edn
  "Safely parse an EDN string. Returns {:ok parsed} or {:error :detail}."
  [s]
  (try
    {:ok (edn/read-string s)}
    (catch Exception e
      {:error :read-error
       :detail (.getMessage e)})))

(defn- validate-query
  "Check that parsed query has required keys."
  [parsed]
  (when-not (and (map? parsed)
                 (contains? parsed :find)
                 (contains? parsed :where))
    {:error :invalid-query
     :detail "Query must be a map with :find and :where keys"}))

(defn- run-query
  "Execute the parsed query against the DB snapshot."
  [db parsed params timeout]
  (let [result (deref (future (apply d/q parsed db params))
                      timeout
                      ::timeout)]
    (if (= result ::timeout)
      {:error :timeout}
      (if (and (map? result) (:exception result))
        {:error :exception :detail (:exception result)}
        {:ok result}))))

(defn execute-query
  "Execute a Datalog query string against the database.
   Returns {:status :ok :results [...] :truncated bool :elapsed_ms N}
          or {:status :error :detail \"...\"}."
  [conn query-str opts]
  (let [{:keys [timeout limit]
         :or   {timeout 15000
                limit  5000}} opts
        timeout (min timeout 60000)
        limit   (min limit 50000)
        {:keys [error ok detail]} (safe-read-edn query-str)]
    (cond
      error
      {:status :error :detail detail}

      :else
      (if-let [err (validate-query ok)]
        {:status :error :detail (:detail err)}
        (let [db       (d/db conn)
              start    (System/currentTimeMillis)
              result   (run-query db ok (:params opts) timeout)
              elapsed  (- (System/currentTimeMillis) start)]
          (if (:error result)
            {:status :error :detail (:detail result)}
            (let [rows (:ok result)
                  truncated (> (count rows) limit)]
              {:status    :ok
               :results   (take limit rows)
               :truncated truncated
               :elapsed_ms elapsed})))))))
