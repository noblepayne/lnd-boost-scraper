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
   4. fetch-unified-orders             — Zaprite HTTP (thin; creation-asc,
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

(defn- content-identical?
  "True when every candidate produces an identical boost: same normalized
   username, slug, ep, sats, and message. Display-case differences (memphis vs
   Memphis) do not break identity — usernames are normalized. Message is
   compared normalized so whitespace/case variance cannot split a group."
  [target cands]
  (let [f (fn [o]
            [(candidate-id o)
             (:show-slug target)
             (:show-ep target)
             (:sats target)
             (db/normalize-name (str (get-in o [:metadata :message])))])]
    (apply = (map f cands))))

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
            :candidates [orders]}.

   Multiple candidates (retry-pattern duplicate label+amount): HIGH when all
   candidates are content-identical — the anchor is then deterministic
   bookkeeping (later Zaprite completion upsert-merges via the unique
   zaprite_order_id) — and the tie-break picks the LAST candidate in the
   delivered order. The reconcile fetcher delivers orders creation-ascending
   (sortBy=createdAt, verified live), so the latest-created retry wins, which
   live-data analysis says is the settled invoice's true anchor. Candidates
   that differ in content stay :manual-review — content is never guessed."
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
      (if (content-identical? target cands)
        {:confidence :high :order (last cands) :candidates cands}
        {:confidence :manual-review :order nil :candidates cands}))))

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

(def unified-statuses ["PENDING" "PAID" "COMPLETE" "OVERPAID"])

(defn unified-orders-query
  "Query params for the reconcile fetch: all relevant statuses (one `status`
   param per value — the array form is ignored), creation-ascending so the
   returned sequence carries creation-order signal (sortBy=createdAt is
   undocumented but honored by the API — verified live 2026-08-28). COMPLETE
   items carry paidAt, giving the pairing rule its anchor times in one pull."
  [page]
  {"status" unified-statuses
   "sortBy" "createdAt"
   "sortOrder" "asc"
   "page" (str page)})

(defn fetch-unified-orders
  "Fetch all reconcile-relevant Zaprite orders (all pages), creation-ascending.
   Replaces the PENDING-only fetch for reconcile: one pull yields both the
   pairing data and the creation-order tie-break signal."
  [api-key]
  (fetch-pending-orders*
   (fn [key page]
     (utils/with-retries
       (fn []
         (-> (http/get (str zaprite/zaprite-api-base "/v1/orders")
                       {:headers {"Authorization" (str "Bearer " key)}
                        :query-params (unified-orders-query page)})
             (utils/check-http-status "Zaprite")
             :body
             (json/parse-string true)))))
   api-key))

(def pairing-window-seconds
  "Max gap between the invoice's anchoring time (LND settle, else creation)
   and COMPLETE paidAt for the pairing rule. Observed gaps are ~5s-2min for
   timely webhooks; 2h is generous but bounded to cover webhook-lag cases.
   Measured live 2026-09-02: Hydragyrum LUP-668 (invoice 327805 settled
   20:45:29, order od_hTEwY3DUVX paidAt 21:30:34) — a 45min webhook lag that
   previously fell outside the old 600s window, leaving a false positive."
  7200)

(defn invoice-pairing-target
  "Extract the pairing key from a settled web-boost invoice:
   normalized username, slug, ep, sats, creation epoch, and — when recorded —
   the LND settle epoch (the authority for pairing; falls back to creation)."
  [invoice]
  (let [parsed (parse-web-boost-memo (:invoice/memo invoice))
        sats (coerce-sats (:invoice/value invoice))
        settle-epoch (some-> (:invoice/settle_date invoice) parse-rfc3339 .getEpochSecond)]
    (when (and parsed sats (:invoice/creation_date invoice))
      {:username (db/normalize-name (:username parsed))
       :show-slug (:show-slug parsed)
       :show-ep (:show-ep parsed)
       :sats sats
       :creation-epoch (:invoice/creation_date invoice)
       :settle-epoch settle-epoch})))

(defn order-tx-sats
  "True sats the customer actually paid, taken from the order's transaction
   record (not the order total).

   WHY THIS EXISTS — the fiat-order detection blindspot (2026-08-29):
   Web Boost customers can pay a dollar-denominated order via lightning. The
   Zaprite ORDER is recorded as fiat (currency \"USD\", totalAmount in cents),
   but the underlying TRANSACTION settles in BTC sats through nodecan LND.
   Verified across all 240 COMPLETE orders (2026-08-28 pull):
   - BTC orders: transactions[0] is always {method LIGHTNING, currency BTC,
     amount == totalAmount} — zero exceptions.
   - Fiat orders: transactions[0] is either fiat (PAYPAL/VENMO/CARD, amount
     == totalAmount, currency USD) or BTC (LIGHTNING/BITCOIN, amount = true
     sats paid). Seven fiat COMPLETEs carry BTC txs; those sats are real
     money that moved through nodecan.
   So for pairing purposes the transaction — not totalAmount — is the
   authoritative \"what was paid\" signal. Examples from live data:
     USD 20000 order, tx LIGHTNING 312088 BTC-sats (Satsquatch, TWIB 117)
     USD 1000 order, tx LIGHTNING 15556 BTC-sats (CypherCitizen, TWIB 109)

   Returns the tx sats as a long when the order has a confirmed BTC
   transaction, else nil (fiat-only txs, no txs, PENDING orders)."
  [{:keys [transactions] :as _order}]
  (some (fn [{:keys [method currency status amount]}]
          (when (and (contains? #{"LIGHTNING" "BITCOIN"} method)
                     (= "BTC" currency)
                     (= "CONFIRMED" status)
                     (pos? (or (parse-long-or-nil amount) 0)))
            (parse-long-or-nil amount)))
        transactions))

(defn pairs-with-complete?
  "COMPLETE-pairing rule: an invoice is already-boosted when a COMPLETE order
   exists that represents the same payment, with a paidAt within the pairing
   window of the invoice's LND settle time (fallback: invoice creation).
   Zaprite marks COMPLETE only after its polling observes settlement, so
   paidAt lands seconds-to-minutes after settlement — but a lagging webhook
   can stretch that to ~45min (see pairing-window-seconds), so the window is
   bounded but generous.

   \"Same payment\" is tested by amount, and amount has two valid forms:

   1. BTC order: order totalAmount (in sats) equals the invoice sats, via
      `order-sats`.
   2. FIAT order paid via lightning/onchain: order totalAmount is fiat cents
      and CANNOT be compared to sats — but the order's confirmed BTC
      transaction (`order-tx-sats`) records the true sats paid. This case
      closes the fiat-pairing blindspot that left 4 invoices (415,156 sats)
      falsely unmatched after the phase-5 deploy: e.g. Satsquatch's USD 20000
      order whose LIGHTNING tx paid exactly 312088 sats — the same payment as
      the settled 312088-sat invoice 343910.

   Username is load-bearing in both cases: same-show/same-amount COMPLETEs
   from other users exist. Payment rail is not: a fiat order paid via
   lightning IS the same payment as its invoice, while a fiat order paid via
   card never has a BTC tx and still cannot pair (its sats-equivalent never
   touched nodecan)."
  [{:keys [status paidAt] :as order}
   {:keys [username show-slug show-ep sats creation-epoch settle-epoch] :as _target}]
  (boolean
   (and (= "COMPLETE" status)
        paidAt
        (or (= sats (order-sats order))                 ; BTC order
            (= sats (order-tx-sats order)))             ; fiat order paid in sats
        (= username (candidate-id order))
        (when-let [par (parse-web-boost-memo (str (:label order)))]
          (and (= show-slug (:show-slug par))
               (= show-ep (:show-ep par))))
        (when-let [paid (parse-rfc3339 paidAt)]
          ;; Anchor on LND settle when recorded (authority), else creation.
          ;; Webhook lag can push paidAt minutes after settle (observed 45min),
          ;; so the window must be bounded but generous.
          (let [anchor (or settle-epoch creation-epoch)
                gap (- (.getEpochSecond paid) anchor)]
            (and (pos? gap) (<= gap pairing-window-seconds)))))))

(defn pending-orders-query
  "Legacy single-status PENDING filter. Superseded by unified-orders-query for
   reconcile; kept because `status=PENDING` + page params remain the minimal
   correct form (the array `status[]` variant is silently ignored by the API)."
  [page]
  {"status" "PENDING"
   "page" (str page)})

(defn fetch-pending-orders
  "Fetch all PENDING Zaprite orders (all pages, status=PENDING only).
   Superseded by fetch-unified-orders for reconcile; retained for callers
   that genuinely want the PENDING-only view."
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
   reconcile order set (creation-ascending; PENDING + COMPLETE etc).

   invoices :: seq of invoice maps (see find-web-boost-invoices)
   orders   :: seq of Zaprite orders (fetch-unified-orders; status-bearing)
   boosted  :: {:identifiers #{id} :order-ids #{order-id}} from find-boosted-keys

   Returns
   {:scanned N
    :already-boosted N          ;; skipped via dedup guard OR COMPLETE-pairing rule
    :orphans [...]              ;; high-confidence: order matched + entity ready
    :manual-review [...]        ;; candidates exist but content diverges
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
              pairing-target (invoice-pairing-target invoice)
              result (if parsed
                       (match-order-candidates orders (assoc parsed :sats sats'))
                       {:confidence :none})
              order-id (some-> (:order result) :id)
              dedup (or (contains? boosted-ids id)
                        (and order-id (contains? boosted-oids order-id))
                        (and pairing-target
                             (boolean (some #(pairs-with-complete? % pairing-target) orders))))]
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

(def reconcile-broadcast-keys
  "WebSocket payload keys for a written orphan — identical to what
   `sync-zaprite-boosts!` broadcasts for a normal Zaprite boost."
  [:boostagram/sender_name_normalized
   :boostagram/value_sat_total
   :boostagram/app_name
   :boostagram/podcast
   :boostagram/episode
   :boostagram/message
   :invoice/creation_date])

(defn sync-web-boost-reconcile!
  "Compose detection + optional write (spec §11 Phase 3 shared core).

   conn     :: nodecan-conn (Datalevin conn)
   api-key  :: Zaprite API key
   opts     :: {:allow-write? bool  — when true, d/transact! each HIGH-confidence
                                  :entity and broadcast it (default false)
                :fetch-orders f      — injectable unified-orders fetcher,
                                  arity [api-key] → orders (default
                                  fetch-unified-orders; inject to avoid HTTP)
                :broadcast-fn f     — injectable WebSocket broadcaster, arity
                                  [entity] (default no-op; the web suite passes
                                  ws/broadcast! so live clients see the write)}

   Returns the detect-orphans map plus :written (entities transacted). Preview
   callers pass no opts; the write is always opt-in. Idempotent: the dedup
   guard inside detect-orphans skips identifiers/order-ids already boosted, so
   a second call (or a crashed-backfill restart) writes nothing new."
  ([conn api-key]
   (sync-web-boost-reconcile! conn api-key {}))
  ([conn api-key {:keys [allow-write? fetch-orders broadcast-fn]}]
   (let [fetcher (or fetch-orders fetch-unified-orders)
         broadcast (or broadcast-fn (fn [_] nil))
         detection (detect-orphans (find-web-boost-invoices conn)
                                   (fetcher api-key)
                                   (find-boosted-keys conn))
         written (if allow-write?
                   (do (doseq [o (:orphans detection)]
                         (let [entity (:entity o)]
                           (d/transact! conn [entity])
                           (broadcast (select-keys entity reconcile-broadcast-keys))))
                       (count (:orphans detection)))
                   0)]
     (assoc detection :written written))))

(defn find-web-boost-invoice
  "One settled web-boost invoice by identifier (nil when absent)."
  [conn identifier]
  (first (filter #(= identifier (:invoice/identifier %))
                 (find-web-boost-invoices conn))))

(defn resolve-manual-review!
  "Operator resolution for a manual-review invoice (spec §11 Phase 3 — the
   human pick the matcher refuses to guess).

   Records the boost from the settled invoice itself: LND settlement is the
   authority (§6 rule 3), the memo supplies username/show/episode, and the
   amount is the settled sats. When `order` is supplied its METADATA only
   (message, episode title) is used — payment state is never inferred from
   the order. With `order` nil the boost is invoice-anchored (no
   zaprite_order_id, message \"\"). This is the deliberate treatment for the
   webhook-drop orphans: the money moved, the order never recorded it, and we
   must not guess which retry twin was paid.

   Gated: callers (the web route) must enforce WEB_BOOST_RECONCILE_WRITE.
   Idempotent: upserts by :invoice/identifier; a resolve for an already-
   boosted invoice returns {:status :already-boosted} and writes nothing.

   Returns {:status :ok|:already-boosted|:not-found
            :entity (broadcast keys, when :ok)}."
  [conn identifier order]
  (let [invoice (find-web-boost-invoice conn identifier)]
    (cond
      (nil? invoice)
      {:status :not-found}

      (contains? (:identifiers (find-boosted-keys conn)) identifier)
      {:status :already-boosted}

      :else
      (let [parsed (parse-web-boost-memo (:invoice/memo invoice))
            sats (coerce-sats (:invoice/value invoice))]
        (when-not (and parsed sats)
          (throw (ex-info (str "Resolve refused: unparseable memo or amount for invoice " identifier)
                          {:identifier identifier :memo (:invoice/memo invoice)})))
        (let [entity (build-boost-entity
                      parsed
                      {:invoice-id identifier
                       :sats sats
                       :settle-date (:invoice/settle_date invoice)
                       :creation-date (epoch-to-rfc3339 (:invoice/creation_date invoice))}
                      order)]
          (d/transact! conn [entity])
          {:status :ok
           :entity (select-keys entity reconcile-broadcast-keys)})))))

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
                                          (fetch-unified-orders api-key)
                                          (find-boosted-keys conn))
                report-path (write-report! out-dir detection)]
            (println (reconcile-report detection))
            (println)
            (println "Report written to:" report-path))
          (finally
            (d/close conn)))))))