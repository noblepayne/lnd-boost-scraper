# Manual-Review Cleanup Plan — matcher upgrade + resolution

Status: PROPOSED (2026-08-28). Context: Phase 4 backfill wrote 16 HIGH-confidence orphans;
8 manual-review rows (37,776 sats reported) remained. Live-data analysis (dev-log 2026-08-28)
showed the true outstanding amount is **4,444 sats (2 invoices)**; the rest are dead retries
and already-boosted false positives.

## What the live-data analysis established

Pairing key: a COMPLETE order's `paidAt` lands seconds-to-minutes *after* its invoice's LND
settle (polling confirm). Groups:

| Group | Verdict |
|---|---|
| mg101010 ×3 (LUP 671, 2222) | All 3 invoices pair 1:1 with 3 COMPLETEs (already in ledger). **False positives.** |
| anon TWIB-108 22,222 (326036) | Pairs with COMPLETE `od_Bhv8qdahBt` (paidAt 09:35:10 vs settle 09:34:49). **False positive.** |
| anon TWIB-108 ×3 (2,222) | 2 pair with COMPLETEs; invoice 2 (settle 06-11 02:13:26, no nearby complete) is the **real orphan**. Anchor = 1 of 4 PENDINGs. |
| memphis (342724) | The minimal case: real orphan, anchor = 1 of 2 PENDINGs. |

The PENDING candidates are not "expired" (`expiresAt: null` on all 10) — but most are dead
retries that will never complete.

## NEW discovery: creation order IS extractable — with two corrections

Zaprite's API does not return a `createdAt` field, but **accepts `sortBy=createdAt`** and orders
results by it (verified 2026-08-28). A unified pull (`status=PENDING&status=PAID&status=COMPLETE
&status=OVERPAID&sortBy=createdAt&sortOrder=asc`, 416 orders) interleaves PENDING and COMPLETE
in true creation order.

**Correction 1 — phase-0 memphis hypothesis is likely WRONG.** In creation-asc order,
`od_vff0Bfkh8g` (pos 326) was created BEFORE `od_bY1at35Vl9` (pos 327). If each checkout attempt
creates one order + one invoice (attempt 1 → canceled 342723, attempt 2 → settled 342724), the
settled invoice anchors to the *later* order = `od_bY1at35Vl9` — the opposite of the phase-0
guess (which was explicitly unconfirmed).

**Correction 2 — order/invoice creation is not always 1:1.** The anon TWIB-108 orphan invoice
(323627, created 06-11 02:13:26) has NO PENDING order created near that time — the neighboring
positions in creation order belong to other users. Nearest anon-2222 PENDING was created ~4.5h
earlier. Per adevries17's own orphan message ("just keep refreshing the page and you can send it
again"), refreshes may re-issue invoices on an *existing* order. Consequence: position analysis
gives signal, not proof; the anchor for such invoices cannot always be pinned exactly.

**Design consequence:** the load-bearing safety rule is **content-identity** — when all surviving
candidates produce an identical boost (same user/episode/message), any anchor choice is
bookkeeping-equivalent, and a later Zaprite completion upsert-merges onto our entity. The
creation-order position is a deterministic *tie-break* (pick latest-created = the retry that
likely settled), not a truth claim.

## Plan

### 1. Matcher upgrade (scraper, no worker change)

Extend `reconcile.clj`:

a. **Fetch COMPLETE orders once per detection run** (scraper already has `fetch-orders`; cache
   per run, ~3 pages for 240 orders).

b. **`already-boosted` pairing rule**: invoice is already-boosted if a COMPLETE order exists
   with (label-parsed slug+ep == invoice's, amount == sats, `paidAt` within ~10 min of settle).
   Clears the 4 false-positive rows with a test-lockable rule.

c. **Creation-order tie-break** (`match-order-candidates`): when >1 PENDING candidate survives
   the guards, use the **unified creation-ordered pull** (§ NEW discovery) and pick the
   **latest-created** candidate (the retry that likely settled — see Correction 1; memphis
   fixture must resolve to `od_bY1at35Vl9`). `fetch-pending-orders` must thread the
   creation-order index through (sortBy=createdAt, asc).

d. **Safety (load-bearing)**: confidence is `:high` only when (i) exactly one candidate remains
   after pairing math, or (ii) all candidates are **content-identical** (same normalized user +
   episode + message — true for both live cases). Otherwise remain `:manual-review`. Rationale:
   the anchor order-id is bookkeeping (later completion upsert-merges); content must never be
   guessed (Correction 2).

Tests: pairing-rule unit tests (creation/paidAt gaps, 1:1 and N:1 cases), creation-order
tie-break tests (memphis fixture: `od_bY1at35Vl9` must win), content-identity guard tests
(including a content-divergent group that must stay manual), and regression: full fixture
resolves to exactly 2 written + 6 already-boosted/false-positive.

### 2. Web-boost worker fix (durable, future-proof)

Embed the euid tail into the invoice memo at checkout time:

```
Payment for Web Boost: LUP 670 — Memphis [euid:21957ee1-b11c-4849-b96b-579d644566ba:6310f9ee]
```

- Parser (`parse-web-boost-memo`) already tolerates suffixes (username is greedy remainder) —
  needs a small extraction + a memo-stripping step so label matching stays clean.
- Matcher upgrade: when an invoice carries an euid tail and exactly one candidate's full euid
  matches (token + fingerprint), confidence is `:high` with exact anchoring. Kills the
  ambiguity class permanently, including the memphis-style same-browser fingerprint cluster.
- Deployment: worker-side change only; scraper picks it up automatically for *new* invoices.
  Historic invoices stay unresolvable by this path (hence plan §1).

### 3. Resolution run (after §1 ships)

1. Deploy upgraded matcher (routine flake update; write flag off).
2. Preview → expect: manual-review drops to 0; orphans = 2 (memphis-invoice 342724 + anon
   invoice 323627); already-boosted includes the 6 cleared rows.
3. Flip `reconcileWrite = true`, backfill, verify `/boosts` (+2 rows, +4,444 sats), flip back.

Expected final anchors: memphis → `od_bY1at35Vl9` (creation-order tie-break); anon-2222 →
latest-created surviving candidate (content-identical group, so any is equivalent).

## Out of scope / deferred

- Dashboard-based creation timestamps: unnecessary given `sortBy=createdAt` works. Keep as
  fallback if the undocumented sort regresses (see zaprite-access doc).
- Marking dead PENDING orders expired/canceled in Zaprite: out of scope (DB is the ledger),
  but would clean the PENDING list for future runs.
