# Phase 4 — Backfill Deployment Runbook

Goal: turn the 13 settled-but-never-boosted web-boost payments into boost entities in the
nodecan-conn ledger, via the deployed server's HTTP routes.

Prereqs: `v2-feed` pushed with Phase 3 (commit `9a24f94` or later). Spec §11 Phase 4; decisions
recorded in `docs/dev-log.md`.

## Plan (one rebuild, then preview → backfill)

We deploy with `reconcileWrite = true` from the start. This is safe because:

- `GET /api/v1/reconcile/preview` **never writes**, flag on or off — the confirmation step below
  is guaranteed read-only.
- `POST /api/v1/reconcile/backfill` only writes when it is explicitly called, and only the
  HIGH-confidence orphan set (bounded, idempotent, dedup-guarded). A stray POST while armed does
  the intended thing and can't create dupes or touch the memphis manual-review case.
- One rebuild instead of two.

## Step 1 — Rebuild on the box

```sh
# on the box, in your flake repo that tracks lnd-boost-scraper
git pull                      # or nix flake update <lnd-boost-scraper input>
nix flake update              # your normal flow
# set in the lnd-boost-scraper module config:
#   services.lnd-boost-scraper.reconcileWrite = true;
sudo nixos-rebuild switch --flake <box-flake>#<host>    # (impure if your flow needs it)
systemctl restart lnd-boost-scraper
```

Sanity: `curl -s http://100.120.212.39:3223/ping` → `pong`.

## Step 2 — Preview (read-only confirmation) — DO NOT skip

```sh
curl -s http://100.120.212.39:3223/api/v1/reconcile/preview | jq '
  {write_enabled: .write_enabled,
   scanned: .scanned,
   already_boosted: .already_boosted,
   orphans: (.orphans | length),
   manual_review: (.manual_review | length),
   unmatched: (.unmatched | length),
   orphan_sats: .total_sats_orphaned}'
```

- `write_enabled: true` (proves the flag landed)
- `orphans: 12` — the HIGH-confidence set that backfill will write
- `manual_review: 1` — memphis (`od_bY1at35Vl9` / `od_vff0Bfkh8g`), never auto-written
- `unmatched: ≥ 0` — **normal if non-zero**: older web-boost payments whose Zaprite orders are
  already COMPLETE (already in the ledger via the normal sync; not written by reconcile).

**Abort here (do not backfill) if** `orphans ≠ 12`, `manual_review ≠ 1`, or any orphan's amount
disagrees with the fixture (sum below).

## Step 3 — Backfill (the write)

Only after Step 2 confirms. `jq` the summary as well:

```sh
curl -s -X POST http://100.120.212.39:3223/api/v1/reconcile/backfill | jq '
  {written: .written, skipped: .skipped, manual_review: .manual_review, unmatched: .unmatched}'
```

Expected: `written: 12, skipped: 0, manual_review: 1`.

## Step 4 — Verify

```sh
# re-run preview: should now be written 0 / skipped 12 (idempotent, no dups)
curl -s http://100.120.212.39:3223/api/v1/reconcile/preview | jq '{orphans: (.orphans|length), already_boosted}'
# spot-check the ledger via the existing endpoints
curl -s "http://100.120.212.39:3223/boosts?show=all&since=0&json=true" | jq '.boosts[] | select(.payment_rail=="lightning")' | head
```

Then in the show reports / analysis UI: the 12 show as boosts, `app: Zaprite`,
settle-time timestamps, amounts matching the fixture — no doubles with the Zaprite COMPLETE set.

## Step 5 — Close the write path (follow-up config change, not urgent)

Set `reconcileWrite = false` back and rebuild on your next routine deploy. The backfill is
one-time; keep the write gate closed. (Phase 5 keep-fresh loop is intentionally deferred until a
future orphan recurs post-webhook-fix.)

## Fixture (reference — 13 settled, 158,787 sats)

Anchors (verified live 2026-08-19): **325042** TWIB 108 — NorthLakeTaHodl (12,345) ·
**325043** TWIB 108 — debitcoinkoers.eu (10,000) · **336940** LUP 673 — mg (11,111) ·
**342724** LUP 670 — Memphis (2,222, **manual-review**) · **344542** TWIB 118 — Adam Curry
(88,888). The remaining identifiers are on the box at `/dev/shm/orphan_reconcile.md` (Phase 0
full table), but the aggregate check is: 12 writable + Memphis 2,222 = 158,787 total sats, so the
12 writable should sum to **156,565**. The known identifiers are 325042, 325043, 329454, 331716,
336940, 342724, 334619, 334731, 340403, 341023, 342834, 344542, 346574.

## Don'ts

- Don't backfill before Step 2 confirms the exact buckets.
- Don't manually `PATCH` Zaprite orders to COMPLETE — DB is the ledger (out of scope).
- Don't treat `unmatched` as an error, and don't try to force-write memphis — it stays
  manual-review until a human picks `od_vff0Bfkh8g` (order-id ordering hypothesis) or accepts it
  as documented-manual.