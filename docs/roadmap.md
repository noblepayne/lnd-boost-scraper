# Roadmap & Committed Follow-ups

Standing list of things we have **decided to do** but not yet done, so they
survive context loss. Each item says *why* it matters, not just what it is.
Specs live in `docs/`; this file is the index of commitments.

Last reviewed: 2026-11-19.

---

## Committed — do these

### 1. Move feed and reports onto the accounting core ⏳ NOT STARTED
**Why:** `contributions.clj` is now the single definition of a counted value
movement (payment-identity dedupe, integer sats/cents, policy). Boosties was
rebuilt on it and the sats total reproduced **exactly** (14,165,036) while 225
duplicate/non-boost records fell away. `feed.clj` and `reports.clj` still carry
their own query paths, so they can drift from the meter again — exactly the
failure the core exists to prevent.

**How:** refactor each onto `contributions` **only after** proving equality on
fixed windows with golden tests (the same method used for Boosties).

**Agreed:** 2026-11-19.

### 2. `GET /api/v1/schema` — attribute introspection ✅ DONE 2026-11-19
**Why it mattered:** a "smart bot" querying the API had to read `.clj` files to
learn attribute names, types, and shapes. A schema endpoint makes Datalog
queries writable from documentation alone.

**Shipped:** `GET /api/v1/schema` — names, declared flag, types, plus opt-in
`?examples=true` / `?counts=true` and `?q=` filtering. It immediately surfaced
**58 real, queryable, undocumented attributes** (`:boostagram/boost_link` on
313k rows, `:boostagram/name` on 377k, `:boostagram/app_version`, `:feedID`,
`:guid`, …) — proof that the "undeclared attributes are invisible" gap was
costing us.

Implementation note for the future: Datalevin auto-registers every attribute it
has seen, so `d/schema` is a complete, in-memory index of the data model.
Discovery needs no datom sampling (an earlier sampling version cost 44s/call;
this costs ~0.15s).

### 3. Campaigns & goals — P1 ⏳ NOT STARTED
See `docs/campaign-goal-spec.md`. Model + composable matcher + internal
`GET /api/v1/campaigns/:slug`. First campaign: *Get Brent to the Studio for
LUP 700*.

### 4. Identity — deterministic passes + exposure ⏳ NOT STARTED
See `docs/campaign-goal-spec.md` §3. Passes 0–2 (normalization, `sender_id`
edges, conservative auto-merge) plus the review-queue endpoints. Fixes split
handles in today's leaderboards (`thedudeabides` / `thedudeabides@fountain.fm`).

### 5. Datalevin 0.9.13 → 1.0.2 ⏳ PLANNED
See `docs/datalevin-upgrade-plan.md`. Two hops; unlocks `:server-safe` resolver
mode, `get-some-else`, `:db/udf`, `:having`/`:order-by`, planner + storage wins.

---

## Decided (so they are not re-litigated)

### Campaigns are a **lens**, not a ring-fence — AGREED 2026-11-19
Campaign-tagged boosts are ordinary show support that *also* count toward the
goal. No separate payment path, no split accounting. Ring-fencing (an earmarked
checkout) is only justified if a fundraiser genuinely requires earmarked money,
and would be a new decision, not a default.

### Publish **artifacts**, never expose the process — AGREED 2026-11-19
The scraper carries a query API and reconcile routes. Public meters get a static
JSON artifact (R2 → static page), never a public tunnel to the app. If live data
is ever needed, a proxy (showfactory) reads the internal API.

### Campaign rules — AGREED 2026-11-19
- **Attribution:** `#700club` tag **or** episode 700. Hashtag covers the
  pre-episode run (694–699); the finale counts by episode.
- **What counts:** sats boosts + fiat boosts (reported separately). Member-free
  boosts add contributors, not value. **Streams excluded** (policy, per show).
- **Target:** USD cents canonical; sats derived at snapshot rate and labelled.
- **Publishing:** static JSON artifact → R2 → static page. Never the scraper.
- **Identity:** deterministic passes + exposure endpoints ship with campaigns;
  review queue is continuous, later.

### The anonymity bucket is not a person — AGREED 2026-11-19
`anonymous` is one string used by many people: counted in totals, never ranked,
reported as its own line. Implemented as `handle-class` in the core.

### Boosties window is the current year in the show's timezone — AGREED 2026-11-19
`since` = 2026-01-01 00:00 America/Los_Angeles (08:00Z), inclusive.

---

## Watchlist — decided later, may need revisiting

- **Ballers inclusion for fiat-paid-via-lightning boosts.** Real sats recorded
  only on the bare LND invoice (which carries `value_sat_total 0`), so they are
  invisible to sats totals. Needs a deliberate policy decision + a re-measure.
  See dev-log 2026-08-29.
- **Anon invoice 323627** (2,222 sats) — still in `manual-review` by choice.
  Options in `docs/manual-review-guide.md`.
- **Identity ambiguity that cannot be solved:** one human may legitimately span
  several wallets/nodes; different `sender_id`s prove nothing. Reviewed merges
  only, never guesses.
