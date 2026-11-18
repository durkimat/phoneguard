// "Where to?": place search and journeys, with pretend Transitous and postcodes.io answers.
import assert from "node:assert/strict";
import { searchPlaces, planRoute, postcodeOf, outcodeOf, km, handleGeo, handleTile } from "../src/geo.js";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

assert.equal(postcodeOf("wf177dx"), "WF17 7DX"); assert.equal(postcodeOf("AL1  3rd"), "AL1 3RD"); assert.equal(postcodeOf("SW1A 1AA"), "SW1A 1AA"); assert.equal(postcodeOf("M1 1AE"), "M1 1AE"); assert.equal(postcodeOf("hatfield"), null); assert.equal(postcodeOf("12345"), null);
assert.equal(outcodeOf("al1"), "AL1"); assert.equal(outcodeOf("WF17"), "WF17"); assert.equal(outcodeOf("station"), null); ok("UK postcodes and the first half of one are recognised, with or without the space");
assert.ok(Math.abs(km({ lat: 51.7637, lon: -0.324 }, { lat: 51.5308, lon: -0.1238 }) - 29.5) < 1); ok("distances are about right (St Albans to King's Cross is about 29 km)");

const json = (o, status = 200) => new Response(JSON.stringify(o), { status });
const trans = [
  { type: "STOP", name: "Hexham Bus Station", lat: 54.97, lon: -2.096, country: "GB", areas: [{ name: "England", default: false }, { name: "Northumberland", default: true }] },
  { type: "PLACE", name: "Hexham Fire Station", lat: 54.969, lon: -2.094, country: "GB", areas: [{ name: "Northumberland", default: true }] },
  { type: "STOP", name: "Hexham Station", lat: -32.829, lon: 151.684, country: "AU", areas: [{ name: "Newcastle-Maitland", default: true }] },
  { type: "STOP", name: "Hexham Bus Station", lat: 54.97, lon: -2.096, country: "GB", areas: [{ name: "Northumberland", default: true }] },
  { type: "ADDRESS", name: "1 High Street", lat: 54.971, lon: -2.1, country: "GB", areas: [{ name: "Hexham", default: true }] },
  { type: "TRACK", name: "ignore me", lat: 54, lon: -2, country: "GB" }];
{ const urls = []; const f = async (u) => { urls.push(u); return u.includes("postcodes.io") ? json({ result: { postcode: "WF17 7DX", latitude: 53.69, longitude: -1.64, admin_district: "Kirklees", admin_ward: "Batley East" } }) : json(trans); };
  let r = await searchPlaces(f, "Hexham Station", { lat: 54.97, lon: -2.1 });
  assert.deepEqual(r.map((p) => p.name), ["Hexham Bus Station", "Hexham Fire Station", "1 High Street"]); assert.ok(r.every((p) => p.km < 1)); assert.equal(r[0].kind, "stop"); assert.equal(r[0].area, "Northumberland"); assert.equal(r[2].kind, "address");
  assert.match(urls[0], /place=54\.97,-2\.1&placeBias=5/); ok("results are Britain only, deduplicated, the known kinds only, with distance from the phone; the phone's position is passed as a bias");
  r = await searchPlaces(f, "wf17 7dx"); assert.equal(r[0].name, "WF17 7DX"); assert.equal(r[0].kind, "postcode"); assert.equal(r[0].area, "Batley East, Kirklees"); assert.ok(urls.some((u) => /postcodes\.io\/postcodes\/WF177DX$/.test(u))); ok("a postcode comes first, looked up on postcodes.io");
  assert.deepEqual(await searchPlaces(f, "a"), []); ok("one letter is not searched"); }
{ const f = async (u) => u.includes("postcodes.io") ? json({ error: "x" }, 404) : json(trans);
  assert.equal((await searchPlaces(f, "AL1 3RD"))[0].kind, "stop"); ok("an unknown postcode still returns any place matches");
  await assert.rejects(searchPlaces(async () => json({}, 500), "station"), /search failed|answered/); ok("if the place service is down the search fails (the phone says so) instead of showing nothing"); }

const plan = { direct: [{ duration: 1242, legs: [{ mode: "WALK", distance: 1422, legGeometry: { points: "abc", precision: 6 } }] }],
  itineraries: [{ duration: 3180, startTime: "2026-11-17T08:14:00Z", endTime: "2026-11-17T09:07:00Z", transfers: 1, legs: [
    { mode: "WALK", duration: 360, distance: 480, from: { name: "START", lat: 51.7637, lon: -0.324 }, to: { name: "Lancaster Road", lat: 51.76, lon: -0.33 }, startTime: "2026-11-17T08:14:00Z", endTime: "2026-11-17T08:20:00Z", legGeometry: { points: "p1", precision: 6 } },
    { mode: "BUS", routeShortName: "357", headsign: "City Centre", duration: 660, from: { name: "Lancaster Road", lat: 51.76, lon: -0.33 }, to: { name: "St Albans City Railway Station", lat: 51.75, lon: -0.327 }, startTime: "2026-11-17T08:23:00Z", endTime: "2026-11-17T08:34:00Z", realTime: true, intermediateStops: [{}, {}, {}], legGeometry: { points: "p2", precision: 6 } },
    { mode: "SUBURBAN", routeShortName: "Thameslink", duration: 1260, from: { name: "St Albans City", lat: 51.75, lon: -0.327 }, to: { name: "London St Pancras", lat: 51.53, lon: -0.126 }, startTime: "2026-11-17T08:46:00Z", endTime: "2026-11-17T09:07:00Z", legGeometry: { points: "p3", precision: 6 } }] }] };
{ let url; const f = async (u) => { url = u; return json(plan); };
  const r = await planRoute(f, { lat: 51.7637, lon: -0.324 }, { lat: 51.5308, lon: -0.1238 }, "2026-11-17T08:00:00Z");
  assert.equal(r.prefer, "walk"); assert.equal(r.walk.minutes, 21); assert.equal(r.walk.metres, 1422); assert.equal(r.options[0].minutes, 53); assert.equal(r.options[0].live, true); assert.deepEqual(r.options[0].legs.map((l) => l.mode), ["walk", "bus", "train"]);
  assert.equal(r.options[0].legs[1].line, "357"); assert.equal(r.options[0].legs[1].stops, 4); assert.deepEqual(r.options[0].legs[1].geometry, { points: "p2", precision: 6 }); assert.match(url, /fromPlace=51\.7637,-0\.324&toPlace=51\.5308,-0\.1238/);
  ok("a journey is trimmed to walk and transport legs with times, lines, live flags and route lines");
  const far = { ...plan, direct: [{ duration: 6000, legs: [{ mode: "WALK", distance: 8000 }] }] };
  assert.equal((await planRoute(async () => json(far), { lat: 51.7, lon: -0.3 }, { lat: 51.5, lon: -0.1 })).prefer, "transit");
  assert.equal((await planRoute(async () => json({ direct: far.direct, itineraries: [] }), { lat: 51.7, lon: -0.3 }, { lat: 51.5, lon: -0.1 })).prefer, "walk");
  assert.equal((await planRoute(async () => json({ itineraries: [] }), { lat: 51.7, lon: -0.3 }, { lat: 51.5, lon: -0.1 })).prefer, "none");
  const near = { ...plan, direct: [{ duration: 1500, legs: [{ mode: "WALK", distance: 1900 }] }] };
  assert.equal((await planRoute(async () => json(near), { lat: 51.7, lon: -0.3 }, { lat: 51.5, lon: -0.1 })).prefer, "walk");
  ok("walking leads when it is under 45 minutes or 2 miles; otherwise public transport; walking only if nothing else; 'none' if neither");
  await assert.rejects(planRoute(f, { lat: 40, lon: 0 }, { lat: 51.5, lon: -0.1 }), RangeError); ok("places outside Britain are refused"); }

// the whole route with a pretend database
{ const stub = (status = 200, body = { ok: true }) => ({ fetch: async () => Response.json(body, { status }) });
  const req = (path, body) => new Request("https://x" + path, { method: "POST", headers: { "x-pg-v": "1", "x-pg-did": "d", "x-pg-ctr": "5", "x-pg-n": "n", "x-pg-s": "s" }, body: typeof body === "string" ? body : JSON.stringify(body) });
  const f = async (u) => (u.includes("geocode") ? json(trans) : json(plan));
  let r = await handleGeo(req("/api/geo/search", { q: "hexham" }), stub(), "search", f), j = await r.json(); assert.equal(r.status, 200); assert.equal(j.places[0].name, "Hexham Bus Station");
  r = await handleGeo(req("/api/geo/route", { from: { lat: 51.7637, lon: -0.324 }, to: { lat: 51.5308, lon: -0.1238 } }), stub(), "route", f); assert.equal((await r.json()).options.length, 1);
  assert.equal((await handleGeo(req("/api/geo/search", { q: "h" }), stub(), "search", f)).status, 400);
  assert.equal((await handleGeo(req("/api/geo/search", "not json"), stub(), "search", f)).status, 400);
  assert.equal((await handleGeo(req("/api/geo/route", { from: { lat: 1, lon: 1 }, to: { lat: 51.5, lon: -0.1 } }), stub(), "route", f)).status, 400);
  assert.equal((await handleGeo(req("/api/geo/search", { q: "x".repeat(3000) }), stub(), "search", f)).status, 413);
  r = await handleGeo(req("/api/geo/search", { q: "hexham" }), stub(429, { error: "used_up" }), "search", f); assert.equal(r.status, 429);
  r = await handleGeo(req("/api/geo/search", { q: "hexham" }), stub(), "search", async () => json({}, 500)); assert.equal(r.status, 503); assert.equal((await r.json()).error, "unavailable");
  ok("the route checks the body, passes the database's refusals straight through, and says 'unavailable' when the public services fail"); }

// map pictures
{ const stub = (status = 200) => ({ fetch: async () => Response.json({ ok: true }, { status }) });
  const treq = (p) => new Request("https://x" + p, { headers: { "x-pg-v": "1", "x-pg-did": "d", "x-pg-ctr": "5", "x-pg-n": "n", "x-pg-s": "s" } });
  const store = new Map(), cache = { match: async (k) => (store.has(k.url) ? new Response(store.get(k.url)) : undefined), put: async (k, r) => { store.set(k.url, await r.arrayBuffer()); } };
  let calls = 0; const png = async () => { calls++; return new Response(new Uint8Array([137, 80, 78, 71]), { status: 200 }); };
  let r = await handleTile(treq("/api/geo/tile/15/16345/10890"), stub(), png, cache); assert.equal(r.status, 200); assert.equal(r.headers.get("content-type"), "image/png"); assert.equal((await r.arrayBuffer()).byteLength, 4);
  r = await handleTile(treq("/api/geo/tile/15/16345/10890"), stub(), png, cache); assert.equal(r.status, 200); assert.equal(calls, 1);
  assert.equal((await handleTile(treq("/api/geo/tile/5/1/1"), stub(), png, cache)).status, 400);
  assert.equal((await handleTile(treq("/api/geo/tile/19/1/1"), stub(), png, cache)).status, 400);
  assert.equal((await handleTile(treq("/api/geo/tile/12/99999/1"), stub(), png, cache)).status, 400);
  assert.equal((await handleTile(treq("/api/geo/tile/15/1"), stub(), png, cache)).status, 404);
  assert.equal((await handleTile(treq("/api/geo/tile/15/16345/10891"), stub(429), png, cache)).status, 429);
  assert.equal((await handleTile(treq("/api/geo/tile/15/16345/10892"), stub(), async () => new Response("x", { status: 500 }), cache)).status, 503);
  ok("map pictures: signed, zoom 11 to 18 only, kept in our cache, refusals passed through, 503 when OpenStreetMap is down"); }

console.log(`\n${n} checks passed`);
