# Boost Data Query API — Briefing for an AI Agent

You are being given read-only access to a podcast-boost analytics database. It
contains **boostagrams** — value-for-value tips listeners send to shows — with
the sender's message, the show/episode, the amount, and when it happened.

You can query it over HTTP to answer questions like:
- "Which shows/topics get boosted the most?"
- "Who are the top boosters?"
- "How does engagement vary over time?"

This briefing teaches you the one endpoint you need, the rules you must follow,
and proven queries. Everything here is verified to work against the live
service.

---

## 1. The one endpoint

```
POST /api/v1/query
Content-Type: application/json
```

Request body (JSON) with an EDN Datalog query as a **string**:

```json
{
  "query": "{:find [?sender (sum ?v)] :where [[?e :boostagram/value_sat_total ?v] [?e :boostagram/sender_name_normalized ?sender]]}",
  "limit": 20,
  "timeout": 15000
}
```

- `query` — EDN Datalog query map (required). Must have `:find` and `:where`.
- `limit` — max result rows (default 5000, hard cap 50000).
- `timeout` — max ms (default 15000, hard cap 60000).
- `params` — optional vector for `:in` bindings (rarely needed).

### Response — success (HTTP 200)

```json
{
  "status": "ok",
  "results": [["alice", 150000], ["bob", 42000]],
  "truncated": true,
  "elapsed_ms": 1183
}
```

`results` is a list of rows; each row is a list of values matching the `:find`
spec in order. `truncated: true` means more rows exist than returned.

### Response — error (HTTP 400 / 429)

```json
{"status": "error", "detail": "Query function or form not allowed: clojure.core/load-string"}
```

`detail` explains the problem. HTTP **429** means the server is busy — wait a
moment and retry. Always read `status` before using `results`.

---

## 2. The data model (attributes)

Boosts live on entities. Key attributes (all `?e`-prefixed queries start from
an entity):

| Attribute | Type | Notes |
|---|---|---|
| `:boostagram/sender_name_normalized` | string | sender, lowercased/trimmed |
| `:boostagram/value_sat_total` | long | amount in sats |
| `:boostagram/value_msat_total` | long | raw amount in msats |
| `:boostagram/podcast` | string | show name (e.g. "LINUX Unplugged") |
| `:boostagram/episode` | string | episode name |
| `:boostagram/podcast_slug` | string | short slug (`lup`, `twib`, `launch`, `ssh`, `coder`) |
| `:boostagram/message` | string | the listener's text message |
| `:boostagram/app_name` | string | client app (Fountain, Podverse, ...) |
| `:boostagram/type` | keyword | `:sat`, `:fiat`, or `:member-free` |
| `:boostagram/action` | string | `"boost"` or `"stream"` |
| `:boostagram/amount_fiat_cents` | long | fiat amount in cents (fiat boosts) |
| `:scraper/source` | string | `alby`, `JB`, `nodecan`, `zaprite`, `r2-member` |
| `:invoice/creation_date` | long | **epoch seconds** — use for time filtering |

Shows are matched by **slug** or **regex** on the podcast name. Slugs:
`lup`=LINUX Unplugged, `twib`=This Week in Bitcoin, `launch`=The Launch,
`ssh`=Self-Hosted, `coder`=Coder Radio, `all`=everything.

**Time** is epoch seconds in **America/Los_Angeles**. Compute boundaries with:
`python3 -c "from datetime import datetime; print(int(datetime(2026,1,1).timestamp()))"`

---

## 3. Rules you MUST follow (the API is deliberately locked down)

This endpoint is **not** a general Clojure evaluator. It only runs Datalevin
Datalog with a fixed set of allowed functions. Violations return a `400`.

1. **Query is a map** with `:find` and `:where`. Keep it simple.
2. **Allowed functions** (use these only): `get-else`, `get-some`, `missing?`,
   `ground`, `str`, `count`, `re-matches`, `re-find`, `re-pattern`, `subs`,
   `namespace`, `type`, `get`, comparisons (`<` `<=` `>` `>=` `=`), arithmetic
   (`+` `-` `*` `/` `quot` `rem` `mod` `inc` `dec`), `like`/`not-like`,
   `in`/`not-in`, `fulltext`, `vector`, `list`, `set`, `hash-map`,
   `contains?`, `not-empty`, `empty?`, `range`.
3. **Aggregates in `:find`**: `sum`, `count`, `count-distinct`, `avg`,
   `median`, `variance`, `stddev`, `min`, `max`, `distinct`, `rand`, `sample`.
4. **Forbidden** (400): anything else — especially `load-string`, `slurp`,
   `eval`, `clojure.java.shell/*` — plus **dot-forms** (`.method`), `apply`,
   rule bindings (`%`/`%%`), and `pull`. Don't try to work around this; it's
   a security boundary.
5. **`re-matches` needs a Pattern, not a string.** Build it first with
   `re-pattern`:
   ```clojure
   [(re-pattern "(?i).*launch.*") ?pat]
   [(re-matches ?pat ?p)]
   ```
6. **Keywords can't come through `params`** (JSON has no keywords). Inline
   keyword literals directly in the query (e.g. `:boostagram/type :sat`).
7. **Avoid `get-else`/`get-some` over full-table scans** — it's ~40× slower
   than a plain pattern clause (9.7s vs 58ms measured). Prefer querying
   attributes that exist, or run two cheap queries and merge.
8. If you need only a few rows, set a small `limit` — it's faster.

---

## 4. Cookbook — proven queries

```clojure
;; Top boosters by total sats
{:find [?s (sum ?v)]
 :where [[?e :boostagram/value_sat_total ?v]
         [?e :boostagram/sender_name_normalized ?s]]}

;; Boost count by app
{:find [?a (count ?e)]
 :where [[?e :boostagram/app_name ?a]]}

;; Boosts for ONE show, with messages (topic analysis)
{:find [?s ?msg ?v]
 :where [[?e :boostagram/podcast ?p]
         [(re-pattern "(?i).*launch.*") ?pat]
         [(re-matches ?pat ?p)]
         [?e :boostagram/sender_name_normalized ?s]
         [?e :boostagram/message ?msg]
         [?e :boostagram/value_sat_total ?v]]}

;; Boosts in a time window (epoch seconds, LA timezone)
{:find [?s ?cd ?v]
 :where [[?e :boostagram/sender_name_normalized ?s]
         [?e :invoice/creation_date ?cd]
         [?e :boostagram/value_sat_total ?v]
         [(>= ?cd 1788000000)]]}

;; Distinct sat-boosters for a show (count-distinct)
{:find [(count-distinct ?s)]
 :where [[?e :boostagram/type :sat]
         [?e :boostagram/podcast ?p]
         [(re-pattern "(?i).*launch.*") ?pat]
         [(re-matches ?pat ?p)]
         [?e :boostagram/sender_name_normalized ?s]]}
```

---

## 5. How to answer "which topics get boosted most" (methodology)

The API returns **raw** boosts — messages + episode + sats. Topics are not
pre-labeled; **you** must classify them. This works well:

1. **Pull the universe** for the show of interest (all boosts: sender, message,
   sats, episode, timestamp). Paginate with `limit` if large.
2. **Use the episode title as the topic prior** — group boosts by episode;
   episode titles usually name the topic/segment.
3. **Classify each message** into a topic or theme. Separate **signal**
   (names a topic: "loved the AI segment", "privacy bit was great") from
   **noise** ("first boost!", "great show").
4. **Aggregate two ways**: by **count** (how often a topic gets a boost) and
   by **total sats** (how much value a topic attracts). Report both — a topic
   may get few but large boosts.
5. **Quote evidence** — include real message snippets so the ranking is
   auditable, not vibes.
6. **Caveat**: not every boost names a topic; generic praise is common. State
   the share of "no topic/noise" so the reader knows the signal/coverage.

---

## 6. Example investigation walkthrough

**Question**: What topics on The Launch get boosted most?

**Step 1 — pull the data** (all Launch boosts with message + sats + episode):

```json
{
  "query": "{:find [?ep ?s ?msg ?v] :where [[?e :boostagram/podcast ?p] [(re-pattern \"(?i).*launch.*\") ?pat] [(re-matches ?pat ?p)] [?e :boostagram/episode ?ep] [?e :boostagram/sender_name_normalized ?s] [?e :boostagram/message ?msg] [?e :boostagram/value_sat_total ?v]]}",
  "limit": 5000,
  "timeout": 60000
}
```

**Step 2 — read the rows.** Each row is `[episode, sender, message, sats]`.

**Step 3 — classify.** Group messages into topics; tally count and sats per
topic; pull sample quotes per topic.

**Step 4 — output** a ranked table: `| # | Topic | Boosts | Total sats | Sample quote |`.

---

## 7. Good practices

- Always check `status` and `truncated` before trusting `results`.
- If you get a `400` with "not allowed", your query used a forbidden function
  or form — rewrite with the allowed set above.
- If you get `429`, back off and retry once.
- Ask one focused question at a time; the data rewards iterative querying.
