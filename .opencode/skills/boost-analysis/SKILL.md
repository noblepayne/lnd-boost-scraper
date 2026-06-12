---
name: boost-analysis
description: >
  Analyze boost data from the lnd-boost-scraper Datalevin database. Use when
  the user asks "who was the top booster for X show in Y month", "how often does
  show Z get boosts on Monday", "run a boost analysis", "make a boost report",
  or any ad-hoc question about boostagram/sender/value patterns. Also triggers
  on "data analysis", "run some queries", or "check the numbers" in the context
  of this project.
compatibility: clojure, nrepl
metadata:
  author: wes
  version: "2.0"
---

# Boost Analysis

Run ad-hoc Datalog queries against the boost scraper database to answer questions
about boost patterns, top boosters, and temporal distribution.

## Prerequisites

- An nREPL connection to the project (port in `.nrepl-port`)
- The project is loaded and compiled (`clojure-dev_clojure_eval` works)
- The canonical DB exists at `/dev/shm/nodecan`

## Workflow

### 1. Connect to the Datalevin DB

```clojure
(require '[datalevin.core :as d]
         '[boost-scraper.db :as db]
         '[boost-scraper.analysis :as analysis])

(def conn (d/get-conn "/dev/shm/nodecan" db/schema))
```

`conn` is an atom — you def it once and reuse across queries.

### 2. Pick the Show

The `analysis.clj` namespace provides convenience regexes:
- `analysis/lup-regex` — LINUX Unplugged
- `analysis/twib-regex` — This Week in Bitcoin
- `analysis/launch-regex` — The Launch
- `analysis/all-regex` — matches everything (`".*"`)

Or compile your own for any pattern:
```clojure
(def ssh-regex (re-pattern "(?i).*hosted.*"))
```

### 3. Run Queries

**Top N boosters for a show in a time range:**
```clojure
(analysis/top-boosters conn analysis/lup-regex
                       (core/->epoch #inst "2026-04-01T07:00:00Z")
                       (core/->epoch #inst "2026-05-01T06:59:00Z")
                       5)  ;; top 5; nil = no limit
```

Optional boost-type filter (nil = all):
```clojure
(analysis/top-boosters conn analysis/lup-regex start end 5 :fiat)
(analysis/top-boosters conn analysis/lup-regex start end 5 :sat)
(analysis/top-boosters conn analysis/lup-regex start end 5 :member-free)
```

**Day-of-week engagement:**
```clojure
(analysis/boost-counts-by-day-of-week conn analysis/twib-regex)
;; => {MONDAY 136, TUESDAY 191, WEDNESDAY 542, ...}

(analysis/boost-counts-by-day-of-week conn analysis/twib-regex :sat)
(analysis/boost-counts-by-day-of-week conn analysis/twib-regex :fiat)
```

**Monday/frequency analysis:**
```clojure
(analysis/monday-boost-summary conn analysis/launch-regex)
;; => {:per-day-of-week {...} :total-weeks 65 :weeks-with-monday 25 ...}

(analysis/print-monday-summary conn analysis/lup-regex)
;; Pretty-printed output

(analysis/print-monday-summary conn analysis/lup-regex :fiat)
```

**Per-month leaderboard (all time):**
```clojure
(analysis/top-booster-per-month conn analysis/lup-regex)
;; => {"2026-01" ["sender" total] "2026-02" ["sender" total] ...}

(analysis/top-booster-per-month conn analysis/lup-regex :fiat)
```

**App distribution:**
```clojure
(analysis/app-percentages conn)
;; => [["Fountain" 65.3] ["Podverse" 20.1] ["Alby" 14.6]]
```

### 4. Timezone & Epoch Bounds

Always `America/Los_Angeles`. April is PDT (UTC-7). Compute month bounds:

| Month | Start (LA midnight) | End (LA 23:59:59) |
|-------|---------------------|--------------------|
| April 2026 | `#inst "2026-04-01T07:00:00Z"` | `#inst "2026-05-01T06:59:00Z"` |

Use `(core/->epoch #inst "...")` to convert to epoch seconds for Datalog queries.

### 5. HTTP API Endpoints

When the server is running, these endpoints are available:

**Analysis endpoints** (call analysis.clj directly):
- `GET /api/v1/analysis/top-boosters?show=lup&start=EPOCH&end=EPOCH&limit=5&type=sat`
- `GET /api/v1/analysis/dow?show=twib&type=fiat`
- `GET /api/v1/analysis/monday?show=launch&type=sat`
- `GET /api/v1/analysis/monthly?show=lup&type=fiat`
- `GET /api/v1/analysis/apps`

**Raw Datalog proxy** (query the DB directly):
```
POST /api/v1/query
Content-Type: application/json

{"query": "{:find [?s (sum ?v)] :where [[?e :boostagram/sender_name_normalized ?s] [?e :boostagram/value_sat_total ?v]]}",
 "timeout": 15000,
 "limit": 5000}
```

**Query templates** (pre-defined queries):
- `GET /api/v1/templates` — list available templates
- `GET /api/v1/templates/top-boosters?show=lup&limit=5` — run a template

### 6. Build a Report

**Markdown** — top 5 per show in a table, Monday frequency in a summary table.

**HTML for Slack** — standalone file with inline styles:
- Red `h2` headers (`#c0392b`)
- `<ol>` for ranked lists
- `<hr>` between sections
- Grey pull-out boxes: `<p style="background: #f0f0f0; padding: .4em .8em; border-left: 3px solid #888;">`
- Source line at bottom in grey
- No external deps, no `<style>` block

Report files go in `reports/` (gitignored, private). See `reports/april-2026-boost-report.md` and `reports/april-2026-boost-report.html` for working examples.

### 7. When Done

```clojure
(d/close conn)
```

## Function Signatures

All functions accept optional `boost-type` parameter:
- `boost-type`: `nil` (all), `:sat`, `:fiat`, `:member-free`

```clojure
(top-boosters conn show-regex start end)
(top-boosters conn show-regex start end n)
(top-boosters conn show-regex start end n boost-type)

(boost-counts-by-day-of-week conn regex)
(boost-counts-by-day-of-week conn regex boost-type)

(monday-boost-summary conn regex)
(monday-boost-summary conn regex boost-type)

(print-monday-summary conn regex)
(print-monday-summary conn regex boost-type)

(top-booster-per-month conn regex)
(top-booster-per-month conn regex boost-type)

(app-percentages conn)
```

## Important Rules

- **Never modify existing source files** — analysis queries go in REPL eval or new files only
- **Use `sender_name_normalized`**, not raw `sender_name`
- **Match both podcast AND episode** using `(or [(re-matches ...)] ...)` — a boost's episode might match even when the podcast field doesn't
- **Use `get-else`** for optional fields: `[(get-else $ ?e :boostagram/episode "Unknown Episode") ?episode]`
- **Filter by `:boostagram/action "boost"`** unless specifically analyzing streams
- **Timezone always `America/Los_Angeles`**

## Reference

- Schema details: `src/boost_scraper/db.clj` (the `schema` map)
- Show registry: `src/boost_scraper/shows.clj` (the `shows` sorted-map)
- Existing queries to learn from: `src/boost_scraper/boosties.clj`, `src/boost_scraper/reports.clj`
- Analysis functions: `src/boost_scraper/analysis.clj`
- Query proxy: `src/boost_scraper/query_proxy.clj`
- HTTP routes: `src/boost_scraper/web.clj`
- Query templates: `resources/query_templates.edn`
- Tests: `test/boost_scraper/analysis_test.clj`
- Project config: `.opencode/AGENTS.md`
