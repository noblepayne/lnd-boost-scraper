# Manual Review — Operator Picks

**Current state (2026-11-19): one row open — Anon invoice `323627`.**

The three original rows were resolved on 2026-09-02 after a forensic pass that
disproved the original "pick a retry twin" premise. Full evidence and reasoning:
`docs/dev-log.md` → *2026-09-02 · Manual-review picks resolved*.

> **What the forensics changed:** the Zaprite API *does* expose `paidAt` on
> COMPLETE orders (it only hides it on PENDING). Aligning `paidAt` against LND
> `settle_date` showed the candidate orders had **never paid the target
> invoices**: PENDING candidates carried `invoice: None` and
> `transactions: []` (checkout abandoned before payment), and COMPLETE
> candidates had already settled *other* invoices. So "pick the retry that
> settled" was not a valid model for these rows.

---

## Resolved — Memphis, invoice `342724` (2,222 sats, LUP 670) ✅ BOOKED

**Treatment:** booked invoice-anchored (no order linkage) via the gated
`POST /api/v1/reconcile/resolve` path on 2026-09-02.

**Why:** real settled receipt (LND settle 2026-08-07 03:36:14Z), memo names
Memphis/LUP-670, and **no prior booking existed** for Memphis on that episode.
The message is unrecoverable — the boost carries none rather than an invented
one. Verify: `:boostagram/sender_name_normalized = "memphis"`,
`value_sat_total = 2222`, episode 670, no `zaprite_order_id`.

## Resolved — Hydragyrum, invoice `327805` (2,000 sats, LUP 668) ✅ ALREADY BOOSTED

**Treatment:** no write; reclassified as already-boosted.

**Why:** both LUP-668 settlements are already represented by Hydragyrum's two
COMPLETE orders. `od_ujd5Yk70Kv` paid 21:34:09 ↔ invoice `327806` (settle
21:34:03). `od_hTEwY3DUVX` paid 21:30:34 but invoice `327805` settled at
**20:45:29** — a **45-minute webhook lag**, not a second payment. Booking
`327805` would have double-booked the same money.

**Matcher fix shipped with it:** `pairs-with-complete?` now anchors on the LND
`settle_epoch` (fallback: creation) with a **1-hour** window. Validated in both
directions on live data — the 45-min lag pairs, while the Anon 81-min
non-neighbor (below) correctly does not.

---

## OPEN — Anon, invoice `323627` (2,222 sats, TWIB 108)

**State:** remains in `manual-review` by deliberate choice. Nothing is broken;
this is an accounting judgment call left open.

**Evidence:**
- Two Anon TWIB-108 2,222-sat boosts **already exist** and are booked
  (`od_2QMi1ppumF` paid 06-10 21:41:44, message *"Capital gains tax this IRS."*;
  `od_y7jTeGxbD1` paid 06-11 03:36:03, message *"Testing!"*), each paired to its
  own invoice (`323503`, `323628`).
- Invoice `323627` **did settle** (06-11 02:14:48Z) — real sats moved.
- Its four remaining candidates are PENDING with `invoice: None` /
  `transactions: []` — they never initiated payment. The order that actually
  paid `323627` is not identifiable from the API.

**Why left open:** a third identical-destination, identical-amount entry with
no source document is the textbook double-count risk. Conservative treatment =
leave it; the understatement is 2,222 immaterial and anonymous sats.

**Options if you ever want to close it:**
1. **Leave as-is (current).** Recommended. Manual-review is informational; the
   row does not affect ballers, fiat, or any money-critical view.
2. **Book it invoice-anchored** — flip `WEB_BOOST_RECONCILE_WRITE=true`, then
   `POST /api/v1/reconcile/resolve {"identifier":"323627"}`, then flip the gate
   back off. This books real sats that demonstrably settled; accept the
   small risk that it was an aborted-checkout artefact rather than a
   deliberate third boost.
3. **Get better evidence** — Zaprite dashboard (order→payment linkage /
   checkout logs for that window) would settle it definitively. The plain API
   cannot.

---

## How to run a resolution (for any future manual-review row)

The write path is gated by design; a resolution is a deliberate three-step
sequence, never automatic:

```bash
# 1. Inspect current state (read-only)
curl -s http://<host>:3223/api/v1/reconcile/preview | jq '.manual-review, .orphans'

# 2. With the gate ON (flake: reconcileWrite = true; rebuild; deploy), resolve:
curl -s -X POST http://<host>:3223/api/v1/reconcile/resolve \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"<invoice-identifier>"}'
#    optional: add "order-id":"od_..." to use that order's message/episode
#    metadata (metadata only — payment state is never inferred from an order)

# 3. Flip the gate back OFF (reconcileWrite = false; rebuild; deploy) and verify:
#    manual-review should drop by one; the boost appears in reports/feed.
```

Responses: `200` written, `403` gate off, `404` unknown invoice, `409` already
boosted (idempotent — no duplicate is created).
