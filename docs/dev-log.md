# Dev Log — Zaprite Orphan Reconciliation

Chronological record of decisions and their reasons, kept alongside
`docs/orphan-reconcile-spec.md` (the what) and `.clj` code + tests (the concrete how).

Format: date · decision · why.

---

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