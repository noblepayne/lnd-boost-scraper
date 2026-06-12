# lnd-boost-scraper — Agent Guide

## What This Is

Scrapes Lightning Network boostagrams (value-for-value podcast tips) from multiple sources into Datalevin databases, syncs them, and provides a web UI + JSON API for generating boost reports.

## Database

### Location
The canonical (nodecan) database lives at `/dev/shm/nodecan`. Open it with:

```clojure
(def conn (d/get-conn "/dev/shm/nodecan" db/schema))
```

There are also LND-only and Alby-only DBs at paths set via env vars `JBNODE_DBI` and `ALBY_DBI`.

### Schema (key attributes)

| Attribute | Type | Description |
|-----------|------|-------------|
| `:invoice/identifier` | string (unique) | Invoice ID from upstream |
| `:invoice/creation_date` | long (epoch sec) | **Use for time filtering** |
| `:invoice/created_at` | instant (Date) | Same as above but as Date object |
| `:boostagram/sender_name_normalized` | string | Sender name, trimmed+lowercased |
| `:boostagram/value_sat_total` | long | Boost amount in sats |
| `:boostagram/value_msat_total` | long | Boost amount in msats (raw) |
| `:boostagram/podcast` | string | Podcast name |
| `:boostagram/episode` | string | Episode name |
| `:boostagram/app_name` | string | Client app used |
| `:boostagram/action` | string | "boost" or "stream" |
| `:boostagram/message` | string | Text message |
| `:boostagram/content_id` | string | SHA-256 content hash (dedup) |
| `:scraper/source` | string | "alby" / "JB" / "nodecan" / "zaprite" / "r2-member" |
| `:boostagram/type` | keyword | `:sat` / `:fiat` / `:member-free` — set at ingest |
| `:boostagram/r2_object_key` | string (unique) | R2 object key (member-free boosts) |
| `:boostagram/payment_rail` | string | "lightning" / "card" / "member-free" |
| `:boostagram/podcast_slug` | string | Short slug (e.g. "lup") |
| `:boostagram/episode_guid` | string | Episode GUID |

### Show Registry (shows.clj)

| Slug | Name | Regex |
|------|------|-------|
| `lup` | LINUX Unplugged | `(?i).*unplugged.*` |
| `twib` | This Week in Bitcoin | `(?i).*bitcoin.*` |
| `launch` | The Launch 🚀 | `(?i).*launch.*` |
| `ssh` | Self-Hosted | `(?i).*hosted.*` |
| `coder` | Coder Radio | `(?i).*coder.*` |
| `all` | All Shows | `.*` |

Boosts match by **both** `:boostagram/podcast` AND `:boostagram/episode` using `re-matches`. Use the `(get-else $ ?e :boostagram/episode "Unknown Episode")` pattern for missing episode values.

### Source Files

| File | Purpose |
|------|---------|
| `src/boost_scraper/core.clj` | Main entry, concurrent scrape loop, sync logic |
| `src/boost_scraper/db.clj` | Schema, data coercion, boost decoding |
| `src/boost_scraper/web.clj` | Aleph web server, HTML + JSON API |
| `src/boost_scraper/shows.clj` | Show registry (slug → regex map) |
| `src/boost_scraper/reports.clj` | Heavy Datalog queries, report formatting |
| `src/boost_scraper/client_state.clj` | Per-client last-seen tracking |
| `src/boost_scraper/schemas.clj` | Malli schemas |
| `src/boost_scraper/upstream.clj` | IBoostScrape protocol |
| `src/boost_scraper/upstream/lnd.clj` | LND REST scraper |
| `src/boost_scraper/upstream/alby.clj` | Alby API scraper |
| `src/boost_scraper/upstream/zaprite.clj` | Zaprite API scraper (fiat + sat boosts) |
| `src/boost_scraper/upstream/r2.clj` | R2 member-free boost scraper (cognitect.aws S3) |
| `src/boost_scraper/utils.clj` | Date formatting, virtual thread helpers, retry logic |
| `src/boost_scraper/analysis.clj` | **Ad-hoc analysis queries** (see skill) |
| `src/boost_scraper/boosties.clj` | Leaderboard queries |
| `src/boost_scraper/legacy.clj` | Old query approach (pre-refactor) |

## Testing Conventions

### Running tests
```bash
clojure -M:test              # unit tests (includes Datalevin JVM opts)
clojure -X:test              # same, via exec-fn
```

Tests auto-discover namespaces ending in `-test`. When adding a new test namespace, name it `boost-scraper.<thing>-test` in `test/boost_scraper/<thing>_test.clj`.

### Principles
- **Unit tests need zero infrastructure**: no database, no network, no filesystem.
- **Pure functions are tested directly**. See `db_test.clj` for `normalize-name`, `remove-nil-vals`, `sha256`, `flatten-paths`, `namespace-invoice-keys`.
- **Protocol mocking uses `reify` on `IBoostScrape`**. See `core_test.clj` for call-counting pagination mock with `atom`.
- **Integration tests are gated behind env vars**. `api_test.clj` only runs when `TEST_BASE_URL` is set.
- **Edge cases are explicit**: non-divisible msats (1234 -> 1 sat), empty sequences, invalid Base64, timezone offsets.

### Key test files
| File | Tests |
|------|-------|
| `test/boost_scraper/db_test.clj` | Schema coercion, boost decoding, batch processing, backfill scenarios |
| `test/boost_scraper/core_test.clj` | Scrape orchestration: `get-all-boosts-until-epoch` boundary, pagination mocking, credential gating |
| `test/boost_scraper/upstream_test.clj` | Source scrapers: Zaprite process-order, R2 process-record, sort-report thresholds, keyword roundtrip |
| `test/boost_scraper/reports_test.clj` | Report pipeline: sort-report, format-sorted-report, normalize-report, first-booster, podcast-app-percentages |
| `test/boost_scraper/api_test.clj` | Integration: HTTP API lifecycle (requires `TEST_BASE_URL`) |

## Timezone

Always **America/Los_Angeles** (PDT = UTC-7 in summer, PST = UTC-8 in winter).

The `utils.clj` `format-date` function displays in LA time. The `analysis.clj` `la-zone` var provides the ZoneId.

To compute epoch boundaries for a month in LA:
- Midnight LA = UTC 07:00 or 08:00 depending on DST
- April = PDT (UTC-7), so midnight = 07:00 UTC

## Datalog Query Patterns

### Boost matching (standard across all queries)
```clojure
[?e :boostagram/action "boost"]
[?e :boostagram/podcast ?podcast]
[(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]
(or [(re-matches ?regex ?podcast) _]
    [(re-matches ?regex ?episode) _])
```

Pass the compiled `Pattern` via `:in $ ?regex`.

### Sender name normalization
```clojure
[(get-else $ ?e :boostagram/sender_name_normalized "N/A") ?sender]
```
Always use `sender_name_normalized` (not raw `sender_name`) to get consistent casing.

## Output Formatting

### Markdown for reports
Use tables with `| # | Booster | Sats |` header. Top 5-10 per show. Keep it short — Slack-friendly.

### HTML for Slack paste
Produce standalone HTML with inline styles:
- `font-family: -apple-system, sans-serif`
- Red `h2` headers (`color: #c0392b`)
- `<ol>` for ranked lists
- `<hr>` between sections
- Grey pull-out boxes for key takeaways: `<p style="background: #f0f0f0; padding: .4em .8em; border-left: 3px solid #888;">`
- Source line at bottom in grey
- No external deps, no `<style>` blocks

## Deployment

### NixOS Module Options (module.nix)

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `zapriteApiKeyPath` | str | `""` | File path to Zaprite API key |
| `r2AccessKeyIdPath` | str | `""` | File path to R2 access key ID |
| `r2SecretAccessKeyPath` | str | `""` | File path to R2 secret access key |
| `r2AccountId` | str | `""` | Cloudflare account ID |
| `r2BoostBucket` | str | `""` | R2 bucket name |

### Env Vars (file-path pattern for secrets)
- `ZAPRITE_API_KEY_PATH` — path to Zaprite API key file
- `R2_ACCESS_KEY_ID_PATH` — path to R2 access key file
- `R2_SECRET_ACCESS_KEY_PATH` — path to R2 secret key file
- `R2_ACCOUNT_ID` — Cloudflare account ID (direct value)
- `R2_BOOST_BUCKET` — R2 bucket name (direct value)

Secrets use `(some-> PATH not-empty slurp str/trim not-empty)` — read from files managed by sops-nix/agenix.

### Checks
```bash
nix build .#checks.x86_64-linux.module-options  # validate module options
nix flake check --impure                         # full flake check
```

## Price Feed & Fiat Conversion

`price_feed.clj` fetches BTC/USD prices for converting fiat boost amounts to satoshis at report time.

**Fallback chain** (5s timeout each):
1. `mempool.space/api/v1/prices` — user runs their own mempool node
2. `api.coingecko.com/api/v3/simple/price?ids=bitcoin` — CoinGecko
3. `blockchain.info/ticker` — blockchain.info

**Cache**: In-memory atom, 5-minute TTL. On source failure, returns stale cached rate with a log warning. If all sources fail and no cache exists, returns nil (fiat excluded from total, `:fiat-skipped` flag set on report).

**Conversion**: `fiat_sats = fiat_cents * rate / 100`, where rate = `100,000,000 / btc_usd`. Applied in `reports.clj` `add-fiat-to-total` between `normalize-report` and `format-sorted-report`.

**Display**: Summary shows `"Total Sats: 52,000 (incl. 2,000 fiat @ 1,643 sats/USD, via mempool.space)"`.

## Bug Fix History (this session)

| Bug | Root Cause | Fix |
|-----|-----------|-----|
| R2 cursor corruption | `[[cursor-str]]` double destructuring extracted first character `\m` from key string | `[cursor-str]` single destructuring |
| Zaprite re-processes same order | `paidAtMin` API parameter is inclusive, cursor never advanced past boundary | Store cursor as `paidAt + 1ms` via `Instant/parse` + `.plusMillis` |
| LND infinite pagination | Used `first_index_offset` (always newest, never changes) instead of `last_index_offset` | `lnd.clj:35` — one word change |
| Alby always fetches all pages | Server-side `:since` filter existed in scraper code but was never populated from `epoch` | Pass `:since most-recent-timestamp` in `get-all-boosts-until-epoch` call |
| Price feed returned nil on all sources | `json/parse-string` without `true` returns string-keyed maps, keyword `:USD` didn't match `"USD"` | Add `true` keywordize arg to all three API parsers |

## What's Next

- **Push & deploy**: 2 unpushed commits (`b5815a8`, `c9aa123`) — build and deploy to nodecan
- **Verify price feed**: Check journalctl for `"Price feed: got BTC/USD"` after deploy
- **Cycle interval**: Consider bumping `scrape-sleep-interval` from 60s to 300s — external API rate limits (Zaprite, R2, price feed, mempool)
- **Alby/LND/Nodecan slow pagination**: Accept as pre-existing, or investigate `get-all-boosts-until-epoch` — they paginate through all historical invoices every cycle even when caught up
- **Zaprite API key**: Set `zapriteApiKeyPath` in NixOS config if not done
- **Final review on `feat/web_boost` branch**
- **Merge PR #14** to `v2`

## Skills

Located in `.opencode/skills/`. Currently available:

- **boost-analysis** — ad-hoc Datalog query patterns for boost data
