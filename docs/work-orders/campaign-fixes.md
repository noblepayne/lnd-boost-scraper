# Work Orders — Campaign Meter Follow-ups

Post-launch fixes and coverage. The meter is live (2026-11-20); none of this is
urgent except **W8**, which corrects a false claim on a public page.

Decisions already taken (do not relitigate):
- Zero-state meter stays visible — `$0.00 of $2,000` is honest.
- Public `recent` keeps sender handles (sats, usd, sender, at — never message text).
- Card eyebrow keeps the show name (the artifact has no episode label).
- Campaign closes 2027-01-11 as a placeholder; extend if ep 700 slips.

---

## W8 — Correct what the meter claims (do first)

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

- Give the fill a minimum width (~2–3px) whenever `raised.usd > 0` and the
  computed percentage rounds below visibility.
- Do not invent precision in the text: `$0.87 of $2,000` stays exact. Only the
  *fill* gets a floor.
- Guard: an exact zero must still render an empty bar.

Acceptance: unit tests for `percent = 0.0` with `raised > 0` (non-zero width),
and for `raised = 0` (zero width); footer string asserted to match the new copy;
no revaluation logic added.

---

## W9 — Make publishing observable

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

## W10 — Document the campaign surface

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

- **Fiat path unverified live.** All counted contributions so far are sats. A
  single $2.00 USD boost tagged `#700club` exercises `amount_fiat_cents` →
  USD in the meter. Worth doing before launch promotion.
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
