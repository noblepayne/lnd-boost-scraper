# Manual Review — Operator Picks

Three invoices remain in `manual-review` after the full reconcile sweep. The matcher
refused to auto-pick because either multiple candidates exist with equally valid
signals, or the content-identity guard detected divergent messages. These are
**operator decisions**, not matcher gaps.

To resolve: run the preview, pick the correct order for each, and transact the
boost entity. Or leave them — they're informational, not orphans.

---

## 1. Memphis — Invoice 342724 (2,222 sats, LUP 670)

**What happened:** Memphis boosted LUP 670 twice in quick succession (web-boost
widget retry). Both orders went PENDING; one settled the invoice, the other
stayed dead. The matcher found both orders as candidates but their messages
diverge:

| Order | Created | Message | Status |
|-------|---------|---------|--------|
| `od_vff0Bfkh8g` | Earlier | "Thanks for the value!" | PENDING |
| `od_bY1at35Vl9` | Later | "Thanks for all the value! Web boost FTW" | PENDING |

**Why the matcher refused:** content-identity guard — the two messages are
different strings. The guard exists to prevent silent mis-assignment when the
operator can make the right call.

**Likely answer:** The invoice memo says "Payment for Web Boost: LUP 670 — Memphis".
The settled invoice is 2,222 sats. Both orders are for 2,222 sats. The later-created
order (`od_bY1at35Vl9`) is the probable anchor (attempt 1 canceled → attempt 2
settled), based on the creation-order analysis from Phase 5. But this is a judgment
call — check which message Memphis actually sent if you have the correspondence.

**Action:** Pick the order that matches the message Memphis intended. Transact a boost
entity with the chosen order's metadata. The other order stays dead (PENDING, never
completes).

---

## 2. Anon — Invoice 323627 (2,222 sats, TWIB ???)

**What happened:** An anonymous booster sent 2,222 sats. The matcher found 6 candidate
orders — all 2,222 sats, all PENDING, all from the same time window. These are
**double-creation retry twins**: the web-boost widget retried on timeout, creating
multiple orders for the same invoice.

| Orders | Count | Total Sats |
|--------|-------|------------|
| All 2,222 sats, PENDING | 6 | 13,332 |

**Why the matcher refused:** Too many candidates with no distinguishing signal. The
content-identity guard can't collapse them because they share the same (empty or
identical) message. Group-aware COMPLETE-exclusion logic would help (exclude orders
that completed other invoices), but that's future work.

**Likely answer:** Any of the 6 orders is equally valid — they all represent the same
payment. Pick the one with the earliest `createdAt` (first attempt, most likely the
one that actually settled). The others are dead retries.

**Action:** Pick the earliest-created order. Transact a boost entity. The other 5
stay dead.

---

## 3. Hydragyrum — Invoice 327805 (2,000 sats, LUP 668)

**What happened:** Hydragyrum boosted LUP 668 with 2,000 sats. Two candidate orders
exist.

| Order | Created | Status |
|-------|---------|--------|
| Candidate A | Earlier | PENDING |
| Candidate B | Later | PENDING |

**Why the matcher refused:** Two candidates, no COMPLETE-pairing signal (both
PENDING), and the content-identity guard couldn't distinguish them.

**Likely answer:** Same pattern as Memphis — the later-created order is the probable
anchor. But with only 2 candidates and identical amounts, either is a reasonable pick.

**Action:** Pick the later-created order. Transact a boost entity. The other stays dead.

---

## How to resolve

1. Run `GET /api/v1/reconcile/preview` to see the current manual-review rows
2. For each: pick the correct order (see guidance above)
3. Transact via nREPL or a one-off route:
   ```clojure
   ;; Example: resolve Memphis
   (d/transact! conn [{:boostagram/sender_name_normalized "memphis"
                        :boostagram/value_sat_total 2222
                        :boostagram/app_name "Zaprite"
                        :boostagram/podcast "LINUX Unplugged"
                        :boostagram/episode "670: ..."
                        :boostagram/action "boost"
                        :boostagram/type :sat
                        :boostagram/zaprite_order_id "od_bY1at35Vl9"
                        :invoice/identifier "zaprite-od_bY1at35Vl9"
                        :invoice/creation_date <epoch>}])
   ```
4. Re-run preview — manual-review should drop by 1 for each resolved row

Or leave them. They're3 invoices / ~6,444 sats that show in the report's
"unmatched" informational bucket but don't affect ballers, fiat, or any
money-critical view.
