(ns boost-scraper.client-state
  "Client state tracking for agent-based incremental fetching.
   Tracks last-seen timestamp per client+show combination."
  (:require [datalevin.core :as d]))

(defn client-state-key
  "Generate composite key for client-state entity."
  [client-id show-slug]
  (str client-id ":" show-slug))

(defn get-client-state
  "Get client state for a specific client and show.
   Returns entity map or nil if not found."
  [conn client-id show-slug]
  (let [key (client-state-key client-id show-slug)]
    (d/q '[:find (pull ?e [*]) .
           :in $ ?key
           :where [?e :client-state/key ?key]]
         (d/db conn) key)))

(defn get-or-create-client-state
  "Get existing or create new client state.
   Returns entity map."
  [conn client-id show-slug]
  (if-let [state (get-client-state conn client-id show-slug)]
    state
    (let [key (client-state-key client-id show-slug)
          now (long (/ (System/currentTimeMillis) 1000))]
      (d/transact! conn [{:client-state/key key
                          :client-state/client-id client-id
                          :client-state/show-slug show-slug
                          :client-state/last-seen-tx now
                          :client-state/last-accessed-tx now}])
      (get-client-state conn client-id show-slug))))

(defn update-last-seen!
  "Update last-seen-tx to the given timestamp.
   Also updates last-accessed-tx to now. Uses upsert - creates if not exists."
  [conn client-id show-slug new-last-seen]
  (let [key (client-state-key client-id show-slug)
        now (long (/ (System/currentTimeMillis) 1000))]
    (if (get-client-state conn client-id show-slug)
      (d/transact! conn [[:db/add [:client-state/key key] :client-state/last-seen-tx new-last-seen]
                         [:db/add [:client-state/key key] :client-state/last-accessed-tx now]])
      (d/transact! conn [{:client-state/key key
                          :client-state/client-id client-id
                          :client-state/show-slug show-slug
                          :client-state/last-seen-tx new-last-seen
                          :client-state/last-accessed-tx now}]))))

(defn touch-accessed!
  "Update only last-accessed-tx to now. Uses upsert."
  [conn client-id show-slug]
  (let [key (client-state-key client-id show-slug)
        now (long (/ (System/currentTimeMillis) 1000))]
    (if (get-client-state conn client-id show-slug)
      (d/transact! conn [[:db/add [:client-state/key key] :client-state/last-accessed-tx now]])
      (d/transact! conn [{:client-state/key key
                          :client-state/client-id client-id
                          :client-state/show-slug show-slug
                          :client-state/last-accessed-tx now}]))))

(defn delete-client-state!
  "Delete a specific client state. Does nothing if not found."
  [conn client-id show-slug]
  (let [key (client-state-key client-id show-slug)]
    (when (get-client-state conn client-id show-slug)
      (d/transact! conn [[:db/retractEntity [:client-state/key key]]]))))

(defn list-client-states
  "List all client states.
   Returns vector of entity maps."
  [conn]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :client-state/key]]
       (d/db conn)))

(defn cleanup-old-states!
  "Delete client states not accessed within the given number of days.
   Returns count of deleted states."
  [conn days]
  (let [#_:clj-kondo/ignore cutoff (- (long (/ (System/currentTimeMillis) 1000))
                                      (* days 24 60 60))
        old-states (d/q '[:find ?e .
                          :where
                          [?e :client-state/key]
                          [?e :client-state/last-accessed-tx ?tx]
                          [(< ?tx cutoff)]]
                        (d/db conn))
        old-id (if (sequential? old-states)
                 (seq old-states)
                 (when old-states [old-states]))]
    (when (seq old-id)
      (d/transact! conn (mapv #(vector :db/retractEntity [:client-state/key %]) old-id)))
    (count (or old-id []))))
