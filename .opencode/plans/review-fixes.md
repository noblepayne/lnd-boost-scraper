# Review-Fix Plan

## Goal
Address all findings from two sub-agent code reviews: one line-level (test correctness, edge cases, idiomatic Clojure) and one gestalt (coverage gaps, pipeline composition, sustainability).

## Approach
Each "compaction" session picks up from the todo list, works through a batch of items (fmt/lint after each), commits, and notes progress. Minimal context reload between sessions.

## Test Runner Decision: `cognitect-labs/test-runner`

**Chosen** over Kaocha. Rationale:
- Auto-discovers namespaces ending in `-test` — no more manually listing them in `deps.edn`
- Zero configuration beyond the dep itself (no `tests.edn` needed)
- Official Cognitect project, maintained by Sean Corfield, 307 ★
- Lightweight: just discovers and runs `clojure.test` — fits the project's modest size
- The project already follows the `-test` naming convention

Kaocha was considered: watch mode, fail-fast, profiling, pluggable reporters. Rejected as over-engineered for this project's needs. Can adopt later if CI demands it.

Migration: replace the explicit `main-opts` in `:test` alias with `cognitect.test-runner` dependency + `:exec-fn`. `clojure -X:test` becomes the invocation.

## Priority Order

### P0 — Must fix before moving on (breaks correctness)

| # | What | Files | Effort |
|---|------|-------|--------|
| 1 | **`quot` not `/` for msats→sats**: `(/ msats 1000)` returns a `Ratio` (e.g., `1/250`). Schema declares `:db.type/long`. All existing test values happen to be cleanly divisible. Use `(quot msats 1000)` instead. | `db.clj:230`, `db_test.clj` | 2m |
| 2 | **Empty `creation_dates` crash**: `(apply max [])` throws `ArithmeticException` when all records in a batch lack `:creation_date`. Guard with `(when (seq creation_dates) ...)`. | `core.clj:142` | 5m |
| 3 | **Test runner auto-discovery**: Replace explicit namespace list with `cognitect-labs/test-runner`. Add dep to `deps.edn`, switch to `:exec-fn`. | `deps.edn` | 5m |

### P1 — High-value fixes and missing coverage

| # | What | Files | Effort |
|---|------|-------|--------|
| 4 | **sort-report sort key**: Add `:invoice/created_at` to boundary test boost maps so `sort-by` is exercised. Currently all sort keys are `nil` → no-op. | `upstream_test.clj:254-318` | 5m |
| 5 | **Misleading test name + all-nil creation_date test**: Rename and add a case that exercises the crash path. Document behavior. | `core_test.clj` | 5m |
| 6 | **Non-divisible msats**: Add 1234 msats → 1 sats (integer division) assertion to confirm `quot` produces valid schema value. | `db_test.clj` | 2m |
| 7 | **`process-batch` composition test**: Realistic LND-shaped input → expected output. Catches ordering bugs in the 5-step pipeline. Single highest-value missing test. | New test in `db_test.clj` | 15m |
| 8 | **`decode-boost` test**: Known Base64 encoded boostagram → expected parsed map. Zero infrastructure. | New test in `db_test.clj` | 10m |
| 9 | **`flatten-paths` / `namespace-invoice-keys` tests**: Recursive shape transformers — nested maps, empty maps, nil values, separator char. | New test in `db_test.clj` | 10m |
| 10 | **Gate `api_test.clj`**: Skip when `TEST_BASE_URL` not set. Current hardcoded dev IP blocks CI. | `api_test.clj` | 5m |

### P2 — Cleanup and edge cases

| # | What | Files | Effort |
|---|------|-------|--------|
| 11 | **Remove duplicate test**: `test-remove-nil-vals-after-decode` is a copy of `test-remove-nil-vals`. | `db_test.clj` | 2m |
| 12 | **Normalize-name nil input test**: Document that `(str/trim nil)` → `""` so the fn doesn't crash. | `db_test.clj` | 2m |
| 13 | **Alby timezone offset**: Test `+02:00` offset in `created_at` string. | `db_test.clj` | 5m |

### P3 — Sustainability

| # | What | Files | Effort |
|---|------|-------|--------|
| 14 | **Add test conventions to AGENTS.md**: Conventions for mocking, pure fn testing, DB testing, adding new test namespaces. | `AGENTS.md` | 10m |
| 15 | **In-memory Datalevin for `add-boosts`**: End-to-end pipeline validation with `d/get-conn` + cleanup. | New test or separate file | 20m |

## Measurement

Each compaction session should be able to complete at least one P0 + two P1s.

After all items done, re-run both review agents to confirm exit criteria met.
