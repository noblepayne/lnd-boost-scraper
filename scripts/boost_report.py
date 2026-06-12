#!/usr/bin/env python3
"""Generate a printable HTML boost report from the live API.

Usage:
  python3 scripts/boost-report.py [--show SHOW ...] [--year YYYY]

  Fetches top boosters, engagement stats, app distribution, and
  monthly champions from the running scraper API.

  Requires the server at SCRAPER_URL (default http://localhost:3223).

Examples:
  python3 scripts/boost-report.py
  python3 scripts/boost-report.py --year 2026
  python3 scripts/boost-report.py --show lup --show twib --year 2026
"""

import json, urllib.request, os, sys
from datetime import datetime, timezone, timedelta
from argparse import ArgumentParser

SCRAPER_URL = os.environ.get("SCRAPER_URL", "http://localhost:3223")
BASE = f"{SCRAPER_URL}/api/v1/analysis"

def get(path):
    with urllib.request.urlopen(f"{BASE}/{path}") as r:
        return json.loads(r.read())

def epoch(year, month=1, day=1):
    la = timezone(timedelta(hours=-7))
    return int(datetime(year, month, day, 0, 0, 0, tzinfo=la).timestamp())

parser = ArgumentParser(description="Generate boost report")
parser.add_argument("--year", type=int, default=None, help="Filter to year (e.g. 2026)")
parser.add_argument("--show", action="append", default=["lup", "twib", "launch"])
parser.add_argument("--limit", type=int, default=10)
parser.add_argument("--apps", type=int, default=15)
parser.add_argument("--output", default="/dev/shm/render/boost-report.html")
args = parser.parse_args()

now = int(datetime.now(timezone(timedelta(hours=-7))).timestamp())
start = epoch(args.year) if args.year else None
end = now if args.year else None
suffix = f" {args.year} YTD" if args.year else " (all-time)"
time_range = f"&start={start}&end={end}" if args.year else ""

la = timezone(timedelta(hours=-7))
now_la = datetime.now(la)

html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Boost Report{suffix}</title>
<style>
body {{ font-family: system-ui, sans-serif; max-width: 800px; margin: 2em auto; padding: 0 1em; color: #222; }}
h1 {{ color: #c0392b; border-bottom: 2px solid #c0392b; padding-bottom: .3em; }}
h2 {{ color: #c0392b; margin-top: 1.5em; }}
h3 {{ margin-bottom: .2em; }}
ol {{ padding-left: 1.5em; }}
li {{ margin: .15em 0; }}
.info {{ background: #f0f0f0; padding: .4em .8em; border-left: 3px solid #888; margin: .5em 0; }}
table {{ border-collapse: collapse; width: 100%; }}
th, td {{ text-align: left; padding: 4px 8px; border-bottom: 1px solid #ccc; }}
th {{ background: #f5f5f5; }}
.footer {{ margin-top: 2em; font-size: .85em; color: #888; text-align: center; }}
</style>
</head>
<body>
<h1>Boost Report{suffix}</h1>
<p class="info">Generated {now_la.strftime('%Y-%m-%d %H:%M PDT')}</p>
"""

for show in args.show:
    top = get(f"top-boosters?show={show}&limit={args.limit}{time_range}")["boosters"]
    html += f"<h3>{show.upper()}</h3><ol>\n"
    for name, sats in top:
        html += f"  <li><strong>{name}</strong> &mdash; {sats:,} sats</li>\n"
    html += "</ol>\n"

# Engagement — first show
monday = get(f"monday-summary?show={args.show[0]}")
html += f"""<h2>Engagement — {args.show[0].upper()}</h2><ul>\n"""
for dow, cnt in sorted(monday["per-day-of-week"].items(), key=lambda x: x[1], reverse=True):
    html += f"  <li><strong>{dow.title()}</strong>: {cnt} boosts</li>\n"
html += f"""</ul>
<p class="info">{monday['total-boosts']} boosts, {monday['total-weeks']} weeks &mdash; {monday['weeks-with-monday']} with Monday boosts, {monday['weeks-without-monday']} without.</p>
"""

# App distribution
apps = get("app-percentages")["apps"]
html += "<h2>App Distribution</h2><ul>\n"
for app, pct in apps[:args.apps]:
    html += f"  <li><strong>{app}</strong>: {pct:.1f}%</li>\n"
html += "</ul>\n"

# Monthly champions
for show in args.show:
    monthly = get(f"monthly-leaderboard?show={show}")
    if args.year:
        monthly = {m: v for m, v in monthly.items() if m.startswith(str(args.year))}
    if monthly:
        html += f"<h3>{show.upper()} — Monthly Champions</h3><table>\n<tr><th>Month</th><th>Top Booster</th><th>Total</th></tr>\n"
        for month in sorted(monthly.keys()):
            name, sats = monthly[month]
            html += f"<tr><td>{month}</td><td>{name}</td><td>{sats:,} sats</td></tr>\n"
        html += "</table>\n"

html += """<p class="footer">Source: lnd-boost-scraper database via live API.</p>
</body>
</html>"""

with open(args.output, "w") as f:
    f.write(html)

print(f"Written to file://{os.path.abspath(args.output)}")