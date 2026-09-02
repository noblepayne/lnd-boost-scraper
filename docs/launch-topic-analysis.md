# The Launch — What Gets Boosted (Analysis Findings)

Analysis of every launch-boost entity in the nodecan DB (through 2026-08-26),
joined to the show's RSS publication dates. This is the **findings**, not the
method — for the reusable tooling see `scripts/show_intake_analysis.py` and
`docs/query-api-briefing.md`.

**Scale**: ~15k boost entities · 461 authored ("boost") actions · 380 with a
text message · 162 distinct boosters. Most entities (14.7k) are live "stream"
events — silent engagement during the broadcast.

---

## 1. What gets boosted — by engagement *type*

There are two different "most boosted" answers depending on the measure:

**By participation (count): the listener-prompt personal-story episodes.**
Episodes where Chris and Angela ask a question ("what's in your pockets",
pets, hobbies, thrift stores, holiday traditions, your first computer, music
upbringing) draw the most *authored* boosts and the most *messages across
distinct senders*. This is the show's engagement engine. Top message-drawing
episodes: The Things Tim Didn't Tell Us (16 msgs / 12 senders), Eggsistential
(11/7), The Age of Verification (12/…), Spilling the Tea (9/7), Full Self
Drying (9/5), RAM Racket (11/…).

**By value (sats): show-survival / sustaining moments.**
The mega-boosts (100k–600k sats) attach to survival beats — "Please don't stop
the Launch", "Please don't cancel the show!", web-boost fundraising, thanks
for keeping it alive (e.g. "44: Don't Ignore Nora" 633k sats, "Holy IPO" 583k,
"Minimaxxing" 499k). A handful of loyal mega-boosters dominate the value
ranking. The other 46,000-sats-or-less episodes are the long tail that carries
the community.

## 2. Recurring topic clusters (cross-episode)

Beyond prompts and survival, these themes recur and reliably draw support:

- **AI** — both directions: fear-marketing of Anthropic ("42: The Scam Behind
  AI"), AI at work/healthcare adoption, AI-assisted podcasting, AI-at-school.
  Consistent, opinionated engagement.
- **Hardware / gadgets / garage** — Framework Desktop (a running saga),
  Tesla/EVs (including the listener-suggested RTG nuclear option), charger
  brands (Anker vs UGREEN), 3D printers, robot vacuums, batteries.
- **Platform / corporate criticism** — car-makers paywalling purchased
  features, Sony killing physical media, GitHub outages, CTO stock-pumping
  stories. This reliably gets people to sub in.
- **Running bits** — Flock cameras ("pigs at large", a multi-episode saga),
  the van/bus, TIMETRAVEL boosts (pabi), "boosty boosty boosty", first-to-boost
  races. These build loyalty and cross-episode participation.
- **Niche curiosities** — Bigfoot/mythical monsters, aliens & cryptids, septic
  systems, RTG batteries. Odd-ball topics punch above their weight.

## 3. Does a prompt episode lift the *next* episode? — No.

Tested explicitly because the naive comparison looked positive (episodes
following prompt episodes: median 7-day intake 137 vs 113). That signal was **a
time-trend artifact**: prompt episodes cluster in early 2025, when *all*
episodes had 2–3× more intake than 2026 (era medians: 224 vs 96), and recent
episodes have truncated windows.

With a matched-neighbor design (each prompt episode compared against the
episode *before* and *after* it — same era, adjacent weeks):

| Measure | Mean next-vs-prev lift | Win rate |
|---|---|---|
| 7-day all-intake | −14.0 | 4/10 |
| 7-day boost-action only | **0.0** | 4/10 |

**Conclusion: prompt episodes do not prime the following week's baseline.**
Their own intake also matches their era (prompt-early 230 vs era 224;
prompt-late 107 vs era 96).

## 4. What prompt episodes actually buy

Not next-episode volume — **in-episode community participation**: more
distinct listeners writing messages and asserting identity on the show, more
topical signal (message text names the topics), and the ammo for call-backs
and running bits. That's a loyalty/community return, not a reach return.

## 5. Implication for the show

- "Do more prompt episodes" is justified on **community-participation**
  grounds, not on priming the next episode. Set expectations accordingly.
- If the goal is *value*, the lever is pairing content with a **survival or
  thanks beat** — that's where the mode-shifting mega-boosts live.
- The content mix that demonstrably works: personal-listener prompts, sharp
  opinionated tech takes (paywalling, corporate nonsense), recurring
  world-building bits, and odd-ball curiosities.

## 6. Caveats

- n is small (10 prompt episodes; the carryover test is underpowered).
- Prompt classification was manual, from reading the 380 messages; a cleaner
  labeling would use episode descriptions from the RSS feed.
- Intake is dominated by live "stream" events whose variance we don't fully
  attribute to episode type.
- The era trend (2025 intake ≫ 2026) is the single strongest variable in this
  dataset and should be controlled in any future test.