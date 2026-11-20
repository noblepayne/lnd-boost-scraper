# Boost Scraper API

The scraper serves an HTTP API on port `3223` (configurable via `SCRAPER_UIPORT`). All endpoints return JSON.

## Shows

Known show slugs: `lup`, `twib`, `launch`, `ssh`, `coder`, `all`

You can pass a slug name or a raw regex pattern for any `show` parameter.

---

## Schema (self-describing database)

`GET /api/v1/schema`

Write correct Datalog without reading source. Returns every attribute the
database knows about, including ones absent from the application schema.

| Param | Default | Description |
|-------|---------|-------------|
| `q` | — | substring filter on attribute name |
| `examples` | `false` | include sample values (also yields types for undeclared attrs) |
| `counts` | `false` | include entity counts (~1s per attribute — slow) |

```bash
curl 'http://localhost:3223/api/v1/schema'
curl 'http://localhost:3223/api/v1/schema?q=sender'
curl 'http://localhost:3223/api/v1/schema?q=sender_id&examples=true'
```

Response:

```json
{
  "attributes": [
    {"attribute": ":boostagram/podcast", "declared": true, "type": "db.type/string"},
    {"attribute": ":boostagram/sender_id", "declared": false, "type": "db.type/string",
     "examples": ["ANON", "ericpp@getalby.com"]}
  ],
  "counts": {"declared": 40, "undeclared": 58, "total": 98},
  "notes": {"declared": "...", "undeclared": "...", "examples": "...", "entity-counts": "..."}
}
```

`declared` means *in the application schema* (`boost-scraper.db/schema`), not
merely known to Datalevin — Datalevin auto-registers every attribute it has
seen, so 58 attributes here are real and queryable but undocumented until now
(`:boostagram/boost_link` on 313k rows, `:boostagram/name` on 377k,
`:boostagram/app_version`, `:boostagram/feedID`, `:boostagram/guid`, …).

**Performance:** default is ~0.15s (schema is in memory). `examples` costs
~5s for a whole scan, so pair it with `q` to inspect one attribute; `counts`
is ~1s per attribute. Results are cached for an hour.

## Boosties

Booster leaderboards for the periodic Boosties segment.

`GET /api/v1/boosties`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `show` | string | `lup` | Show slug or regex |
| `since` | int | `1767254400` | Epoch seconds — default is **2026-01-01 00:00 America/Los_Angeles** (= 08:00Z). Inclusive: nothing from 2025 or earlier counts. |
| `limit` | int | `10` | Rows per board (totals always cover everyone) |
| `include-self` | bool | `false` | Include chrislas / noblepayne (excluded by default) |

```bash
curl 'http://localhost:3223/api/v1/boosties?show=lup&limit=8'
```

Response:

```json
{
  "show-regex": "(?i).*unplugged.*",
  "since": 1767254400,
  "limit": 8,
  "front-runner": {"handle": "blackhost", "sats": 2469134, "count": 4, "fiat-cents": 0, "weeks": 3},
  "by-sats":   [{"handle": "...", "sats": 0, "count": 0, "fiat-cents": 0, "weeks": 0}],
  "by-count":  [{"handle": "...", "sats": 0, "count": 0, "fiat-cents": 0, "weeks": 0}],
  "fiat":      {"by-amount": [{"handle": "...", "fiat-cents": 0, "usd": 0.0, "count": 0}],
                "by-count":  []},
  "members":   {"by-count": [{"handle": "...", "count": 0, "weeks": 0}],
                "by-weeks": []},
  "streams":   {"by-sats": [], "by-count": []},
  "totals": {
    "boosts":   {"events": 0, "sats": 0, "fiat-cents": 0, "people": 0},
    "fiat":     {"events": 0, "sats": 0, "fiat-cents": 0, "people": 0},
    "members":  {"events": 0, "sats": 0, "fiat-cents": 0, "people": 0},
    "streams":  {"events": 0, "sats": 0, "fiat-cents": 0, "people": 0},
    "combined": {"sats": 0, "events": 0, "people": 0}
  }
}
```

**Money streams are never merged.** Sats boards count `:sat` boosts (integer
sats); the `fiat` boards count dollar-denominated web boosts (integer cents,
with `usd` = cents/100 computed exactly — no BTC-rate conversion here, so the
segment cannot drift with the price). `members` counts member-free boosts
(presence, no per-boost value) and `by-weeks` ranks consistency (distinct
weeks with at least one member boost) — a good "most loyal member" angle.
`combined.sats` is boosts + streams only; fiat stays separate by design.

**The anonymity bucket is not a person.** `anonymous` is one string used by
many different people, so it never occupies a leaderboard slot — but its money
is real. It is excluded from every ranked board and reported once:

```json
"anonymous": {"events": 89, "sats": 120180, "fiat-cents": 5000}
```

`totals.*.people` counts distinct **named** handles only, so the bucket cannot
inflate a "N people supported us" claim. Machine-issued per-install handles
(`user183420225038226`, `N/A`) are likewise unrankable and are kept separate
from the anonymity number. The same rule is available to every lens as
`contributions/handle-class` → `:named` / `:anonymous` / `:auto`.

Boosts and streams are separate acts; a person who does both appears in each
board and is counted once in `combined.people`.

## Analysis Endpoints

### Top Boosters

`GET /api/v1/analysis/top-boosters`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `start` | int | no | Epoch seconds (LA timezone) |
| `end` | int | no | Epoch seconds (LA timezone) |
| `limit` | int | no | Max results (default: all) |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
# Top 10 all-time for LUP
curl 'http://localhost:3223/api/v1/analysis/top-boosters?show=lup&limit=10'

# Top 5 in 2026 for TWIB, fiat only
curl 'http://localhost:3223/api/v1/analysis/top-boosters?show=twib&start=1767250800&end=1781248346&limit=5&type=fiat'
```

Response: `{"boosters": [["name", total], ...]}`

---

### Monday Summary

`GET /api/v1/analysis/monday-summary`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
curl 'http://localhost:3223/api/v1/analysis/monday-summary?show=lup'
```

Response:
```json
{
  "per-day-of-week": {"MONDAY": 354, "TUESDAY": 294, ...},
  "total-boosts": 1847,
  "total-weeks": 94,
  "weeks-with-monday": 84,
  "weeks-without-monday": 10,
  "weeks-without-monday-list": ["2024-W35", ...]
}
```

---

### Monthly Leaderboard

`GET /api/v1/analysis/monthly-leaderboard`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
curl 'http://localhost:3223/api/v1/analysis/monthly-leaderboard?show=twib'
```

Response: `{"2026-01": ["booster", total], "2026-02": ["booster", total], ...}`

---

### App Percentages

`GET /api/v1/analysis/app-percentages`

No params. Returns percentage of total boosts per app, sorted descending.

```bash
curl 'http://localhost:3223/api/v1/analysis/app-percentages'
```

Response: `{"apps": [["Fountain", 68.95], ["Podverse", 7.82], ...]}`

---

## Campaigns

A campaign is a **lens** on boosts already ingested, not a separate pipeline: a
tag, a window, and a goal. Attribution is one representation — the tag in the
message (the web boost form appends it server-side, so it cannot be dropped or
typo'd) or a boost on the campaign's episode. There is no metadata field and no
matcher language.

Definitions live in `resources/campaigns.edn` and ship with the service.

### `GET /api/v1/campaigns`

Index: what exists and whether it is open. `state` is `scheduled`, `active`, or
`closed`, derived from the window and the server clock.

```json
{"campaigns":[{"slug":"lup-700-brent","title":"Get Brent in the Studio for LUP 700","state":"active"}]}
```

### `GET /api/v1/campaigns/:slug`

The meter contract plus `recent`. An unknown slug is `404`. An unavailable
BTC/USD rate is `503`, and that body **carries no numbers** — a meter built on an
invented rate is worse than no meter.

```json
{
  "slug": "lup-700-brent",
  "generated_at": 1795211811,
  "title": "Get Brent in the Studio for LUP 700",
  "subtitle": "Studio time, travel, and a decent chair.",
  "tag": "#700club",
  "stretch": "Every dollar past the goal buys a better chair.",
  "goal": { "usd": 2000.0, "sats": 3386903 },
  "raised": { "usd": 2.87, "sats": 1477, "contributions": 3, "people": 2, "anonymous": 0 },
  "split": {
    "sats": { "usd": 0.87, "sats": 1477, "contributions": 2 },
    "fiat": { "usd": 2.0, "sats": 0, "contributions": 1 }
  },
  "percent": 0.1,
  "remaining_usd": 1997.13,
  "days_left": 52,
  "opens_at": 1795075200,
  "closes_at": 1799654400,
  "rate": { "btc_usd": 59051.0, "at": 1795211811, "source": "mempool.space" },
  "cta": { "label": "Boost with #700club", "url": "https://boost.jupiterbroadcasting.com/" },
  "recent": [{ "sats": 777, "usd": 0.46, "sender": "friendsofbangbus", "at": 1795211768 }]
}
```

What the numbers mean:

- `generated_at` is when this payload was emitted, not when a boost happened.
- `goal.sats` is the fiat goal converted at `rate`, so it drifts as BTC moves.
- `raised.usd` is the progress figure that matters, because the goal is a fiat
  obligation. It is converted at **emit time** with a single rate, so the sats
  portion revalues on every publish and can retreat when BTC falls. The fiat
  portion is fixed.
- `raised.sats` is the **sats rail only** — what arrived in sats, not the total
  expressed in sats. Once fiat is in the mix, `raised.sats` against `goal.sats`
  is not a fraction: a $2.00 boost adds dollars and zero sats. Use `raised.usd`
  against `goal.usd`, or use `split`.
- `stretch` is one line of over-goal copy from the campaign config. It is
  published whether or not the goal is met, so changing the joke never needs a
  frontend deploy; render it only when `raised.usd > goal.usd`. Empty when unset.
- `split` says how the money actually arrived, rail by rail. It reconciles
  exactly: `split.sats.usd + split.fiat.usd == raised.usd`, to the cent, because
  each part is rounded before they are summed. This is the object to report on.
- `percent` is one-decimal, so a sub-0.05% total legitimately rounds to `0.0`. A
  renderer should floor the fill width instead of showing an empty bar.
- `remaining_usd` is the publisher's own arithmetic; prefer it over subtracting.
- `recent` is newest first and capped at 20, and carries **names, not messages**:
  `sender`, `sats`, `usd`, `at`. Anonymous boosters appear as `"anonymous"`.
- Self-boosts (`chrislas`, `noblepayne`) are excluded by policy.

### `GET /api/campaign/:slug` (public)

Served by the web boost site's Pages Function from the artifact the scraper
publishes to R2 at `campaign/<slug>.json`. The scraper itself is never exposed;
the artifact is the only interface.

- `Cache-Control: public, max-age=20, stale-while-revalidate=120,
  stale-if-error=86400`, plus an `ETag`.
- Never `s-maxage` — a shared cache holding a stale meter would publish a number
  that is already wrong.
- A missing or unreadable artifact answers `503` with `no-store` and
  `Retry-After`, never an empty or zeroed meter.
- `HEAD` mirrors `GET` (status and headers, no body), so a probe cannot fall
  through to the app shell.

The page hides the card rather than rendering `$0` when the payload is absent,
malformed, or older than 30 minutes.

---

## Feed

### Get Boosts

`GET /api/v1/feed`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex (validated, ≤200 chars) |
| `podcast` | string | no | Exact podcast name filter |
| `since` | int | no | Epoch seconds — only boosts newer than this |
| `before_time` | int | no | Cursor time (exclusive) — pagination |
| `before_id` | string | no | Cursor identifier (≤256 chars) — pagination tie-break (preferred) |
| `before_index` | int | no | Legacy cursor index — deprecated, use `before_id` |
| `limit` | int | no | Max results (default 100, hard cap 200) |

Pagination: `before_time` + `before_id` defines exclusive cursor `(time, identifier)` sorted `time DESC, identifier ASC`. Server dedups by `content_id` else `identifier`. For same `time`, `before_id` disambiguates (replaces legacy `before_index` which only worked for LND `add_index`).

```bash
# First page
curl 'http://localhost:3223/api/v1/feed?show=lup&limit=5'
# Next page using last item's time+identifier
curl 'http://localhost:3223/api/v1/feed?show=lup&limit=5&before_time=1787750458&before_id=b-1'
```

Response: `[{"time": 1787750458, "sender": "wes", "sats": 1000, "app": "Fountain", "podcast": "LINUX Unplugged", "episode": "...", "message": "...", "index": 348800, "identifier": "348800", "content_id": "abc...", "fiat_cents": 0, "payment_rail": "", "fiat_currency": ""}, ...]`

### Get Podcasts

`GET /api/v1/feed/podcasts?show=lup`

Returns `{"podcasts": ["LINUX Unplugged", ...]}` sorted.

### CSV Export

`GET /feed.csv?show=lup&podcast=...&since=...&end=...`

Same filters as feed, no `limit` cap, deduped, sorted `time DESC, identifier ASC`. Returns `text/csv`.

### WebSocket Live Feed

`WS /ws/boosts` — subscribe to `boost-bus :boosts`. Each message is JSON from `ws/normalize-boost`:

```json
{"time": 1787750458, "sender": "wes", "sats": 1000, "app": "Fountain", "podcast": "...", "episode": "...", "message": "...", "identifier": "my-id", "content_id": "cid", "index": 123, "fiat_cents": 0, "payment_rail": "lightning", "fiat_currency": ""}
```

`identifier`/`content_id` are stable for client dedup (prefer over composite `time|sender|sats|podcast|message`). `index` is legacy LND `add_index`.

---

## Raw Datalog Proxy

For ad-hoc queries that don't have a dedicated endpoint. Accepts an EDN
Datalog query and executes it **read-only** against the DB.

`POST /api/v1/query`

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | string | yes | — | EDN Datalog query map `{:find [...] :where [...]}` |
| `params` | vector | no | `[]` | Bindings for `:in` vars |
| `timeout` | int | no | 15000 | Max ms (capped at 60000) |
| `limit` | int | no | 5000 | Max result rows (capped at 50000) |

```bash
curl -X POST http://localhost:3223/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{:find [?s (sum ?v)] :where [[?e :boostagram/value_sat_total ?v] [?e :boostagram/sender_name_normalized ?s]]}",
    "limit": 5
  }'
```

Response (success, HTTP 200):
```json
{"status": "ok", "results": [["senorsmile", 0], ["d3xbot", 13323], ...], "truncated": true, "elapsed_ms": 1183}
```

Response (error, HTTP 400/429):
```json
{"status": "error", "detail": "Query function or form not allowed: clojure.core/load-string"}
```

### Security: allowed functions only

The endpoint is **not** a general Clojure evaluator. Queries may only call
functions from datalevin's own built-in registry (see the "Query function
not allowed" error). This closes the RCE/file-read hole in datalevin 0.9.13's
embedded resolver. Specifically:

- **Allowed**: registry functions — `get-else`, `get-some`, `missing?`,
  `ground`, `str`, `count`, `re-matches`, `re-find`, `re-pattern`, `subs`,
  `namespace`, `type`, `get`, comparisons (`<` `<=` `>` `>=` `=`), arithmetic
  (`+` `-` `*` `/` `quot` `rem` `mod` `inc` `dec`), `like`/`not-like`,
  `in`/`not-in`, `fulltext`, collection fns (`vector` `list` `set` `hash-map`
  `contains?` `not-empty` `empty?`), etc.
- **Aggregates in `:find`**: `sum`, `count`, `count-distinct`, `avg`,
  `median`, `variance`, `stddev`, `min`, `max`, `distinct`, `rand`, `sample`.
- **Rejected** (HTTP 400): any other symbol (e.g. `load-string`, `slurp`,
  `eval`, `clojure.java.shell/sh`), **dot-forms** (`(.method ...)`), `apply`,
  rule bindings (`%`/`%%`), unknown query-map keys, queries over 64 KiB.
- `pull`/`pull-many` are **not** available through this endpoint.
- When the concurrency limit (3) is hit, returns HTTP 429 with `:code :busy`.

### Query-writing notes

- Use plain pattern clauses and avoid `get-else`/`get-some` over full-table
  scans — any per-row function over a big scan is far slower (~40× in our
  tests: 9.7s vs 58ms). Prefer ensuring attributes exist at write time
  (backfill), then query with plain pattern clauses. `get-some` is for
  "first attribute that's present across a list" (returns `[attr value]`),
  not a faster `get-else`.
- If your query has **no `$`-referencing built-in and no pattern clause**, it
  expects zero inputs and the proxy's auto-passed DB breaks it — add an
  explicit `:in [$]` or include a pattern clause.
- Time ranges are epoch seconds in **America/Los_Angeles**. Compute easily:
  `python3 -c "from datetime import datetime; print(int(datetime(2026,1,1).timestamp()))"`
- Attribute names (from the schema): `:boostagram/sender_name_normalized`,
  `:boostagram/value_sat_total`, `:boostagram/podcast`, `:boostagram/episode`,
  `:boostagram/message`, `:boostagram/app_name`, `:boostagram/type`
  (`:sat`/`:fiat`/`:member-free`), `:boostagram/amount_fiat_cents`,
  `:invoice/creation_date` (epoch seconds), `:scraper/source`, etc.

## Query Cookbook (real, tested queries)

```clojure
;; Top boosters by sats
{:find [?s (sum ?v)]
 :where [[?e :boostagram/value_sat_total ?v]
         [?e :boostagram/sender_name_normalized ?s]]}

;; Boost count by app
{:find [?a (count ?e)]
 :where [[?e :boostagram/app_name ?a]]}

;; Boosts (with messages) for one show
{:find [?s ?m]
 :where [[?e :boostagram/podcast "LINUX Unplugged"]
         [?e :boostagram/sender_name_normalized ?s]
         [?e :boostagram/message ?m]]}

;; Boosts in a time window (epoch seconds)
{:find [?s ?cd ?v]
 :where [[?e :boostagram/sender_name_normalized ?s]
         [?e :invoice/creation_date ?cd]
         [?e :boostagram/value_sat_total ?v]
         [(>= ?cd 1788000000)]]}

;; Boosts of a given type, filtered by regex on the podcast name.
;; NOTE: re-matches needs a Pattern — build it with re-pattern first.
{:find [(count-distinct ?s)]
 :where [[?e :boostagram/type :sat]
         [?e :boostagram/podcast ?p]
         [(re-pattern "(?i).*unplugged.*") ?pat]
         [(re-matches ?pat ?p)]
         [?e :boostagram/sender_name_normalized ?s]]}
```

> Note: `:params` come from a JSON array, so they can only be strings/numbers/
> booleans — you cannot pass a keyword via `params`. To filter by
> `:boostagram/type :sat`, either inline the keyword literal in the query
> (as above) or pass a string and compare against it.

## Tips

- **Time ranges** use epoch seconds in **America/Los_Angeles** timezone.
- Compute them easily: `python3 -c "from datetime import datetime; print(int(datetime(2026,1,1).timestamp()))"`
- The `show` param accepts either a slug name (`lup`, `twib`, etc.) or any regex pattern.
- All endpoints are read-only. The proxy cannot run transactions.