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
| `:scraper/source` | string | "alby" / "JB" / "nodecan" |

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
| `src/boost_scraper/core.clj` | Main entry, scrape loop, sync logic |
| `src/boost_scraper/db.clj` | Schema, data coercion, boost decoding |
| `src/boost_scraper/web.clj` | Aleph web server, HTML + JSON API |
| `src/boost_scraper/shows.clj` | Show registry (slug → regex map) |
| `src/boost_scraper/reports.clj` | Heavy Datalog queries, report formatting |
| `src/boost_scraper/client_state.clj` | Per-client last-seen tracking |
| `src/boost_scraper/schemas.clj` | Malli schemas |
| `src/boost_scraper/upstream.clj` | IBoostScrape protocol |
| `src/boost_scraper/upstream/lnd.clj` | LND REST scraper |
| `src/boost_scraper/upstream/alby.clj` | Alby API scraper |
| `src/boost_scraper/utils.clj` | Date formatting, virtual thread helpers |
| `src/boost_scraper/analysis.clj` | **Ad-hoc analysis queries** (see skill) |
| `src/boost_scraper/boosties.clj` | Leaderboard queries |
| `src/boost_scraper/legacy.clj` | Old query approach (pre-refactor) |

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

## Skills

Located in `.opencode/skills/`. Currently available:

- **boost-analysis** — ad-hoc Datalog query patterns for boost data
