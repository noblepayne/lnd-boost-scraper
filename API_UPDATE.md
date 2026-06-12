# 📢 API Update: Client-Side State Tracking & Schema Normalization

We have upgraded the Boost Scraper API to support intelligent incremental fetching and consistent JSON schemas.

## 1. Native State Tracking (New!)
Agents no longer need to track "last seen" timestamps manually. The server now handles this via the `client` parameter.
- **Usage**: Add `&client=your-unique-id` to your `/boosts` request.
- **Behavior**: 
    - The first request should still include `since=UNIX_TIMESTAMP` to establish a baseline.
    - Subsequent requests can **omit** the `since` parameter. The server will automatically return only boosts seen since your last successful request.
    - The server tracks state per unique combination of `client` and `show`.

## 2. Stable Show Slugs
We have introduced a formal show registry with stable slugs.
- **Discovery**: Hit `GET /api/v1/shows` to get the list of supported shows and their slugs (e.g., `lup`, `coder`, `selfhosted`).
- **Usage**: Always use the `:slug` value in your `?show=` query parameter for guaranteed matches.

## 3. Normalized JSON Output (Malli)
The API output has been hardened using Malli schemas. 
- **Predictable Types**: You will no longer receive `null` for collection keys when no results are found.
- **Empty States**: If no boosts match your query, you will receive:
    - `[]` (empty array) for `:ballers`, `:boosts`, and `:thanks`.
    - `0` for all numeric summary fields.
- **High Water Mark**: Check `summary.last_seen_id` to see the exact timestamp used for your client's state update.

## 4. Example Incremental Pattern
```bash
# 1. First request (establish state)
curl "/boosts?json=true&show=lup&client=agent-001&since=1713139200"

# 2. Subsequent requests (server remembers where you left off)
curl "/boosts?json=true&show=lup&client=agent-001"
```

## 5. Management Endpoints
- `GET /api/v1/client-states`: View current high-water marks for all registered clients.
- `DELETE /api/v1/client-states?client=...&show=...`: Reset state for a specific client.
