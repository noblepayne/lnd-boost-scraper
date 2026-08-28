# Zaprite API — notes for elevated/dashboard access

Purpose: if you get org-dashboard or expanded API access on Zaprite, here's exactly what would
finish the manual-review resolution and verify the matcher upgrade. Low effort, high value.

## Context

We reconcile settled-but-never-boosted web-boost payments: settled LND invoices (source of
truth) are matched to PENDING Zaprite orders by label + amount + username + externalUniqId
prefix. The remaining ambiguity: retry checkouts create multiple (label, amount)-identical
orders; one settles, the rest stay PENDING forever. We can't tell which PENDING order the
settled invoice actually paid for.

## What the plain API gives us today (verified 2026-08-28)

- `GET /v1/orders?status=PENDING` — works (single `status` param; the array form `status[]=`
  is silently ignored).
- `sortBy=createdAt` is **accepted and orders results by creation** (undocumented but works;
  the `createdAt` field itself is NOT returned). This gives creation ORDER.
- `expiresAt: null` on all stuck PENDING orders — no expiry signal.
- COMPLETE orders expose `paidAt`; pairing `paidAt` against LND settle times (gap ~5s–2min)
  already resolved 4 of our 8 manual-review rows as already-boosted.

## What dashboard/elevated access would add (in priority order)

1. **`createdAt` timestamps for PENDING orders** (or just confirmation of the exact sort order
   of `sortBy=createdAt`). Would let us anchor each settled invoice to the correct retry order
   with certainty instead of inference. Needed for: memphis `od_bY1at35Vl9` vs `od_vff0Bfkh8g`,
   and the anonymous TWIB-108 group (`od_ehqB7niRAw`, `od_kK1UvYo4bf`, `od_MnKViAacwF`,
   `od_9ygDCAbgLa` — one of these is the true anchor of the settled 06-11 02:13:26 invoice).

2. **Any way to cancel/expire dead PENDING orders** (dashboard action or write API). The
   matcher treats non-expired PENDING as candidates; dead retries are what create the
   ambiguity. Clearing them makes every future run unambiguous. ~8 dead orders right now.

3. **Webhook/logs visibility**: whether Zaprite ever received a successful webhook for the two
   real orphan invoices (LUP 670 memphis settle 2026-08-07 03:36:14 UTC; anon TWIB-108 settle
   2026-06-11 02:13:26 UTC). Purely diagnostic — confirms the dead-webhook story per-order.

4. **Order→payment linkage** (if visible): the LNbits payment hash or checking_id per order,
   even on PENDING orders. This is the perfect join key and would make the whole matcher
   exact. Don't expect it, but if the dashboard shows it, grab it.

## What NOT to do

- Don't mark orders COMPLETE on our behalf — the DB is the ledger; Zaprite-side completion is
  explicitly out of scope (spec §11 Phase 4 step 5). Canceling dead PENDINGs (#2) is fine and
  welcome; completing paid ones is unnecessary and blurs the audit trail.

## The 10 PENDING orders in question (as of 2026-08-28)

memphis LUP-670: `od_bY1at35Vl9`, `od_vff0Bfkh8g`
mg101010 LUP-671: `od_iMstEbRD9H`, `od_SIOtetojbE` (both believed dead retries)
anon TWIB-108 2222: `od_ehqB7niRAw`, `od_kK1UvYo4bf`, `od_MnKViAacwF`, `od_9ygDCAbgLa`
anon TWIB-108 22222: `od_3K9lI4yrur`, `od_2YiwPkZUO0` (both believed dead retries)
