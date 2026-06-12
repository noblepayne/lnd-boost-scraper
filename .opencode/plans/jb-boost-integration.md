# JB Boost Integration Plan

## Overview
Add support for two new boost sources to lnd-boost-scraper:
1. **Zaprite paid boosts** — fiat and Lightning payments via Zaprite API
2. **Member free boosts** — free member-perk boosts stored in Cloudflare R2

All boosts flow into the existing canonical Datalevin DB (nodecan-conn) and appear in unified reports.

## Workflow

Every chunk follows: **READ → PLAN → ACT → VERIFY**

- **READ**: `clojure_inspect_project`, `read_file`, `glob`, `grep` to understand current code
- **PLAN**: `scratch_pad` to outline approach, confirm against overall design
- **ACT**: `clojure_edit` (preferred for Clojure), then `cljfmt` + `clj-kondo`
- **VERIFY**: `clojure_eval` in REPL, run test suite

Commit after every clean fmt/lint pass. Rebase before merge.

## Chunks

### Chunk 1: Schema + data model (db.clj)
- Add 13 new optional attributes to schema
- Add coerce/transform fns for new source data
- Add sync-cursor attributes for cursor tracking

### Chunk 2: Shared utilities (utils.clj, upstream/lnd.clj)
- Extract `with-retries` from lnd.clj into utils.clj
- Add AWS SigV4 signing functions
- Reference retry from lnd.clj via utils

### Chunk 3: Zaprite scraper (upstream/zaprite.clj)
- Fetch paginated orders from Zaprite API
- Map to boost entity fields
- Sync with cursor tracking

### Chunk 4: R2 scraper (upstream/r2.clj)
- S3-compatible listing and fetch from Cloudflare R2
- AWS SigV4 signing
- Map MemberBoostRecord to boost entity
- Sync with cursor tracking

### Chunk 5: Core integration (core.clj)
- Wire new scrapers into scrape cycle
- Add env var handling (all optional)
- Run as virtual threads

### Chunk 6: Report rework (reports.clj, schemas.clj)
- 3 new Datalog queries: fiat, member-free, summaries
- 2 new display sections: Fiat Boosts, Member Free Boosts
- 2 new summary blocks: Fiat Summary, Member Free Summary
- Extended Malli schemas
- Section ordering via vector of renderer fns

### Chunk 7: Tests + verification
- Integration tests for new scrapers
- Full test suite pass

## Report structure (final)

```
## Baller Boosts          (Lightning + Zaprite BTC, ≥20k sats)
## Boosts                 (Lightning + Zaprite BTC, 2k-20k)
## Thanks                 (Lightning + Zaprite BTC, <2k)
## Fiat Boosts            (USD card/ACH — dollar amounts)
## Member Free Boosts     (free member perk)
## Boost Summary          (existing)
## Stream Summary         (existing)
## Fiat Summary           (total $, count, boosters, currencies)
## Member Free Summary    (count, boosters)
## Summary                (total sats, total fiat, total free, total invoices, total senders)
```

## Schema additions

```clojure
:boostagram/payment_rail          string
:boostagram/zaprite_order_id      string  unique/identity
:boostagram/memberful_member_id   string
:boostagram/amount_fiat_cents     long
:boostagram/amount_fiat_currency  string
:boostagram/received_at           instant
:boostagram/podcast_slug          string
:boostagram/episode_guid          string
:boostagram/episode_key           string
:boostagram/dedup_key             string
:boostagram/boost_id              string
:sync-cursor/key                  string  unique/identity
:sync-cursor/value                string
```

## Env vars (all optional)

| Var | Purpose |
|---|---|
| `ZAPRITE_API_KEY` | Zaprite API auth |
| `R2_ACCOUNT_ID` | Cloudflare R2 account |
| `R2_ACCESS_KEY_ID` | S3-compat access key |
| `R2_SECRET_ACCESS_KEY` | S3-compat secret |
| `R2_BOOST_BUCKET` | Bucket name |
| `R2_BOOST_PREFIX` | Key prefix (default: `member-boosts/v1/by-date/`) |

## Identity strategy

| Source | Dedup attr | Value |
|---|---|---|
| Zaprite paid | `:boostagram/zaprite_order_id` | Zaprite order UUID |
| Member free | `:invoice/identifier` | `"member-" + boostId` |
