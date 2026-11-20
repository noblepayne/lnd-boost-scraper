# Work Order — LUP 700 Campaign Meter

Status: **awaiting review — do not dispatch until Wes passes this.**
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
  "rate": { "btc_usd": 95000.0, "at": 1795075200, "source": "mempool.space" },
  "recent": [
    { "sats": 2222, "usd": 2.11, "sender": "genebean", "at": 1795449000,
      "message": "for the 700 club" }
  ],
  "cta": { "label": "Boost with #700club",
           "url": "https://boost.jupiterbroadcasting.com/" }
}
```

Rules baked into the contract:
- `raised.usd` uses the **frozen campaign rate**; it must never decrease.
- `recent` is newest-first, capped at 20; `sender` may be `"anonymous"`.
- `percent` is clamped for display but exact numbers stay exact.
- Absent artifact ⇒ consumers render nothing, never `0`.

## Steps

### W1 — campaign progress computation (scraper, Clojure)
Files: new `src/boost_scraper/campaigns.clj`; new `test/boost_scraper/campaigns_test.clj`;
add `:campaign/rate {:btc-usd … :at … :source …}` to `resources/campaigns.edn`.
- Load campaigns from the EDN resource.
- `progress [conn campaign now] -> <contract map minus recent>` using
  `contributions` (window-bounded), membership = message contains tag
  (case-insensitive) OR episode = `also-episode`; respect policy
  (`:actions #{:boost}`, fiat counts, member-free presence only, self excluded).
- USD: `sats / 1e8 * rate.btc_usd`, plus fiat cents / 100. Integer sats/cents in,
  rounded USD out.
- Acceptance: golden test on fixtures produces exact usd/sats/percent/remaining;
  refusal test for unknown slug; boundary tests (before open, at close);
  tag case-insensitivity; episode match; anonymous counted, unnamed; self excluded.

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

### W5 — the card (web-boost)
Files: `src/main.ts` (+ styles), test for the pure render function.
- One card above the episode picker, matching existing tokens (near-black,
  1px border, mono orange eyebrow, pill radius).
- Layout: eyebrow `#700club · LUP 700` → title → one-line subtitle →
  `$1,240` (44px tabular) `of $2,000` muted · `$760 to go` right →
  8px bar (orange fill, no stripes/gradients) → `128 boosters · 19 days left`.
- Behaviour: skeleton → fetch → render. **Never render 0.** Stale >5 min →
  amber "delayed"; fetch failure and stale >30 min → hide the card. Count-up
  once on first paint (≤800ms), one 400ms fill transition on a real change, all
  gated on `prefers-reduced-motion`. `<progress>` + `aria-valuetext`.
- Over 100%: clamp fill, honest text, `· goal met`, never re-goal.
- Acceptance: pure render fn unit tests (payload → expected text/attrs;
  missing payload → hidden, never "0"); a11y attributes asserted.

### W6 — docs (driver, after code lands)
`API.md` (new endpoints), `docs/campaign-goal-spec.md` §4.2/§6, dev-log entry,
and the feed snippet for the JB feed host:

```xml
<podcast:funding url="https://boost.jupiterbroadcasting.com/campaign/lup-700-brent">Help get Brent to the studio for LUP 700</podcast:funding>
```
(spec: `<podcast:funding>` is *Count: Multiple*; node value ≤128 chars; LUP
already carries one Memberful funding tag, so this is an addition, not a swap.)

## Dependency graph / dispatch plan

```
driver: freeze contract + rate in campaigns.edn
   ├── Batch A (parallel): W1 (compute) · W4 (function) · W5 (card)
   ├── Batch B:            W2 (routes) · W3 (publish)      ← need W1's shape
   └── Batch C (driver):   W6 docs + integration verify + commit
```

Workers never commit, never run the suite, never delete (contractor §3).
Driver runs tests serially and commits. Reviews use a different model than the
editor (§3.4). Model IDs chosen at dispatch time from the roster.

## Verification before this is called done

1. `clojure -M:test` green (all suites, driver-run).
2. `web-boost`: `npm test` + `tsc --noEmit` green.
3. Golden number: with today's data the contract's `raised.usd` equals an
   independently hand-computed value for the same window; `remaining_usd`
   equals `goal.usd - raised.usd`.
4. Function returns 503 when the artifact is absent (kill switch proof).
5. Card renders hidden, not zero, when the fetch fails.

## Risks

- **Rate freeze** under-values later boosts if BTC moves a lot in 7 weeks. Label
  the meter ("USD valued at campaign rate"); revisit if it ever looks wrong.
- **Tag capture ceiling** for third-party apps (Breez caps ~128 chars). The web
  form is exact because we append server-side; other apps are best-effort.
- **First publish** must precede the first page hit, or the card shows the
  skeleton until the timer fires.
