# Where to?: what the phone sends for places and journeys

Two calls. Both are signed like voice: the same headers, the same counter as sync, and a daily limit per phone (500 requests of either kind; the day changes at 00:00 UTC). The Worker asks two free public services on the phone's behalf, Transitous (named places, stops, buses, trains, walking routes) and postcodes.io (UK postcodes), and stores nothing.

Headers: `X-Pg-V` (`1`), `X-Pg-Did`, `X-Pg-Ctr`, `X-Pg-N`, `X-Pg-S`. The signature covers `V|did|ctr|n|geo:<search or route>:<sha256 of the body bytes in lowercase hex>`. Body: JSON, at most 2,000 characters.

## `POST /api/geo/search`

Body: `{ "q": "Hexham Station", "near": { "lat": 51.76, "lon": -0.32 } }` (`near` is optional; it makes nearby places come first and adds distances).

200: `{ "places": [ { "name", "kind": "postcode" | "stop" | "place" | "address", "area", "lat", "lon", "km"? } ] }`, at most 8, best first. A UK postcode (or the first half of one, such as `AL1`) is looked up on postcodes.io and comes first. Everything else comes from Transitous and is limited to Britain.

## `POST /api/geo/route`

Body: `{ "from": { "lat", "lon" }, "to": { "lat", "lon" }, "time": "2026-11-17T08:00:00Z" }` (`time` is optional, default now; both places must be in Britain).

200:
```
{ "prefer": "walk" | "transit" | "none",
  "walk": { "minutes", "metres", "geometry" } | null,
  "options": [ { "minutes", "start", "end", "transfers", "live",
                 "legs": [ { "mode": "walk" | "bus" | "train" | "tram" | "underground" | "ferry" | "other",
                             "line", "headsign", "from": { "name", "lat", "lon", "time" }, "to": { ... },
                             "minutes", "metres"?, "live", "stops"?, "geometry" } ] } ],
  "searched": "<the time used>" }
```
`prefer` is `walk` when walking takes 45 minutes or less, or is 2 miles (3,219 m) or less; otherwise `transit` when there are options; `walk` if only the walk exists; `none` if nothing was found. `geometry` is `{ "points", "precision" }`, an encoded polyline (precision 6) to draw on the map. `live` is true when the operator publishes live running times for that leg; otherwise the times are timetable times.

## Errors (both calls)

| Status | Body | Meaning |
|---|---|---|
| 400 | `{"error":"bad request"}` | A search shorter than 2 characters, or places that are missing or outside Britain. |
| 403 | `{"error":"rejected"}` or `"bad signature"` | Unknown phone, or the signature does not check out. |
| 409 | `{"error":"replay"}` | The counter was not higher than the last one. Skip it forward, as sync does. |
| 413 | `{"error":"too_long"}` | Body over 2,000 characters. |
| 429 | `{"error":"used_up"}` | 500 requests used today. |
| 503 | `{"error":"unavailable"}` | One of the public services is down or too slow. Try again later. |

## Map pictures: `GET /api/geo/tile/{z}/{x}/{y}`

A 256 px PNG of OpenStreetMap, zoom 11 to 18 only. Signed like the other geo calls (`X-Pg-V`, `X-Pg-Did`, `X-Pg-Ctr`, `X-Pg-N`, `X-Pg-S`); the signature covers `V|did|ctr|n|geo:tile:<sha256 of the text "z/x/y" in lowercase hex>`, for example of `15/16345/10890`. The same errors as the other calls (403 where_off, 409 replay, 429 used_up, 503 unavailable). 4,000 tiles per phone per day, separate from the 500 searches and routes; the Worker keeps tiles for seven days.

The phone asks for the six to twelve tiles the open map needs, one at a time, turns them into four light tones of the screen's ink (so the route stays the darkest thing), keeps up to 40 in memory while the map is open and drops them when it is left. A tile it cannot get leaves the plain background. The map shows "(c) OpenStreetMap contributors" whenever a picture is on it.
