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

## NEW discovery: creation order IS extractable

Zaprite's API does not return a `createdAt` field, but **accepts `sortBy=createdAt`** and orders
results by it (verified 2026-08-28). Sanity check: memphis `od_bY1at35Vl9` sorts before
`od_vff0Bfkh8g` — consistent with the phase-0 hypothesis that the later-created
`od_vff0Bfkh8g` anchors the settled invoice (attempt 1 → canceled invoice 342723; attempt 2 →
settled 342724).

This unlocks **complete automated resolution** — no dashboard access required:

## Plan

### 1. Matcher upgrade (scraper, no worker change)

Extend `reconcile.clj`:

a. **Fetch COMPLETE orders once per detection run** (scraper already has `fetch-orders`; cache
   per run, ~3 pages for 240 orders).

b. **`already-boosted` pairing rule**: invoice is already-boosted if a COMPLETE order exists
   with (label-parsed slug+ep == invoice's, amount == sats, `paidAt` within ~10 min of settle).
   Clears the 4 false-positive rows with a test-lockable rule.

c. **Creation-order tie-break** (`match-order-candidates`): when >1 PENDING candidate survives
   the guards, and the group's COMPLETE pairing math implies exactly one invoice-unclaimed
   anchor, pick the **latest-created** candidate (retry wins — the earlier attempt's invoice
   canceled). Requires threading the creation-order index (from `sortBy=createdAt` page
   position) through `fetch-pending-orders`. Promotes memphis + anon-2222 to HIGH.

d. **Safety**: confidence stays `:high` only when (i) exactly one candidate remains after
   pairing math, or (ii) all candidates are content-identical (same user/episode/message —
   verified for both live cases). Otherwise remain `:manual-review`.

Tests: pairing-rule unit tests (settle/paidAt gaps, 1:1 and N:1 cases), creation-order
tie-break tests (memphis fixture: `od_vff0Bfkh8g` must win), content-identity guard tests,
and regression: full fixture resolves to exactly 2 written + 6 already-boosted/false-positive.

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
2. Preview → expect: manual-review drops to 0; orphans = 2 (memphis-invoice + anon invoice 2);
   already-boosted includes the 6 cleared rows.
3. Flip `reconcileWrite = true`, backfill, verify `/boosts` (+2 rows), flip back.

## Out of scope / deferred

- Dashboard-based creation timestamps: unnecessary given `sortBy=createdAt` works. Keep as
  fallback if the undocumented sort regresses (see zaprite-access doc).
- Marking dead PENDING orders expired/canceled in Zaprite: out of scope (DB is the ledger),
  but would clean the PENDING list for future runs.
