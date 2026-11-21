# Dev Log — Zaprite Orphan Reconciliation

Chronological record of decisions and their reasons, kept alongside
`docs/orphan-reconcile-spec.md` (the what) and `.clj` code + tests (the concrete how).

Format: date · decision · why.

---

## 2026-11-20 · Review doctrine corrected: eyes on the files beat a different model

### What happened

Before the campaign-meter rollout, a review was dispatched to a deliberately
*different* model than the author (`beam-free` vs `deepseek-v4.1-flash`), on the
reasoning that reviewers should not share the editor's model.

It found three real launch blockers, all verified against the code afterwards:

1. `module.nix` never set `R2_CAMPAIGN_BUCKET`, so the publisher's config gate
   fails and the meter 503s forever — green build, one line in a log.
2. `publish.clj` still carried its own `recent` projection (and published
   message text we had decided not to publish). **A commit message four commits
   earlier claimed this had been consolidated. It had not.**
3. The 503 branch sent no `Cache-Control`, so "unavailable" could be cached
   while the 200's `stale-if-error` window kept a stale number alive.

### The correction

The review's value did **not** come from the model being different. It came from
fresh eyes on the actual files. The author had been carrying a mental model of
the code that was one commit out of date — a *context* failure, not a capability
one.

**Standing order, most important first:**

1. **Instructions and context** — specific files, specific failure to hunt.
2. **Tools and setup** — reading the real code beats reasoning about it.
3. **Model capability** — use the strongest available reviewer.
4. **Model diversity** — a tiebreaker for correlated failure modes in a fan-out,
   never a reason to pick a weaker model for a one-off review.

The same ordering applies to dispatching workers generally: a precise brief to a
good model beats a vague brief to a great one, and both beat a "diverse" model
chosen for its difference.

### The operational rules that follow

- **Dispatch reviews with file paths, not just the plan.** "Read these files and
  find where a green build ships a broken meter" produced the findings; "review
  this plan" would not have.
- **A commit message is a claim, and claims get checked.** The worst finding was
  not the duplicate code — it was that the message said it was gone. Grep for
  the duplicate before writing "consolidated"; if the message and the diff
  disagree, the message is the bug.
- **Verify a reviewer's findings against the code before acting on them.** All
  three held. One suggestion (lower `stale-if-error`) was declined with a
  reason: the card already hides itself after 30 minutes from the artifact's
  own `generated_at`, so a cached payload cannot masquerade as current.

Cost of the correction: three small commits (`1267967`, `931728f`) and a net
−118 lines. Cost of not catching them: a meter that never displayed, discovered
on air.

## 2026-11-20 · Campaigns simplified: one representation (tag in the message)

### The critique that prompted it

Wes asked the right question — *will this be bloat when we do more of these, or
is it reusable?* — and floated the alternative: let the web boost just append
the tag to the message. Reviewed against Hickey (complecting), Normand
(abstraction with no instances), Wayne (unstated overlap spec), and plain
senior taste. Verdict: the plumbing was a one-off, the accounting core was not.

### What was wrong

The first cut had **three ways to belong to a campaign** — a
`metadata.campaign` field, a `#700club` regex, and an episode match — reconciled
by an `:any-of` matcher AST, and **two registries** (web-boost TS + scraper
EDN). That is one concept split into three representations, which is precisely
the failure class behind the feed dedup `""`/`null` bug. The AST was also
written but never compiled: an abstraction with zero real instances, designed
for matcher types we do not have.

### What it is now

```
:campaign/tag          "#700club"
:campaign/also-episode "700"
```

```clojure
(or (message-contains? boost tag)
    (= episode also-episode))
```

The web-boost picker survives, but its only job is appending the canonical tag
server-side (so it cannot be deleted after the fact). It is convenience, not
mechanism.

**Deleted:** `:boostagram/campaign` (no data was ever written to it), the
`metadata.campaign` ingestion path, the `:any-of` matcher AST, and the payload
`campaign` field. **Kept:** the accounting core, config-as-data, the picker.

### Why tags beat a structured field

- **One representation** — nothing to reconcile.
- **Universal** — works from every podcast app, not just our form.
- **Multi-valued** — a message can carry two campaign tags, so simultaneous
  campaigns compose without answering an overlap question.
- **Self-documenting** — the tag *is* the contract, and it is the string a
  listener would have typed anyway.

Cost of the pivot was one commit in each repo, because nothing had been built
on top of the AST and no attribution data existed yet. Doing it after the meter
shipped would have meant migrating campaign membership.

**Tests:** scraper 146/828, web-boost 109 (tag appended, tag-only when no
message, no duplicate tag, 300-char bound, unknown/foreign campaign rejected).

## 2026-11-19 · Campaigns plan locked (five decisions) + schema endpoint shipped

### Schema endpoint — shipped

`GET /api/v1/schema`. The default response is ~0.15s and lists every attribute
with its declared flag and type; `?examples=true` (pair with `?q=`) adds sample
values, `?counts=true` adds entity counts.

Two implementation lessons worth keeping:
- **Datalevin auto-registers attributes it has seen**, so `d/schema` is already
  a complete in-memory index of the data model. Discovery needs no sampling —
  an earlier sampling version cost 44s per call; this costs nothing.
- "Declared" must mean *in the application schema*, not "present in
  `d/schema`", or every attribute looks declared and the interesting ones stay
  invisible.

What it immediately revealed: **58 real, queryable, undocumented attributes** —
`:boostagram/boost_link` (313,724 rows), `:boostagram/name` (377,276),
`:boostagram/app_version` (58,678), `:feedID`, `:guid`, `:itemID`,
`:reply_address`, and more. Exactly the kind of thing that was invisible before.

### Campaigns plan — locked with Wes

1. **Attribution: `#700club` OR episode 700.** The hashtag makes pre-episode
   fundraising possible (eps 694–699); the finale counts by episode. No widget
   change needed.
2. **What counts: sats + fiat boosts.** Member-free adds contributors, not
   value. **Streams excluded**, as policy so other shows can differ.
3. **Target in USD cents, canonical.** Sats derived at snapshot rate, labelled
   — a BTC move must not silently move the goal (the cost is a flight).
4. **Publish a static JSON artifact to R2**, page reads it. The scraper is
   never exposed; one JSON per campaign, reusable for Planet Nix/SCaLE.
5. **Identity: deterministic passes + exposure endpoints** ship with campaigns;
   the review queue is worked continuously afterwards.

Still needed from Wes (config, not policy): target amount, window dates, CTA
URL, public title/description.

## 2026-11-19 · Architecture decisions: lens, artifacts, schema endpoint, lens refactor

Recorded so they are not re-litigated, and so the follow-ups survive context
loss. Full list with rationale: `docs/roadmap.md`.

### Decided

1. **Campaigns are a lens, not a ring-fence.** Campaign-tagged boosts are
   ordinary support that *also* count toward the goal. No separate payment
   path, no split accounting. Ring-fencing stays available as a per-campaign
   decision, never the default.
2. **Publish artifacts, never expose the process.** Public meters read a static
   JSON artifact (R2 → static page). The scraper's query API and reconcile
   routes never face the internet. If live data is ever needed, a proxy sits in
   front of the internal API.
3. **`GET /api/v1/schema` is committed** — attributes with types, uniqueness,
   cardinality and example values, including undeclared-but-present attributes
   (that omission is exactly how `:boostagram/sender_id` sat unnoticed on
   353k rows). Goal: a bot can write correct Datalog from the API alone.
4. **Feed and reports will move onto the accounting core** when they are next
   touched. Boosties proved the method: rebuild on `contributions`, prove the
   totals match on fixed windows, then switch. Rationale: they still carry
   their own query paths and can therefore drift from the meter — the precise
   failure the core exists to prevent.

### Context: what prompted this

Boosties was rebuilt on `contributions` today. The sats total reproduced
**exactly** (14,165,036) while 225 duplicate/non-boost records fell away, and
the rebuild surfaced the fiat/member/anonymous handling that the old ad-hoc
queries could not express. That is the evidence that the core approach works
and that the remaining lenses should follow it.

Also captured this session: `docs/datalog-tips.md` — measured Datalog/Datalevin
lessons (engine-side aggregation, the `get-else` cost cliff, `pull` row shape
and `:db/id`, declared-vs-undeclared attributes, resolver security), so we stop
hand-rolling what the engine already does.

## 2026-11-19 · Open items — consolidated watchlist (post-2.5-month regrounding)

Re-grounded after a gap (last entry 2026-09-02). State snapshot:

- **Repo**: `v2` @ `f462ad1`, clean, 123 tests / 744 assertions green.
- **Live box**: service active, `reconcileWrite = false` (correct), reconciler
  scanning **240** web-boost invoices → **239 already-boosted, 0 orphans,
  0 unmatched, 1 manual-review**.
- **Data**: fresh as of today (latest record same-day), **6,362** boost entities.
- **The good news**: in September the scan covered 129 invoices; it is now 240.
  **~111 new web-boost invoices arrived and every one resolved cleanly as
  already-boosted — zero new orphans.** The lnbits/webhook fixes are holding.

### Open items (nothing here is broken; these are decisions/plans)

| # | Item | Status | Where it lives |
|---|---|---|---|
| 1 | **Anon invoice 323627** (2,222 sats, TWIB-108) | **OPEN** — stays in `manual-review` by deliberate choice. Two identical-destination anon bookings already exist; no source document distinguishes a third payment. Booking it risks double-counting; leaving it understates by 2,222 immaterial sats. Options spelled out in `docs/manual-review-guide.md`. | manual-review-guide + this log (09-02) |
| 2 | **Datalevin 0.9.13 → 1.0.2** | **PLANNED, unscheduled.** Two hops: 0.9.13→0.9.27 (same minor, zero migration, reaches the auto-upgrade floor), then 0.9.27→1.0.2 (storage migration; prefer manual `dtlv` dump/load on NixOS for deterministic startup). Unlocks `*resolver-mode* :server-safe` (upstream's own RCE fix), `get-some-else`, `:db/udf`, and the 0.10+ planner/perf work. | `docs/datalevin-upgrade-plan.md` |
| 3 | **Ballers inclusion for fiat-via-lightning** | **PRODUCT DECISION, punted.** Should fiat-denominated boosts that were actually paid in sats via nodecan LND also appear in the sats/ballers aggregation? Clean rule on the table: fiat-with-confirmed-BTC-tx → also ballers; card/PayPal stays fiat-only. Double-count implication needs deliberate acceptance. (Magnitude as measured 2026-08-29: ~415k sats visible only in the fiat section — re-measure before deciding.) | this log (08-29) |
| 4 | **Schema-introspection endpoint** | **NOT BUILT.** `GET /api/v1/schema` (attributes + types + example values) would save any agent from reading `.clj` files to write Datalog. Named as a follow-up in the 09-01 entry. | this log (09-01) |

### Resolved since the last entry (for the record)

- **Manual-review picks**: Memphis 342724 booked (invoice-anchored, gated);
  Hydragyrum 327805 reclassified already-boosted (webhook-lag false positive,
  fixed by settle-anchored pairing + 1h window); Anon 323627 left (above).
- **Query templates**: removed (broken duplicate of the analysis endpoints).
- **Query-API cookbook + security rules**: shipped (`API.md`).
- **`docs/manual-review-guide.md`**: was stale (said "three remain" and gave
  advice the forensics contradicted) — rewritten to current reality.

### Not part of this repo but adjacent

The box `/etc/nixos` has moved independently since September: **lnbits now
starts at boot** ("now that Lightning boosts are live"), lnbits GET-request
auditing was added, and the kernel moved to 6.18 (from nixos-26.05) while
staying on 25.11 userland. Those changes live in the box repo, not here.

## 2026-09-02 · Manual-review picks resolved — forensic finding + gated operator path

### The forensic finding (what the three "picks" actually are)

The three manual-review rows looked like "retry twins — pick the match." The
evidence told a different story. Pulling **Zaprite `paidAt` for every order**
and aligning against **LND `settle_date` for every invoice** produced a clean
pairing table:

| Invoice | Settled (LND) | Candidate order | paidAt (Zaprite) | Status |
|---|---|---|---|---|
| 323627 (Anon TWIB-108 2,222) | 06-11 02:14:48 | od_2QMi1ppumF | 06-10 21:41:44 | COMPLETE → paired to invoice **323503**, already boosted |
| | | od_y7jTeGxbD1 | 06-11 03:36:03 | COMPLETE → paired to invoice **323628**, already boosted |
| | | 4× PENDING | — | **`invoice: None`, `transactions: []` — never paid** |
| 342724 (Memphis LUP-670 2,222) | 08-07 03:36:14 | od_vff0Bfkh8g / od_bY1at35Vl9 | — | PENDING, never held an invoice |
| 327805 (Hydragyrum LUP-668 2,000) | 06-23 20:45:29 | od_hTEwY3DUVX | 06-23 21:30:34 | COMPLETE paid **45 min after** settle |
| | | od_ujd5Yk70Kv | 06-23 21:34:09 | COMPLETE ↔ invoice 327806 (21:34:03) |

Conclusions:
- **All 7 PENDING candidates never initiated payment** (`invoice: None`,
  `transactions: []`) — they are checkout-abandoned retries, not dead-webhook
  orphans. The old guide's "one settles the invoice" premise was wrong.
- **All 4 COMPLETE candidates were already boosted** under *different*
  invoices. None paid the three target invoices.
- Hydragyrum 327805 + 327806 ↔ its two COMPLETE orders — with a **45-minute
  webhook lag** on the first (paidAt 21:30:34 vs LND settle 20:45:29).

### The accounting treatment (ledger view)

The DB is the ledger; LND settle is the cash receipt; Zaprite orders are the
source documents (partly missing here). Per entry:

- **Memphis 342724 — BOOK.** Real settled receipt, memo names Memphis/LUP-670,
  and **no existing booking** exists. The rare genuine orphan. Message is
  unrecoverable; a blank-message boost is honest, not invented.
- **Anon 323627 — DO NOT BOOK.** Two identical-destination bookings already
  exist (21:41 + 03:36). A third same-amount entry with no source document is
  the textbook double-count risk. Conservative treatment: leave it.
- **Hydragyrum 327805 — DO NOT BOOK.** Both COMPLETE orders already booked;
  the 45-min webhook lag means 327805 IS od_hTEwY3DUVX's payment. Booking it
  again would double-book. **The matcher's `pairs-with-complete?` window was
  the bug** (creation-anchored, 600s → the 45-min lag fell outside).

Net effect of the treatment: +2,222 sats booked (Memphis), nothing for Anon
and Hydragyrum, ~4,222 sats remain intentionally unmatched by choice. The
whole exposure (6,444 sats) is immaterial to totals; per-entity accuracy and
no-double-count were the deciding principles.

### What was implemented (gated, not hacky)

1. **COMPLETE-pairing lag fix** — `invoice-pairing-target` now carries
   `:settle-epoch` (LND settle is the authority; creation is the fallback) and
   `pairs-with-complete?` anchors its gap on it. `pairing-window-seconds`
   600 → **3600 (1h)** — validated on live data from both directions: the
   45-min Hydragyrum webhook lag must pair (in), and the Anon 81-min
   non-neighbor must NOT (out; one payment can't settle two invoices). The
   window stays bounded; same-user/same-show/same-amount orders 1h+ later do
   not pair.
2. **`resolve-manual-review!`** in `reconcile.clj` — the operator write path:
   builds the boost from the settled invoice via the existing
   `build-boost-entity` (nil order = invoice-anchored: sender from memo,
   `message ""`, no `zaprite_order_id`, `source "nodecan"`; an order's
   METADATA only is used when supplied). Upserts by `:invoice/identifier`;
   idempotent (`:already-boosted` → 409), unknown → 404.
3. **`POST /api/v1/reconcile/resolve`** in `web.clj` — gated by the same
   `WEB_BOOST_RECONCILE_WRITE` flag as backfill (403 when off; the flag is
   read per-request, so a resolve is a deliberate flag-on → resolve → flag-off
   sequence). Broadcasts the write to the live feed like a normal boost.
4. Tests: webhook-lag pairs now (45 min), 3h does not; resolve invoice-
   anchored entity shape + idempotency + metadata-only-with-order; route 403/
   400/404/200 and broadcast parity. **123 tests / 743 assertions green.**

### The operator action (this deployment)

1. Code deployed with the gate OFF → preview reclassified Hydragyrum as
   already-boosted (manual-review 3 → 2). No writes.
2. Gate ON (`WEB_BOOST_RECONCILE_WRITE=true`) → `POST /resolve {342724}` wrote
   the Memphis boost → preview 2 → 1 (Anon only).
3. Gate back OFF.
4. `docs/orphan-reconcile-spec.md` unchanged (resolve complements, not
   contradicts, the spec — the write-gate contract is identical).

### Post-action verification (live, 2026-09-02)

| Check | Result |
|---|---|
| `write-enabled` after gate-off | `False`, `written 0` |
| Memphis boost entity | present: `memphis` / 2222 sats / LINUX Unplugged / 670 / ts 1786070437 (= LND settle 08-07 03:36:14Z) — no message, no `zaprite_order_id` |
| Memphis aggregation | ep 670 → 2,222 sats in the query proxy |
| Preview after resolve | `manual-review: ['323627']` (Anon only), `already-boosted: 129` |
| Live-feed parity | resolve broadcasts via `ws/broadcast!` like a normal Zaprite boost |
| Full suite | 123 tests / 744 assertions green |

Also during this deploy: a bare `nix flake update` on the box bumped **every**
flake input (nixpkgs too) and the new nixpkgs marked `broadcom-sta` insecure,
breaking the rebuild. Fixed by restoring `flake.lock` from git and re-locking
with the surgical `nix flake lock --update-input lnd-boost-scraper`.
**Lesson: never run a bare `nix flake update` on the box — always scope to the
`lnd-boost-scraper` input.**

Remaining state: Anon 323627 (2,222 sats) stays in manual-review by
deliberate choice — its two identical-destination bookings already exist and
no source document distinguishes a third payment; the conservative treatment
is to leave it unmatched rather than double-book.

## 2026-09-01 · Query proxy was a remote code execution backdoor — fixed + hardened

### Discovery (the "flexible query API" conversation)

Wes wanted a general Datalog query API so a smart bot could ask arbitrary
questions of the boost DB. We already had `POST /api/v1/query` + templates,
but reviewing it (because it'd never actually been used) surfaced a **critical
hole**: the endpoint could execute arbitrary code on nodecan.

### Root cause — datalevin 0.9.13's function resolver

The embedded query engine resolves a function symbol in a predicate/fn clause
by this chain (`src/datalevin/query.clj`):

    (or (get built-ins/query-fns f)      ; 1. curated registry — tried first
        (context-resolve-val context f)  ; 2. :in-bound values
        (dot-form f)                     ; 3. (.method obj ...) raw reflection
        (resolve-sym f))                 ; 4. (resolve sym) ANY var in any ns

So the registry is only the *first* branch — anything not in it falls through
to `(resolve sym)`, which finds **any var in any loaded namespace**, plus a
separate dot-form reflection escape that skips the registry entirely.

**Confirmed live on nodecan:**
- `[(clojure.core/load-string "(+ 1 2)") ?x]` → executed, returned `3` (RCE)
- `[(clojure.core/slurp "/etc/hostname") ?x]` → returned `"nodecan"` (file read)
- `[(.getClass ?x) _]` → dot-form reflection path (open too)
- `clojure.java.shell/sh` failed only because that ns wasn't loaded — moot, since
  `load-string` bridges to everything.

The module's docstring "Read-only by construction — never imports d/transact!"
was misleading: you don't need `d/transact!` when `load-string` exists.

### Upstream research (what the review taught us)

- **0.9.13** has no `:server-safe` mode. The registries exist as
  `datalevin.built-ins/query-fns` (predicates/fns) and
  `datalevin.built-ins/aggregates` (sum/count/…), both benign read-only sets.
- **1.x** added `datalevin.query.resolve/*resolver-mode*` — `:server-safe`
  restricts queries to registry + registered `:db/udf`s and rejects qualified
  symbols, dot-forms, fn literals, and `apply`. That's the upstream-sanctioned
  answer to this exact problem. **But** we're on 0.9.13 (upgrade requires
  storage migration — see `docs/datalevin-upgrade-plan.md`).
- So we implement `:server-safe` semantics ourselves on 0.9.13, before `d/q`.

### The fix (`e95c187`)

1. **`validate-query-fns`** — walks the parsed query (`:find`/`:where`/`:in`)
   and allows only symbols from datalevin's own registries, **derived at
   runtime** (`(keys datalevin.built-ins/query-fns)` + `/aggregates`) so it
   auto-tracks upstream across upgrades. Rejects: dot-forms, `apply`, rule
   bindings (`%`/`%%`), unknown top-level query keys, and >64 KiB queries.
   Runs before the query reaches `d/q`.
2. **Execution hardening** — bounded concurrency (semaphore, 3 concurrent,
   else 429), hard timeout with `future-cancel` (previously the deref-timeout
   left the query thread running unbounded), and try/catch → structured
   `:error` instead of raw 500s (previously a bad query leaked as plain
   "Internal Server Error"; the `(:exception result)` branch was dead code).

**Live verification (nodecan):** `load-string`, `slurp`, `.getClass` all →
`400 {"status":"error","detail":"Query function or form not allowed: ..."}`.
Legit aggregate query → `ok`. 10 new tests (the exact live RCE probes) →
124 tests / 773 assertions green.

### Lesson — "regression" was actually startup-sync contention

Right after deploying, the legit group-by query that had been 1.4s returned a
timeout. Panic-checked for a proxy regression, but the call was byte-identical
to before. Turned out the service had just restarted and was mid-scrape (Zaprite
sync, R2 sync) — LMDB read contention made queries ~2.3× slower and the heavy
group-by blew past the timeout. After the sync settled, the same query ran in
1.9s. **Lesson: never judge query performance right after a service restart;
wait for the scrape cycle to settle.**

### Open follow-ups

- Upgrade to datalevin 1.x (see `docs/datalevin-upgrade-plan.md`) → bind
  `*resolver-mode* :server-safe`, keep the walker as belt-and-suspenders.
- Add a schema-introspection endpoint + bot cookbook for the query API
  (cookbook now lives in `API.md`).

### Removed: the broken query-template system

The 5 templates (`resources/query_templates.edn`) and their 2 routes were
removed. They were broken dead code that duplicated the working `/analysis/*`
endpoints, and 2 of them (day-of-week, monthly) couldn't be implemented under
the new query-function allowlist (no date built-ins in datalevin's registry).
The raw Datalog proxy + `/analysis/*` endpoints + the `API.md` cookbook cover
everything. Deleted: templates file, 2 routes, 3 tests, API.md + skill
references.

### Deliverables: shared query briefing + topic-investigation walkthrough

- `docs/query-api-briefing.md` — a self-contained payload for teaching another
  person's LLM how to query the boost API (endpoint, schema, allowlist rules,
  cookbook, topic-analysis methodology). Shareable directly.
- Method validated live on **The Launch**: pulled the 380 message-bearing
  boosts via the proxy, joined `:boostagram/episode` to the show's RSS feed
  titles (normalization: strip the leading `NN: ` prefix on both sides — 30/30
  top episodes matched cleanly), and read the messages to cluster topic
  engagement. Key finding: The Launch's engagement engine is listener-prompt
  personal stories (pets/hobbies/nostalgia) by count, and show-survival
  sustaining mega-boosts by sats; recurring cross-episode topics (AI,
  hardware/gadgets, Flock-cameras bit, platform/corporate criticism, JB
  rituals like TIMETRAVEL boosts) carry broad support.

## 2026-08-29 · Feed WebSocket dedup bug — fixed (three-layer bug)

### The symptom

Boost cards appeared as duplicates in the live feed when the page was left open. pabi's
"TIMETRAVEL BOOOOST! EP 77...Roborock Vacuums" appeared 3×; genebean's "hold music from
data in LUP680" appeared 2×. The HTTP API returned100 unique items — zero server-side
duplicates — so the duplication was happening client-side via the WebSocket.

### Root cause (three layers deep)

**Layer 1 — `select-keys` omission (server):** All three `broadcast!` call sites
(`upstream/zaprite.clj`, `upstream/r2.clj`, `db.clj`) used `select-keys` that did NOT
include `:boostagram/content_id` or `:invoice/identifier`. So the WebSocket sent
`content_id: null` in every boost message.

**Layer 2 — HTTP vs WS asymmetry (server):** The HTTP feed query uses
`(get-else $ ?e :boostagram/content_id "")` — defaulting to empty string `""` when the
attribute is absent. The WebSocket sent `null`. Same entity, two different representations
for the same field.

**Layer 3 — `boostKey` priority (client):** The client's `boostKey` used `identifier`
first, then `content_id`. With `content_id: null` from the WebSocket, the key fell through
to `identifier` (e.g. `"id:349963"`). But the HTTP-fed card used `content_id` (e.g.
`"cid:3a6c5455..."`). Different keys → dedup failed → duplicate cards inserted.

**Why it was hard to catch:** the server-side dedup (`feed.clj dedup-by-content-id`) works
correctly — it collapses entities sharing a `content_id` into one HTTP row. So the API
always returned clean data. The bug only manifests when the WebSocket broadcasts the raw
entity (no dedup) AND the client's dedup key differs from the HTTP key.

### The fix

Three changes, all needed:

1. **`select-keys` in all 3 `broadcast!` sites** — added `:boostagram/content_id` and
   `:invoice/identifier`. Now the WebSocket sends the same fields the HTTP feed uses.

2. **`boostKey` in `feed.js`** — swapped priority to `content_id` first, matching the
   server-side `dedup-by-content-id` logic. Added prefixed keys (`cid:`, `id:`, `eid:`,
   `raw:`) to prevent accidental collisions between fallback types.

3. **Deployed to box** — `4f9cf0b`, verified: Roborock count 3→1, hold music count 2→1.

### What the near-miss taught

This was a classic "everything works in isolation" bug: the HTTP feed is correct, the
WebSocket broadcast is correct, the client dedup is correct — but the data contract
between HTTP and WS was asymmetric (`""` vs `null` for the same semantic field). The
client-side fix (content_id-first) was necessary but insufficient without the server-side
fix (include the field in select-keys). Both layers needed to agree on the data shape.

### Active session log

Also in this session:
- **Fiat-pairing blindspot fixed** (see entry below): `unmatched` 4→0, `already-boosted`
  118→122. `order-tx-sats` + `pairs-with-complete?` extension.
- **Retracted "fiat boosts show 0 sats" claim**: `value_sat_total 0` on fiat entities is
  by design; reports render "$200.00 (lightning)" correctly. Near-miss lesson documented.
- **Open product decision**: should fiat-via-lightning boosts also appear in ballers?
  Recorded for Wes's consideration.
- **`reconcileWrite` flipped to false** during the deploy.

## 2026-08-29 · Fiat-pairing blindspot fixed (detection completeness)

### The blindspot, explained

After the phase-5 deploy, `unmatched` sat at 4 invoices / 415,156 sats. These were the last
invoices the matcher could not explain. Investigation showed they are NOT orphans — they are
the invoice half of **fiat-denominated web boosts paid via lightning**:

- The web-boost widget lets a customer pick a dollar amount (e.g. $200). Zaprite records the
  ORDER in fiat (`currency: "USD"`, `totalAmount: 20000` cents), but the customer pays through
  nodecan LND, so the underlying TRANSACTION settles in BTC sats (312,088).
- Zaprite marks that order COMPLETE after polling observes the settlement — `paidAt` lands
  ~1 minute after the LND invoice's creation. Same payment, two records: a fiat order
  (already ingested by the normal sync as a `:fiat` boost) and a bare nodecan invoice entity.
- The pairing rule required `currency == "BTC"` on the order, so these could never pair —
  the matcher kept re-reporting them as unmatched noise.

Verified tx semantics across all 240 COMPLETE orders (2026-08-28 pull, zero exceptions):
- BTC orders: `transactions[0]` = LIGHTNING/BTC/amount == totalAmount always.
- Fiat orders: tx is either fiat (PAYPAL/VENMO/CARD, amount == totalAmount — the sats
  equivalent never touched nodecan) or BTC (LIGHTNING/BITCOIN, amount = true sats paid).

### The fix

`order-tx-sats` extracts the confirmed BTC transaction amount from an order; `pairs-with-complete?`
now accepts EITHER the order total (BTC orders) OR the confirmed BTC tx amount (fiat orders
paid via lightning/onchain) as the "same payment" signal. Rail is deliberately NOT a filter:
a fiat order paid via lightning IS the same payment as its invoice; a fiat order paid via card
has no BTC tx and still cannot pair — correctly, because its value never moved through nodecan.

Predicted effect on next deploy: `unmatched` 4 → 0, `already-boosted` 118 → 122.

### What was deliberately NOT changed

An earlier session claim — "fiat boosts show 0 sats, reports are wrong" — was **retracted after
verification**: `value_sat_total 0` on fiat entities is by design. Reports bucket by
`:boostagram/type`: ballers sums sats from `:sat` entities; the fiat section sums
`amount_fiat_cents` from `:fiat` entities and renders "$200.00 (lightning)" — accurate, no
money mis-displayed, no double-count. The near-miss lesson: verify what the report layer
actually renders before "fixing" the data layer.

### Open product decision (Wes's call, recorded here so it isn't lost)

The 4 lightning-settled fiat boosts represent **415,156 real sats received by the shows**, but
they only appear in the fiat section (as dollars), not in ballers (sats). Moving lightning-paid
fiat boosts into ballers would make the sats view complete. The wrinkle: 77+ other fiat boosts
were paid via card/PayPal — real revenue, but those sats never existed in nodecan. Clean rule
if pursued: **fiat boost with a confirmed BTC tx also counts in ballers (tx sats); card/PayPal
fiat stays fiat-only.** Double-count implication (same boost visible in two sections) must be
accepted or the sections relabeled. Not built — needs a deliberate product decision.

## 2026-08-29 · Phase 5 deployed — matcher verified live, two new findings

- **Deployed**: box rebuilt with `db8c2d6` (unified fetch + pairing rule + content-identity
  guard). Live preview: **already-boosted 16 → 118** (pairing rule absorbed ~100 false
  positives), **unmatched 101 → 4**, orphans 0 (nothing left to backfill for BTC-matchable
  invoices), manual-review 3.
- **Finding A — the 4 big unmatched rows (~415k sats) are NOT orphans**: each maps exactly to
  a **fiat-order COMPLETE** (Satsquatch USD 20,000 → tx CONFIRMED 312,088 BTC-sats; daomah
  USD 3,000 → 47,632; John A USD 2,500 → 39,880; CypherCitizen USD 1,000 → 15,556). They are
  already in the ledger via the normal sync — but as `:boostagram/type :fiat` entities with
  **`value_sat_total 0`**. Pairing requires currency==BTC, so the matcher cannot see them.
  Fix direction: pairing should also consider the LIGHTNING transaction amount (available in
  the order's `transactions`), not just `totalAmount` — then these 4 classify as
  already-boosted and unmatched truly goes to 0.
- **Finding B — fiat boosts lose sats fidelity (separate improvement, user-facing)**:
  `process-order` sets `value_sat_total 0` for fiat orders, but the Zaprite transaction
  carries the true sats. A 312k-sat boost shows as 0 sats in reports. Worth fixing in
  `process-order` (take `transactions[0].amount` when BTC-sats and `type :fiat`), with a
  backfill sweep for existing fiat entities. Satsquatch's July BTC order (25,353) already
  carries correct sats, so only fiat rows are affected.
- **Memphis guard held correctly**: the two candidates' messages genuinely differ ("Thanks for
  all the value! Web boost FTW" vs "Thanks for the value!") — content-identity refuses them,
  as designed. This is now an operator choice, not a matcher gap. Same for the anon 323627
  group (6 candidates — double-creation twins; needs group-aware COMPLETE-exclusion logic to
  collapse) and a new Hydragyrum LUP-668 pair (2,000, previously unmatched).
- **Backfill: intentionally not run** — orphans=0, nothing to write. The write flag remains
  on the box; flip to false on next routine rebuild per the plan (§3 step 3 amended: no
  backfill needed).

- **Plan** (`docs/orphan-reconcile-phase5-plan.md`) went through an agent design review
  (verdict BUILD AS AMENDED). Amendments adopted:
  - Pairing key gains **normalized username + explicit BTC currency** (same-show/same-amount
    COMPLETEs from other users exist; fiat totalAmount is not sats).
  - Tie-break reframed honestly: it's a **deterministic bookkeeping pick**, and the
    double-count risk was defused analytically — a duplicate would require a dead twin to be
    picked AND the true order to later complete, but completion only happens during the
    checkout session (all 240 observed paidAt within ~2min of settle — which is exactly why
    orphans exist) and dead twins' invoices are canceled so they can never complete.
  - **Unified fetch adopted**: `fetch-unified-orders` (sortBy=createdAt asc, statuses
    PENDING/PAID/COMPLETE/OVERPAID) replaces the PENDING-only fetch for reconcile — one pull
    gives the pairing data AND creation-order tie-break signal. The worker sync's
    `fetch-orders` paidAt-cursor contract is untouched.
  - **Worker memo-euid fix cut** from the plan (orthogonal, future work).
- **Two live-data corrections recorded** (§ NEW discovery in the plan): the phase-0 memphis
  hypothesis is likely flipped (`od_bY1at35Vl9` is the later-created, so the likely anchor),
  and order/invoice creation is not always 1:1 — so creation-order position is a tie-break,
  not a truth claim. The **content-identity guard is the load-bearing safety rule**.
- **Tests rewritten to be self-deriving** (per Wes: adaptive, generative-inspired): fixture
  timestamps are *computed at runtime* (e.g. creation = paidAt − 35s) instead of hand-baked
  epoch literals — three separate failures from my own hand-computed epochs proved the point.
  Tests are named for the rule ("latest-created wins"), not the predicted outcome.
- Implementation: `unified-orders-query` / `fetch-unified-orders` (vector `status` value —
  babashka renders it as repeated single params, same as the proven paid-sync query),
  `invoice-pairing-target`, `pairs-with-complete?` (600s window), `content-identical?`,
  tie-break `{:confidence :high :order (last cands)}` in `match-order-candidates`,
  pairing-or-dedup in `detect-orphans`' skip check. `sync-web-boost-reconcile!` injection
  key renamed `:fetch-pending` → `:fetch-orders`.
- Live validation: run through the **deployed preview route** after the next box rebuild
  (read-only by construction, live DB + live Zaprite — strictly better than a local snapshot
  copy, which was attempted and abandoned: 3.2 GB pull, redundant, torn-copy risk).

## 2026-08-19 · Investigation

- **Root cause**: Web Boost checkout settles a BOLT-11 invoice on nodecan LND; Zaprite marks the
  order COMPLETE only via polling. If the checkout session ends before settlement, the order stays
  PENDING with `transactions: []` and the scraper's paid-only sync never ingests it.
  LNbits→Zaprite `onPaidWebhook` is dead (111/112 → HTTP 400, Zaprite SaaS side, unfixable locally).
- **Measured: 13 orphans / 158,787 sats** of settled-but-never-boosted nodecan payments, all
  resolving to real PENDING web-boost orders (172 PENDING live, 114 web-boost).

## 2026-08-19 · Live API probe (production key)

- **`status[]=PENDING` is silently ignored by Zaprite** — returns the unfiltered default COMPLETE
  set. Fixed both my new `pending-orders-query` and the pre-existing `upstream/zaprite.clj`
  `orders-query` (which never filtered at all; it only worked by accident). Live-verified both.
- **`PATCH /v1/orders/{id}` accepts `status: COMPLETE`** — but per user decision Zaprite-side
  completion is out of scope; the DB is the ledger.
- **`externalUniqId` = `web-boost:{slug}:{episodeKey}:{currency}:{amount}:{token}:{fingerprint}`**.
  Prefix (slug/currency/amount) is a strong confirm guard; the token+fingerprint tail is NOT
  derivable from LND, so it can't split identical label+amount pairs (memphis → `:manual-review`).

## 2026-08-19 · Design decisions (user Q1–Q4)

1. **DB is the ledger.** Zaprite registry not completed; `PATCH order→COMPLETE` exists but unused.
2. **nodecan LND is source of truth for "paid"**; Zaprite = metadata only, never payment status.
3. **Deploy via HTTP routes on the deployed server**, not the `:reconcile` dev alias — the box has
   no repo checkout; the server already holds the DB conn + key in-process.
4. **Write is flag-gated** (`WEB_BOOST_RECONCILE_WRITE`, default off); detection is read-only.
5. **Detect orphans DB-locally** (`:invoice/settled`/`value`/memo persist on nodecan entities; no
   LND REST needed at runtime).

## 2026-08-20 · Critical audit of the Phase 3 spec

Re-examined the spec against actual producers (`process-order`, `sync-zaprite-boosts!`, reports).
Initial findings were mostly my own over-engineering — killed four of six:

- **#1 dual-producer double-count — DISPROVEN empirically.** In-memory probe: reconcile write
  (identifier add_index) + later `process-order` transact (same `:boostagram/zaprite_order_id`)
  → **ONE entity**. The unique identity attr does the merge; `:invoice/identifier` just gets
  re-keyed to `zaprite-<id>` (harmless). No sync-side guard needed.
- **#2 memphis exit gate — KEPT.** "13 of 13 orphans visible" was wrong: memphis matches two
  PENDING orders → never auto-written. Correct gate: 12 auto + memphis operator-resolved.
- **#3 `WINDOW_DAYS` — DROPPED.** Premature optimization; full sweep is cheap (2-page PENDING
  fetch). Keep-fresh loop itself deferred to Phase 5.
- **#4 reconcile-report sats mislabel — acceptable for now** (cosmetic; revisit if ops confused).
- **#5 memphis "noise every cycle" — RETRACTED.** Self-resolves once memphis is operator-resolved
  (order-id enters dedup set). No suppression feature.
- **#6 unauthenticated POST — ACCEPTED.** Consistent with existing tailnet posture
  (`/api/v1/client-states/cleanup` same pattern).

**Scope cut**: Phase 3 = routes (`preview`/`backfill`) + `WEB_BOOST_RECONCILE_WRITE` module
passthrough. No window, no loop wiring, no sync guard, no suppression. Rationale: fix the ledger
with the smallest surface that deploys cleanly; add loop only if orphans recur post-webhook-fix.

## 2026-08-20 · Phase 3 built (code + tests, all green)

- **`sync-web-boost-reconcile!`** (reconcile.clj): composes detection seams, optionally writes
  HIGH-confidence orphans (`d/transact!` + broadcast with the `process-order` key set). Injected
  `:fetch-pending` / `:broadcast-fn` for hermetic tests; write is strictly opt-in.
- **Routes** (web.clj): `GET /api/v1/reconcile/preview` (read-only, never writes) +
  `POST /api/v1/reconcile/backfill` (403 unless `WEB_BOOST_RECONCILE_WRITE` = "true"). Both
  read env per-request; no new args through `web/serve`.
- **module.nix** `reconcileWrite` (bool, default false) → `WEB_BOOST_RECONCILE_WRITE`; flake
  module-options assert extended.
- **Tests**: idempotency (double-run → one entity, writes 0), dual-producer merge pinned against
  real `process-order` (one entity, identifier re-keys to `zaprite-<id>`), route gating
  (preview 200/no-write; backfill 403 off → 200 on; idempotent across requests).
- Why the shape: keep the write path as the *only* new mutation surface — everything else
  (window, loop, suppression) deferred/dropped per the audit.
- Verification: 99 tests / 712 assertions green; clj-kondo 0/0; cljfmt clean; alejandra clean;
  `nix build .` + `nix build .#checks.x86_64-linux.module-options` pass.
- Note: backfill route tests needed the PENDING fetcher injected (`rec/fetch-pending-orders`
  redef) — without it the route hits real Zaprite with the test key (401 retries).

## 2026-08-20 · Phase 4 deployment decision (single-rebuild plan)

- **Deploy once with `reconcileWrite = true`**, then preview → backfill in that order. Why not
  the two-rebuild (deploy false → verify → arm true) option:
  - `GET /preview` never writes regardless of the flag, so the confirmation step is equally safe
    either way; the flag only arms `POST /backfill`.
  - A stray POST while armed is bounded: it writes exactly the HIGH-confidence orphan set
    (dedup-guarded, idempotent, never touches memphis manual-review) — i.e. the intended end
    state, not a surprise.
  - Saves a full rebuild/restart cycle on the box.
- Follow-up: flip `reconcileWrite = false` on the next routine deploy — backfill is one-time and
  the write gate should stay closed.
- Full runbook in `docs/orphan-reconcile-phase4.md` (step 2 is a hard gate: abort if orphans ≠
  12 / manual-review ≠ 1 / sums disagree; unmatched ≥ 0 is normal — completed orders live in the
  ledger via the normal sync already).

## 2026-08-28 · Phase 4 deployed + backfilled (prod)

- **Deploy**: box config (`/etc/nixos` flake.nix) set `reconcileWrite = true`; flake input
  updated input-only (`nix flake update lnd-boost-scraper`, 9d895cc → aa2bcaa on `v2-feed`).
  Note: the box also pins `lnd-boost-scraper.url` to `github:noblepayne/lnd-boost-scraper/v2-feed`
  (uncommitted there, along with the lnbits webhook patch in lnbits.nix).
- **Preview gate deviation, decomposed and verified**: preview returned orphans 16 /
  manual-review 8 / unmatched 101 — not the runbook's 12/1/≥0. The original 12 are all present
  (sums reconcile exactly: 163,564 − 6,999 = 156,565 = predicted 12-writable sum). The 4 extras
  are the same dead-webhook failure class that predates or postdates the Phase-0 probe: adevries17
  (Jun 25), Anonymous ×2 (Jun 11/20), and the box owner's own "test boost 2" that settled 4
  minutes *before* the webhook-fix restart. Root cause of the fixture gap: this was the first
  real prod run of `detect-orphans` (Phase 2's prod dry-run was always deferred to this route).
- **Webhook fix verified live**: running lnbits is the patched `lnbits-webhook-fixed` build
  (`json=json.loads(payment.json())` at notifications.py:249), restarted Aug 19 14:29 PDT. All 5
  webhooked payments since show `webhook_status=200`. The post-fix test boost (557 sats) went
  COMPLETE normally and is correctly NOT an orphan — the fix works.
- **Backfill executed**: `POST /backfill` → `written: 16, skipped: 0, manual-review: 8`.
  Idempotency re-run: `orphans: 0, already-boosted: 16`. All 16 verified present in `/boosts`
  (ballers bucket) with correct senders/sats/episodes. Report date-ordering fix (2953e19) also
  verified: every sender batch renders chronologically.
- **Backfill route test lesson (minor)**: with-redefs on the route's env helpers is required in
  tests — the default fetcher hits real Zaprite otherwise (401 retries).
- Remaining cleanup: flip `reconcileWrite = false` on next routine deploy; commit the box's
  `/etc/nixos` uncommitted state; manual-review rows documented below.

## 2026-08-28 · Manual-review SOLVED (post-backfill live-data analysis)

Follow-up probing after the backfill changed the picture substantially:

- **COMPLETE-order pairing**: a COMPLETE's `paidAt` lands ~5s–2min after its invoice's LND
  settle (polling confirm). Pairing settle times against the 240 COMPLETE orders (all already
  in the ledger) resolved **4 of the 8 manual-review rows as already-boosted false positives**
  (mg101010 ×3, anon TWIB-108 22,222). True outstanding: **2 invoices × 2,222 = 4,444 sats**,
  not 37,776.
- **`sortBy=createdAt` works**: the API never returns a `createdAt` field, but accepts
  `sortBy=createdAt` and orders results by it. PENDING creation order is extractable.
  Sanity-checked: memphis `od_bY1at35Vl9` (pos 29) created before `od_vff0Bfkh8g` (pos 30) —
  consistent with the phase-0 hypothesis that `od_vff0Bfkh8g` anchors settled invoice 342724
  (attempt 1 canceled → attempt 2 settled).
- **`expiresAt` is null** on all 10 stuck PENDINGs — they're not expiring, just dead retries.
- Consequence: **full automated resolution is possible without dashboard access.** Plan
  written: `docs/orphan-reconcile-phase5-plan.md` (matcher upgrade: pairing rule + creation-
  order tie-break + content-identity guard; plus the durable worker fix — euid tail in the
  invoice memo). Dashboard-access notes if the user obtains elevated access:
  `docs/zaprite-access-notes.md`.
---

## 2026-11-20 · Campaign meter live in production

### What shipped

- **Scraper** (`1d11cf3`, deployed): campaign compute, internal routes, and the
  R2 publisher wired into the scrape cycle.
- **R2**: private bucket `web-boost-campaigns`, written by a token scoped to
  that bucket alone. Artifact verified directly against the S3 endpoint:
  `campaign/lup-700-brent.json` → HTTP 200 with the contract.
- **Pages** (`449c7d1`, deployed): the campaign card and the Function serving the
  artifact with `public, max-age=20, stale-while-revalidate=120,
  stale-if-error=86400`, an ETag, and no `s-maxage`.

Live checks (2026-11-20):

- `GET /api/v1/campaigns` → `lup-700-brent`, state `active`.
- `GET /api/v1/campaigns/lup-700-brent` → full contract (goal $2,000 / 3,397,893
  sats at 58,860 USD/BTC, 52 days left, rate source mempool.space).
- `GET /api/campaign/lup-700-brent` → 200, `application/json`, ETag, correct
  `Cache-Control`, no `s-maxage`.
- Unknown slug → **503** with `no-store` and `Retry-After` (kill switch works).
- `/api/config` exposes the campaign with `podcastSlugs: ["lup"]`, so the card
  scopes to LUP only.

### Bugs caught during the deploy

1. **HEAD fell through to the SPA handler.** `curl -I` on the artifact path
   answered `200 text/html` because the Function only exported `onRequestGet`;
   HEAD matched the static handler instead. A monitoring probe would have called
   the endpoint healthy while it served the app shell — and for a *missing*
   artifact it would have hidden the 503. Both verbs now share one builder.
   This also fooled the first verification pass: the check that "failed" was my
   own `-I`, not the endpoint.
2. **The deploy summary logged only `MEMBER_BOOSTS`** while the generated config
   carried both bindings. Under-reporting logs are exactly how the missing
   binding hid the first time; the summary now lists every binding.
3. **Shared R2 secrets were world-readable** (`0644`) beside macaroons already at
   `0440`. Tightened to `0440 lnd:lnd`; the service runs as `lnd` and reads them
   fine.

### Deliberately not done

- `raised` is `$0.00` — a valid payload with no tagged boosts yet. The card
  renders `$2,000 to go`. Worth a product look: a zero-state meter is honest but
  unexciting; hiding it until the first contribution is a one-line policy change.
- Not yet verified in a real browser (no browser tool in this session), nor with
  a live tagged boost. Both are on the post-launch list.

---

## 2026-11-20 · The meter froze: an unbounded query, not a busy box

### Symptom

The published artifact went stale at 16:54:49 and was 8.9 minutes old when a
screenshot caught the card showing `delayed`. The UI was right — it refused to
show a number that had stopped moving — but the fundraiser was down.

### Diagnosis

Thread dump on the live JVM, main thread RUNNABLE:

```
datalevin.db.DB._eav_scan_v_list
boost_scraper.core$unique_content_ids
boost_scraper.core$sync_mising_boosts_BANG_
```

`sync-mising-boosts!` was called with `(- AUTOSCRAPE_START 3600)` — a constant in
**December 2023** — so every cycle re-diffed every boost ever recorded. Four
queries per cycle (destination and source, twice over), each scanning 347k rows.

### Measurements on the real database

```
unbounded window : 380,182 ms   347,352 rows
7-day window     :       241 ms     1,149 rows
```

### The box was not the problem

Worth writing down, because the obvious read was wrong. `MemAvailable` 18.6 GB,
memory pressure `0.00` across 10s/60s/300s, swap I/O idle. Swap was full of
**cold** pages, 8.3 GB of them belonging to `elementsd` (against 1.2 GB resident).
The stall was a linear query losing a fight for disk to a full Bitcoin + Elements
+ mempool stack doing 20–40 MB/s of reads.

### Three fixes

1. **Decoupled publishing** (`cdfab51`). The publisher was a future inside the
   ingest cycle, so ingest slowness silenced the meter for exactly as long as the
   ingest took. It now runs on a daemon thread at 60s, verified publishing on
   cadence while the cycle ran for minutes. This alone would have prevented the
   outage.
2. **Bounded the repair window** (`205618d`). Week of recent history instead of
   everything since 2023. `SYNC_LOOKBACK_DAYS` overrides. The commented-out
   `(two-days-ago)` at the call site says the intent was always bounded.
3. **Indexed `invoice/creation_date`** (`5eb9ff1`). Kept, because it makes the
   bounded query a 241ms range seek — but the index **alone did not fix this**:
   measured with the index and an unbounded range it was still 380s, because the
   planner had no reason to seek when the range was the entire database.

Production after: `Syncing JB` → `Destination IDs` went from 8s warm / 9min
loaded to **1 second**. Full sync across two sources: 6 seconds.

### Two self-inflicted bugs worth remembering

- **`reindex.clj` did not exit.** Datalevin starts non-daemon executor pools
  (`dirigiste`, `manifold`), so `-main` returning left the JVM alive and idle.
  The thread dump had no `main` thread at all — just `DestroyJavaVM` — and the
  I/O counters froze because the work was done, not because it was stuck. It
  read as a hung migration for 20 minutes. Fixed with `shutdown-agents` +
  `System/exit 0`.
- **I killed my own migration.** A cleanup `pkill -f migrate-index.sh` terminated
  the loop that was still working through the databases. `pkill -f` matches your
  own scripts as happily as the thing you meant.

### Cost

~45 minutes of meter downtime, of which roughly 20 was the JVM exit bug and the
interrupted loop rather than the migration itself. alby 204s, jbnode ~5min,
nodecan 16m49s to re-index.
