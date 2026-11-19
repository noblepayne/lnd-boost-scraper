#!/usr/bin/env python3
"""Generate a show-ready Boosties markdown report from the live API.

Usage:
    python3 scripts/boosties_report.py --show lup --limit 10 \
        --out reports/boosties-$(date +%F).md
    python3 scripts/boosties_report.py --show lup --since 1735689600

Requires only the stdlib. Talks to GET /api/v1/boosties.
"""

import argparse
import json
import urllib.request
from datetime import datetime, timezone

SHOWS = {
    "lup": "LINUX Unplugged",
    "launch": "The Launch",
    "twib": "This Week in Bitcoin",
    "ssh": "Self-Hosted",
    "coder": "Coder Radio",
}


def fetch(api, show, since, limit):
    url = f"{api}/api/v1/boosties?show={show}&limit={limit}"
    if since:
        url += f"&since={since}"
    with urllib.request.urlopen(url, timeout=120) as r:
        d = json.load(r)
    if not isinstance(d, dict) or "by-sats" not in d:
        raise SystemExit(f"unexpected response: {str(d)[:200]}")
    return d


def n(x):
    return f"{x:,}"


def render(d, show):
    t = d["totals"]
    fr = d["front-runner"]
    title = SHOWS.get(show, show)
    since = datetime.fromtimestamp(d["since"], timezone.utc).strftime("%Y-%m-%d")
    gen = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    L = []
    A = L.append
    A(f"# Boosties — {title}")
    A("")
    A(f"> Data: nodecan | Window: since {since} | Generated: {gen} | "
      "Self-boosts excluded (chrislas, noblepayne) | Action: boost + stream")
    A("")
    A("## The Board")
    A("")
    if fr:
        A(f"**Current front runner: {fr['sender']} — {n(fr['sats'])} sats "
          f"from {fr['count']} boosts.**")
        A("")
    A("## 💰 Most Sats")
    A("")
    A("| # | Booster | Sats | Boosts |")
    A("|---|---------|------|--------|")
    for i, x in enumerate(d["by-sats"], 1):
        A(f"| {i} | {x['sender']} | {n(x['sats'])} | {x['count']} |")
    A("")
    A("## 🔁 Most Boosts")
    A("")
    A("| # | Booster | Boosts | Sats |")
    A("|---|---------|--------|------|")
    for i, x in enumerate(d["by-count"], 1):
        A(f"| {i} | {x['sender']} | {x['count']} | {n(x['sats'])} |")
    if d["streams"]["by-sats"]:
        A("")
        A("## 📡 Stream Leaders")
        A("")
        A("| # | Streamer | Sats | Streams |")
        A("|---|----------|------|---------|")
        for i, x in enumerate(d["streams"]["by-sats"], 1):
            A(f"| {i} | {x['sender']} | {n(x['sats'])} | {n(x['count'])} |")
    A("")
    A("## Totals")
    A("")
    A(f"+ Total Boosted Sats: {n(t['boosts']['sats'])}")
    A(f"+ Total Boosts: {n(t['boosts']['events'])}")
    A(f"+ Total Boosters: {n(t['boosts']['people'])}")
    A(f"+ Total Streamed Sats: {n(t['streams']['sats'])}")
    A(f"+ Total Streams: {n(t['streams']['events'])}")
    A(f"+ Total Streamers: {n(t['streams']['people'])}")
    A(f"+ Total v4v Sats: {n(t['combined']['sats'])}")
    A(f"+ Total v4v Actions: {n(t['combined']['events'])}")
    A(f"+ Total Unique People: {n(t['combined']['people'])}")
    A("")
    hook = d.get("on-air-hooks") or []
    if hook:
        A("---")
        A("")
        A("**On-air hooks:**")
        A("")
        for h in hook:
            A(f"- {h}")
        A("")
    A("> ⚠️ Handles are counted as-is: several people appear twice under variant "
      "handles (e.g. `name` / `name@fountain.fm`). Combined totals are unaffected; "
      "per-person rankings may split a supporter across two rows.")
    return "\n".join(L) + "\n"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--api", default="http://100.120.212.39:3223")
    p.add_argument("--show", default="lup")
    p.add_argument("--since", type=int, default=None,
                   help="epoch seconds; default = API default (2025-01-01)")
    p.add_argument("--limit", type=int, default=10)
    p.add_argument("--out", default=None)
    args = p.parse_args()

    md = render(fetch(args.api, args.show, args.since, args.limit), args.show)
    if args.out:
        with open(args.out, "w") as f:
            f.write(md)
        print(f"wrote {args.out}")
    else:
        print(md)


if __name__ == "__main__":
    main()
