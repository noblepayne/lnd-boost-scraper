(ns boost-scraper.reconcile
  "Web Boost orphan reconciliation (spec: docs/orphan-reconcile-spec.md).

   Seams, in increasing infra exposure (each lower seam is unit-tested,
   higher seams stay thin):
   1. parse / match / build            — pure, zero infra (§5-§6)
   2. detect-orphans                   — pure: invoices + orders + boosted-key
                                          sets → detection report
   3. find-web-boost-invoices /
      find-boosted-keys                — Datalevin reads (tested with
                                          in-memory conns)
   4. fetch-pending-orders             — Zaprite HTTP (thin, mirrors
                                          upstream.zaprite), pageable fetcher
                                          `fetch-pending-orders*` is injected
                                          and unit-tested"
  (:require [babashka.http-client :as http]
            [boost-scraper.db :as db]
            [boost-scraper.shows :as shows]
            [boost-scraper.upstream.zaprite :as zaprite]
            [boost-scraper.utils :as utils]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d])
  (:import [java.time Instant]
           [java.util Date]))

(def web-boost-memo-prefix "Payment for Web Boost: ")

(def web-boost-label-prefix "Web Boost: ")

(def web-boost-memo-re
  #"^([a-zA-Z0-9]+(?:[-_][a-zA-Z0-9]+)*)\s+(\d+)\s*(?:[—–-])\s*(.+)$")

(defn- strip-web-boost-prefix
  "Normalize the three observed memo forms (see spec §5) to the core grammar:
   {SLUG} {EP} <sep> {USER}."
  [memo]
  (-> memo
      (str/replace #"^Payment for Web Boost:\s*" "")
      (str/replace #"^Web Boost:\s*" "")
      str/trim))

(defn parse-web-boost-memo
  "Parse a web-boost BOLT-11 memo (or Zaprite label) into structured parts.

   Accepts canonical, label-prefixed, and bare forms:
     \"Payment for Web Boost: TWIB 118 — Adam Curry\"
     \"Web Boost: TWIB 118 — Adam Curry\"
     \"TWIB 108 — debitcoinkoers.eu\"
   Separator may be — (U+2014), – (U+2013), or -. Slug may be hyphenated.

   Returns {:show-slug (lowercased slug)
            :show-ep (episode digits, string)
            :username (greedy remainder, trimmed)
            :label (input as-is, trimmed)}
   or nil when the memo is not a web-boost memo."
  [memo]
  (when (and memo (seq (str/trim memo)))
    (let [label (str/trim memo)
          core (strip-web-boost-prefix memo)]
      (when-let [[_ slug ep user] (re-matches web-boost-memo-re core)]
        {:show-slug (str/lower-case slug)
         :show-ep ep
         :username (str/trim user)
         :label label}))))

(defn order-sats
  "BTC order totalAmount in sats as a long, or nil when the order is not BTC
   or exposes no amount."
  [order]
  (when (= "BTC" (:currency order))
    (let [v (:totalAmount order)]
      (when (some? v)
        (try (Long/parseLong (str v))
             (catch NumberFormatException _ nil))))))

(defn candidate-id
  "Normalized candidate identity for an order: metadata.username first,
   falling back to parsing the order label."
  [order]
  (or (some-> (get-in order [:metadata :username]) db/normalize-name)
      (some-> (parse-web-boost-memo (str (:label order))) :username db/normalize-name)))

(defn parse-long-or-nil [s]
  (when s
    (try (Long/parseLong (str s))
         (catch NumberFormatException _ nil))))

(defn euid-parts
  "Parse a web-boost externalUniqId into its stable prefix parts.
   Format: web-boost:{slug}:{episodeKey}:{currency}:{amount}:{token}:{fingerprint}
   Returns {:slug :currency :amount} or nil when not a web-boost id."
  [order]
  (when-let [euid (get order :externalUniqId)]
    (let [[prefix slug _ currency amount] (str/split euid #":" 6)]
      (when (and (= "web-boost" prefix) currency amount)
        {:slug slug :currency currency :amount amount}))))

(defn euid-matches-target?
  "externalUniqId as a hardening guard: when present, its prefix parts must
   agree with the settled invoice's slug + sats (the tail — client token +
   message fingerprint — is not derivable from the LND side, so it confirms
   but cannot split identical label+amount pairs like memphis)."
  [order target]
  (if-let [parts (euid-parts order)]
    (and (= (:slug parts) (:show-slug target))
         (= "BTC" (:currency parts))
         (= (parse-long-or-nil (:amount parts)) (:sats target)))
    true))

(defn match-order-candidates
  "Across Zaprite orders, find web-boost candidates for a settled invoice.

   target :: {:show-slug :show-ep :username :sats}
   A candidate must:
   - have metadata.app = \"web-boost\"
   - pass the externalUniqId hardening guard when it carries one
   - match the invoice username (normalized, via metadata or label)
   - agree on show slug + episode when its label is parseable
   - expose a BTC amount equal to the settled sats

   Returns {:confidence :high | :manual-review | :none
            :order best-order-or-nil
            :candidates [orders]} — :manual-review when more than one
   candidate qualifies (e.g. the memphis dup-label pair)."
  [orders target]
  (let [username (db/normalize-name (:username target))
        cands (into []
                    (filter
                     (fn [o]
                       (let [par (parse-web-boost-memo (str (:label o)))
                             position (or (nil? par)
                                          (and (= (:show-slug target) (:show-slug par))
                                               (= (:show-ep target) (:show-ep par))))]
                         (and (= "web-boost" (get-in o [:metadata :app]))
                              (euid-matches-target? o target)
                              (= username (candidate-id o))
                              position
                              (= (order-sats o) (:sats target))))))
                    orders)]
    (case (count cands)
      0 {:confidence :none :order nil :candidates []}
      1 {:confidence :high :order (first cands) :candidates cands}
      {:confidence :manual-review :order nil :candidates cands})))

(defn parse-rfc3339
  "Parse an RFC3339 timestamp string to an Instant, or nil."
  [s]
  (when (and s (seq (str/trim s)))
    (try (Instant/parse (str/trim s))
         (catch Exception _ nil))))

(defn coerce-sats
  "Coerce an LND invoice value (number or numeric string) to a positive long."
  [v]
  (when (some? v)
    (try (let [n (Long/parseLong (str v))]
           (when (pos? n) n))
         (catch NumberFormatException _ nil))))

(defn build-boost-entity
  "Build the enrichment entity for a reconciled web-boost payment (spec §6).

   Upserts by :invoice/identifier (= LND add_index string, the DB identity)
   onto the existing nodecan invoice entity — never creates a new one.

   parsed :: {:show-slug :show-ep :username}            from parse-web-boost-memo
   info   :: {:invoice-id (string add_index)
              :sats (long)
              :settle-date (RFC3339)     LND settlement — authority
              :creation-date (RFC3339)   fallback when settle-date unknown}
   order  :: Zaprite order map (metadata only; never trusted for payment state)

   LND settle data is authoritative: :boostagram/received_at and
   :invoice/creation_date come from the LND settle timestamp, per field
   precedence (§6 rule 3)."
  [{:keys [show-slug show-ep username]}
   {:keys [invoice-id sats settle-date creation-date]}
   order]
  (let [meta (get order :metadata {})
        sender (or (get meta :username) username)
        sats-long (coerce-sats sats)
        settle-instant (parse-rfc3339 (or settle-date creation-date))
        settle-epoch (some-> settle-instant .getEpochSecond)
        settle-date-obj (some-> settle-instant (Date/from))
        podcast-name (or (get meta :podcastName)
                         (:name (shows/resolve-show show-slug)))]
    (db/remove-empty-vals
     {:invoice/identifier invoice-id
      :boostagram/zaprite_order_id (get order :id)
      :boostagram/podcast podcast-name
      :boostagram/podcast_slug show-slug
      :boostagram/episode (or (get meta :episodeTitle)
                              (some-> (get meta :episodeNumber) str)
                              show-ep)
      :boostagram/episode_guid (get meta :episodeGuid)
      :boostagram/sender_name sender
      :boostagram/sender_name_normalized (db/normalize-name (or sender ""))
      :boostagram/message (or (get meta :message) "")
      :boostagram/action "boost"
      :boostagram/payment_rail "lightning"
      :boostagram/app_name "Zaprite"
      :scraper/source "nodecan"
      :boostagram/type :sat
      :boostagram/value_sat_total sats-long
      :invoice/creation_date settle-epoch
      :invoice/created_at settle-date-obj
      :boostagram/received_at settle-date-obj})))

(defn epoch-to-rfc3339
  "Unix epoch seconds → RFC3339 string, or nil for non-numeric input."
  [epoch]
  (when (integer? epoch)
    (str (Instant/ofEpochSecond epoch))))

(defn find-web-boost-invoices
  "Read settled web-boost invoices from the nodecan Datalevin conn.

   Returns invoice maps with the stored fields (:invoice/identifier,
   :invoice/memo, :invoice/value, :invoice/settled, :invoice/settle_date —
   may be absent — and :invoice/creation_date). Settled-only, memo must
   parse as a web-boost memo (§5), and the identity must be present.

   Note: pull only surfaces schema-declared attrs, so the payload is read
   via d/entity to also expose the undeclared :invoice/settled /
   :invoice/value / :invoice/settle_date that nodecan's scraper persists."
  [conn]
  (let [db (d/db conn)
        rows (d/q '[:find ?e ?id ?memo
                    :where [?e :invoice/memo ?memo]
                    [?e :invoice/identifier ?id]]
                  db)]
    (into []
          (comp (map (fn [[e id memo]]
                       (let [en (into {} (d/entity db e))]
                         {:invoice/identifier id
                          :invoice/memo memo
                          :invoice/value (:invoice/value en)
                          :invoice/settled (:invoice/settled en)
                          :invoice/settle_date (:invoice/settle_date en)
                          :invoice/creation_date (:invoice/creation_date en)})))
                (filter (fn [m]
                          (and (true? (:invoice/settled m))
                               (some? (:invoice/identifier m))
                               (some? (parse-web-boost-memo (:invoice/memo m)))))))
          rows)))

(defn find-boosted-keys
  "Sets of identities already ingested as boosts in the conn (dedup guard).

   Returns {:identifiers #{:invoice/identifier strings of entities with
                           :boostagram/action \"boost\"}
            :order-ids   #{:boostagram/zaprite_order_id strings}}"
  [conn]
  (let [db (d/db conn)]
    {:identifiers (into #{} (d/q '[:find [?id ...]
                                   :where [?e :invoice/identifier ?id]
                                   [?e :boostagram/action "boost"]]
                                 db))
     :order-ids (into #{} (d/q '[:find [?oid ...]
                                 :where [?e :boostagram/zaprite_order_id ?oid]]
                               db))}))

(defn fetch-pending-orders*
  "Page through PENDING Zaprite orders with an injected fetch function.

   fetch-fn :: api-key page-number → parsed response {:items [...] :meta {:pagesCount N}}
   or nil (page unavailable → stop reading). Returns all items flattened.
   Pages until pagesCount when present; a response without pagesCount is
   treated as a single page."
  [fetch-fn api-key]
  (loop [page 1 acc []]
    (let [resp (fetch-fn api-key page)]
      (if (nil? resp)
        acc
        (let [items (or (:items resp) [])
              pages (or (get-in resp [:meta :pagesCount]) 1)]
          (if (< page pages)
            (recur (inc page) (into acc items))
            (into acc items)))))))

(defn pending-orders-query
  "Query params for the PENDING filter.

   Zaprite ignores `status[]` (array-form) — verified live 2026-08-19: the
   param is silently dropped and the API returns the unfiltered default set
   (all COMPLETE+UNDERPAID). The single `status` param is the correct form."
  [page]
  {"status" "PENDING"
   "page" (str page)})

(defn fetch-pending-orders
  "Fetch all PENDING Zaprite orders (all pages, status=PENDING only)."
  [api-key]
  (fetch-pending-orders*
   (fn [key page]
     (utils/with-retries
       (fn []
         (-> (http/get (str zaprite/zaprite-api-base "/v1/orders")
                       {:headers {"Authorization" (str "Bearer " key)}
                        :query-params (pending-orders-query page)})
             (utils/check-http-status "Zaprite")
             :body
             (json/parse-string true)))))
   api-key))

(defn orphan-row
  "Normalized report row for a high-confidence orphan, including the
   ready-to-write enrichment entity (spec §6)."
  [invoice parsed sats order]
  (let [settle (or (:invoice/settle_date invoice)
                   (epoch-to-rfc3339 (:invoice/creation_date invoice)))]
    {:identifier (:invoice/identifier invoice)
     :memo (:invoice/memo invoice)
     :sats sats
     :settle-date settle
     :show-slug (:show-slug parsed)
     :show-ep (:show-ep parsed)
     :username (:username parsed)
     :order-id (:id order)
     :confidence :high
     :entity (build-boost-entity
              parsed
              {:invoice-id (:invoice/identifier invoice)
               :sats sats
               :settle-date (:invoice/settle_date invoice)
               :creation-date (epoch-to-rfc3339 (:invoice/creation_date invoice))}
              order)}))

(defn detect-orphans
  "Core detection (pure). Classify each settled web-boost invoice against the
   pending Zaprite orders.

   invoices :: seq of invoice maps (see find-web-boost-invoices)
   orders   :: seq of pending Zaprite orders
   boosted  :: {:identifiers #{id} :order-ids #{order-id}} from find-boosted-keys

   Returns
   {:scanned N
    :already-boosted N          ;; skipped via dedup guard
    :orphans [...]              ;; high-confidence: order matched + entity ready
    :manual-review [...]        ;; duplicate-label candidates (e.g. memphis)
    :unmatched [...]            ;; settled web-boost, no matching order
    :total-sats-skipped N
    :total-sats-orphaned N}     ;; sats in the three non-skipped buckets"
  [invoices orders boosted]
  (let [boosted-ids (or (:identifiers boosted) #{})
        boosted-oids (or (:order-ids boosted) #{})]
    (loop [remaining (seq invoices)
           scanned 0
           skipped 0
           orphans []
           review []
           unmatched []
           sats-skip 0
           sats-orphan 0]
      (if-let [invoice (first remaining)]
        (let [id (:invoice/identifier invoice)
              memo (:invoice/memo invoice)
              sats (coerce-sats (:invoice/value invoice))
              sats' (or sats 0)
              parsed (parse-web-boost-memo memo)
              result (if parsed
                       (match-order-candidates orders (assoc parsed :sats sats'))
                       {:confidence :none})
              order-id (some-> (:order result) :id)
              dedup (or (contains? boosted-ids id)
                        (and order-id (contains? boosted-oids order-id)))]
          (cond
            dedup (recur (rest remaining) (inc scanned) (inc skipped)
                         orphans review unmatched
                         (+ sats-skip sats') sats-orphan)
            (= :high (:confidence result))
            (recur (rest remaining) (inc scanned) skipped
                   (conj orphans (orphan-row invoice parsed sats (:order result)))
                   review unmatched
                   sats-skip (+ sats-orphan sats'))
            (= :manual-review (:confidence result))
            (recur (rest remaining) (inc scanned) skipped
                   orphans
                   (conj review {:identifier id :memo memo :sats sats
                                 :parsed parsed
                                 :candidates (mapv :id (:candidates result))})
                   unmatched
                   sats-skip (+ sats-orphan sats'))
            :else
            (recur (rest remaining) (inc scanned) skipped
                   orphans review
                   (conj unmatched {:identifier id :memo memo :sats sats
                                    :parsed parsed})
                   sats-skip (+ sats-orphan sats'))))
        {:scanned scanned
         :already-boosted skipped
         :orphans orphans
         :manual-review review
         :unmatched unmatched
         :total-sats-skipped sats-skip
         :total-sats-orphaned sats-orphan}))))

(defn reconcile-report
  "Deterministic markdown summary of a detect-orphans result."
  [detection]
  (let [line (fn [o]
               (str "- `" (:identifier o) "` " (:username o)
                    " (" (:show-slug o) " " (:show-ep o) "): "
                    (:sats o) " sats, settle " (:settle-date o)
                    ", order " (:order-id o)))
        orphans (sort-by (juxt :settle-date :identifier) (:orphans detection))]
    (str/join "\n"
              (concat
               ["# Zaprite Orphan Reconcile Report"
                ""
                (str "Scanned: " (:scanned detection))
                (str "Already boosted (skipped): " (:already-boosted detection))
                (str "Orphans: " (count orphans)
                     " (" (:total-sats-orphaned detection) " sats)")
                (str "Manual review: " (count (:manual-review detection)))
                (str "Unmatched: " (count (:unmatched detection)))
                ""
                "## Orphans"]
               (map line orphans)
               [""
                "## Manual review"]
               (map (fn [r]
                      (str "- `" (:identifier r) "` " (:memo r)
                           " — candidates: " (str/join ", " (:candidates r))))
                    (:manual-review detection))
               [""
                "## Unmatched"]
               (map (fn [u]
                      (str "- `" (:identifier u) "` " (:memo u)
                           " (" (:sats u) " sats)"))
                    (:unmatched detection))))))

(defn write-report!
  "Persist a reconcile report as markdown + JSON under out-dir.
   Returns the written markdown file path."
  [out-dir detection]
  (let [stem (str "orphan-reconcile-" (str/replace (str (Instant/now)) #"[:.]" "-"))
        md-file (io/file out-dir (str stem ".md"))
        json-file (io/file out-dir (str stem ".json"))]
    (io/make-parents md-file)
    (spit md-file (str (reconcile-report detection) "\n"))
    (spit json-file (json/generate-string detection))
    (str md-file)))

(defn -main
  "One-shot dry-run reconcile (spec §11 Phase 2 exit gate).

   Scans nodecan-conn for settled web-boost invoices, fetches PENDING Zaprite
   orders, detects orphans, and writes markdown+JSON reports to
   WEB_BOOST_RECONCILE_REPORT_DIR (default: reports/).

   Reads env: NODECAN_DBI, ZAPRITE_API_KEY_PATH.
   Never writes to the DB — write mode is Phase 3 and flag-gated."
  [& _]
  (let [dbi (some-> (System/getenv "NODECAN_DBI") not-empty)
        api-key (some-> (System/getenv "ZAPRITE_API_KEY_PATH") slurp str/trim not-empty)
        out-dir (or (some-> (System/getenv "WEB_BOOST_RECONCILE_REPORT_DIR") not-empty) "reports")]
    (if (or (nil? dbi) (nil? api-key))
      (do
        (binding [*out* *err*]
          (println "reconcile: requires NODECAN_DBI and ZAPRITE_API_KEY_PATH env vars"))
        (System/exit 1))
      (let [conn (d/get-conn dbi db/schema)]
        (try
          (let [detection (detect-orphans (find-web-boost-invoices conn)
                                          (fetch-pending-orders api-key)
                                          (find-boosted-keys conn))
                report-path (write-report! out-dir detection)]
            (println (reconcile-report detection))
            (println)
            (println "Report written to:" report-path))
          (finally
            (d/close conn)))))))