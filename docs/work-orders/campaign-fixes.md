# Work Orders — Campaign Meter Follow-ups

Post-launch fixes and coverage. The meter is live (2026-11-20).

**Status: W8, W9 and W10 DONE 2026-11-20.** Code shipped and verified in
production (`web-boost 53f4f2b`, `scraper 8e1544a`); the campaign endpoints are
documented in `API.md`. Only the manual items remain.

Decisions already taken (do not relitigate):
- Zero-state meter stays visible — `$0.00 of $2,000` is honest.
- Public `recent` keeps sender handles (sats, usd, sender, at — never message text).
- Card eyebrow keeps the show name (the artifact has no episode label).
- Campaign closes 2027-01-11 as a placeholder; extend if ep 700 slips.

---

## W8 — Correct what the meter claims — DONE

Shipped in `web-boost 53f4f2b`; production verified: new copy present in the
bundle, old false copy absent, 3px floor present, endpoint unchanged.
146 → 149 tests.

Two defects, both visible to listeners.

**8a. The footer asserts a valuation we do not perform.**
`src/campaign-card.ts` renders `USD valued at time of boost`. We actually value
every contribution at **emit time** with the current rate, and revalue the whole
total on each publish. The sentence is false.

- Fix the copy to something true, e.g. `USD valued at the current rate`.
- Keep `rate.at` rendered nearby (or in the title attribute) so the label is
  auditable, not decorative.
- Do **not** implement per-boost historical valuation here. That was declined:
  it would touch all three ingest paths for a 7-week campaign. The copy should
  describe what the system does.

**8b. The bar reads empty while there is money in.**
`percent` rounds to one decimal, so $0.87 of $2,000 is `0.0` and the fill is
zero-width — indistinguishable from zero support.

- Give the fill a minimum width (~2–3px) whenever `raised.usd > 0`. The first
  fix only fired when `percent` rounded to zero; the fiat boost produced `0.1`,
  which is greater than zero and still about half a pixel on a real bar. A
  min-width is inert at 62% and correct at 0.1%, so it applies to any nonzero
  total.
- Do not invent precision in the text: `$0.87 of $2,000` stays exact. Only the
  *fill* gets a floor.
- Guard: an exact zero must still render an empty bar.

Acceptance: unit tests for `percent = 0.0` with `raised > 0` (non-zero width),
and for `raised = 0` (zero width); footer string asserted to match the new copy;
no revaluation logic added.

---

## W9 — Make publishing observable — DONE

Shipped in `scraper 8e1544a`. Production line:

```
Campaign published lup-700-brent: $0.87 raised (1477 sats, 2 contributions), rate 58990 USD/BTC (mempool.space)
```

A successful publish is silent, so "is it publishing?" can only be answered by
fetching from R2. That is the difference between a 2-minute and a 20-minute
diagnosis next time.

- Log one line per publish with: campaign slug, `raised.usd`, contribution count,
  and the rate used. Keep it to one line per cycle.
- Log when the artefact **changes** distinctly from when it is unchanged, if that
  is cheap; otherwise one line per cycle is fine.
- Do not log inside the put path in a way that can fail the publish.

Acceptance: a unit test with an injected logger asserts the line is emitted on
success and includes the slug and raised amount; the no-rate path keeps its
existing skip message.

---

## W10 — Document the campaign surface — DONE

Written into `API.md` §Campaigns, including the emit-time valuation rule, the
`sats`-is-stable guidance, the one-decimal `percent` rounding, and why the public
path must never carry `s-maxage`.

`API.md` covers feed, analysis, schema and boosties but not campaigns.

- `GET /api/v1/campaigns` — index: `slug`, `title`, `state`
  (`scheduled` / `active` / `closed`).
- `GET /api/v1/campaigns/:slug` — the artifact contract plus `recent`;
  404 unknown, 503 `no-rate` with **no numbers** in the body.
- `GET /api/campaign/:slug` (public, Pages Function) — the published artifact;
  `Cache-Control: public, max-age=20, stale-while-revalidate=120,
  stale-if-error=86400`, ETag, **never `s-maxage`**; 503 + `Retry-After` +
  `no-store` when unavailable. HEAD mirrors GET.
- The artifact contract itself, copied from the work order, including the rule
  that `recent` carries names but not message text.
- A note that `raised.usd` is converted at emit time, so it moves with the rate.

Acceptance: a reader can call every endpoint correctly without reading source.

---

## Manual / calendar — no work order

- ~~**Fiat path unverified live.**~~ **VERIFIED 2026-11-20.** A $2.00 USD tagged
  boost moved the meter $0.87 → $2.87 with `sats` correctly unchanged at 1,477
  and `people` still 2 (the same handle boosted twice). The journal showed the
  transition, and the artifact followed one cycle later.
- **Browser check.** The card and `?campaign=` preselection have never been
  seen rendering (chrome MCP exposes no tools in the agent session). Open
  `https://boost.jupiterbroadcasting.com/?campaign=lup-700-brent` and confirm:
  picker preselected, card shows $0.87-class numbers, goal-met styling not
  triggered, and switching to another show hides the card.
- **Extend the window** if ep 700 slips past 2027-01-03 — one line in
  `resources/campaigns.edn`, redeploy.
- **Send the funding tag** to the JB feed host (W7.5), now with the campaign URL:
  `https://boost.jupiterbroadcasting.com/?campaign=lup-700-brent`

## Deliberately skipped

Bumping wrangler to fix `r2 object get` (workerd SQLite bug). The
`curl --aws-sigv4` workaround is adequate and upgrading mid-campaign adds risk
for no user-visible benefit.

## Follow-ups from the fiat verification (2026-11-20)

- **Whole dollars now yield to cents below $10** (`web-boost ad7f175`). $0.87
  used to render as "$1", overstating the most visible number on the page by 13
  cents. Above $10 the cents return to being noise against a four-figure goal.
- **Tag matching is pinned against punctuation** (`scraper 215fdca`).
  `#700club!`, `#700club.`, `#700club,`, `(#700club)`, `#700CLUB?` all count,
  because the rule is case-insensitive containment. Accepted trade: a longer
  word containing the tag also matches.
