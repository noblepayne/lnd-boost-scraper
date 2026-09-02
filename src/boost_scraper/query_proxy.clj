(ns boost-scraper.query-proxy
  "Safe Datalog query proxy for the boost scraper database.
   Read-only by construction — never imports d/transact!.

   SECURITY: datalevin 0.9.13's embedded query engine resolves function
   symbols with a fallthrough to `(resolve sym)` — any var in any loaded
   namespace — plus a raw-Java-reflection dot-form escape hatch. A query like
   {:find [?x] :where [[_ :boostagram/value_sat_total _]
                       [(clojure.core/load-string \"(shell ...)\") ?x]]}
   executes arbitrary code (confirmed live: load-string and slurp work).
   Upstream added a `*resolver-mode* :server-safe` switch in 1.x, but 0.9.13
   does not have it. So we enforce registry-only function resolution OURSELVES,
   before the query ever reaches d/q, by walking the parsed query and
   allowing only symbols from datalevin's own built-in registries
   (datalevin.built-ins/query-fns + aggregates). This mirrors upstream's
   1.x :server-safe semantics and auto-tracks the registry across upgrades."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalevin.built-ins :as bi]
            [datalevin.core :as d])
  (:import (java.util.concurrent Semaphore)))

;; ---------------------------------------------------------------------------
;; Allowlist — derived from datalevin's own registries (auto-tracks upstream)
;; ---------------------------------------------------------------------------

(def ^:private query-fn-allowlist
  "Symbols callable as predicates/function expressions inside :where and
   nested fn args. Derived from datalevin's own built-in registry so it stays
   correct across upgrades (0.9.13 → 1.x)."
  (delay (set (keys bi/query-fns))))

(def ^:private aggregate-allowlist
  "Symbols allowed as :find aggregation heads. Also derived from upstream."
  (delay (set (keys bi/aggregates))))

(def ^:private structural-heads
  "Datalog structural clauses in :where that recurse rather than call a fn."
  #{'or 'and 'not 'not-join 'or-join})

(def ^:private allowed-top-level-keys
  "Query-map keys supported by 0.9.13. STRICT: anything else is rejected.
   Extend this set when we upgrade (e.g. 0.10+/1.x adds :having)."
  #{:find :with :in :where :keys :strs :syms :order-by :limit :offset})

(def ^:private max-query-length 65536)

(defn- disallowed
  [sym]
  {:error :disallowed-fn
   :symbol sym
   :detail (str "Query function or form not allowed: " sym)})

(defn- dot-form?
  "0.9.13 lets a query call arbitrary Java methods via (.method obj ...) —
   raw reflection that bypasses the registry. Reject any head symbol that
   starts with a dot."
  [s]
  (and (symbol? s)
       (str/starts-with? (name s) ".")))

(declare check-fn-call)

(defn- check-exprs
  "Walk a collection of expressions; reject any nested fn call not in the
   allowlist. Returns nil (ok) or an error map."
  [xs]
  (loop [xs (seq xs)]
    (when xs
      (let [x (first xs)]
        (cond
          (list? x)  (or (check-fn-call x) (recur (next xs)))
          (vector? x) (or (check-exprs x) (recur (next xs)))
          :else      (recur (next xs)))))))

(defn- check-fn-call
  "Validate a function-call list (head must be in query-fn-allowlist)."
  [s]
  (let [head (first s)]
    (cond
      (dot-form? head)        (disallowed head)
      (not (symbol? head))    (disallowed head)
      (contains? structural-heads head) nil
      (contains? @query-fn-allowlist head) (check-exprs (rest s))
      :else                   (disallowed head))))

(defn- check-find-expr
  "Validate a :find aggregation expression (head must be in aggregates)."
  [s]
  (let [head (first s)]
    (cond
      (dot-form? head)        (disallowed head)
      (not (symbol? head))    (disallowed head)
      (contains? @aggregate-allowlist head) (check-exprs (rest s))
      :else                   (disallowed head))))

(defn- check-find-element
  "Validate one :find spec: a plain var/constant, an aggregation expression
   (checked against the aggregate allowlist), or a collection spec (walked
   as exprs). Returns nil (ok) or an error map."
  [el]
  (cond
    (list? el)   (check-find-expr el)
    (vector? el) (check-exprs el)  ; collection find spec — vars/exprs only
    :else        nil))             ; plain var/constant

(defn- check-in-binding
  "Validate a single :in binding. Reject rules (% / %% — rule calls would
   need name-based holes in the allowlist) and any unexpected form."
  [b]
  (cond
    (symbol? b)
    (if (str/starts-with? (name b) "%")
      (disallowed b)
      nil)
    (vector? b) (check-exprs b)   ; collection/tuple binding — vars only
    (list? b)   (disallowed (first b))
    :else       nil))

(defn- check-where-clause
  "Validate one :where clause (pattern vector, pred/fn vector, or structural
   list). Returns nil (ok) or the first error."
  [clause]
  (cond
    ;; Pattern / predicate / fn-binding clause (vector). Any list inside is a
    ;; fn expression and must be allowlisted.
    (vector? clause)
    (check-exprs clause)

    ;; Structural clause (or/and/not/not-join/or-join) or a rule call (list).
    (list? clause)
    (let [head (first clause)]
      (if (contains? structural-heads head)
        (some check-where-clause (rest clause))
        ;; Non-structural list = rule call. Rules are unsupported (we reject
        ;; % / %% in :in), so reject the head outright.
        (disallowed head)))

    :else nil))

(defn- validate-query-fns
  "Enforce registry-only function resolution over the parsed query.
   Returns nil (ok) or an error map."
  [parsed]
  (let [top-key (some (fn [k] (when-not (contains? allowed-top-level-keys k) k))
                      (keys parsed))]
    (cond
      top-key
      {:error :invalid-query
       :detail (str "Unsupported query key: " top-key)}

      (> (count (pr-str parsed)) max-query-length)
      {:error :invalid-query
       :detail (str "Query too large (max " max-query-length " bytes)")}

      :else
      (or (some check-find-element (:find parsed))
          (some check-in-binding (:in parsed))
          (some check-where-clause (:where parsed))
          nil))))

;; ---------------------------------------------------------------------------
;; EDN parse / validation / execution
;; ---------------------------------------------------------------------------

(defn safe-read-edn
  "Safely parse an EDN string. Returns {:ok parsed} or {:error :detail}.
   Uses the safe reader — no code evaluation, reader tags rejected."
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

;; ---------------------------------------------------------------------------
;; Execution hardening — bounded concurrency, hard timeout, structured errors
;; ---------------------------------------------------------------------------

(def ^:private max-concurrent-queries 3)
(def ^:private query-semaphore (Semaphore. max-concurrent-queries))

(defn- run-query
  "Execute the parsed query against the DB snapshot with a hard timeout.
   Runs on a bounded pool (semaphore). Cancels the worker on timeout.
   Converts any thrown exception into a structured error instead of letting
   it escape as a raw 500."
  [db parsed params timeout]
  (if-not (.tryAcquire query-semaphore)
    {:error :busy :detail "Server is busy (concurrent query limit reached)"}
    (try
      (let [fut (future (try (apply d/q parsed db params)
                             (catch Throwable t {:dl/error t})))
            res (deref fut timeout ::timeout)]
        (cond
          (= res ::timeout)
          (do (future-cancel fut)
              {:error :timeout})

          (and (map? res) (contains? res :dl/error))
          (let [t (:dl/error res)]
            {:error :exception
             :detail (or (.getMessage t) (str t))})

          :else
          {:ok res}))
      (finally
        (.release query-semaphore)))))

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
      (if-let [err (or (validate-query ok) (validate-query-fns ok))]
        {:status :error :detail (:detail err)}
        (let [db       (d/db conn)
              start    (System/currentTimeMillis)
              result   (run-query db ok (:params opts) timeout)
              elapsed  (- (System/currentTimeMillis) start)]
          (if (:error result)
            (if (= :busy (:error result))
              {:status :error :detail (:detail result) :code :busy}
              {:status :error :detail (:detail result)})
            (let [rows (:ok result)
                  truncated (> (count rows) limit)]
              {:status    :ok
               :results   (take limit rows)
               :truncated truncated
               :elapsed_ms elapsed})))))))
