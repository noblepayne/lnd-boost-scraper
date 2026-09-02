# Datalevin Upgrade Plan — 0.9.13 → 1.0.2

Consulting notes from the 2026-09-01 query-API review session. Written while
upstream source and changelogs were fresh. Nothing here is urgent — the
zero-migration hop (Step 1) can happen anytime; the 1.x hop is a deliberate
maintenance window.

## Current state

| Thing | Value |
|---|---|
| Our version | `datalevin 0.9.13` (2024-11-09, per `deps.edn`) |
| Latest upstream | `1.0.2` (2026-08-11) |
| Java | openjdk 21.0.9 on nodecan — **meets 1.x minimum (21)** ✓ |
| Storage | embedded LMDB, three DBs under `cfg.dataDir`: `nodecan`, `jbnode`, `alby` |
| DB size | nodecan: ~360k max-eid, modest datom count — migration is minutes, not hours |
| API surface | tiny + conservative: `get-conn`, `db`, `q`, `transact`, `pull`, `pull-many`, `entity`, `datoms`, `close` |
| Schema | all scalar types (string/long/instant/keyword); **no** `:db.type/ref`, **no** tuples, **no** components |

The small API surface and ref-free scalar schema make this a low-risk upgrade.
The storage-format changes between 0.9.13 and 1.x don't touch anything we use.

## Why upgrade at all

1. **Security posture**: 1.x added `*resolver-mode*` with `:server-safe` —
   upstream's own mechanism restricting query functions to built-ins. Exactly
   what the query-proxy needs. (On 0.9.13 we must run our own allowlist
   walker; see query-proxy review.)
2. **`:db/udf`** — registered named functions callable from queries. The
   sanctioned way to do custom logic without eval.
3. **Performance**: VAE index removal (0.10.1), DLMDB storage with prefix
   compression (~40% smaller footprint), rewritten rule engine, cost-based
   planner improvements, `:order-by`/`:having`/arithmetic-over-aggregates.
4. **`get-some-else`** built-in (post-WIP releases) — the query-side answer to
   missing-attr defaults (though write-time normalization remains better).
5. 0.9.13 → 0.9.27 alone brings a year of bug fixes with **zero migration**.

## Release-by-release breaking changes (0.9.13 → 1.0.2)

### 0.9.14 … 0.9.27 (2024-11 → 2025-11) — all non-breaking
Same minor version ⇒ no migration expected. **Drop-in dep bump.** This is the
hop that reaches the auto-upgrade floor (0.9.27).

### 0.10.1 (2026-01-21) — the big break
- KV storage switched to **DLMDB** (counted DBIs, prefix compression). Storage
  format change ⇒ migration required.
- **VAE index removed** (irrelevant to us — no ref attrs).
- **Java 21 minimum** (we're on 21.0.9 ✓).
- Native deps now statically compiled and bundled — *fewer* NixOS dynamic-lib
  headaches, not more.
- `apply` added as a query function (our proxy allowlist must still reject it).
- Auto-upgrade from **0.9.27+** databases when opened by a newer version —
  downloads the old uberjar, streams logical data into a staging DB. Needs
  internet at migration time; causes downtime proportional to data size.

### 0.10.7 (2026-03) — noted, then reverted
WAL made default for Datalog stores. **0.10.16 reverted it** — embedded default
is `:wal? false` again. At 1.0.2 there is no WAL surprise; WAL is opt-in.

### 1.0.0 (2026-07-20)
- **Server mode** restricts query functions to built-ins + registered UDFs
  ("to prevent arbitrary code execution on server"). Embedded mode keeps host-var
  resolution, but `datalevin.query.resolve/*resolver-mode*` is a **dynamic var**
  — we can `(binding [*resolver-mode* :server-safe] (d/q ...))` ourselves.
- Composite tuple storage rewritten (migrated automatically). **We use no
  tuples** ⇒ no-op for us.
- Implicit schema gains `:db/created-at` / `:db/updated-at` / `:db/udf` —
  additive, harmless.
- `require-migration?` is true for 1.0.x ⇒ opening a pre-0.10 DB triggers the
  migration path.

### 1.0.1 / 1.0.2 (2026-08) — fixes only
Schema patch semantics (`d/schema` output safe to reuse as input), atomic
`update-schema`, top-k planner improvements. No action needed.

## Recommended upgrade path (two hops)

### Step 1 — 0.9.13 → 0.9.27 (anytime, zero migration)
1. Bump `deps.edn` to `0.9.27`.
2. Full test suite (`clojure -M:test`) — 114 tests green expected unchanged.
3. Deploy to nodecan; service opens the DBs normally. No migration runs.
4. Verify: entity counts per attribute match pre-deploy numbers; feed and
   report spot-checks; reconcile preview unchanged.

This alone gets a year of fixes and positions us on the auto-upgrade floor.

### Step 2 — 0.9.27 → 1.0.2 (deliberate maintenance window)
Two options:

**Option A — auto-migration (simplest):**
1. Stop the service (backup window).
2. **Back up all three DB dirs** (plain `cp -a` while stopped is fine; or
   `dtlv copy` which also compacts).
3. Bump deps to 1.0.2, rebuild, start service. First open auto-migrates each
   DB: downloads the 0.9.27 uberjar (needs internet on nodecan), streams data
   into a staging DB. Expect minutes for our data sizes; three DBs migrate
   sequentially at startup.
4. Verify (below), delete nothing until confident — keep backups.

**Option B — manual dump/load (most control, no internet needed at cutover):**
1. Backup as above.
2. With the 0.9.27 build: `dtlv -d <dir> -g -f dump-file dump` for each DB
   (dump format is version-independent).
3. Swap deps to 1.0.2, rebuild.
4. `dtlv -d <new-dir> -f dump-file -g load` into fresh dirs; point the service
   at them (or replace dirs).
5. Start, verify.

Option B also sidesteps any surprise from auto-migration downloading binaries
at service start — on NixOS we prefer deterministic startups, so **B is the
recommended default**; A is fine if internet-at-startup is acceptable.

### Post-upgrade code changes (both hops)
- **query-proxy**: bind `*resolver-mode* :server-safe` around `d/q` (replaces
  most of the allowlist walker's job; keep the walker as belt-and-suspenders or
  retire it — decide then).
- Nothing else. `get-conn`/`q`/`transact`/`pull`/`entity`/`datoms` signatures
  unchanged across the entire range.
- Optional niceties now available: `:order-by` in `:find`, `:having`,
  `get-some-else`, arithmetic over aggregates (simplifies several analysis
  queries that currently do client-side aggregation).

### Post-upgrade verification checklist
- [ ] Entity count per major attribute matches pre-upgrade (one `d/q` per attr:
      `(count ?e)` grouped, or `d/datoms` count on AVE).
- [ ] Feed: `/api/v1/feed` row count + first/last timestamps match pre-upgrade.
- [ ] Report page renders; ballers/fiat totals match pre-upgrade screenshot.
- [ ] Reconcile preview: same unmatched/manual-review counts.
- [ ] Query proxy: template queries run; allowlist rejects `load-string` probe.
- [ ] Tests green (114/761).

## Risks / notes

- **Downgrade impossible**: Datalevin never supports opening a newer-format DB
  with an older library. Backups are the only undo.
- **Rollback plan**: keep the 0.9.27 build in the Nix store (previous
  generation) + DB backups; `nixos-rebuild --rollback` restores service with
  pre-migration data from backup.
- **JVM opts**: our `--add-opens java.base/java.nio` etc. flags remain correct
  for 1.x (upstream's own libraries add the same).
- **The `d/q` input-arity quirk** (function-only queries expect zero inputs
  when no `$` is referenced) may behave differently post-rewrite — the API
  cookbook documents adding `:in [$]`; re-test during verification.
- **MCP server, JSON API, HA, vector/embedding features** exist in 1.x but are
  out of scope — note for future ideas (e.g., `:db/embedding true` on
  `:boostagram/message` would enable semantic topic search over boost messages
  — relevant to the Sunday "member topics" analysis).

## Sources
- Changelog: github.com/datalevin/datalevin/blob/master/CHANGELOG.md
- Upgrade doc: github.com/datalevin/datalevin/blob/master/doc/upgrade.md
- Resolver modes: src/datalevin/query/resolve.clj (`*resolver-mode*`)
- Built-in registry: src/datalevin/built_ins.clj (`query-fns`)
- Version constant: src/datalevin/constants.clj (`version`, `require-migration?`)
