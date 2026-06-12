# Boost Scraper API

The scraper serves an HTTP API on port `3223` (configurable via `SCRAPER_UIPORT`). All endpoints return JSON.

## Shows

Known show slugs: `lup`, `twib`, `launch`, `ssh`, `coder`, `all`

You can pass a slug name or a raw regex pattern for any `show` parameter.

---

## Analysis Endpoints

### Top Boosters

`GET /api/v1/analysis/top-boosters`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `start` | int | no | Epoch seconds (LA timezone) |
| `end` | int | no | Epoch seconds (LA timezone) |
| `limit` | int | no | Max results (default: all) |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
# Top 10 all-time for LUP
curl 'http://localhost:3223/api/v1/analysis/top-boosters?show=lup&limit=10'

# Top 5 in 2026 for TWIB, fiat only
curl 'http://localhost:3223/api/v1/analysis/top-boosters?show=twib&start=1767250800&end=1781248346&limit=5&type=fiat'
```

Response: `{"boosters": [["name", total], ...]}`

---

### Monday Summary

`GET /api/v1/analysis/monday-summary`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
curl 'http://localhost:3223/api/v1/analysis/monday-summary?show=lup'
```

Response:
```json
{
  "per-day-of-week": {"MONDAY": 354, "TUESDAY": 294, ...},
  "total-boosts": 1847,
  "total-weeks": 94,
  "weeks-with-monday": 84,
  "weeks-without-monday": 10,
  "weeks-without-monday-list": ["2024-W35", ...]
}
```

---

### Monthly Leaderboard

`GET /api/v1/analysis/monthly-leaderboard`

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `show` | string | yes | Show slug or regex |
| `type` | keyword | no | `sat`, `fiat`, or `member-free` (default: all) |

```bash
curl 'http://localhost:3223/api/v1/analysis/monthly-leaderboard?show=twib'
```

Response: `{"2026-01": ["booster", total], "2026-02": ["booster", total], ...}`

---

### App Percentages

`GET /api/v1/analysis/app-percentages`

No params. Returns percentage of total boosts per app, sorted descending.

```bash
curl 'http://localhost:3223/api/v1/analysis/app-percentages'
```

Response: `{"apps": [["Fountain", 68.95], ["Podverse", 7.82], ...]}`

---

## Raw Datalog Proxy

For ad-hoc queries that don't have a dedicated endpoint.

`POST /api/v1/query`

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | string | yes | — | EDN Datalog query map |
| `params` | vector | no | `[]` | Bindings for `:in` vars |
| `timeout` | int | no | 15000 | Max milliseconds (capped at 60000) |
| `limit` | int | no | 5000 | Max result rows (capped at 50000) |

```bash
curl -X POST http://localhost:3223/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{:find [?s (sum ?v)] :where [[?e :boostagram/value_sat_total ?v] [(get-else $ ?e :boostagram/sender_name_normalized \"N/A\") ?s]]}",
    "limit": 5
  }'
```

Response: `{"status": "ok", "results": [...], "truncated": true, "elapsed_ms": 9141}`

---

## Query Templates

Pre-built queries available through the proxy.

### List Templates

`GET /api/v1/query/templates`

```bash
curl 'http://localhost:3223/api/v1/query/templates'
```

### Run a Template

`GET /api/v1/query/templates/:name`

Template params are passed as query string arguments. Each template documents its own params in the listing response.

```bash
curl 'http://localhost:3223/api/v1/query/templates/top-boosters?show=lup&limit=5'
```

---

## Tips

- **Time ranges** use epoch seconds in **America/Los_Angeles** timezone.
- Compute them easily: `python3 -c "from datetime import datetime; print(int(datetime(2026,1,1).timestamp()))"`
- The `show` param accepts either a slug name (`lup`, `twib`, etc.) or any regex pattern.
- All endpoints are read-only. The proxy cannot run transactions.