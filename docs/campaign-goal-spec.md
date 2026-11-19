# Campaigns & Goals — Design Spec

Status: **proposal, for review** (2026-11-19)
First use: *Get Brent to the Studio for LUP 700*. Generalizes to Planet Nix,
SCaLE, TWiB per-episode goals, and any future fundraiser.

---

## 0. The framing: one accounting core, many lenses

We already compute "how much support came in" in three places that do not
fully agree:

| Lens | Lives in | Question it answers |
|---|---|---|
| Feed | `feed.clj` | what happened, newest first |
| Reports | `reports.clj` | period summary per show |
| Boosties | `boosties.clj` | who is on top |

A campaign meter is a **fourth lens**. The trap is building it as a fourth
independent query path — then the meter disagrees with the report on air, and
we spend the next year explaining the difference.

**Principle: one accounting core, many lenses.** All four read from the same
`contribution` definition (what counts, and how it is deduped). Campaigns add
only *selection* (which contributions belong to this goal) and *presentation*
(the meter), never their own arithmetic.

This spec therefore has two halves: the **accounting core** (§2) and the
**campaign layer** (§3-§5). The core is the work that makes the project shine;
the campaign layer is small on top of it.

---

## 1. What already exists (do not rebuild)

- **Boost entities** with `:boostagram/*` attributes, `:invoice/identifier`,
  `:invoice/creation_date`, `:scraper/source`, `:boostagram/type`
  (`:sat` / `:fiat` / `:member-free`).
- **Reconcile machinery** that already had to answer "is this the same
  payment?" for fiat-order↔invoice pairs. That knowledge is the dedupe rule.
- **Price feed** (`price-feed.clj`): BTC/USD with a fallback chain and 5-min
  cache, already used for fiat display in reports.
- **Show registry** (`shows.clj`): slugs → regexes.
- **Boosties** (`/api/v1/boosties`): healed, but implemented on its own query.

---

## 2. The accounting core — `contribution`

### 2.1 Definition

A **contribution** is one settled value movement, resolved to a person and
deduped to a payment identity.

```
{:contribution/payment-id     "zaprite-od_..." | "342724"   ; dedupe key
 :contribution/entity         359123                          ; source datom
 :contribution/person         "person:genebean" | "person:unknown:xyz"
 :contribution/action         :boost | :stream
 :contribution/type           :sat | :fiat | :member-free
 :contribution/sats           1500        ; integer; 0 for fiat/member-free
 :contribution/fiat-cents     200         ; nil unless :fiat
 :contribution/occurred-at    1786073774  ; epoch seconds (LND settle preferred)
 :contribution/show           "lup"
 :contribution/episode        "700"
 :contribution/evidence       {:matched-by :episode}  ; why a campaign counted it
}
```

### 2.2 Rules (the whole point — written once, used everywhere)

1. **Settled only.** A boost entity exists only after settlement (webhook or
   sync); no separate "pending" arithmetic.
2. **Payment-identity dedupe.** One payment = one contribution. Key:
   `:boostagram/zaprite_order_id` when present, else `:invoice/identifier`.
   *This is the rule that prevents the known fiat-order + LND-invoice double
   record.* Established during the reconcile work; here it becomes canonical.
3. **Self-boosts are a policy, not a hardcode.** `:exclude-self?` with the
   `#{chrislas noblepayne}` set, default on for public lenses.
4. **Types are kept distinct, never silently merged.** `:sat` contributes
   sats; `:fiat` contributes cents; `:member-free` contributes presence but
   no value. A lens chooses what it sums (see §2.3).
5. **Actions are kept distinct.** `:boost` and `:stream` are different acts;
   lenses may include both but must say so.
6. **Amounts are integer sats / integer cents.** No floats in the core.
   Conversion happens only at presentation, with the rate recorded.

### 2.3 Value resolution (the honest money model)

Two currencies, never conflated:

```
raised_sats  = Σ sats   (type :sat)
raised_cents = Σ cents  (type :fiat)          ; already USD cents
raised_usd   = raised_cents/100 + sats_to_usd(raised_sats, rate_at_snapshot)
```

Money-critical screens report **both** and label the conversion, exactly like
the existing reports do (`"Total Sats: 52,000 (incl. 2,000 fiat @ 1,643
sats/USD, via mempool.space)"`).

### 2.4 API

```clojure
(core/contributions conn {:show "lup" :from t0 :to t1
                          :actions #{:boost} :exclude-self? true})
;; => seq of contribution maps, deduped, person-resolved
```

Lenses build on it: `feed`, `reports`, `boosties` are refactored onto it
**only after** it provably reproduces today's numbers (see §7).

### 2.5 Why not just extend Boosties?

Because Boosties is a *ranking* lens with no notion of dedupe, identity, or
policy. Campaigns need all three, and the feed/report need dedupe too. Putting
the rule in one place removes a class of future bugs rather than adding a
layer.

---

## 3. Identity — `person`

Handle strings are noisy. Observed in the live data:

```
thedudeabides          thedudeabides@fountain.fm
biggles                biggles@fountain.fm
r-podcast              r-podcast (eric)
odysseywestra          odysseywestra@fountain.fm
```

Today these are separate leaderboard rows. For a "who funded it" list and for
fair rankings, one human should be one row.

### 3.1 Model

```
person  { :person/id "p-uuid"  :person/display-name "thedudeabides"
          :person/aliases #{"thedudeabides" "thedudeabides@fountain.fm"} }

alias   { :alias/normalized "thedudeabides"
          :alias/person "p-uuid"  :alias/source :derived | :manual }
```

### 3.2 Resolution (composable, in order)

1. **Exact alias** (already mapped) → person.
2. **Deterministic normalization** → strip `@<app>` suffix, strip trailing
   parenthetical, lowercase, collapse whitespace. If a prior alias matches,
   bind it (source `:derived`).
3. **Manual alias** — an operator mapping for the exceptions
   (`r-podcast` / `r-podcast (eric)`), stored in config, reviewable.
4. **Unclaimed** — otherwise a stable person keyed off the raw handle. Never
   drop a contribution because identity is unknown.

### 3.3 Guardrail

Derived aliases are **append-only evidence**, never destructive: we record
"this handle resolved to this person at this time". If a merge turns out
wrong, we can split it. Never rewrite historical boosts.

---

## 4. Campaign — the model

Config lives in the repo (EDN under `resources/`, git-versioned, reviewable);
runtime snapshots live in Datalevin.

```clojure
{:campaign/slug        "lup-700-brent"
 :campaign/title       "Get Brent to the Studio for LUP 700"
 :campaign/description "Fly Brent out so episode 700 can be recorded together."
 :campaign/scope       {:shows #{"lup"} :episodes #{"700"}}
 :campaign/window      {:opens 1795000000 :closes 1801000000}  ; epochs (UTC)
 :campaign/target      {:fiat-cents 250000            ; USD 2,500 — the real cost
                        :rate-at-creation 1650000      ; sats/BTC snapshot
                        :rate-source "mempool.space"}
 :campaign/matcher     {:any-of [{:type :episode :show "lup" :episode "700"}
                                 {:type :message :regex "(?i)#\\s*700\\s*club"}]}
 :campaign/policy      {:actions #{:boost} :count-fiat? true :exclude-self? true}
 :campaign/cta         {:label "Boost #700club"
                        :url "https://boost.jupiterbroadcasting.com/"}
 :campaign/state       :auto}   ; :auto derives from window; explicit override allowed
```

### 4.1 Why the target is in fiat cents (and sats are derived)

The cost of flying Brent is **USD**. If the target were stored in sats, a BTC
price move would silently change the fundraiser's goal. So:

- `target.fiat-cents` is canonical and frozen at creation.
- `sats_needed = fiat_to_sats(target.fiat-cents, rate_at_snapshot)` is shown
  alongside, labelled as today's rate.
- Progress shows `raised_usd` (see §2.3) and `pct = raised_usd / target_usd`.

### 4.2 Matcher — composable, data not code

```
{:type :episode  :show "lup" :episode "700"}          ; structural match
{:type :message  :regex "(?i)#\\s*700\\s*club"}        ; text match
{:type :tag      :key :campaign :value "700club"}      ; future widget field
{:any-of [...]} {:all-of [...]} {:not {...}}           ; composition
```

Compiled to a Datalog where-builder. Adding a campaign never adds code.

**Start with `episode OR #700club`**: the hashtag makes pre-episode
fundraising possible (eps 694-699), episode match covers the finale, and
neither needs a widget change. The `:tag` form reserves the clean path for
when the web-boost widget can emit a campaign field.

### 4.3 Lens semantics — the product question

**Decision required:** is a campaign a *lens* or a *ring-fence*?

- **Lens (recommended):** campaign-tagged boosts are ordinary show support
  that also count toward the goal. Nothing is siphoned; the meter answers
  "how much of our support was for this". No new payment path, no accounting
  split, works with the web boost as-is.
- **Ring-fence:** a separate checkout whose money is earmarked. Cleaner
  promises ("your $20 goes only to the trip") but needs a separate payment
  flow and separate accounting, and splits the audience's giving.

Recommendation: ship the lens first (it is honest and cheap), and only build
ring-fencing if a fundraiser genuinely requires earmarking.

---

## 5. Snapshot — the audit record

```
{:snapshot/campaign    "lup-700-brent"
 :snapshot/at          1795200000
 :snapshot/raised-sats 412345
 :snapshot/raised-cents 0
 :snapshot/raised-usd  227.43
 :snapshot/target-cents 250000
 :snapshot/pct         9.1
 :snapshot/contributors 37          ; distinct persons
 :snapshot/contributions 52         ; counted contributions
 :snapshot/top [{:person "p-..." :display "blackhost" :usd 98.10} ...]
 :snapshot/rate {:sats-per-btc 1650000 :source "mempool.space" :at ...}
 :snapshot/version 1}
```

Snapshots are **immutable and timestamped**. They give us:
- the published artifact (a JSON projection of the latest snapshot),
- an audit trail ("what did the meter say on Friday at 3pm"),
- reproducibility (the rate used is recorded).

Idempotency: a snapshot is keyed by `(campaign, at)`; recomputation at the same
minute is a no-op.

---

## 6. Publication — decoupled

The campaign model knows nothing about hosting.

| Projection | Mechanism |
|---|---|
| Internal preview | `GET /api/v1/campaigns`, `GET /api/v1/campaigns/:slug` |
| Public meter | JSON artifact → R2 (public read) → static page fetches it |
| Future | showfactory pulls the internal API; or a dedicated checkout |

**Do not expose the scraper publicly.** It carries a query API and reconcile
routes; a static artifact removes that entire surface. A tunnel is acceptable
only for a single read-only path behind an authenticated edge, and even then
the artifact is simpler.

Cadence: publish every 1-5 minutes. Staleness is irrelevant for a thermometer;
if "live" is ever needed, the page can poll a cached artifact.

---

## 7. Rollout, and how we avoid breaking today's numbers

**P1 — accounting core + campaigns (no publishing).**
1. `contributions` core with dedupe + policy. **Golden test:** it must
   reproduce, exactly, today's `/api/v1/boosties` totals and
   `/analysis/top-boosters` numbers on fixed windows before anything depends
   on it.
2. `identity` with the deterministic rules + a small manual alias table.
3. `campaigns` model + matcher compiler + `GET /api/v1/campaigns/:slug`
   (internal).
4. Tests: dedupe (fiat+invoice pair counted once), self-exclusion, window
   boundaries, matcher composition, identity normalization.

**P2 — snapshots + publication.**
5. Snapshot writer (on a timer or on request), stored in Datalevin.
6. JSON projection → R2; static thermometer page (target USD, raised USD+sats,
   pct, contributor count, countdown, CTA).

**P3 — optional depth.**
7. Widget campaign field (`:tag` matcher becomes available).
8. Refactor `boosties`/`reports` lenses onto `contributions` (numbers already
   proven equal in step 1, so this is mechanical).
9. showfactory integration.

---

## 8. Open decisions for review

1. **Lens vs ring-fence** (§4.3) — recommend lens.
2. **Do fiat boosts count** toward campaign progress? Recommend yes, reported
   as a separate USD line (they are real money and the target is USD).
3. **Do `:member-free` boosts count?** Recommend: count contributors/presence,
   not value.
4. **Do streams count?** Recommend no for fundraisers (they are ambient
   listen-through), and make it policy so TWiB can choose otherwise.
5. **Identity scope now** — full deterministic + manual aliases, or exact-only
   for v1? Recommend deterministic + manual; it is a small table and it fixes
   a visible split in today's leaderboards.
6. **Target currency** — recommend USD-cents canonical with sats derived (§4.1).
7. **Publish host** — R2 static page vs Cloudflare Pages vs showfactory.

---

## 9. Anti-goals (things this design refuses to do)

- No parallel ledger that copies boost amounts (drift by construction).
- No campaign-specific arithmetic in web handlers or page JS.
- No floats in the accounting core.
- No hardcoded hashtag/episode logic in code — matchers are data.
- No public exposure of the scraper process.
- No silent merging of fiat and sats; conversions are labelled, with the rate.

---

## 10. Naming

"Campaign" = the umbrella (a purpose with a target and a window).
"Goal" = the target number inside a campaign.
"Contribution" = one counted value movement.
"Snapshot" = the meter at a moment.
"Person" = a resolved identity.

Keeping these four words distinct is most of the composability.
