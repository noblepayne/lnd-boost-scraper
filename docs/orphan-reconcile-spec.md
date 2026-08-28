# Zaprite Orphan Reconciliation — Spec

Status: ACTIVE — Phase 0 ✅ (2026-08-19, verified against live infra); Phase 1 ✅ (pure parser/matcher + tests); Phase 2 ✅ code + live-verified (query-form fix), prod dry-run = via Phase 3 preview route (the box has no checkout, so the `:reconcile` alias is dev-only); Phase 3 ✅ code + tests (2026-08-20, routes + write gate); Phase 4 ✅ deployed + backfilled 2026-08-28 — **16 orphans written** (the 12-fixture prediction grew to 16 on first real prod run; see dev-log), 8 manual-review rows documented, idempotency verified, webhook fix confirmed live. Phase 5 (keep-fresh loop) deferred until an orphan recurs post-fix.
Repo: lnd-boost-scraper
Author context: investigation of a stuck Web Boost order (`od_nVJ3uLtbZz`, TWIB 118 — Adam Curry, 88,888 BTC) that settled in nodecan LND but never became a COMPLETE boost in the ledger.

## 1. Problem statement

Web Boost payments flow through Zaprite as checkout UI over an LNbits connector pointing at
nodecan's `web-boost-ingress-zaprite` wallet (LndRestWallet → nodecan LND). The customer pays a
BOLT-11 invoice issued on nodecan directly. Zaprite marks an order `COMPLETE` only when its
connector observes the settlement (polling `GET /api/v1/payments/{hash}` on LNbits) **and** writes
a `CONFIRMED` transaction. The LNbits→Zaprite `onPaidWebhook` safety net is dead (111/112 → HTTP
400; Zaprite SaaS side, not fixable locally).

Failure mode: if polling ends before settlement (checkout session ends), Zaprite records nothing,
the order stays `PENDING` with `transactions: []`, and the existing scraper never ingests it
because `sync-zaprite-boosts!` only fetches `status[] = PAID/COMPLETE/OVERPAID`
(`upstream/zaprite.clj:18`).

Measured impact (verified against live Zaprite API with production key):
- 13 orphans / 158,787 sats of settled nodecan payments with no COMPLETE Zaprite order.
- All 13 map to a PENDING Zaprite order with `metadata.app = "web-boost"`; metadata (message,
  episodeGuid, username) is fully recoverable via `GET /v1/orders/{id}`.
- All orphans have settled BOLT-11 invoices in nodecan LND with the web-boost memo pattern
  (`Payment for Web Boost: {Show} {Ep} — {User}`).

## 2. Design principle

**nodecan LND is the source of truth for "paid".** Zaprite provides only order metadata and is
never trusted for payment status. The scraper already pulls all nodecan LND invoices every cycle
(`src/boost_scraper/core.clj` `autoscrape-nodecan`) and persists every invoice (not just
boostagram ones) into the nodecan Datalevin DB. This reconciliation turns those dried-up LND
invoices into boost entities.

Read-only enforcement: the reconcile job detects and reports only; it writes boost entities only
when explicitly enabled via a flag, and never mutates Zaprite.

## 3. Data sources already available

| Source | Access in scraper today | What it gives us |
|---|---|---|
| nodecan LND REST (`autoscrape-nodecan`) | `NODECAN_MACAROON_PATH`, url in `core.clj` | settled invoice, memo, amount, `add_index`, timestamps |
| nodecan Datalevin DB (`nodecan-conn`) | `NODECAN_DBI` | every invoiced entity incl. web-boost memo invoices |
| Zaprite API `GET /v1/orders` | `ZAPRITE_API_KEY_PATH` | PENDING/COMPLETE order metadata, label, amount |
| Zaprite API `GET /v1/orders/{id}` | same key | full metadata: username, message, episodeGuid, pubDate |

No new secrets or infrastructure required.

## 4. Detection (read-only)

For each settled nodecan LND invoice in `nodecan-conn` whose memo matches
`(?i)^Payment for Web Boost: (.+)$`:

1. Extract show/episode/username from the memo (see parser in §5).
2. Query `nodecan-conn` for an existing boost entity with the matching `:invoice/identifier`
   carrying `:boostagram/action "boost"` **or** any entity with the same
   `:boostagram/zaprite_order_id` (`find-boosted-keys`). If one exists → already reconciled,
   skip.
3. Fetch Zaprite orders (`GET /v1/orders?status=PENDING`, page through all pages —
   **single `status` param; the array form `status[]=PENDING` is silently ignored by
   Zaprite** (verified live 2026-08-19: it returns the unfiltered default COMPLETE set) and
   match by **label** (label == memo minus the `Payment for ` prefix), disambiguated by BTC
   amount + normalized username, hardened by the `externalUniqId` prefix guard (§5/§10).
   Multiple PENDING orders may share a label (observed: `memphis` dupe); a match is
   HIGH-confidence when amount + episode + label + euid align, otherwise flagged for manual
   review. Zaprite exposes neither `createdAt` nor `paidAt` on PENDING orders, so settle-time
   proximity is **not** a viable disambiguator.
4. Emit a report:
   - settled-but-not-boosted invoices, keyed by LNbits `checking_id` ≈ LND payment hash
   - per row: amount (sats), show/episode, username, settle time, matched Zaprite order id,
     conflict flag (duplicate label / no matching order).

## 5. Memo parser (new pure function)

```clojure
;; input:  "Payment for Web Boost: TWIB 118 — Adam Curry"
;; output: {:show-slug "twib" :show-ep "118" :username "Adam Curry"
;;          :label "Web Boost: TWIB 118 — Adam Curry"}
```

Label grammar observed in production: `Web Boost: {SLUG} {EP} — {USER}`, where `{SLUG}` is
`LUP`, `TWIB`, `THE-LAUNCH` (see `podcasts.config.json` in web-boost). Must also tolerate the
older label forms seen on PENDING orders (`Web Zap The Launch!`, `Launch 39 Web Zap ⚡`,
`Texas Linux Festival Trip Support`) — these are NOT web-boost apiOrders (they use `odp_` /
`od_` prefixes and no `metadata.app`); treat them as unmatchable and report only.

Input forms to accept (Phase 0 confirmed the DB stores memos inconsistently):
- `Payment for Web Boost: TWIB 118 — Adam Curry` — canonical LND memo
- `Web Boost: TWIB 118 — Adam Curry` — Zaprite label form
- `TWIB 108 — debitcoinkoers.eu` — bare form observed in some DB entities

Separator may be `—` (U+2014), `–` (U+2013), or `-`. Slug may be hyphenated (`THE-LAUNCH`).
Username is the greedy remainder of the line (may contain dashes/emoji/spaces).

Tests: unit tests only (no DB/network), e.g. memo → map round-trip, unknown label → nil, emoji/
unicode, `—` vs `-` variants.

## 6. Enrichment & write path (flag-gated: `WEB_BOOST_RECONCILE_WRITE`)

When enabled (+ only for HIGH-confidence matches):

1. `GET /v1/orders/{id}` for the matched order.
2. **Enrich the existing nodecan LND entity, do not create a new one.** The nodecan scrape
   already persists this invoice under `:invoice/identifier = add_index` (unique, `db.clj:9`).
   Upsert by that identifier, adding:
   - `:boostagram/podcast` / `:boostagram/episode` / `:boostagram/sender_name(_normalized)`
   - `:boostagram/message` (from order metadata)
   - `:boostagram/zaprite_order_id` → unique identity (`db.clj:45`), making future Zaprite
     completion an **upsert-merge**, not a duplicate
   - `:boostagram/type :sat`, `:boostagram/value_sat_total` (from LND value)
   - `:boostagram/payment_rail "lightning"`, `:boostagram/action "boost"`
   - `:scraper/source` stays `"nodecan"` (ground truth is LND proof)
3. Explicit field-precedence rule for the upsert-merge: `:boostagram/received_at` and
   `:invoice/creation_date` from the **LND settle** timestamp (settlement authority); Zaprite
   `paidAt` only as cross-check logging, never overwriting.
4. `d/transact!` into `nodecan-conn`.

Not enabled by default. Dry-run output is identical minus the write.

Dedup guard against the normal Zaprite sync: before writing, check `nodecan-conn` for any entity
with matching `:boostagram/zaprite_order_id` OR matching LND `:invoice/identifier` already flagged
as a boost. Belt-and-suspenders: the shared entity path means a later Zaprite sync upserts onto the
same entity (probe-pinned, §10), so no double-count can occur even without the guard.

## 7. Wiring

- New namespace `src/boost_scraper/reconcile.clj` (mirrors `upstream/zaprite.clj` style; pure
  helpers in `boost-scraper.reconcile` for testability).
- Phase 3 deploy vehicle: the **deployed** server exposes detection + write over HTTP routes
  (§11 Phase 3: `GET /api/v1/reconcile/preview`, `POST /api/v1/reconcile/backfill`). Per-cycle
  loop wiring (`core.clj` alongside `sync-zaprite-boosts!`) is deferred to Phase 5 (keep-fresh).
- Report written to `reports/` (existing gitignored dir) by the dev-only `:reconcile` alias.

## 8. HTTP endpoint

Folded into Phase 3 (§11) — the earlier "optional glance endpoint" idea became the two shipped
routes: preview (read-only dry-run) + backfill (write, flag-gated). Formats follow `reports.clj`
conventions; responses are plain detection JSON maps.

## 9. Acceptance criteria

- [ ] Parser: web-boost memo round-trips; non-web-boost labels → nil; `—`/`-` handled.
- [ ] Detection on seeded fixture: settled invoice + PENDING order → HIGH-confidence orphan.
- [ ] Dedup: running reconcile twice produces one entity (unique zaprite_order_id).
- [ ] Dual-producer merge: reconcile-write + later `process-order` on the same order id → one entity.
- [ ] Dry-run mode writes nothing (assert DB unchanged).
- [ ] Write mode entity matches `process-order` shape for the same order id (field parity).
- [ ] Existing tests still pass (`clojure -M:test`).
- [ ] Production preview lists the 13 orphans (12 HIGH + memphis manual-review); backfill writes
      exactly the HIGH-confidence set (before write mode flips on).

## 10. Known open items / risks

- **Schema coverage — RESOLVED (Phase 0)**: Datalevin *does* persist schema-undeclared attrs;
  `:invoice/settled` (bool) and `:invoice/value` (int) are present on the 13 orphan entities.
  `:invoice/r_hash` is **not** persisted — never rely on it; identity anchors are
  `:invoice/identifier` (= add_index string, `db.clj:9`) and `:boostagram/zaprite_order_id`
  (`db.clj:45`).
- **Canceled-invoice trap (NEW, Phase 0)**: unpaid retries exist for the very same
  users/shows/amounts as the orphans (mg add_index 341022, whomeverwiz 334732, adam curry
  345004, memphis 342723 — all `CANCELED`). The matcher **must** key on `:invoice/settled = true`
  only and must never upsert onto a canceled invoice.
- **Memo prefix variance (NEW, Phase 0)**: DB memos observed both with and without the
  `Payment for Web Boost: ` prefix (and possibly without any prefix) on different entities.
  Parser must accept all three forms (§5).
- **Zaprite hides order `createdAt` / `paidAt` (NEW, Phase 0)**: the API exposes neither on
  PENDING orders. The `memphis` pair (two PENDING orders, one settled + one canceled invoice)
  cannot be disambiguated by time via API — stays manual-review; order-id ordering suggests
  `od_bY1at35Vl9`=canceled, `od_vff0Bfkh8g`=settled, unconfirmed.
- **Extra scrape-cycle source**: `autoscrape-nodecan` uses `https://100.120.212.39:8080/v1/invoices`
  (`core.clj:183`), not the runbook's `100.115.78.27`; the reconcile must read entity identifiers
  consistent with whatever that live scrape stores. (Phase 0 box bot pulled from
  `https://127.0.0.1:8080` on the nodecan host — same node, loopback.)
- **Dual-producer seam on the same order — RESOLVED (probe-pinned in-memory 2026-08-19)**: the
  normal Zaprite sync creates entities with `:invoice/identifier = "zaprite-<id>"` (`process-order`,
  zaprite.clj:70) while the reconcile creates them keyed by add_index — **both** carry the same
  unique `:boostagram/zaprite_order_id`. Verified: a later `process-order` transact for the same
  order id upserts onto the existing entity → **ONE entity, not two**, with `:invoice/identifier`
  re-keyed to `zaprite-<id>` (harmless — memo + order-id still anchor it; reports don't read
  identifier). No sync-side guard needed. Still locked in by the field-parity/merge test (LND
  settle timestamps win over a later Zaprite `paidAt` per §6 rule 3).
- **Ambiguity**: duplicate-label PENDING orders (e.g. `memphis` ×2) must stay in a manual-review
  bucket rather than guessing.
- **Time window** for settle-matching must be generous (Zaprite can lag minutes on webhook-less
  orders).
- **Merge field precedence** on upsert: Zaprite completion later merging `paidAt`/value conflicts
  must resolve to LND settle values (§6 rule 3); add a test asserting precedence.
- **Zaprite PENDING filter**: the API **ignores array-form query params**. `status[]=PENDING`
  is silently dropped (returns the unfiltered default COMPLETE set), and the same bug made the
  existing `upstream/zaprite.clj:orders-query` never filter at all — the paid sync only worked
  because paid orders are the API's default return set. Correct form is single `status=PENDING`
  (verified live 2026-08-19). The default (no filter) excludes PENDING; a status param overrides.
- **externalUniqId hardening (NEW, 2026-08-19 live probe)**: every web-boost PENDING order
  carries `externalUniqId` = `web-boost:{slug}:{episodeKey}:{currency}:{amount}:{token}:{fingerprint}`.
  The prefix parts (slug/currency/amount) are a strong confirm guard; the token+fingerprint TAIL
  is NOT derivable from the LND invoice side (message lives only in Zaprite metadata), so it
  confirms but cannot split identical label+amount pairs (memphis stays `:manual-review`, test-locked).

## 12. Phase 0 results (verified 2026-08-19, live infra)

Source: box bot read-only pull — nodecan LND REST, scraper Datalevin DB
(`/srv/lnd-boost-scraper/nodecan`), LNbits `apipayments`, Zaprite API. Full table at
`/dev/shm/orphan_reconcile.md`.

- **All 13 orphans confirmed paid**: 12 distinct SETTLED nodecan LND invoices + the memphis
  ambiguity pair (one canceled invoice + one settled), **158,787 sats** total. Every settled
  memo matches the web-boost pattern.
- **Datalevin persists undeclared attrs**: entities carry `:invoice/memo`, `:invoice/identifier`,
  `:invoice/value`, `:invoice/settled`. **No `:invoice/r_hash`** anywhere.
- **LNbits confirms the bug**: all 14 have `webhook_status=400` in `apipayments`, but
  `status=success` (paid in LND). The webhook-dead diagnosis is confirmed on the LNbits side.
- **Canceled-invoice matrix** exists (mg 341022, whomeverwiz 334732, adam curry 345004, memphis
  342723) — see §10 trap.
- **Zaprite API exposes neither order `createdAt` nor `paidAt`** on PENDING orders —
  disambiguation is DB/LND-side only.
- **Detection is fully DB-local**: `:invoice/settled`, memo, value, identifier all present in
  `nodecan-conn`; no LND REST or Zaprite call needed for *detection*. LND REST still useful to
  backfill `:invoice/settle_date` (persistence unconfirmed) and LNbits `apipayments` as
  secondary cross-check.
- Backfill fixture: the 13 settled add_indexes = 325042, 325043, 329454, 331716, 336940, 342724,
  334619, 334731, 340403, 341023, 342834, 344542, 346574.
- Datalevin gotcha (locked in by test): `pull` in a `:find` only returns **schema-declared**
  attributes, so `:invoice/settled` / `:invoice/value` / `:invoice/settle_date` would be dropped
  from a pulled map. `find-web-boost-invoices` reads via `d/entity` instead, which surfaces the
  undeclared attrs that nodecan's scraper persists. Verified in-memory (Phase 2 test
  `test-find-web-boost-invoices`).

## 11. Implementation plan (phased, each phase = checkable seam)

Note: the LNbits-side webhook fix is being done in parallel (by the other bot's operator). It is
complementary, not a substitute — this plan proceeds regardless; reconcile catches webhook-dead
and session-ended-before-settlement cases that the webhook fix alone never will.

### Phase 0 — Assumption check ✅ DONE (2026-08-19, see §12)
Verify against the live nodecan DB + live LND before writing code:
1. ✅ 13 orphans present in `nodecan-conn` with memo + identifier; fixture captured.
2. ✅ Datalevin persists undeclared attrs (`:invoice/settled`, `:invoice/value` present;
   `:invoice/r_hash` absent). Detection is DB-local; no runtime LND/Zaprite dependency.
3. ✅ Reconcile uses the same nodecan host as the live scrape; box bot pulled from loopback
   on the same node.

### Phase 1 — Pure functions + tests (zero infra)
`src/boost_scraper/reconcile.clj` with **no network/DB dependency** in these fns:
- `parse-web-boost-memo` — memo → `{:show-slug :ep :username :label}`; unknown label → nil
- `match-order-candidates` — (orders, invoice) → best matched order + confidence
  (`:high` / `:manual-review` for dup labels like `memphis` ×2)
- `build-boost-entity` — (invoice, order) → entity map per §6 (upsert onto existing
  `:invoice/identifier`, `:boostagram/zaprite_order_id`, LND-settle precedence rule)
Tests in `test/boost_scraper/reconcile_test.clj` (clojure test runner, no DB/network).
Exit: `clojure -M:test` green; parser + matcher unit tests all pass.

### Phase 2 — Detection / dry-run (read-only) ✅ code + live-verified; prod dry-run gates on Phase 3 preview
In `reconcile.clj`:
- ✅ Sweep LND REST → superseded: detection is **DB-local** (Phase 0), reads settled
  web-boost invoices straight from `nodecan-conn` (`find-web-boost-invoices`).
- ✅ Fetch Zaprite orders with `status=PENDING` (single param — the array form
  `status[]=PENDING` is ignored by the API, verified live), page through **all** pages
  (`fetch-pending-orders*` injected fetcher, unit-tested on pagination). The same
  array-form bug in `upstream/zaprite.clj:orders-query` (`status[]`) was fixed in the same
  pass — it never filtered anything and the paid sync only worked because paid orders are
  the API's default return set.
- ✅ Match via Phase 1 fns (`detect-orphans`), emit markdown+JSON to report dir
  (`reconcile-report`, `write-report!`).
- ✅ `:reconcile` alias in `deps.edn` → `clojure -M:jvm-base:reconcile`; dry-run only,
  never writes to the DB (write path is Phase 3).
Exit: dry-run against prod lists **exactly the 13 known orphans** — 12 HIGH-confidence + the
memphis dup-label case in manual-review. No DB writes (assert DB hash unchanged). ⏳ **dev-only
tool**:
the box has no repo checkout, so the prod dry-run runs via the Phase 3 `GET /reconcile/preview`
route on the deployed server instead of this alias.

### Phase 3 — Write mode + HTTP routes (deploy vehicle)
Design decisions locked in 2026-08-19/20 (user Q1–Q4, live API probe, scope-cut audit):
- **DB is the ledger.** Zaprite-side completion is optional and out of scope; the `PATCH
  /v1/orders/{id}` endpoint exists but is not needed for acceptance.
- **Deploy vehicle = HTTP routes on the deployed server**, not the `:reconcile` alias (the
  box has no repo checkout; the server already holds the DB conn + Zaprite key in-process).
- **Scope cut (2026-08-20)** — after a critical re-audit, this phase is routes + write-gate only.
  No `WEB_BOOST_RECONCILE_ENABLED`, no per-cycle loop, no `WINDOW_DAYS`, no sync-side guard:
  each was either premature optimization or solved by the verified merge (§10 dual-producer
  RESOLVED). Keep-fresh loop lands in Phase 5 only if future orphans recur (webhooks are being
  fixed, so the one-shot backfill may fully suffice). See docs/dev-log.md.

**Env surface** (all default-off; read per-request in the handlers, consistent with `serve`
reading `SCRAPER_UIPORT` at web.clj:483):

| Env | Meaning | Default |
|---|---|---|
| `WEB_BOOST_RECONCILE_WRITE` | Allow `POST /api/v1/reconcile/backfill` to transact | off |
| `ZAPRITE_API_KEY_PATH` | Already shipped via module.nix; slurped per request for the PENDING fetch | (existing) |

**Shared core** (`reconcile.clj`, injected-fn testable like `fetch-pending-orders*`):
`sync-web-boost-reconcile!` composes the Phase 2 seams `find-web-boost-invoices` →
`find-boosted-keys` → `fetch-pending-orders` → `detect-orphans`; when write is allowed it
`d/transact!` each HIGH-confidence `:entity` and `ws/broadcast!`s it (same keys as
`process-order` broadcasts). Dry = same minus transact. Both routes call this one function
(read-only vs write-gated); wiring into the scrape loop is Phase 5.

**Write-path rules** (per §6):
- Transact only HIGH-confidence orphans; `:manual-review` (memphis) and `:unmatched` are
  reported, never written.
- Dedup guard before writing (already inside `detect-orphans` via `find-boosted-keys`):
  skip if the `:invoice/identifier` already has `:boostagram/action "boost"`, or if the
  matched order id already appears as a `:boostagram/zaprite_order_id`.
- Restart is safe: detection re-sweeps with the guard, so a partial/crashed backfill is
  idempotent.

**Routes** (`web.clj`, mirroring the analysis-route style):
- Both handlers read their config straight from the process env: `ZAPRITE_API_KEY_PATH` is
  slurped per request, `WEB_BOOST_RECONCILE_WRITE` gates the write. No new args through
  `web/serve`.
- `GET /api/v1/reconcile/preview` — read-only detection against the in-process conn +
  `ZAPRITE_API_KEY_PATH`. Never writes. This is the Phase 2 prod dry-run vehicle.
- `POST /api/v1/reconcile/backfill` — runs the same detection, then writes the HIGH-confidence
  `:entity`s **only if** `WEB_BOOST_RECONCILE_WRITE` is set; otherwise `403 {"error "...}`.
  Returns `{:written N, :skipped N, :manual-review N, :unmatched N}` plus the detection rows.
  Body: none. (Spec §8's earlier `GET /api/v1/reconcile` is folded into these two.)

**`module.nix`** — add `reconcileWrite` (bool, default false) → `WEB_BOOST_RECONCILE_WRITE`.
`reconcileEnabled` (loop) is deferred to Phase 5; `reconcileWindowDays` was dropped.

**`flake.nix`** — extend the `module-options` assert block with `reconcileWrite`.

**Tests** (reconcile_test.clj + web test if present):
- Idempotency: run the backfill composition twice on a fixture → exactly one entity per
  order (`:boostagram/zaprite_order_id` unique), second run reports `already-boosted`.
- Write-mode field parity with `process-order` for the same order id (spec §9 checklist).
- Dual-producer merge pinned: reconcile-write entity + later `process-order` transact for the
  same order id land on ONE entity (probe-pinned, §10; identifier re-keys, harmless).
- Route handlers: preview returns 200 + no transact; backfill returns 403 when write flag
  off; backfill with flag on writes exactly the HIGH-confidence set.
Exit: field-parity + merge tests green; double-run creates no dups; `nix build .` + `nix flake
check` green (modulo the pre-existing devenv devShell quirk).

### Phase 4 — Backfill + verification ✅ DONE (2026-08-28, see dev-log for the full record)
1. Flake/nix rebuild with `reconcileWrite=true` → restart service.
2. `GET /api/v1/reconcile/preview` on the box → confirms orphans before anything is written.
3. `POST /api/v1/reconcile/backfill` → writes the HIGH-confidence orphans.
4. Verify in reports/analysis that the writes show as boosts under source "nodecan", amounts match,
   no double-count with any Zaprite-side COMPLETE.
5. (Out of scope per user decision 2026-08-19) Zaprite's own registry is **not** completed —
   the DB is the ledger. The `PATCH /v1/orders/{id}` endpoint exists (status→COMPLETE) but is
   not needed for acceptance and was explicitly deprioritized.
**Prod outcome**: preview found **16 HIGH** (12 predicted + 4 same-class extras surfaced by the
first real prod run) and **8 manual-review** (retry-pattern duplicates, documented in dev-log;
cleanup tiers there). Backfill wrote all 16 (`written: 16`), idempotency re-run `already-boosted:
16, orphans: 0`, all 16 verified in `/boosts`. Webhook fix on the box verified working (all
post-fix webhooked payments → 200). Follow-up: flip `reconcileWrite=false` on next routine deploy.

### Phase 5 — Keep-fresh + monitoring (deferred; only if orphans recur post-fix)
- Status as of 2026-08-28: the webhook fix is verified live (all post-fix webhooked payments →
  200), so no new orphans are expected. If one does appear (checkout session dies before any
  webhook fires — the residual gap), wire `WEB_BOOST_RECONCILE_ENABLED` + a `reconcile-fut` in
  the scrape loop (core.clj, next to `zaprite-fut` ~line 257) so `sync-web-boost-reconcile!`
  runs each cycle as defense-in-depth. Full sweep per cycle is cheap (PENDING fetch = ~2 pages;
  invoice sweep is add_index-ordered) → **no window param** (dropped 2026-08-20 as premature
  optimization). The Phase 4 full-scan backfill is only ever run once.
- `GET /api/v1/reconcile/preview` doubles as the live status/monitoring endpoint.
- Durable fix for the manual-review class lives in the **web-boost worker**: embed the euid tail
  (token:fingerprint) into the LND invoice memo → exact-match resolution (dev-log 2026-08-28).
Exit: scrape loop logs reconcile summary each cycle; a future orphan gets caught + ingested
without manual intervention.