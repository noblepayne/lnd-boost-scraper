(ns boost-scraper.scrapev2
  (:gen-class)
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [babashka.cli :as cli]
            [clojure.core.async :as async]
            #_[babashka.pods :as pods]
            [datalevin.core :as d]
            [clojure.java.io :as io]
            [clojure.instant]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.edn :as edn]
            [boost-scraper.scrape :as v1]))

#_(pods/load-pod 'huahaiy/datalevin "0.8.25")
#_(require '[pod.huahaiy.datalevin :as d])

(def alby-incoming-invoices-url "https://api.getalby.com/invoices/incoming")
(def alby-token-refresh-url "https://api.getalby.com/oauth/token")

#_(def dbi "/media/wes/a8dc84bb-2377-490e-a4b3-027425850e03/workdir/boosts/boostdb")
#_(def dbi "/home/wes/Downloads/boosts/boostdb")
(def dbi "/home/wes/Downloads/boosts/nodeboostdb")

(def show-by-id {:lup "LINUX Unplugged"
                 :lan "Linux Action News"
                 :ssh "Self-Hosted"
                 :coder "Coder Radio"
                 :office "Office Hours 2.0"
                 :bdadpod "Bitcoin Dad Pod"})

(def id-by-show (into {} (map (comp vec reverse) show-by-id)))
;; FIXME: support LIVE and Premium Shows! FIXME: where?

(defn format-date [unix-time]
  (-> (java.time.Instant/ofEpochSecond unix-time)
      (.atZone (java.time.ZoneId/of "America/Los_Angeles"))
      (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy/MM/dd h:mm:ss a zzz"))))

(defn int-comma [n] (clojure.pprint/cl-format nil "~:d"  (float n)))

(defn get-new-auth-token [basic-auth-secret refresh-token]
  (-> alby-token-refresh-url
      (http/post
       {:headers {:authorization (str "Basic " basic-auth-secret)}
        :form-params {"refresh_token" refresh-token
                      "grant_type" "refresh_token"}})
      :body
      (json/parse-string true)))

#_(def get-boosts-state (atom {}))
(def scrape-can-run (atom true))
(defn get-boosts [{:keys [:token :items :page :wait :after :since] :as last_}]
  (when @scrape-can-run
    (println "still going!" page)
    (let [query-params {:items items :page page}
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
                                    :page (inc page)})

                 :data data}]
      (try
        (->> data
             (filter :creation_date)
             (map :creation_date)
             sort
             first
             (#(println "stamp: " % "   " (format-date (or % 0)))))
        (catch Exception e (println "OH NO: " e)))
      (when wait
        (Thread/sleep wait))
      #_(reset! get-boosts-state (:next next_))
      next_)))


(defn get-all-boosts [token items-per-page & get-boost-args]
  (iteration get-boosts
             {:somef (comp seq :data)
              :vf :data
              :kf :next
              :initk (into {:token token
                            :items items-per-page
                            :page 1}
                           (apply hash-map get-boost-args))}))

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
             first
             (#(println "stamp: " % "   " (format-date (or (Integer/parseInt %) 0)))))
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
;; NEW

(def schema
  {:invoice/identifier {:db/valueType :db.type/string
                        :db/unique :db.unique/identity}
   :invoice/source {:db/valueType :db.type/string}
   :invoice/creation_date {:db/valueType :db.type/long}
   :invoice/creation_date_per_30 {:db/valueType :db.type/long}
   :invoice/created_at {:db/valueType :db.type/instant}
   :invoice/add_index {:db/valueType :db.type/long}
   :boostagram/sender_name {:db/valueType :db.type/string}
   :boostagram/sender_name_normalized {:db/valueType :db.type/string}
   :boostagram/episode {:db/valueType :db.type/string}
   :boostagram/podcast {:db/valueType :db.type/string}
   :boostagram/app_name {:db/valueType :db.type/string}
   :boostagram/action {:db/valueType :db.type/string}
   :boostagram/message {:db/valueType :db.type/string}
   #_:boostagram/dedup_id #_{:db/valueType :db.type/tuple
                             :db/unique :db.unique/identity
                             :db/tupleAttrs [:boostagram/sender_name_normalized
                                             :boostagram/podcast
                                             :boostagram/message
                                             :boostagram/value_msat_total
                                             :boostagram/action
                                             :invoice/creation_date_per_30]}
   :boostagram/value_msat_total {:db/valueType :db.type/long}
   :boostagram/value_sat_total {:db/valueType :db.type/long}}
    ;;
    ;; :table/column {:db/valueType :db.type/...}
  )

(defn remove-nil-vals [m]
  (->> m
       (remove
        (fn [[_ v]]
          (or (nil? v)
              (and (string? v)
                   (empty? v)))))
       (into {})))

(defn namespace-invoice-keys [toplvl-key m]
  (reduce
   (fn [xs [k v]]
     (if (map? v)
       (assoc xs k v)
       (let [toplvl (get xs toplvl-key {})
             toplvl (assoc toplvl k v)]
         (assoc xs toplvl-key toplvl))))
   {}
   m))

(defn flatten-paths
  "https://andersmurphy.com/2019/11/30/clojure-flattening-key-paths.html"
  [separator m]
  (letfn [(flatten-paths [m separator path]
            (lazy-seq
             (when-let [[[k v] & xs] (seq m)]
               (cond (and (map? v) (not-empty v))
                     (into (flatten-paths v separator (conj path k))
                           (flatten-paths xs separator path))
                     :else
                     (cons [(->> (conj path k)
                                 (map name)
                                 (clojure.string/join separator)
                                 keyword) v]
                           (flatten-paths xs separator path))))))]
    (into {} (flatten-paths m separator []))))

(defn normalize-name [name_]
  (-> name_
      str/trim
      str/lower-case
      ((fn [s] (if (str/starts-with? s "@")
                 (.substring s 1)
                 s)))))

(defn decode-boost [rawboost]
  (try
    (let [decoder (java.util.Base64/getDecoder)]
      (-> decoder
          (.decode rawboost)
          (#(java.lang.String. %))
          json/parse-string
          remove-nil-vals
          (#(namespace-invoice-keys :boostagram %))
          (#(flatten-paths "/" %))))
    (catch Exception e (println "EXCEPTION DECODING BOOST: " rawboost) {})))

(defn coerce-invoice-vals [invoice]
  (let [invoice (if-let [created_at (get invoice :invoice/created_at)]
                  (assoc
                   invoice
                   :invoice/created_at
                   (clojure.instant/read-instant-date created_at))
                  invoice)
        invoice (if-let [add_index (get invoice :invoice/add_index)]
                  (assoc
                   invoice
                   :invoice/identifier
                   add_index)
                  invoice)
        invoice (if-let [add_index (get invoice :invoice/add_index)]
                  (assoc
                   invoice
                   :invoice/add_index
                   (Integer/parseInt add_index))
                  invoice)
        invoice (if-let [creation_date (get invoice :invoice/creation_date)]
                  (assoc
                   invoice
                   :invoice/creation_date
                   (if (string? creation_date) (Integer/parseInt creation_date) creation_date))
                  invoice)
        invoice (if-let [creation_date (get invoice :invoice/creation_date)]
                  (assoc
                   invoice
                   :invoice/creation_date_per_30
                   (if (= "boost" (get invoice :boostagram/action))
                     (quot creation_date 30)
                     (rand-int 1000000000)))
                  invoice)
        invoice (if-let [creation_date (get invoice :invoice/creation_date)]
                  (assoc
                   invoice
                   :invoice/created_at
                   (java.util.Date/from (java.time.Instant/ofEpochSecond creation_date)))
                  invoice)
        invoice (if-let [[{{rawboost :7629169} :custom_records} :as debug] (get invoice :invoice/htlcs)]
                  (do #_(println debug "\n")
                   (into
                    (dissoc invoice :invoice/htlcs)
                    (decode-boost rawboost)))
                  invoice)
        invoice (if-let [sender_name (get invoice :boostagram/sender_name)]
                  (assoc
                   invoice
                   :boostagram/sender_name_normalized
                   (normalize-name sender_name))
                  invoice)
        invoice (if-let [msats (get invoice :boostagram/value_msat_total)]
                  (assoc
                   invoice
                   :boostagram/value_sat_total
                   (/ msats 1000))
                  invoice)]
    invoice))

(defn process-batch [batch]
  (into []
        (comp (map #(namespace-invoice-keys :invoice %1))
              (map #(flatten-paths "/" %1))
              (map remove-nil-vals)
              (map coerce-invoice-vals))
        #_(map #(into (sorted-map) %))
        batch))

(defn scrape-boosts-since [conn token items-per-page wait since]
  (->> (get-all-boosts token items-per-page :wait wait :since since)
       (map process-batch)
       (run! (fn [x]
               (d/transact! conn x)))))


(defn scrape-boosts-after [conn token items-per-page wait after]
  (->> (get-all-boosts token items-per-page :wait wait :after after)
       (map process-batch)
       #_(#(do (clojure.pprint/pprint %) %))
       (run! (fn [x]
               #_(println "BATCH!")
               (d/transact! conn x)))))

(defn scrape-lnd-boosts [conn macaroon wait]
  (->> (get-all-lnd-boosts macaroon :wait wait)
       (map process-batch)
       #_(run! (fn [x] (println x)))
       (run! (fn [x]
               (d/transact! conn x)))))

#_(reduce
   (fn [xs x] (+ xs (peek x)))
   0.0)
#_(filter (fn [[_ _ _ total]] (<= 2000 total)))
#_(sort-by #(nth % 3) #(compare %2 %1))

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

;; WIP: new boost report query
(def base-boost-q
  '[;; find last seen boost
    [?last-e :invoice/identifier ?last-seen]
     ;; get its creation_date
    [?last-e :invoice/creation_date ?last-creation_date]
     ;; for every boost, get its creation date
    [?e :invoice/creation_date ?creation_date]
     ;; it must be after last creation date
    [(< ?last-creation_date ?creation_date)]
     ;; it should be a boost
    [?e :boostagram/action "boost"]
     ;; bind podcasts name
    [?e :boostagram/podcast ?podcast]
     ;; bind search pattern
    [(re-pattern ".*Unplugged.*") ?regex]
     ;; match show
    (or [(re-matches ?regex ?podcast)]
        [?e :boostagram/podcast "LINUX Unplugged"])])

(defn get-ballers [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (> (:boostagram/value_sat_total x1)
              (:boostagram/value_sat_total x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(<= 20000 ?ms)]])}
             (d/db conn)
             last-seen)))

(defn get-ballers-2 [conn show-regex last-seen]
  (d/q '[:find ?sender_name_normalized ?sat_total ?boost_count ?boosts
         :in $ ?regex ?last-seen
         :where
         ;; find all invoices since last-seen for show-regex
         [(d/q [:find ?e
                :in $ ?regex' ?last-seen'
                :where
                ;; find invoices after our last seen id
                [?last :invoice/identifier ?last-seen']
                [?last :invoice/creation_date ?last_creation_date]
                [?e :invoice/creation_date ?creation_date]
                [(< ?last_creation_date ?creation_date)]
                ;; boosts only
                [?e :boostagram/action "boost"]
                ;; filter out those troublemakers
                (not [?e :boostagram/sender_name_normalized "chrislas"])
                (not [?e :boostagram/sender_name_normalized "noblepayne"])
                ;; match our particular show
                [?e :boostagram/podcast ?podcast]
                [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                (or [(re-matches ?regex' ?podcast) _]
                    [(re-matches ?regex' ?episode) _])] 
               $ ?regex ?last-seen) ;; subquery inputs
          ?valid_eids] ;; subquery outputs
         ;; compute sum of total sats and count of boosts per sender
         [(d/q [:find ?sender_name_normalized (sum ?sats) (count ?e)
                :in $ [[?e] ...]
                :where
                [?e :boostagram/sender_name_normalized ?sender_name_normalized]
                [?e :boostagram/value_sat_total ?sats]]
               $ ?valid_eids) ;; subquery inputs
          ?sats_by_eid] ;; subquery outputs
         ;; filter for sat total >= 20000
         [(d/q [:find ?sender_name_normalized' ?sat_total' ?boost_count'
                :in [[?sender_name_normalized' ?sat_total' ?boost_count'] ...]
                :where [(<= 20000 ?sat_total')]]
               ?sats_by_eid) ;; subquery inputs
          [[?sender_name_normalized ?sat_total ?boost_count] ...]] ;; subquery outputs
         ;; boosts by sender (keeping filtering from initial subquery)
         [(d/q [:find [(d/pull ?e [:boostagram/sender_name_normalized
                                   :boostagram/value_sat_total]) ...]
                :in $ [[?e] ...] ?sender_name_normalized'
                :where
                [?e :boostagram/sender_name_normalized ?sender_name_normalized']]
               $ ?valid_eids ?sender_name_normalized) ;; subquery inputs
          ?boosts]] ;; subquery outputs
       (d/db conn) show-regex last-seen)) ;; top level query inputs


(defn get-normal-boosts [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(<=  2000 ?ms)]
                                          [(< ?ms 20000)]])}
             (d/db conn)
             last-seen)))

(defn get-thanks [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                #_:boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at])]
              :in '[$ ?last-seen]
              :where (into base-boost-q '[[?e :boostagram/value_sat_total ?ms]
                                          [(< ?ms 2000)]])}
             (d/db conn)
             last-seen)))


(defn get-summary [conn last-seen]
  (d/q {:find '[(sum ?s) (count ?e) (count-distinct ?b)]
        :in '[$ ?last-seen]
        :where (into base-boost-q '[[?e :boostagram/value_sat_total ?s]
                                    [?e :boostagram/sender_name_normalized ?b]])}
       (d/db conn)
       last-seen))

(defn get-stream-summary [conn show-regex last-seen]
  (into [] cat
        (d/q {:find '[(sum ?s) (count ?e) (count-distinct ?b) (distinct ?b)]
              :in '[$ ?regex ?last-seen]
              :where '[[?last-e :invoice/identifier ?last-seen]
                       [?last-e :invoice/creation_date ?last-creation_date]
                       [?e :invoice/creation_date ?creation_date]
                       [(< ?last-creation_date ?creation_date)]
                       [?e :boostagram/action "stream"]
                       [?e :boostagram/podcast ?podcast]
                       [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
                       (or
                        [(re-matches ?regex ?episode) _]
                        [(re-matches ?regex ?podcast) _])
                       [?e :boostagram/value_sat_total ?s]
                       [?e :boostagram/sender_name_normalized ?b]]}
             (d/db conn)
             show-regex
             last-seen)))

(defn get-all-boosts-since [conn last-seen]
  (into (sorted-set-by
         (fn [x1 x2]
           (compare (:invoice/created_at x1)
                    (:invoice/created_at x2))))
        (comp cat (map #(into (sorted-map) %)))
        (d/q {:find '[(pull ?e [:boostagram/app_name
                                :boostagram/podcast
                                :boostagram/episode
                                #_:boostagram/sender_name
                                :boostagram/sender_name_normalized
                                :boostagram/value_msat_total
                                :boostagram/value_sat_total
                                :boostagram/message
                                #_:invoice/comment
                                :invoice/identifier
                                :invoice/created_at
                                :invoice/creation_date])]
              :in '[$ ?last-seen]
              :where base-boost-q}
             (d/db conn)
             last-seen)))


(defn get-lnd-boosts-from-db [conn show-regex last-seen-idx]
  (into (sorted-set-by (fn [& args] (apply compare (reverse (map :invoice/creation_date args)))))
        (comp cat
              #_(take 10)
                ;; sort keys of each boost
              (map #(into (sorted-map) %)))
        (d/q '[:find (d/pull ?e #_[:*] [:boostagram/app_name
                                        :boostagram/podcast
                                        :boostagram/episode
                                        :boostagram/sender_name_normalized
                                        :boostagram/value_msat_total
                                        :boostagram/value_sat_total
                                        :boostagram/message
                                        :invoice/identifier
                                        :invoice/created_at
                                        :invoice/creation_date])
               :in $ ?regex ?last-seen-idx
               :where
                 ;; only find boosts
               [?e :boostagram/action "boost"]
                 ;; with creation_date's after our "since" marker
               [?e :invoice/creation_date ?cd]
               [?e0 :invoice/identifier ?last-seen-idx]
               [?e0 :invoice/creation_date ?cd0]
               [(< ?cd0 ?cd)]
               #_[(< ?last-seen-idx ?cd)]
                 ;; match podcast and episode to find all episodes of `show`
               [?e :boostagram/podcast ?podcast]
               [(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
               (or
                [(re-matches ?regex ?podcast) _]
                [(re-matches ?regex ?episode) _])]
             (d/db conn)
             show-regex
             last-seen-idx)))

(defn v2->v1 [v2-boosts]
  (->> v2-boosts
         ;; remove namespaces from keys
       (mapv
        (fn [boost]
          (into {}
                (map (fn [[k v]]
                       [(keyword (name k)) v]))
                boost)))
         ;; rename :sender_name_normalized -> :sender_name
       (mapv
        (fn [boost]
          (into {}
                (map (fn [[k v]]
                       [(if (= k :sender_name_normalized) :sender_name k) v]))
                boost)))))

(defn boost-report-v2 [conn show-regex last-seen-index]
  (->> (get-lnd-boosts-from-db conn show-regex last-seen-index)
       v2->v1
       v1/boost-report
       #_(#(with-out-str (clojure.pprint/pprint %)))
       (spit "/tmp/new_boosts.md")))

(defn make-stream-summary [conn show-regex last-seen]
  (let [[sats streams streamers] (get-stream-summary conn show-regex last-seen)]
    (str "### Stream Totals\n"
         "+ Total Sats: " (v1/int-comma sats) "\n"
         "+ Total Streams: " (v1/int-comma streams) "\n"
         "+ Unique Streamers: " (v1/int-comma streamers) "\n")))


(alter-var-root #'*out* (constantly *out*))

(defn -main [& args]
  (let [conn (d/get-conn dbi schema)
        macaroon
        (-> "/home/wes/src/lnd_workdir/admin.macaroon"
            (as-> $
                  (.getPath (java.nio.file.FileSystems/getDefault) $ (into-array String [])))
            java.nio.file.Files/readAllBytes
            javax.xml.bind.DatatypeConverter/printHexBinary
            (#(.toLowerCase %)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (println "closing") (reset! scrape-can-run false) (d/close conn))))
    (while true
      (scrape-lnd-boosts conn macaroon 500)
      (Thread/sleep 60000))
    (println args)))

(comment

  (require '[portal.api :as p])
  (p/open)
  (add-tap #'p/submit)

  (def test-token (or (System/getenv "ALBY_ACCESS_TOKEN") ""))

  (def last-lup #inst "2023-03-27T12-07:00")


;; v2

  #_(require '[datalevin.core :as d])
  #_(require '[datalevin.bits :as b])
  #_(require '[datalevin.storage :as s])
  #_(require '[datalevin.datom :as dac])
  #_(inspect (#'b/indexable 1 1 "abc" nil))
  #_(#'s/low-datom->indexable {"abdd" 1222} (d/datom 1 "abdd" "bbb"))


  ;; (def conn (d/get-conn "/tmp/testdb" schema))
  (def conn (d/get-conn dbi schema))

  (get-boosts {:token test-token :items 2
               :page 1 :after #inst "2024-08-01T11:10:00"


               #_:since #_"txjbH2VGZJ7Z9MzCtAaQWhJ4"})

  (count (d/datoms (d/db conn) :eav))
  (d/datoms (d/db conn) :eav 1)
  (reset! scrape-can-run false)
  (reset! scrape-can-run true)
  #_@get-boosts-state

  (async/take!
   (async/thread-call (fn []
                     ;;(scrape-boosts-since conn test-token
                        (scrape-boosts-after conn test-token
                                             100 3000
                                             #_#inst "2024-07-25T00:00"
                                             #inst "2023-12-31T11:59Z")))
   (fn [_] (println "ALL DONE!")))

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
  (count-of-boosts (streams-by-total-amount conn))

  (into (sorted-map)
        (map (fn [[_ a v]] [a v]))
        (d/datoms (d/db conn) :eav 4473))


  #_(first (d/rseek-datoms (d/db conn) :eav))
  #_(first (d/seek-datoms (d/db conn) :ave :invoice/creation_date))
  ;; TODO: slow and probably wrong
  #_(do
      (println (take 1 (d/seek-datoms (d/db conn) :ave :invoice/created_at)))
      (str (java.time.Instant/now)))

;; better, but what happens under the hood?
  (d/q '[:find (max ?v) :where [_ :invoice/created_at ?v] [(>= ?v #inst "2023-01-01T00:00")]] (d/db conn))
  (count (d/q '[:find ?e :where [?e :invoice/created_at ?v]
                [(> ?v #inst "2023-01-01T00:00")]]
              (d/db conn)))
  (sort-by second
           (d/q '[:find ?v (count ?e)
                  :where [?e :boostagram/app_name ?v]] (d/db conn)))

  (take 100 (d/q '[:find ?e :where [?e :boostagram/app_name "Fountain"]] (d/db conn)))

  (d/datoms (d/db conn) :ave :boostagram/app_name "Fountain")

  (d/q '[:find ?v
         :where [?e :boostagram/app_name "Fountain"]
         [?e :boostagram/sender_name ?v]]
       (d/db conn))

  (->> (d/q '[:find (max ?v)
              :where [_ :boostagram/value_msat_total ?v]]
            (d/db conn))
       first
       first
       (d/datoms (d/db conn) :ave :boostagram/value_msat_total))

  (d/q '[:find ?e
         :where [?e :invoice/identifier "6HWqe6RCNbGobHBdHDQocBYR"]]
       (d/db conn))

  (sort (d/q '[:find ?v :where [_ :boostagram/podcast ?v]] (d/db conn)))

  (d/q '[:find (pull ?e ["*"])
         :where [?e :boostagram/podcast "Mere Mortals"]]
       (d/db conn))



  (boost-report-v2 conn #"(?i).*linux.*" (-> #inst "2024-07-01T00:00Z" (#(.getTime %)) (/ 1000)))
  (boost-report-v2 conn #"(?i).*self.*" "443250")

  (boost-report-v2 conn #"(?i).*linux.*" "450565")

  (make-stream-summary conn #"(?i).*self.*" "443250")

  (let [[[output]]
        (d/q '[:find (min ?i)
               :where [?e :invoice/creation_date ?i]]
             (d/db conn))]
    (str (java.time.Instant/ofEpochSecond output))
    (format-date output))

  (let [output
        (d/q '[:find ?i
               :where [?e :invoice/creation_date ?i]]
             (d/db conn))]
    (->> (into [] cat output)
         sort
         (take 10)
         (map format-date)))

  (defn seconds-between [inst1 inst2]
    (.toSeconds
     (java.time.Duration/between
      (.toInstant inst1)
      (.toInstant inst2))))

  ;; TODO: docs on the property we need to set for this to work
  (http/get "https://100.115.78.27:8080/v1/invoices"
            {:client (http/client {:ssl-context {:insecure true}})
             :headers {"Grpc-Metadata-macaroon" macaroon}
             :query-params {"reversed" "true"}})

  ;; macaroon -> hex
  (def macaroon
    (-> "/home/wes/src/lnd_workdir/admin.macaroon"
        (as-> $
              (.getPath (java.nio.file.FileSystems/getDefault) $ (into-array String [])))
        java.nio.file.Files/readAllBytes
        javax.xml.bind.DatatypeConverter/printHexBinary
        (#(.toLowerCase %))))

  ;; basic base64
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes "wes"))
  (java.lang.String. (.decode (java.util.Base64/getDecoder) "d2Vz"))

  (get-lnd-boosts {:macaroon macaroon})
  (get-all-lnd-boosts {:macaroon macaroon :wait 2000})

  (reset! scrape-can-run false)
  (reset! scrape-can-run true)
  (async/take!
   (async/thread-call (fn []
                        (scrape-lnd-boosts conn macaroon 500)))
   (fn [x] (println "========== DONE ==========" x)))

  (d/q '[:find [?id]
         :where
         [(d/q [:find [(min ?cd)]
                :where
                [?e2 :boostagram/action "boost"]
                [?e2 :invoice/creation_date ?cd]] $) [?mincd]]
         [?e :invoice/creation_date ?mincd]
         [?e :invoice/identifier ?id]]
       (d/db conn))

  (get-ballers-2 conn #"(?i).*linux.*" "450565")


  (def schema
    {:invoice/identifier {:db/valueType :db.type/string
                          :db/unique :db.unique/identity}
     :t1 {:db/valueType :db.type/string}
     :t2 {:db/valueType :db.type/string}
     :uniq {:db/valueType :db.type/tuple
            :db/unique :db.unique/identity
            :db/tupleAttrs [:t1 :t2]}})

  #_(def conn (d/get-conn "/tmp/dp5" schema))

  (d/close conn)
  )