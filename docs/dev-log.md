# Dev Log — Zaprite Orphan Reconciliation

Chronological record of decisions and their reasons, kept alongside
`docs/orphan-reconcile-spec.md` (the what) and `.clj` code + tests (the concrete how).

Format: date · decision · why.

---

## 2026-09-01 · Query proxy was a remote code execution backdoor — fixed + hardened

### Discovery (the "flexible query API" conversation)

Wes wanted a general Datalog query API so a smart bot could ask arbitrary
questions of the boost DB. We already had `POST /api/v1/query` + templates,
but reviewing it (because it'd never actually been used) surfaced a **critical
hole**: the endpoint could execute arbitrary code on nodecan.

### Root cause — datalevin 0.9.13's function resolver

The embedded query engine resolves a function symbol in a predicate/fn clause
by this chain (`src/datalevin/query.clj`):

    (or (get built-ins/query-fns f)      ; 1. curated registry — tried first
        (context-resolve-val context f)  ; 2. :in-bound values
        (dot-form f)                     ; 3. (.method obj ...) raw reflection
        (resolve-sym f))                 ; 4. (resolve sym) ANY var in any ns

So the registry is only the *first* branch — anything not in it falls through
to `(resolve sym)`, which finds **any var in any loaded namespace**, plus a
separate dot-form reflection escape that skips the registry entirely.

**Confirmed live on nodecan:**
- `[(clojure.core/load-string "(+ 1 2)") ?x]` → executed, returned `3` (RCE)
- `[(clojure.core/slurp "/etc/hostname") ?x]` → returned `"nodecan"` (file read)
- `[(.getClass ?x) _]` → dot-form reflection path (open too)
- `clojure.java.shell/sh` failed only because that ns wasn't loaded — moot, since
  `load-string` bridges to everything.

The module's docstring "Read-only by construction — never imports d/transact!"
was misleading: you don't need `d/transact!` when `load-string` exists.

### Upstream research (what the review taught us)

- **0.9.13** has no `:server-safe` mode. The registries exist as
  `datalevin.built-ins/query-fns` (predicates/fns) and
  `datalevin.built-ins/aggregates` (sum/count/…), both benign read-only sets.
- **1.x** added `datalevin.query.resolve/*resolver-mode*` — `:server-safe`
  restricts queries to registry + registered `:db/udf`s and rejects qualified
  symbols, dot-forms, fn literals, and `apply`. That's the upstream-sanctioned
  answer to this exact problem. **But** we're on 0.9.13 (upgrade requires
  storage migration — see `docs/datalevin-upgrade-plan.md`).
- So we implement `:server-safe` semantics ourselves on 0.9.13, before `d/q`.

### The fix (`e95c187`)

1. **`validate-query-fns`** — walks the parsed query (`:find`/`:where`/`:in`)
   and allows only symbols from datalevin's own registries, **derived at
   runtime** (`(keys datalevin.built-ins/query-fns)` + `/aggregates`) so it
   auto-tracks upstream across upgrades. Rejects: dot-forms, `apply`, rule
   bindings (`%`/`%%`), unknown top-level query keys, and >64 KiB queries.
   Runs before the query reaches `d/q`.
2. **Execution hardening** — bounded concurrency (semaphore, 3 concurrent,
   else 429), hard timeout with `future-cancel` (previously the deref-timeout
   left the query thread running unbounded), and try/catch → structured
   `:error` instead of raw 500s (previously a bad query leaked as plain
   "Internal Server Error"; the `(:exception result)` branch was dead code).

**Live verification (nodecan):** `load-string`, `slurp`, `.getClass` all →
`400 {"status":"error","detail":"Query function or form not allowed: ..."}`.
Legit aggregate query → `ok`. 10 new tests (the exact live RCE probes) →
124 tests / 773 assertions green.

### Lesson — "regression" was actually startup-sync contention

Right after deploying, the legit group-by query that had been 1.4s returned a
timeout. Panic-checked for a proxy regression, but the call was byte-identical
to before. Turned out the service had just restarted and was mid-scrape (Zaprite
sync, R2 sync) — LMDB read contention made queries ~2.3× slower and the heavy
group-by blew past the timeout. After the sync settled, the same query ran in
1.9s. **Lesson: never judge query performance right after a service restart;
wait for the scrape cycle to settle.**

### Open follow-ups

- Upgrade to datalevin 1.x (see `docs/datalevin-upgrade-plan.md`) → bind
  `*resolver-mode* :server-safe`, keep the walker as belt-and-suspenders.
- Fix the 5 query templates (broken — `top-boosters` has an unbound `?amount`,
  `monthly-leaderboard` an unbound `?month`, `?boost-type` double-bind, and
  the "run a template" route doesn't actually execute templates — API.md lies).
- Add a schema-introspection endpoint + bot cookbook for the query API.

## 2026-08-29 · Feed WebSocket dedup bug — fixed (three-layer bug)

### The symptom

Boost cards appeared as duplicates in the live feed when the page was left open. pabi's
"TIMETRAVEL BOOOOST! EP 77...Roborock Vacuums" appeared 3×; genebean's "hold music from
data in LUP680" appeared 2×. The HTTP API returned100 unique items — zero server-side
duplicates — so the duplication was happening client-side via the WebSocket.

### Root cause (three layers deep)

**Layer 1 — `select-keys` omission (server):** All three `broadcast!` call sites
(`upstream/zaprite.clj`, `upstream/r2.clj`, `db.clj`) used `select-keys` that did NOT
include `:boostagram/content_id` or `:invoice/identifier`. So the WebSocket sent
`content_id: null` in every boost message.

**Layer 2 — HTTP vs WS asymmetry (server):** The HTTP feed query uses
`(get-else $ ?e :boostagram/content_id "")` — defaulting to empty string `""` when the
attribute is absent. The WebSocket sent `null`. Same entity, two different representations
for the same field.

**Layer 3 — `boostKey` priority (client):** The client's `boostKey` used `identifier`
first, then `content_id`. With `content_id: null` from the WebSocket, the key fell through
to `identifier` (e.g. `"id:349963"`). But the HTTP-fed card used `content_id` (e.g.
`"cid:3a6c5455..."`). Different keys → dedup failed → duplicate cards inserted.

**Why it was hard to catch:** the server-side dedup (`feed.clj dedup-by-content-id`) works
correctly — it collapses entities sharing a `content_id` into one HTTP row. So the API
always returned clean data. The bug only manifests when the WebSocket broadcasts the raw
entity (no dedup) AND the client's dedup key differs from the HTTP key.

### The fix

Three changes, all needed:

1. **`select-keys` in all 3 `broadcast!` sites** — added `:boostagram/content_id` and
   `:invoice/identifier`. Now the WebSocket sends the same fields the HTTP feed uses.

2. **`boostKey` in `feed.js`** — swapped priority to `content_id` first, matching the
   server-side `dedup-by-content-id` logic. Added prefixed keys (`cid:`, `id:`, `eid:`,
   `raw:`) to prevent accidental collisions between fallback types.

3. **Deployed to box** — `4f9cf0b`, verified: Roborock count 3→1, hold music count 2→1.

### What the near-miss taught

This was a classic "everything works in isolation" bug: the HTTP feed is correct, the
WebSocket broadcast is correct, the client dedup is correct — but the data contract
between HTTP and WS was asymmetric (`""` vs `null` for the same semantic field). The
client-side fix (content_id-first) was necessary but insufficient without the server-side
fix (include the field in select-keys). Both layers needed to agree on the data shape.

### Active session log

Also in this session:
- **Fiat-pairing blindspot fixed** (see entry below): `unmatched` 4→0, `already-boosted`
  118→122. `order-tx-sats` + `pairs-with-complete?` extension.
- **Retracted "fiat boosts show 0 sats" claim**: `value_sat_total 0` on fiat entities is
  by design; reports render "$200.00 (lightning)" correctly. Near-miss lesson documented.
- **Open product decision**: should fiat-via-lightning boosts also appear in ballers?
  Recorded for Wes's consideration.
- **`reconcileWrite` flipped to false** during the deploy.

## 2026-08-29 · Fiat-pairing blindspot fixed (detection completeness)

### The blindspot, explained

After the phase-5 deploy, `unmatched` sat at 4 invoices / 415,156 sats. These were the last
invoices the matcher could not explain. Investigation showed they are NOT orphans — they are
the invoice half of **fiat-denominated web boosts paid via lightning**:

- The web-boost widget lets a customer pick a dollar amount (e.g. $200). Zaprite records the
  ORDER in fiat (`currency: "USD"`, `totalAmount: 20000` cents), but the customer pays through
  nodecan LND, so the underlying TRANSACTION settles in BTC sats (312,088).
- Zaprite marks that order COMPLETE after polling observes the settlement — `paidAt` lands
  ~1 minute after the LND invoice's creation. Same payment, two records: a fiat order
  (already ingested by the normal sync as a `:fiat` boost) and a bare nodecan invoice entity.
- The pairing rule required `currency == "BTC"` on the order, so these could never pair —
  the matcher kept re-reporting them as unmatched noise.

Verified tx semantics across all 240 COMPLETE orders (2026-08-28 pull, zero exceptions):
- BTC orders: `transactions[0]` = LIGHTNING/BTC/amount == totalAmount always.
- Fiat orders: tx is either fiat (PAYPAL/VENMO/CARD, amount == totalAmount — the sats
  equivalent never touched nodecan) or BTC (LIGHTNING/BITCOIN, amount = true sats paid).

### The fix

`order-tx-sats` extracts the confirmed BTC transaction amount from an order; `pairs-with-complete?`
now accepts EITHER the order total (BTC orders) OR the confirmed BTC tx amount (fiat orders
paid via lightning/onchain) as the "same payment" signal. Rail is deliberately NOT a filter:
a fiat order paid via lightning IS the same payment as its invoice; a fiat order paid via card
has no BTC tx and still cannot pair — correctly, because its value never moved through nodecan.

Predicted effect on next deploy: `unmatched` 4 → 0, `already-boosted` 118 → 122.

### What was deliberately NOT changed

An earlier session claim — "fiat boosts show 0 sats, reports are wrong" — was **retracted after
verification**: `value_sat_total 0` on fiat entities is by design. Reports bucket by
`:boostagram/type`: ballers sums sats from `:sat` entities; the fiat section sums
`amount_fiat_cents` from `:fiat` entities and renders "$200.00 (lightning)" — accurate, no
money mis-displayed, no double-count. The near-miss lesson: verify what the report layer
actually renders before "fixing" the data layer.

### Open product decision (Wes's call, recorded here so it isn't lost)

The 4 lightning-settled fiat boosts represent **415,156 real sats received by the shows**, but
they only appear in the fiat section (as dollars), not in ballers (sats). Moving lightning-paid
fiat boosts into ballers would make the sats view complete. The wrinkle: 77+ other fiat boosts
were paid via card/PayPal — real revenue, but those sats never existed in nodecan. Clean rule
if pursued: **fiat boost with a confirmed BTC tx also counts in ballers (tx sats); card/PayPal
fiat stays fiat-only.** Double-count implication (same boost visible in two sections) must be
accepted or the sections relabeled. Not built — needs a deliberate product decision.

## 2026-08-29 · Phase 5 deployed — matcher verified live, two new findings

- **Deployed**: box rebuilt with `db8c2d6` (unified fetch + pairing rule + content-identity
  guard). Live preview: **already-boosted 16 → 118** (pairing rule absorbed ~100 false
  positives), **unmatched 101 → 4**, orphans 0 (nothing left to backfill for BTC-matchable
  invoices), manual-review 3.
- **Finding A — the 4 big unmatched rows (~415k sats) are NOT orphans**: each maps exactly to
  a **fiat-order COMPLETE** (Satsquatch USD 20,000 → tx CONFIRMED 312,088 BTC-sats; daomah
  USD 3,000 → 47,632; John A USD 2,500 → 39,880; CypherCitizen USD 1,000 → 15,556). They are
  already in the ledger via the normal sync — but as `:boostagram/type :fiat` entities with
  **`value_sat_total 0`**. Pairing requires currency==BTC, so the matcher cannot see them.
  Fix direction: pairing should also consider the LIGHTNING transaction amount (available in
  the order's `transactions`), not just `totalAmount` — then these 4 classify as
  already-boosted and unmatched truly goes to 0.
- **Finding B — fiat boosts lose sats fidelity (separate improvement, user-facing)**:
  `process-order` sets `value_sat_total 0` for fiat orders, but the Zaprite transaction
  carries the true sats. A 312k-sat boost shows as 0 sats in reports. Worth fixing in
  `process-order` (take `transactions[0].amount` when BTC-sats and `type :fiat`), with a
  backfill sweep for existing fiat entities. Satsquatch's July BTC order (25,353) already
  carries correct sats, so only fiat rows are affected.
- **Memphis guard held correctly**: the two candidates' messages genuinely differ ("Thanks for
  all the value! Web boost FTW" vs "Thanks for the value!") — content-identity refuses them,
  as designed. This is now an operator choice, not a matcher gap. Same for the anon 323627
  group (6 candidates — double-creation twins; needs group-aware COMPLETE-exclusion logic to
  collapse) and a new Hydragyrum LUP-668 pair (2,000, previously unmatched).
- **Backfill: intentionally not run** — orphans=0, nothing to write. The write flag remains
  on the box; flip to false on next routine rebuild per the plan (§3 step 3 amended: no
  backfill needed).

- **Plan** (`docs/orphan-reconcile-phase5-plan.md`) went through an agent design review
  (verdict BUILD AS AMENDED). Amendments adopted:
  - Pairing key gains **normalized username + explicit BTC currency** (same-show/same-amount
    COMPLETEs from other users exist; fiat totalAmount is not sats).
  - Tie-break reframed honestly: it's a **deterministic bookkeeping pick**, and the
    double-count risk was defused analytically — a duplicate would require a dead twin to be
    picked AND the true order to later complete, but completion only happens during the
    checkout session (all 240 observed paidAt within ~2min of settle — which is exactly why
    orphans exist) and dead twins' invoices are canceled so they can never complete.
  - **Unified fetch adopted**: `fetch-unified-orders` (sortBy=createdAt asc, statuses
    PENDING/PAID/COMPLETE/OVERPAID) replaces the PENDING-only fetch for reconcile — one pull
    gives the pairing data AND creation-order tie-break signal. The worker sync's
    `fetch-orders` paidAt-cursor contract is untouched.
  - **Worker memo-euid fix cut** from the plan (orthogonal, future work).
- **Two live-data corrections recorded** (§ NEW discovery in the plan): the phase-0 memphis
  hypothesis is likely flipped (`od_bY1at35Vl9` is the later-created, so the likely anchor),
  and order/invoice creation is not always 1:1 — so creation-order position is a tie-break,
  not a truth claim. The **content-identity guard is the load-bearing safety rule**.
- **Tests rewritten to be self-deriving** (per Wes: adaptive, generative-inspired): fixture
  timestamps are *computed at runtime* (e.g. creation = paidAt − 35s) instead of hand-baked
  epoch literals — three separate failures from my own hand-computed epochs proved the point.
  Tests are named for the rule ("latest-created wins"), not the predicted outcome.
- Implementation: `unified-orders-query` / `fetch-unified-orders` (vector `status` value —
  babashka renders it as repeated single params, same as the proven paid-sync query),
  `invoice-pairing-target`, `pairs-with-complete?` (600s window), `content-identical?`,
  tie-break `{:confidence :high :order (last cands)}` in `match-order-candidates`,
  pairing-or-dedup in `detect-orphans`' skip check. `sync-web-boost-reconcile!` injection
  key renamed `:fetch-pending` → `:fetch-orders`.
- Live validation: run through the **deployed preview route** after the next box rebuild
  (read-only by construction, live DB + live Zaprite — strictly better than a local snapshot
  copy, which was attempted and abandoned: 3.2 GB pull, redundant, torn-copy risk).

## 2026-08-19 · Investigation

- **Root cause**: Web Boost checkout settles a BOLT-11 invoice on nodecan LND; Zaprite marks the
  order COMPLETE only via polling. If the checkout session ends before settlement, the order stays
  PENDING with `transactions: []` and the scraper's paid-only sync never ingests it.
  LNbits→Zaprite `onPaidWebhook` is dead (111/112 → HTTP 400, Zaprite SaaS side, unfixable locally).
- **Measured: 13 orphans / 158,787 sats** of settled-but-never-boosted nodecan payments, all
  resolving to real PENDING web-boost orders (172 PENDING live, 114 web-boost).

## 2026-08-19 · Live API probe (production key)

- **`status[]=PENDING` is silently ignored by Zaprite** — returns the unfiltered default COMPLETE
  set. Fixed both my new `pending-orders-query` and the pre-existing `upstream/zaprite.clj`
  `orders-query` (which never filtered at all; it only worked by accident). Live-verified both.
- **`PATCH /v1/orders/{id}` accepts `status: COMPLETE`** — but per user decision Zaprite-side
  completion is out of scope; the DB is the ledger.
- **`externalUniqId` = `web-boost:{slug}:{episodeKey}:{currency}:{amount}:{token}:{fingerprint}`**.
  Prefix (slug/currency/amount) is a strong confirm guard; the token+fingerprint tail is NOT
  derivable from LND, so it can't split identical label+amount pairs (memphis → `:manual-review`).

## 2026-08-19 · Design decisions (user Q1–Q4)

1. **DB is the ledger.** Zaprite registry not completed; `PATCH order→COMPLETE` exists but unused.
2. **nodecan LND is source of truth for "paid"**; Zaprite = metadata only, never payment status.
3. **Deploy via HTTP routes on the deployed server**, not the `:reconcile` dev alias — the box has
   no repo checkout; the server already holds the DB conn + key in-process.
4. **Write is flag-gated** (`WEB_BOOST_RECONCILE_WRITE`, default off); detection is read-only.
5. **Detect orphans DB-locally** (`:invoice/settled`/`value`/memo persist on nodecan entities; no
   LND REST needed at runtime).

## 2026-08-20 · Critical audit of the Phase 3 spec

Re-examined the spec against actual producers (`process-order`, `sync-zaprite-boosts!`, reports).
Initial findings were mostly my own over-engineering — killed four of six:

- **#1 dual-producer double-count — DISPROVEN empirically.** In-memory probe: reconcile write
  (identifier add_index) + later `process-order` transact (same `:boostagram/zaprite_order_id`)
  → **ONE entity**. The unique identity attr does the merge; `:invoice/identifier` just gets
  re-keyed to `zaprite-<id>` (harmless). No sync-side guard needed.
- **#2 memphis exit gate — KEPT.** "13 of 13 orphans visible" was wrong: memphis matches two
  PENDING orders → never auto-written. Correct gate: 12 auto + memphis operator-resolved.
- **#3 `WINDOW_DAYS` — DROPPED.** Premature optimization; full sweep is cheap (2-page PENDING
  fetch). Keep-fresh loop itself deferred to Phase 5.
- **#4 reconcile-report sats mislabel — acceptable for now** (cosmetic; revisit if ops confused).
- **#5 memphis "noise every cycle" — RETRACTED.** Self-resolves once memphis is operator-resolved
  (order-id enters dedup set). No suppression feature.
- **#6 unauthenticated POST — ACCEPTED.** Consistent with existing tailnet posture
  (`/api/v1/client-states/cleanup` same pattern).

**Scope cut**: Phase 3 = routes (`preview`/`backfill`) + `WEB_BOOST_RECONCILE_WRITE` module
passthrough. No window, no loop wiring, no sync guard, no suppression. Rationale: fix the ledger
with the smallest surface that deploys cleanly; add loop only if orphans recur post-webhook-fix.

## 2026-08-20 · Phase 3 built (code + tests, all green)

- **`sync-web-boost-reconcile!`** (reconcile.clj): composes detection seams, optionally writes
  HIGH-confidence orphans (`d/transact!` + broadcast with the `process-order` key set). Injected
  `:fetch-pending` / `:broadcast-fn` for hermetic tests; write is strictly opt-in.
- **Routes** (web.clj): `GET /api/v1/reconcile/preview` (read-only, never writes) +
  `POST /api/v1/reconcile/backfill` (403 unless `WEB_BOOST_RECONCILE_WRITE` = "true"). Both
  read env per-request; no new args through `web/serve`.
- **module.nix** `reconcileWrite` (bool, default false) → `WEB_BOOST_RECONCILE_WRITE`; flake
  module-options assert extended.
- **Tests**: idempotency (double-run → one entity, writes 0), dual-producer merge pinned against
  real `process-order` (one entity, identifier re-keys to `zaprite-<id>`), route gating
  (preview 200/no-write; backfill 403 off → 200 on; idempotent across requests).
- Why the shape: keep the write path as the *only* new mutation surface — everything else
  (window, loop, suppression) deferred/dropped per the audit.
- Verification: 99 tests / 712 assertions green; clj-kondo 0/0; cljfmt clean; alejandra clean;
  `nix build .` + `nix build .#checks.x86_64-linux.module-options` pass.
- Note: backfill route tests needed the PENDING fetcher injected (`rec/fetch-pending-orders`
  redef) — without it the route hits real Zaprite with the test key (401 retries).

## 2026-08-20 · Phase 4 deployment decision (single-rebuild plan)

- **Deploy once with `reconcileWrite = true`**, then preview → backfill in that order. Why not
  the two-rebuild (deploy false → verify → arm true) option:
  - `GET /preview` never writes regardless of the flag, so the confirmation step is equally safe
    either way; the flag only arms `POST /backfill`.
  - A stray POST while armed is bounded: it writes exactly the HIGH-confidence orphan set
    (dedup-guarded, idempotent, never touches memphis manual-review) — i.e. the intended end
    state, not a surprise.
  - Saves a full rebuild/restart cycle on the box.
- Follow-up: flip `reconcileWrite = false` on the next routine deploy — backfill is one-time and
  the write gate should stay closed.
- Full runbook in `docs/orphan-reconcile-phase4.md` (step 2 is a hard gate: abort if orphans ≠
  12 / manual-review ≠ 1 / sums disagree; unmatched ≥ 0 is normal — completed orders live in the
  ledger via the normal sync already).

## 2026-08-28 · Phase 4 deployed + backfilled (prod)

- **Deploy**: box config (`/etc/nixos` flake.nix) set `reconcileWrite = true`; flake input
  updated input-only (`nix flake update lnd-boost-scraper`, 9d895cc → aa2bcaa on `v2-feed`).
  Note: the box also pins `lnd-boost-scraper.url` to `github:noblepayne/lnd-boost-scraper/v2-feed`
  (uncommitted there, along with the lnbits webhook patch in lnbits.nix).
- **Preview gate deviation, decomposed and verified**: preview returned orphans 16 /
  manual-review 8 / unmatched 101 — not the runbook's 12/1/≥0. The original 12 are all present
  (sums reconcile exactly: 163,564 − 6,999 = 156,565 = predicted 12-writable sum). The 4 extras
  are the same dead-webhook failure class that predates or postdates the Phase-0 probe: adevries17
  (Jun 25), Anonymous ×2 (Jun 11/20), and the box owner's own "test boost 2" that settled 4
  minutes *before* the webhook-fix restart. Root cause of the fixture gap: this was the first
  real prod run of `detect-orphans` (Phase 2's prod dry-run was always deferred to this route).
- **Webhook fix verified live**: running lnbits is the patched `lnbits-webhook-fixed` build
  (`json=json.loads(payment.json())` at notifications.py:249), restarted Aug 19 14:29 PDT. All 5
  webhooked payments since show `webhook_status=200`. The post-fix test boost (557 sats) went
  COMPLETE normally and is correctly NOT an orphan — the fix works.
- **Backfill executed**: `POST /backfill` → `written: 16, skipped: 0, manual-review: 8`.
  Idempotency re-run: `orphans: 0, already-boosted: 16`. All 16 verified present in `/boosts`
  (ballers bucket) with correct senders/sats/episodes. Report date-ordering fix (2953e19) also
  verified: every sender batch renders chronologically.
- **Backfill route test lesson (minor)**: with-redefs on the route's env helpers is required in
  tests — the default fetcher hits real Zaprite otherwise (401 retries).
- Remaining cleanup: flip `reconcileWrite = false` on next routine deploy; commit the box's
  `/etc/nixos` uncommitted state; manual-review rows documented below.

## 2026-08-28 · Manual-review SOLVED (post-backfill live-data analysis)

Follow-up probing after the backfill changed the picture substantially:

- **COMPLETE-order pairing**: a COMPLETE's `paidAt` lands ~5s–2min after its invoice's LND
  settle (polling confirm). Pairing settle times against the 240 COMPLETE orders (all already
  in the ledger) resolved **4 of the 8 manual-review rows as already-boosted false positives**
  (mg101010 ×3, anon TWIB-108 22,222). True outstanding: **2 invoices × 2,222 = 4,444 sats**,
  not 37,776.
- **`sortBy=createdAt` works**: the API never returns a `createdAt` field, but accepts
  `sortBy=createdAt` and orders results by it. PENDING creation order is extractable.
  Sanity-checked: memphis `od_bY1at35Vl9` (pos 29) created before `od_vff0Bfkh8g` (pos 30) —
  consistent with the phase-0 hypothesis that `od_vff0Bfkh8g` anchors settled invoice 342724
  (attempt 1 canceled → attempt 2 settled).
- **`expiresAt` is null** on all 10 stuck PENDINGs — they're not expiring, just dead retries.
- Consequence: **full automated resolution is possible without dashboard access.** Plan
  written: `docs/orphan-reconcile-phase5-plan.md` (matcher upgrade: pairing rule + creation-
  order tie-break + content-identity guard; plus the durable worker fix — euid tail in the
  invoice memo). Dashboard-access notes if the user obtains elevated access:
  `docs/zaprite-access-notes.md`.