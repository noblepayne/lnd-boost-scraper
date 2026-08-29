# Dev Log — Zaprite Orphan Reconciliation

Chronological record of decisions and their reasons, kept alongside
`docs/orphan-reconcile-spec.md` (the what) and `.clj` code + tests (the concrete how).

Format: date · decision · why.

---

## 2026-08-28 · Phase 5 matcher upgrade built (tests first, review-amended)

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