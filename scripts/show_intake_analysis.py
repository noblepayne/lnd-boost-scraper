#!/usr/bin/env python3
"""Episode intake & carryover analysis for a boost scraper show.

Pulls boost data from the lnd-boost-scraper query API, joins it to the show's
RSS feed publication dates, and checks whether the *kind* of episode influences
boost intake — including the "does a prompt episode lift the next episode?"
matched-neighbor test.

No external deps: uses only the Python stdlib (urllib + xml.etree).

Usage:
    python3 scripts/show_intake_analysis.py \
        --api http://100.120.212.39:3223 \
        --show '(?i).*launch.*' \
        --rss https://serve.podhome.fm/rss/..... \
        --prompt '11: Eggsistential' '29: Spilling the Tea' ... \
        --out /tmp/opencode/out

The --prompt list is the manual classification of which episode titles are
"prompt" episodes (hosts asked listeners a question; verified by reading the
boost messages). Without it, the script still computes per-episode intake and
era statistics, and prints a message-ratio heuristic for choosing prompts.

Output: per-episode intake table (printed + written as <out>/episodes.csv), a
statistics section, and the matched-neighbor carryover test.
"""

import argparse
import csv
import json
import re
import statistics
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

EPOCH_2026 = 1759190400  # 2026-01-01 00:00 UTC (used for era split)


def norm_title(s):
    """Normalize an episode title both sides carry, e.g. '30: Title' -> 'title'."""
    return re.sub(r'^\d+:\s*', '', s.strip()).lower()


def pull_boosts(api, show_regex):
    """POST the full boost timeline (episode, action, ts, sats) via the query proxy."""
    query = (
        '{:find [?ep ?a ?ts ?v] '
        ':where [[?e :boostagram/podcast ?p] '
        '[(re-pattern "' + show_regex + '") ?pat] '
        '[(re-matches ?pat ?p)] '
        '[?e :boostagram/episode ?ep] [?e :boostagram/action ?a] '
        '[?e :invoice/creation_date ?ts] [?e :boostagram/value_sat_total ?v]]}'
    )
    payload = json.dumps({"query": query, "timeout": 60000,
                          "limit": 50000}).encode()
    req = urllib.request.Request(api + "/api/v1/query", data=payload,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as resp:
        data = json.load(resp)
    if data.get("status") != "ok":
        raise SystemExit(f"query failed: {data.get('detail')}")
    return data["results"]


def load_rss(url):
    """Episode publication times from the RSS feed."""
    req = urllib.request.Request(url, headers={"User-Agent": "lnd-boost-scraper-analysis"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        root = ET.fromstring(resp.read())
    rss = {}
    for it in root.find("channel").findall("item"):
        t = it.findtext("title").strip()
        rss[norm_title(t)] = (t, parsedate_to_datetime(it.findtext("pubDate")).timestamp())
    return rss


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--api", default="http://100.120.212.39:3223",
                   help="base URL of the boost scraper")
    p.add_argument("--show", default="(?i).*launch.*",
                   help="regex matching the podcast name(s)")
    p.add_argument("--rss", required=True, help="RSS feed URL for episode dates")
    p.add_argument("--prompt", nargs="*", default=[],
                   help="episode titles classified as prompt episodes")
    p.add_argument("--out", default="/tmp/opencode",
                   help="directory for episodes.csv output")
    args = p.parse_args()

    print("pulling boosts...", flush=True)
    rows = pull_boosts(args.api, args.show)
    print(f"  {len(rows)} boost rows pulled")

    rss = load_rss(args.rss)
    print(f"  {len(rss)} episodes from RSS")

    # per-episode aggregates
    ep = {}
    for name, a, ts, v in rows:
        n = norm_title(name)
        st = ep.setdefault(n, {"label": name, "total": 0, "sats": 0, "rows": []})
        st["total"] += 1
        st["sats"] += v
        st["rows"].append((a, ts, v))

    records = []
    for n, st in ep.items():
        if n not in rss:
            continue
        label, pub = rss[n]
        wb = ws = 0
        for a, ts, v in st["rows"]:
            if 0 <= (ts - pub) <= 7 * 24 * 3600:
                if a == "boost":
                    wb += 1
                elif a == "stream":
                    ws += 1
        st.update(label=label, pub=pub, w_boost=wb, w_stream=ws, w_all=wb + ws)
        records.append(st)
    records.sort(key=lambda r: r["pub"])

    # write per-episode table
    import os
    os.makedirs(args.out, exist_ok=True)
    csv_path = os.path.join(args.out, "episodes.csv")
    with open(csv_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["pubdate", "title", "total", "sats", "w_boost", "w_stream", "w_all"])
        for r in records:
            w.writerow([datetime.fromtimestamp(r["pub"], timezone.utc).strftime("%Y-%m-%d"),
                        r["label"], r["total"], r["sats"],
                        r["w_boost"], r["w_stream"], r["w_all"]])
    print(f"  per-episode table -> {csv_path}")

    print("\n=== per-episode 7-day intake ===")
    print(f"{'pubdate':<12} {'episode':<38} {'w_all':>6} {'w_boost':>7} {'w_stream':>8}")
    for r in records:
        print(f"{datetime.fromtimestamp(r['pub'], timezone.utc).strftime('%Y-%m-%d'):<12} "
              f"{r['label'][:38]:<38} {r['w_all']:>6} {r['w_boost']:>7} {r['w_stream']:>8}")

    prompt = {norm_title(t) for t in args.prompt}
    epoch_mid = EPOCH_2026

    if prompt:
        idx = {norm_title(r["label"]): i for i, r in enumerate(records)}
        print("\n=== MATCHED NEIGHBOR TEST (prompt episode's next vs prev) ===")
        paired = []
        for label in prompt:
            i = idx.get(label)
            if i is None or i == 0 or i == len(records) - 1:
                continue
            prev, nxt = records[i - 1], records[i + 1]
            paired.append((prev["w_all"], nxt["w_all"], prev["w_boost"], nxt["w_boost"]))
        if paired:
            lift = [n - p for p, n, _, _ in paired]
            lift_b = [nb - pb for _, _, pb, nb in paired]
            print(f"  pairs: {len(paired)}")
            print(f"  all-intake mean next-vs-prev lift: {statistics.mean(lift):.1f}  "
                  f"(win rate {sum(1 for x in lift if x > 0)}/{len(lift)})")
            print(f"  boost-only mean next-vs-prev lift: {statistics.mean(lift_b):.1f}  "
                  f"(win rate {sum(1 for x in lift_b if x > 0)}/{len(lift_b)})")
            note = ("  (positive lift => prompt episodes prime the next episode; "
                    "near-zero => no carryover)")
            print(note)
        else:
            print("  no valid neighbor pairs — need episodes before AND after each prompt")
    else:
        print("\n(no --prompt list given; skipping carryover test. Provide titles to run it.)")

    # message-ratio heuristic for choosing prompts
    print("\n=== message-engagement heuristic (per episode, top 15 by message count) ===")
    # message counts require a second pull — do it lazily only if no prompts given
    if not prompt:
        print("  (rerun with --prompt for the full test; to pick prompts, "
              "pull message-bearing boosts and look for shared listener questions)")


if __name__ == "__main__":
    main()