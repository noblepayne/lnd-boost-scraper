# Work Order — LUP 700 Campaign Meter

Status: **W1-W4 complete and green; W5-W7 remaining.**
Batches A and B are committed (scraper 174 tests / 964 assertions; web-boost
113 tests). Nothing is deployed yet.
Date: 2026-11-20. Owner: driver session. Repos: `lnd-boost-scraper`, `web-boost`.

## Objective

Show live progress toward "Get Brent in the Studio for LUP 700" ($2,000) on the
web-boost page, computed from boosts already in the scraper DB.

## Non-goals (explicitly skipped)

- No leaderboard / top-contributor list (invites status gaming; `sender_name` is
  unvalidated free text, so it is spoofable by design).
- No per-boost historical BTC rate capture. One rate is frozen per campaign.
- No new database, no KV/D1, no Cloudflare Tunnel, no public bucket.
- No identity/person merging (separate roadmap item).
- Streaming events excluded (policy already in config).

## The seam: published JSON contract

Everything hangs off this object. The scraper produces it; the web app consumes
it. Neither side knows the other's internals.

```json
{
  "slug": "lup-700-brent",
  "title": "Get Brent in the Studio",
  "subtitle": "Studio time, travel, and a decent chair.",
  "tag": "#700club",
  "goal":   { "usd": 2000.0, "sats": 2105263 },
  "raised": { "usd": 1240.0, "sats": 1305263,
              "contributions": 128, "people": 96, "anonymous": 12 },
  "remaining_usd": 760.0,
  "percent": 62.0,
  "days_left": 19,
  "opens_at": 1795075200,
  "closes_at": 1799654400,
  "generated_at": 1795450000,
  "rate": { "btc_usd": 95000.0, "at": 1795450000, "source": "mempool.space" },
  "recent": [
    { "sats": 2222, "usd": 2.11, "sender": "genebean", "at": 1795449000,
      "message": "for the 700 club" }
  ],
  "cta": { "label": "Boost with #700club",
           "url": "https://boost.jupiterbroadcasting.com/" }
}
```

Rules baked into the contract:
- `raised.usd` is converted with the rate **at emit time**, taken from the
  existing cached price feed (`price_feed/get-btc-usd`, 5-min TTL + stale
  fallback). `rate.at` records the moment, so the UI can label it honestly.
- If the feed has neither a fresh nor a stale rate, the publisher **skips the
  emit** (last good artifact stays) rather than publishing a wrong number.
- `recent` is newest-first, capped at 20; `sender` may be `"anonymous"`.
- `percent` is clamped for display but exact numbers stay exact.
- Absent artifact ⇒ consumers render nothing, never `0`.

## Steps

### W1 — campaign progress computation (scraper, Clojure)
Files: new `src/boost_scraper/campaigns.clj`; new `test/boost_scraper/campaigns_test.clj`.
No config change: the BTC/USD rate comes from `price_feed/get-btc-usd` at call time.
- Load campaigns from the EDN resource.
- `progress [conn campaign now] -> <contract map minus recent>` using
  `contributions` (window-bounded), membership = message contains tag
  (case-insensitive) OR episode = `also-episode`; respect policy
  (`:actions #{:boost}`, fiat counts, member-free presence only, self excluded).
- USD: `sats / 1e8 * rate`, plus fiat cents / 100, where `rate` is the cached
  BTC/USD at emit time. Integer sats/cents in, rounded USD out.
- No rate available (fresh or stale) → return a `:no-rate` result; the caller
  must not publish. This is a refusal, not a fallback to zero.
- Acceptance: golden test on fixtures produces exact usd/sats/percent/remaining;
  refusal test for unknown slug; boundary tests (before open, at close);
  tag case-insensitivity; episode match; anonymous counted, unnamed; self
  excluded; **no-rate refusal** (feed empty and no stale value ⇒ `:no-rate`).

### W2 — internal HTTP surface (scraper)
Files: `src/boost_scraper/web.clj`, `test/boost_scraper/web_test.clj`.
- `GET /api/v1/campaigns` (list: slug + title + state from window)
- `GET /api/v1/campaigns/:slug` (the contract object, including `recent`)
- 404 unknown slug. Read-only. No write path.
- Acceptance: route tests for both, plus 404.

### W3 — R2 publisher (scraper)
Files: new `src/boost_scraper/publish.clj` + test; wire into the existing loop.
- Serialize the contract to `campaign/<slug>.json` in a **private** bucket.
- Timer-driven, **outside** the ingest path, per-publish timeout; a failed put
  must never stall ingestion and must leave the previous object intact.
- `:no-rate` ⇒ skip the put entirely (keep last good object) and log it.
- Acceptance: unit test for key/serialization; timeout test proves the caller
  is not blocked; no new ingest coupling.

### W4 — Pages Function serving the artifact (web-boost)
Files: `functions/api/campaign/[slug].json.ts`, `wrangler.toml` binding.
- Read from the R2 binding; return JSON with
  `Cache-Control: public, max-age=20, stale-while-revalidate=120, stale-if-error=86400`
  and an `ETag`. **Never `s-maxage`.** Missing object ⇒ **503** with
  `Retry-After` (never 404, which would evict good cache).
- Acceptance: 503 with no object; 200 + exact headers with object; unknown slug
  ⇒ 503 as well (no enumeration signal).

### W5 — the card on the page (web-boost)

Files: `src/main.ts` (+ styles), a pure render module + its tests.
Status: **not started.** The HTML preview at
`web-boost/docs/mockups/campaign-card.html` is the visual specification.

**Markup and layout** (inherit the page's existing tokens — near-black card,
1px border, mono uppercase eyebrow, pill radius, `--jb-*` colours):

1. eyebrow: tag pill `#700club` + `LUP 700`
2. title (`h2`, ~1.9rem): from payload `title`
3. one-line subtitle from `subtitle`
4. numbers row: `$1,240` (~44px, tabular-nums) · `of $2,000` muted ·
   `$760 to go` right-aligned in gold
5. bar: 8px, pill radius, **solid `--jb-orange` fill** (no gradient, no stripes),
   track `rgba(255,255,255,.08)` + 1px inset ring
6. footer (13px, quiet): `128 boosters · 19 days left · USD valued at time of boost`

**Placement:** one card, directly above the boost form's first section, and only
when the selected podcast has an active campaign. It never appears twice.

**Data:** `GET /api/campaign/<slug>` (the artifact). The card never computes a
number — `raised.usd`, `remaining_usd`, `percent`, `days_left` are read as-is.

**Failure and staleness rules** (these are the contract, not polish):

- Skeleton while loading. **Never render `0`.** `$0` is legal only from a parsed
  payload with a valid `generated_at`.
- Payload age > 5 min → append an amber `delayed` note; keep showing the last
  known number.
- Fetch failure or age > 30 min → **hide the card** entirely.
- `503` from the endpoint is a normal state, not an error: hide the card.

**Over 100%:** clamp the fill at 100%, keep the text honest (`$2,410 of $2,000`),
replace `$X to go` with `goal met`, shift the fill to gold. **Never** reset to a
new smaller goal.

**Motion:** one count-up on first paint (≤800ms), one 400ms width transition on a
real change. Nothing loops. All gated behind `prefers-reduced-motion:
no-preference`. The final number is present in the DOM without JS.

**Accessibility:** `<progress>` (or `role="progressbar"` with min/max/now) plus
`aria-valuetext="$1,240 of $2,000 raised, $760 to go"`. Fill vs track ≥3:1, text
≥4.5:1, and "goal met" must not be signalled by colour alone.

**Campaign picker preselection — `?campaign=<slug>` (decided 2026-11-20):**

- The picker **defaults to "No campaign"**. It is never preselected by default:
  the campaign is a lens on ordinary support, so defaulting would silently
  attribute normal boosts to the trip and make the total a lie.
- A `?campaign=<slug>` URL parameter preselects it **when that campaign is active
  for the currently selected podcast**. Intent follows the link, not the page.
- Invalid, unknown, or closed campaign in the URL → **ignore silently** and leave
  "No campaign". A stale tweet must not break the form.
- Changing the podcast re-evaluates: keep the preselection only if that show runs
  that campaign.
- **No localStorage.** A remembered choice would keep tagging boosts to a
  campaign that has since closed.
- Links that must carry the parameter: the meter card's own CTA, the
  `<podcast:funding>` URL, show notes, and any on-air link.

**Tests (pure render + param parsing; no network):**

- payload → expected numbers, bar width, and `aria-valuetext`;
- missing/garbage payload → hidden, and specifically **not** `0`;
- age > 5 min → `delayed` note present, number unchanged;
- age > 30 min or 503 → hidden;
- over-goal payload → fill clamped, `goal met` shown, no new goal;
- `?campaign=` valid for the show → preselected; for another show → not;
  unknown/closed → not;
- reduced-motion preference suppresses count-up and transition.

### W6 — documentation (driver, after code lands)

`API.md` (the campaign endpoints and the artifact contract),
`docs/campaign-goal-spec.md` §4.2/§6, and a dev-log entry. Also hand the feed
host the funding snippet:

```xml
<podcast:funding url="https://boost.jupiterbroadcasting.com/?campaign=lup-700-brent">Help get Brent to the studio for LUP 700</podcast:funding>
```

(`<podcast:funding>` is *Count: Multiple*; node value ≤128 chars; LUP already
carries a Memberful funding tag, so this is an addition, not a swap. The URL
carries `?campaign=` on purpose — that link is the campaign's front door.)

### W7 — operations and rollout (driver + Wes)

Nothing here is code; it is the path to a number on a screen.

1. **Create the R2 bucket** (private) and confirm the deploy account can write
   it. Set `R2_CAMPAIGN_BUCKET` on the scraper box; confirm the existing R2
   credential variables are readable by the service.
2. **Deploy the scraper** (batches A+B: compute, routes, publisher). Verify:
   - `GET /api/v1/campaigns` lists `lup-700-brent` with state `active`;
   - `GET /api/v1/campaigns/lup-700-brent` returns the contract (or a clean 503
     if the price feed is down — never partial numbers);
   - the publisher's first artifact lands in `campaign/lup-700-brent.json`.
3. **Bind and deploy web-boost** (Cloudflare Pages): `CAMPAIGN_BUCKET` binding to
   the real bucket, then `nix develop -c npm run pages:deploy:production`.
4. **Verify production:**
   - `curl https://boost.jupiterbroadcasting.com/api/campaign/lup-700-brent`
     returns the artifact with the agreed `Cache-Control` and no `s-maxage`;
   - the card renders on the page for LUP and is **absent** for other shows;
   - `?campaign=lup-700-brent` preselects the picker; `?campaign=nope` does not;
   - a boost through the form arrives in the scraper with `#700club` appended to
     the message and appears in the meter within one publish cycle.
5. **Send the funding snippet** to the JB feed host (external dependency, not our
   repo).
6. **Kill-switch drill:** point the function at a missing object and confirm the
   card hides rather than showing `$0`.

### Order of work and who does what

```
W5 card (worker)            → driver: suites + review + commit
W7.1 bucket/creds (Wes)     → W7.2 scraper deploy (driver)
W7.3 pages deploy (Wes+driver) → W7.4 production verify (driver)
W6 docs (driver)            → W7.5 funding snippet to feed host (Wes)
```

W5 and W7.1 are independent: the bucket can be created while the card is being
built. Production verification needs both.

## Dispatch rules (still in force for W5)

Workers never commit, never run the suite, never delete (contractor §3).
The driver runs the suites serially, reviews, and commits; reviews use a
different model than the editor (§3.4); one writer per working tree (§3.2 —
use a git worktree when two workers need the same repo).

## Verification before this is called done

Code gates (driver):

1. `clojure -M:test` green.
2. `web-boost`: `npm test` + `tsc --noEmit` green.
3. Golden number: the contract's `raised.usd` equals an independently
   hand-computed value for the same window; `remaining_usd` equals
   `goal.usd - raised.usd`.
4. The function returns 503 when the artifact is absent.
5. The card renders hidden, never `0`, when the fetch fails.

Production gates: see W7.4.

## Risks

- **Rate at emit time (decided 2026-11-20).** Reusing the cached feed is the
  simpler design — no stored knob, and reports already use it. The accepted
  tradeoff: a falling BTC price makes `raised.usd` retreat. Mitigations kept:
  `rate.at` is published so the UI labels the conversion, `raised.sats` is
  published alongside so the stable number is always visible, and the UI never
  animates a decrease. Revisit if it ever looks bad on air.
- **Tag capture ceiling** for third-party apps (Breez caps ~128 chars). The web
  form is exact because we append server-side; other apps are best-effort.
- **First publish** must precede the first page hit, or the card shows the
  skeleton until the timer fires.
