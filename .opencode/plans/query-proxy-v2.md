# Boost Scraper Query Proxy — Implementation Tracker

## Goal
Expose the boost scraper's Datalevin database to clients via a three-tier architecture: canned REST endpoints, a raw Datalog proxy, and EDN query templates.

## Status: COMPLETE

### Phase 1: Rewrite analysis.clj ✅
- Added `all-regex` for universal matching
- Created dynamic query builders: `or-clause`, `base-in`, `base-params`, `build-sender-totals-query`, `build-monthly-query`
- All public functions now accept `boost-type` param
- Added `app-percentages` function
- Fixed `(take nil ...)` NPE in `top-boosters`
- Added `:with [?e]` to `boost-timestamps` to avoid duplicate timestamp collapse
- Removed `core.clj` dependency to break cyclic load dependency
- Note: slug matching removed — Datalevin's `or` clause requires all branches to share same free vars

### Phase 2: Create query_proxy.clj ✅
- `safe-read-edn`: EDN parsing with `{:ok parsed}` / `{:error :detail}` wrapper
- `validate-query`: checks for `:find` and `:where` keys
- `run-query`: executes with timeout via `deref`/`future`
- `execute-query`: full pipeline with timeout/limit guards, returns `{:status :ok :results [...] :truncated bool :elapsed_ms N}`
- Read-only by construction (never imports `d/transact!`)

### Phase 3: Add routes to web.clj ✅
- New requires: `analysis`, `query-proxy`, `clojure.edn`
- 7 new route groups:
  - `GET /api/v1/analysis/top-boosters`
  - `GET /api/v1/analysis/dow`
  - `GET /api/v1/analysis/monday`
  - `GET /api/v1/analysis/monthly`
  - `GET /api/v1/analysis/apps`
  - `POST /api/v1/query` (raw Datalog proxy)
  - `GET /api/v1/templates` and `GET /api/v1/templates/:name`

### Phase 4: Create query_templates.edn ✅
- 5 templates: `top-boosters`, `boost-counts-by-dow`, `fiat-breakdown-by-source`, `monday-summary`, `monthly-leaderboard`
- Pure EDN data (no Clojure reader macros)
- Loads via `delay` at startup

### Phase 5: Write analysis_test.clj ✅
- 17 new tests, 488 assertions total across 77 tests
- Fresh Datalevin DB per test via `:each` fixture
- Tests: safe-read-edn, execute-query, top-boosters (basic/limit/fiat/time-range), DOW, monday-summary, monthly-leaderboard, app-percentages, empty-DB

### Phase 6: Update SKILL.md ✅
- Updated function signatures with `boost-type` and `slug` params
- Documented HTTP API endpoints
- Documented query proxy and template endpoints
- Added function signature reference table
- Updated version to 2.0

## Files Modified
- `src/boost_scraper/analysis.clj` — dynamic query builders, type/slug params
- `src/boost_scraper/web.clj` — new routes
- `.opencode/skills/boost-analysis/SKILL.md` — updated docs

## Files Created
- `src/boost_scraper/query_proxy.clj` — safe Datalog proxy
- `resources/query_templates.edn` — 5 query templates
- `test/boost_scraper/analysis_test.clj` — 17 new tests

## Key Decisions
- Dynamic query building via `case` dispatch instead of form duplication
- `safe-read-edn` returns `{:ok parsed}` wrapper (caller must destructure)
- `(take nil ...)` NPE workaround in `top-boosters`
- `:with [?e]` in `boost-timestamps` to avoid duplicate timestamp collapse
- Removed `core.clj` dependency from `analysis.clj` to break cyclic load
- Fresh DB per test via `:each` fixture with `d/close` before cleanup
