# lnd-boost-scraper contract notes

The durable writer-side contract lives at `web-boost/docs/shared-interface.md`
in the web-boost-worker repo. Verified against the worker source at
`functions/api/member-boost.ts` and `functions/_lib/member-boosts.ts`.

This doc is the scraper's consumer-side notes: what we read, what we store,
where we deliberately deviate from the spec.

## What we read

### R2 ($1 of upstream contract)

- Key format: `member-boosts/v1/r/{base36_ts}-{uuid}.json` (base36-encoded
  `createdAt` ms timestamp, then `-`, then `boostId` as UUID v4)
  - The spec says `{ulid}.json` but the worker uses a UUID — lex order
    preserved either way
- We do NOT list `d/` (sentinels — they exist for atomic dedup at write time,
  not for the scraper to read)
- 10-field record body per $1.2 of upstream contract; the field name for the
  sender is `username` (confirmed at `functions/api/member-boost.ts:64`)

### Zaprite ($2 of upstream contract)

- Statuses: `PAID`, `COMPLETE`, `OVERPAID`
- Filter on `metadata.app == "web-boost"`
- 5-field metadata ($2.1 of upstream contract)

### Cursors ($4 of upstream contract)

- R2: `sync-cursor/key` = `"r2-member"`, value = lex-last R2 key
- Zaprite: `sync-cursor/key` = `"zaprite"`, value = `paidAt` ISO timestamp

## How we store it

Field-to-attribute mapping is in:
- `src/boost_scraper/upstream/r2.clj` :: `process-record`
- `src/boost_scraper/upstream/zaprite.clj` :: `process-order`

Read those for the current truth. Identity:
- R2: `:boostagram/r2_object_key` = full R2 key (`:db/unique :db.unique/identity`)
- Zaprite: `:boostagram/zaprite_order_id` = Zaprite order UUID (`:db/unique :db.unique/identity`)

Time semantics:
- `:invoice/created_at` (Date), `:invoice/creation_date` (long epoch),
  `:boostagram/received_at` (Date) all derived from the upstream timestamp.
  Three fields, same source. Kept separate so future work can distinguish
  "upstream view" (`received_at`) from "our view" (`created_at`) if needed.

## Fields read but always absent (future-proofing)

- `:boostagram/amount_fiat_cents` and `:boostagram/amount_fiat_currency` are
  read from the R2 record body. The current worker (`_lib/member-boosts.ts:21`)
  explicitly forbids payment fields (`amount`, `currency`, `value`, `sats`,
  `usd`) in member boost requests, so these will always be nil. They are
  retained for forward compatibility if fiat member boosts are added later.

## Fields dropped from original plan

- `:boostagram/episode_key`: Not stored. Reports query by podcast/episode via
  `:boostagram/podcast` and `:boostagram/episode` which are always set by all
  three ingest paths. A separate lookup key adds no value.
- `:boostagram/dedup_key`: Not stored. Dedup is handled at the write level by
  `:db/unique` on `:boostagram/r2_object_key` and `:boostagram/zaprite_order_id`.
  An application-level dedup key would duplicate the DB's own identity mechanism.

## Deviations from upstream contract

- We store the raw `memberId` (no HMAC). Memberful member IDs are not PII
  on their own (opaque integers, not credentials). Storing raw lets us join
  boost -> member profile without an unmapping table.
- We do not store `episodePubDate`. It is not displayed in any current report.
  If a future report needs it, add the field then.
- We do not use a versioned cursor key (`r2-member-v1` per the spec). We use
  `r2-member` because no old-format cursor exists in the wild; the scraper
  has never run against the R2 bucket. If a stale `r2-member` cursor ever
  exists with the wrong value format, the migration is a one-shot `d/retract`
  at sync start.

## Open items to revisit

- `:boostagram/time` vs `:boostagram/ts`: `time` is a wall-clock string
  (LND-ism, "HH:MM:SS"), `ts` is epoch long. New sources (R2, Zaprite)
  set `ts` but not `time`. Should we drop `:boostagram/time` from the
  schema? Currently only LND display formatting uses it, and reports format
  dates from `:invoice/created_at`. Decide in a follow-up PR.
- Zaprite cursor timestamp precision: `paidAt` ISO timestamp in Zaprite
  responses may or may not include fractional seconds. If whole-seconds,
  each sync re-fetches orders within the same second. Cheap in practice,
  not free. Verify with test creds.
