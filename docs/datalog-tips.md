# Datalog & Datalevin — Tips, Tricks, and Traps

Living reference. **Everything marked ✅ was verified on this database (0.9.13)
with measurements; everything marked 📋 comes from the upstream changelog and
has not been exercised here yet.** Rule of thumb: if the engine can do it,
don't hand-roll it in Clojure.

---

## 1. Let the engine aggregate — don't materialize rows to sum them

**✅ Verified (the biggest win this project has had).**

`GET /api/v1/boosties` recomputed a leaderboard by pulling every matching
entity into Clojure. Streams are ~38k rows per window, so each request dragged
38k maps across the boundary and summed them by hand: **13.8s**.

Two changes, in order of impact:

1. **Aggregate in the query** — `[:find ?sender (count ?e) (sum ?sats)]` returns
   ~75 rows and does the work in the engine.
2. **A short TTL cache** for a read-only board endpoint (60s).

Result: **13.8s → 6.9s cold → 55ms warm.**

```clojure
;; ✅ do this
'[:find ?sender (count ?e) (sum ?sats)
  :in $ ?since ?until ?regex
  :where ...]

;; ❌ not this, when the row count is large
(->> (d/q all-rows-query ...) (group-by :sender) (map (fn [[k v]] (reduce + ...))))
```

**Heuristic:** if the result set you *return from the query* is small but the
input set is large, the engine should do the reduction.

Aggregates available: `count`, `sum`, `avg`, `median`, `variance`, `stddev`,
`min`, `max`, `distinct`, `count-distinct`, `rand`, `sample`.
(`count-distinct` is how we count unique boosters ✅.)

## 2. `get-else` is convenient and expensive

**✅ Measured on 359,100 entities:**

| Query shape | Time |
|---|---|
| plain pattern clause | **241 ms** |
| same + `(sum ?v)` aggregate | 1.44 s |
| same + `get-else` per row | **9.7 s** |

`get-else` is a per-entity index probe — ~27µs/row. It is fine for small,
filtered result sets; it is a trap over a full scan.

**What to do instead:**
- **Normalize at write time** so the attribute is always present, then use a
  plain pattern (this is the real fix; it also removes branching from queries).
- Run **two cheap queries** (has-attr / `missing?`) and merge in Clojure.
- From 1.x, `get-some-else` covers "first present attr, else default" 📋.

**`get-else` ≠ `get-some`.** They are different tools, not a fast/slow pair:

| Function | Signature | Returns | Use for |
|---|---|---|---|
| `get-else` | `(get-else $ ?e :attr default)` | the value or the default | default for **one** missing attr |
| `get-some` | `(get-some $ ?e :a :b :c)` | `[attr value]` of the first present attr | prefer one attr, fall back to another |
| `get-some-else` | 1.x only 📋 | first present, else default | the combination |

## 3. `pull` in `:find` — and the row-shape gotcha

**✅ Verified.** One pass over the attributes instead of a `get-else` per
attribute:

```clojure
'[:find (pull ?e [:db/id :boostagram/action :boostagram/podcast])
  :in $ ...
  :where ...]
```

**⚠️ Trap:** with `pull` in `:find`, **each row is a one-element vector
containing the map** — `([{:db/id 1, ...}])`. Destructure: `(fn [[m]] ...)`.
Forgetting this produced silently-nil fields (and a dedupe that collapsed every
row into one) during the contributions build.

**⚠️ Trap:** `pull` only surfaces **schema-declared** attributes. For
undeclared ones (`:invoice/settled`, `:invoice/value`, `:invoice/settle_date`)
use `d/entity`, which returns everything:

```clojure
;; ✅ read an undeclared attr
(into {} (d/entity db e))        ; then (:invoice/settled m)
```

Also worth requesting `:db/id` explicitly — it is not included by default, and
without it you cannot build stable identity/dedupe keys.

## 4. Indexes: patterns beat predicates

**✅ Observed.** Equality and pattern clauses use the indexes
(`[?e :boostagram/type :sat]`, `[?e :invoice/identifier "342724"]`); regex and
arbitrary predicates are applied per row:

```clojure
[?e :boostagram/podcast ?podcast]
[(re-matches ?regex ?podcast) _]     ; scans — unavoidable for free-text match
```

Practical consequences:
- Filter as much as possible with **equality/range patterns first**, predicates
  last.
- A regex over a bounded window is fine; a regex over all time is a scan.
- `[(contains? ?set ?action)]` with a set parameter works and is cheap ✅, but a
  literal `[?e :boostagram/action "boost"]` is better still — it uses the index.

## 5. Query input arity is inferred from the form

**✅ Verified.** Datalevin infers expected inputs from the query body. A query
that never references `$` (e.g. only function clauses) expects **zero** inputs;
passing the DB then fails with `Extra inputs passed, expected: [], got: 1`.
Pattern clauses imply `$`. If you see that error, add an explicit `:in [$]` or
include a pattern clause.

## 6. Identity attributes give you upserts for free

**✅ Used throughout.** `:db/unique :db.unique/identity` on
`:invoice/identifier` and `:boostagram/zaprite_order_id` means transacting an
entity with an existing identity **merges onto it** rather than duplicating.
That is what makes the reconcile backfill and the operator `resolve` path
idempotent — no "does it exist?" check needed.

```clojure
(d/transact! conn [{:invoice/identifier "342724" :boostagram/value_sat_total 2222}])
;; second identical transact updates the same entity
```

Lookup refs then work directly:
`(d/entity (d/db conn) [:invoice/identifier "342724"])`.

## 7. Time fields: prefer the long

**✅ Verified.** This schema carries both:
- `:invoice/creation_date` — **long epoch seconds** (queries, ranges, ports)
- `:invoice/created_at` — `:db.type/instant` (Date objects)

Ranges on the long are simple and portable:
`[(>= ?cd ?since)] [(<= ?cd ?until)]`. Use the instant only when you need
date arithmetic in the engine. Mixing them up is a classic silent-empty-result
bug.

## 8. Persisted-but-undeclared attributes are a feature

**✅ Verified and load-bearing.** Datalevin persists attributes that are not in
the schema. Two consequences we exploit:

- The generic boostagram flattener stores **every** field it sees, including
  `:boostagram/sender_id` — which turned out to be present on **353,204 rows**
  and is the strongest identity signal we have, despite nothing ever reading it.
- Reconcile reads `:invoice/settled` / `:invoice/value` / `:invoice/settle_date`
  via `d/entity`.

Caveat: undeclared attributes are not schema-typed or indexed the way declared
ones are, and `pull` will not return them. Declare what you query often.

## 9. Ingest-side habits that make queries cheap

**✅ Learned the hard way in this project.**

- **Drop empty values at ingest** (`remove-empty-vals`) — a missing message is
  absent, not `""`. Queries then test presence, not emptiness. (It also means a
  "blank message" write leaves no attribute at all.)
- **Normalize once, at write time** (`sender_name_normalized`) so every query
  doesn't repeat the work.
- **Type your rows at ingest** (`:boostagram/type` → `:sat` / `:fiat` /
  `:member-free`). Multi-typed money in one attribute is where double-counting
  comes from.
- **Keep the raw payload for valuable rows.** The boostagram raw JSON is decoded
  and discarded; keeping it for `action = "boost"` (~6.3k rows) would let future
  heuristics see fields we never flattened.

## 10. Security: 0.9.13's resolver is not a sandbox

**✅ Verified live (this was an RCE).** 0.9.13 resolves function symbols as:

```clojure
(or (get built-ins/query-fns f)      ; registry — tried first
    (context-resolve-val context f)  ; :in-bound values
    (dot-form f)                     ; (.method obj …) raw reflection
    (resolve-sym f))                 ; (resolve sym) — ANY var in ANY namespace
```

So a query can call `load-string`, `slurp`, or any loaded var, and dot-forms
bypass the registry entirely. **The registry is decoration unless you enforce
it.** Our `query-proxy` walks the parsed query and allows only registry symbols
(plus refuses dot-forms, `apply`, rule bindings) — the 0.9.13 equivalent of
1.x's `:server-safe` mode.

1.x ships `datalevin.query.resolve/*resolver-mode*` as a dynamic var: bind it to
`:server-safe` and only registry functions + registered `:db/udf` are callable 📋.

## 11. What 1.x buys us (beyond the fixes)

From the upgrade plan (`docs/datalevin-upgrade-plan.md`), 📋 changelog-sourced:

- **`:server-safe` resolver mode** and **`:db/udf`** — registered functions, the
  sanctioned way to extend queries.
- **`get-some-else`**, **`:order-by`** (including column indices into
  aggregation results), **`:having`**, **arithmetic over aggregates**
  (e.g. `(+ (sum ?x) (sum ?y))`).
- Cost-based planner work, top-k push-down, parallel index scans.
- WAL (opt-in), VAE removal, prefix compression (~40% smaller DB).
- Nested `q` as a query function, `apply` as a query function (still denied by
  our proxy on purpose).

Mind the versioning rule: **0.9.13 → 0.9.27 is a same-minor, zero-migration
bump; 0.9.27 → 1.x needs a data migration.** Auto-migration only works from
0.9.27+ databases, which is why the two-hop plan exists.

---

## Checklist before writing a query here

1. Is the **window bounded**? (unbounded scans are the default failure mode)
2. Can the **engine** do the aggregation instead of Clojure?
3. Can a **pattern/equality clause** replace a predicate or `get-else`?
4. Do I need `:db/id` in the `pull` list?
5. Is the attribute **declared**, or do I need `d/entity`?
6. Am I comparing a **long epoch** where I think I'm comparing an instant?
7. If this runs on an HTTP path, does it need a **cache**?
