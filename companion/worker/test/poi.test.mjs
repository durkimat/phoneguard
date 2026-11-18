// "Navigate" search by kind of place: the word table, the Overpass question, opening hours, the tidy answer, the 1 km / 2 km rule,
// the short memory and the failure paths. Everything is pretend: no test touches a real server.
import assert from "node:assert/strict";
import { matchCategory, buildQuery, parseWeek, openNow, typeLabel, tidy, overpass, searchCategory, clearPoiCache, OVERPASS, MAX_PLACES, nominatimText, wanted, viewbox, nominatimUrl, nominatimElements, nominatim, lookup, pace, NOMINATIM } from "../src/poi.js";
import { handleGeo } from "../src/geo.js";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const json = (o, status = 200) => new Response(JSON.stringify(o), { status });
const T = (s) => new Date(s);
const WED_NOON = T("2026-11-18T12:00:00Z");

// ---- which words mean a kind of place
for (const [w, id] of [["cafe", "cafe"], ["Cafes", "cafe"], ["coffee shop", "cafe"], ["Post Office", "post_office"], ["post offices", "post_office"], ["chemists", "pharmacy"], ["pharmacy", "pharmacy"], ["ATM", "atm"], ["cash machine", "atm"],
  ["toilets", "toilets"], ["loo", "toilets"], ["pubs", "pub"], ["bar", "pub"], ["bakeries", "bakery"], ["butchers", "butcher"], ["the nearest cafe", "cafe"], ["cafes near me", "cafe"], ["restaurants nearby", "restaurant"], ["petrol station", "fuel"], ["car park", "parking"], ["GP", "doctors"]])
  assert.equal(matchCategory(w)?.id, id, w);
ok("typed words, singular or plural, with a few synonyms and 'nearest' / 'near me' wrapping, find the kind of place");
for (const w of ["cafe royal", "hexham", "St Albans station", "baker street", "bank street", "the red lion pub", "", "a", "12345", "AL1 3RD"]) assert.equal(matchCategory(w), null, w);
ok("anything that is more than the kind of place stays an ordinary name search");
{ const p = matchCategory("pizza"), q = matchCategory("curry"), r = matchCategory("fish and chips");
  assert.equal(p.id, "cuisine:pizza"); assert.match(p.filters[0], /"cuisine"~"pizza"/); assert.match(q.filters[0], /indian/); assert.match(r.filters[0], /fish_and_chips/); assert.equal(matchCategory("pizzas").id, "cuisine:pizza");
  ok("cuisine words search restaurants and takeaways by their cuisine"); }

// ---- the question put to Overpass: only table words and numbers go in it
assert.equal(buildQuery(matchCategory("cafe"), 51.75, -0.33, 1000), '[out:json][timeout:10];(nwr["amenity"="cafe"](around:1000,51.75000,-0.33000););out tags center 80;');
assert.equal(buildQuery(matchCategory("pubs"), 51.75, -0.33, 2000), '[out:json][timeout:10];(nwr["amenity"~"^(pub|bar)$"](around:2000,51.75000,-0.33000););out tags center 80;');
assert.match(buildQuery(matchCategory("pizza"), 51.75, -0.33, 1000), /\["amenity"~"\^\(restaurant\|fast_food\)\$"\]\["cuisine"~"pizza"\]/);
ok("the query is built from the table and rounded numbers only");

// ---- opening hours
const O = (s, d) => openNow(s, T(d));
const WEEK = "Mo-Fr 09:00-17:00; Sa 10:00-16:00";
assert.deepEqual(O(WEEK, "2026-11-18T12:00:00Z"), { open: true, until: "17:00" });
assert.deepEqual(O(WEEK, "2026-11-18T18:00:00Z"), { open: false });
assert.deepEqual(O(WEEK, "2026-11-18T08:00:00Z"), { open: false, opens: "09:00" });
assert.deepEqual(O(WEEK, "2026-11-21T10:30:00Z"), { open: true, until: "16:00" });
assert.deepEqual(O(WEEK, "2026-11-22T12:00:00Z"), { open: false });
ok("weekday and Saturday hours: open until, closed, opens later today, closed on an unlisted Sunday");
assert.deepEqual(O("24/7", "2026-11-18T03:00:00Z"), { open: true }); assert.deepEqual(O("Mo-Su 00:00-24:00", "2026-11-18T03:00:00Z"), { open: true });
assert.deepEqual(O("09:00-17:00", "2026-11-18T12:00:00Z"), { open: true, until: "17:00" });
assert.deepEqual(O("Mo-Su 09:00-12:00,13:00-17:00", "2026-11-18T12:30:00Z"), { open: false, opens: "13:00" });
assert.deepEqual(O("Mo-Fr 09:00-24:00", "2026-11-18T12:00:00Z"), { open: true, until: "00:00" });
ok("24/7, no days given, split days and midnight closing");
assert.deepEqual(O("Fr-Sa 20:00-02:00", "2026-11-20T22:00:00Z"), { open: true, until: "02:00" });
assert.deepEqual(O("Fr-Sa 20:00-02:00", "2026-11-21T01:00:00Z"), { open: true, until: "02:00" });
assert.deepEqual(O("Fr-Sa 20:00-02:00", "2026-11-21T03:00:00Z"), { open: false, opens: "20:00" });
assert.deepEqual(O("Sa-Mo 10:00-12:00", "2026-11-22T11:00:00Z"), { open: true, until: "12:00" });
ok("a night shift past midnight counts for the night before; day ranges can wrap the week");
assert.deepEqual(O("Mo-Fr 09:00-17:00; We off", "2026-11-18T12:00:00Z"), { open: false });
assert.deepEqual(O("Mo-Fr 09:00-17:00; PH off", "2026-11-18T12:00:00Z"), { open: true, until: "17:00" });
ok("a later rule overrides earlier ones for its days; public holidays are ignored (a hint, not a promise)");
assert.deepEqual(O("Mo-Su 09:00-17:00", "2026-06-17T15:30:00Z"), { open: true, until: "17:00" });
assert.deepEqual(O("Mo-Su 09:00-17:00", "2026-06-17T16:30:00Z"), { open: false });
ok("British summer time: 16:30 UTC in June is 17:30 and shut");
for (const s of ["sunrise-sunset", "Mo-Fr 09:00-17:00 open", "Jan-Mar Mo 09:00-12:00", "Mo[1] 09:00-12:00", "by appointment", "", "Mo-Fr 25:00-26:00", "Wed 09:00-12:00"]) assert.equal(openNow(s, WED_NOON), null, s);
ok("anything unusual is not guessed at (the phone shows the text as written)");

// ---- one element to one tidy place
const CAFE = matchCategory("cafe");
assert.equal(typeLabel({ amenity: "restaurant", cuisine: "italian" }, CAFE), "Italian restaurant");
assert.equal(typeLabel({ amenity: "fast_food", cuisine: "pizza;italian" }, CAFE), "Pizza takeaway");
assert.equal(typeLabel({ amenity: "restaurant", cuisine: "fish_and_chips" }, CAFE), "Fish and chips restaurant");
assert.equal(typeLabel({ amenity: "restaurant", cuisine: "<b>x</b>" }, CAFE), "Restaurant");
assert.equal(typeLabel({ amenity: "pharmacy" }, CAFE), "Chemist"); assert.equal(typeLabel({ shop: "butcher" }, CAFE), "Butcher"); assert.equal(typeLabel({ amenity: "post_office" }, CAFE), "Post office"); assert.equal(typeLabel({ amenity: "atm" }, CAFE), "Cash machine");
assert.equal(typeLabel({ leisure: "playground" }, CAFE), "Playground"); assert.equal(typeLabel({ amenity: "pub", cuisine: "english" }, CAFE), "Pub");
ok("the short type: cuisine plus kind where it helps, tidy names for the common kinds, odd tags ignored");
{ const el = { type: "node", id: 1, lat: 51.75, lon: -0.33, tags: { amenity: "cafe", name: "  Costa \n Coffee  ", opening_hours: "Mo-Su 07:00-19:00", takeaway: "yes", wheelchair: "limited", "diet:vegan": "yes", "diet:vegetarian": "yes", phone: "+44 1727 123456 ext <script>", website: "https://x.example" } };
  const p = tidy(el, CAFE);
  assert.deepEqual(p, { name: "Costa Coffee", kind: "poi", type: "Cafe", info: { hours: "Mo-Su 07:00-19:00", takeaway: "yes", diet: ["vegan", "vegetarian"], wheelchair: "limited", phone: "+44 1727 123456" }, lat: 51.75, lon: -0.33, osm: "n1" });
  assert.equal(JSON.stringify(p).includes("example"), false);
  assert.equal(tidy({ type: "way", id: 9, center: { lat: 51.7511111, lon: -0.33 }, tags: { amenity: "cafe", takeaway: "maybe", wheelchair: "sometimes" } }, CAFE).info.takeaway, undefined);
  assert.equal(tidy({ type: "relation", id: 3, tags: { amenity: "cafe" } }, CAFE), null);
  assert.equal(tidy({ type: "way", id: 9, center: { lat: 51.7511111, lon: -0.33 }, tags: { amenity: "cafe" } }, CAFE).lat, 51.75111);
  assert.equal(tidy({ type: "node", id: 4, lat: 51.7, lon: -0.3, tags: { amenity: "cafe" } }, CAFE).name, "");
  ok("an element becomes {name, type, info, position}: names tidied, no website, odd values dropped, centre used for buildings, unnamed kept with an empty name"); }

// ---- the one network function
{ const calls = [], seq = (...rs) => async (u, o) => { calls.push({ u, o }); const r = rs.shift(); if (r instanceof Error) throw r; return r; };
  let r = await overpass(seq(json({}, 500), json({ elements: [1] })), "Q"); assert.deepEqual(r, { elements: [1] }); assert.equal(calls.length, 2); assert.equal(calls[0].u, OVERPASS[0]); assert.equal(calls[1].u, OVERPASS[1]);
  assert.equal(calls[0].o.method, "POST"); assert.equal(calls[0].o.body, "data=Q"); assert.match(calls[0].o.headers["user-agent"], /phoneguard/);
  calls.length = 0; await assert.rejects(overpass(seq(json({}, 400), json({})), "Q")); assert.equal(calls.length, 1);
  calls.length = 0; r = await overpass(seq(json({}, 429), json({ ok: 1 })), "Q"); assert.equal(calls.length, 2);
  calls.length = 0; await assert.rejects(overpass(seq(new Error("net"), new Error("net")), "Q")); assert.equal(calls.length, 2);
  calls.length = 0; await assert.rejects(overpass(seq(json({}, 503), json({}, 504)), "Q")); assert.equal(calls.length, 2);
  calls.length = 0; r = await overpass(seq(new Error("net"), json({ ok: 2 })), "Q"); assert.deepEqual(r, { ok: 2 });
  ok("Overpass: POST with our User-Agent; the second server is tried only after a network error, 429 or 5xx; a bad question is not repeated; never more than two tries"); }

// ---- Nominatim: the first choice
assert.equal(nominatimText(matchCategory("cafes")), "cafe"); assert.equal(nominatimText(matchCategory("takeaway")), "fast food"); assert.equal(nominatimText(matchCategory("post office")), "post office"); assert.equal(nominatimText(matchCategory("butcher")), "butcher");
assert.equal(nominatimText(matchCategory("chemist")), "pharmacy"); assert.equal(nominatimText(matchCategory("cash machine")), "atm"); assert.equal(nominatimText(matchCategory("petrol station")), "petrol station");
assert.equal(nominatimText(matchCategory("pizza")), "pizza restaurant"); assert.equal(nominatimText(matchCategory("fish and chips")), "fish and chip shop");
ok("what Nominatim is asked is a table word, or '<cuisine> restaurant', never the typed text");
assert.deepEqual(wanted(CAFE).pairs, [["amenity", "cafe"]]); assert.deepEqual(wanted(matchCategory("pub")).pairs, [["amenity", "pub"], ["amenity", "bar"]]); assert.deepEqual(wanted(matchCategory("butcher")).pairs, [["shop", "butcher"]]);
{ const w = wanted(matchCategory("pizza")); assert.deepEqual(w.pairs, [["amenity", "restaurant"], ["amenity", "fast_food"]]); assert.ok(w.cuisine.test("pizza")); assert.ok(w.cuisine.test("pizza;italian")); assert.ok(!w.cuisine.test("italian")); assert.ok(!w.cuisine.test("pizzeria"));
  assert.ok(wanted(matchCategory("sushi")).cuisine.test("japanese")); assert.equal(wanted(CAFE).cuisine, null); }
ok("the kinds of result that count (and the cuisine pattern) come from the category's own filters");
{ const [l, t, r, b] = viewbox(51.75, -0.33, 1000).split(",").map(Number);
  assert.ok(l < -0.33 && r > -0.33 && t > 51.75 && b < 51.75); assert.ok(Math.abs((t - b) / 2 * 110540 - 1000) < 1); assert.ok(Math.abs((r - l) / 2 * 111320 * Math.cos(51.75 * Math.PI / 180) - 1000) < 1);
  const u = new URL(nominatimUrl(CAFE, 51.75, -0.33, 1000)); assert.equal(u.origin + u.pathname, NOMINATIM);
  for (const [k, v] of [["q", "cafe"], ["format", "jsonv2"], ["limit", "40"], ["extratags", "1"], ["addressdetails", "0"], ["bounded", "1"]]) assert.equal(u.searchParams.get(k), v, k);
  assert.equal(u.searchParams.get("viewbox"), viewbox(51.75, -0.33, 1000));
  ok("the viewbox is a square of +-radius around the point, bounded, with tags, 40 results at most"); }

// the shape of a real Nominatim answer (jsonv2 with extratags), invented names
const NOM = [
  { place_id: 1, osm_type: "node", osm_id: 1001, lat: "51.7500000", lon: "-0.3300000", category: "amenity", type: "cafe", name: "The Courtyard Cafe", display_name: "The Courtyard Cafe, St Albans", importance: 0.2, extratags: { opening_hours: "Tu-Sa 08:45-15:00", takeaway: "yes", "diet:vegetarian": "yes", wheelchair: "limited", "contact:phone": "+44 1727 111222", website: "https://x.example" } },
  { place_id: 2, osm_type: "way", osm_id: 1002, lat: "51.7510000", lon: "-0.3300000", category: "amenity", type: "cafe", name: "", extratags: null },
  { place_id: 3, osm_type: "node", osm_id: 1003, lat: "51.7520000", lon: "-0.3300000", category: "shop", type: "bakery", name: "Wrong kind" },
  { place_id: 4, osm_type: "node", osm_id: 1004, lat: "x", lon: "-0.33", category: "amenity", type: "cafe", name: "Bad position" },
  { place_id: 5, osm_type: "node", lat: "51.75", lon: "-0.33", category: "amenity", type: "cafe", name: "No id" }];
{ const els = nominatimElements(NOM, CAFE); assert.deepEqual(els.map((e) => e.id), [1001, 1002]);
  const p = tidy(els[0], CAFE); assert.equal(p.name, "The Courtyard Cafe"); assert.equal(p.osm, "n1001"); assert.deepEqual(p.info, { hours: "Tu-Sa 08:45-15:00", takeaway: "yes", diet: ["vegetarian"], wheelchair: "limited", phone: "+44 1727 111222" });
  assert.equal(JSON.stringify(p).includes("example"), false); const q = tidy(els[1], CAFE); assert.equal(q.name, ""); assert.equal(q.osm, "w1002"); assert.equal(q.type, "Cafe");
  assert.deepEqual(nominatimElements("nonsense", CAFE), []);
  const piz = matchCategory("pizza"), r = (id, cu, type = "restaurant") => ({ osm_type: "node", osm_id: id, lat: "51.75", lon: "-0.33", category: "amenity", type, name: `P${id}`, extratags: cu ? { cuisine: cu } : {} });
  assert.deepEqual(nominatimElements([r(1, "pizza;italian"), r(2, "italian"), r(3), r(4, "pizza", "fast_food"), r(5, "Pizza")], piz).map((e) => e.id), [1, 4, 5]);
  ok("a Nominatim answer becomes tidy places: tags mapped (hours, takeaway, diet, wheelchair, phone), wrong kinds and bad rows dropped, cuisine words need the cuisine tag"); }

// ---- pacing: one request a second
{ clearPoiCache(); const slept = []; let t = 1000; const clock = (x) => ({ now: () => x, sleep: async (ms) => { slept.push(ms); } });
  await pace(() => 1000, async (ms) => slept.push(ms)); await pace(() => 1200, async (ms) => slept.push(ms)); await pace(() => 1200, async (ms) => slept.push(ms)); await pace(() => 9000, async (ms) => slept.push(ms));
  assert.deepEqual(slept, [800, 1800]); ok("Nominatim requests are spaced at least a second apart, even when they arrive together; a quiet spell costs nothing"); }
{ clearPoiCache(); const calls = []; const clock = { now: () => 5000, sleep: async () => {} };
  const f = async (u, o) => { calls.push({ u, o }); return json(NOM); };
  const els = await nominatim(f, CAFE, 51.75, -0.33, 1000, clock); assert.equal(els.length, 2); assert.equal(calls.length, 1); assert.match(calls[0].u, /^https:\/\/nominatim\.openstreetmap\.org\/search\?/); assert.match(calls[0].o.headers["user-agent"], /phoneguard/);
  await assert.rejects(nominatim(async () => json({}, 429), CAFE, 51.75, -0.33, 1000, clock), /429/); await assert.rejects(nominatim(async () => { throw new Error("net"); }, CAFE, 51.75, -0.33, 1000, clock));
  ok("one Nominatim request with our User-Agent; a refusal or network error is an error"); }

// ---- Nominatim first, Overpass only as the fallback
{ clearPoiCache(); const calls = [];
  const f = async (u, o) => { calls.push({ u, o }); return u.includes("nominatim") ? json(NOM) : json({ elements: [{ type: "node", id: 9, lat: 51.75, lon: -0.33, tags: { amenity: "cafe", name: "Via Overpass" } }] }); };
  assert.equal((await lookup(f, CAFE, 51.75, -0.33, 1000)).length, 2); assert.equal(calls.length, 1);
  clearPoiCache(); calls.length = 0; assert.deepEqual(await lookup(async (u) => { calls.push(u); return json([]); }, CAFE, 51.75, -0.33, 1000), []); assert.equal(calls.length, 1);
  clearPoiCache(); calls.length = 0;
  const g = async (u, o) => { calls.push({ u, o }); return u.includes("nominatim") ? json({}, 503) : json({ elements: [{ type: "node", id: 9, lat: 51.75, lon: -0.33, tags: { amenity: "cafe", name: "Via Overpass" } }] }); };
  const els = await lookup(g, CAFE, 51.75, -0.33, 1000); assert.equal(els[0].tags.name, "Via Overpass"); assert.equal(calls.length, 2); assert.equal(calls[1].u, OVERPASS[0]); assert.match(decodeURIComponent(calls[1].o.body), /timeout:5\]/);
  clearPoiCache(); calls.length = 0; await assert.rejects(lookup(async (u) => { calls.push(u); return json({}, 503); }, CAFE, 51.75, -0.33, 1000)); assert.equal(calls.length, 2); assert.ok(!calls.some((u) => u.includes("kumi")));
  ok("Nominatim answers the search (an empty answer is final); only when it fails is Overpass tried, once, on the main server, with a 5 second query; no mirror chain"); }

// ---- around me
const el = (id, lat, extra = {}, type = "node") => ({ type, id, lat, lon: -0.33, tags: { amenity: "cafe", name: `Cafe ${id}`, ...extra } });
const HERE = { lat: 51.75, lon: -0.33 };
{ clearPoiCache(); const radii = [];
  const look = async (f, cat, lat, lon, r) => { radii.push(r); return [el(1, 51.75, { opening_hours: "Mo-Su 07:00-19:00", takeaway: "yes", wheelchair: "yes", phone: "+44 1727 123456" }), { type: "way", id: 2, center: { lat: 51.751, lon: -0.33 }, tags: { amenity: "cafe", name: "Nero" } }, { type: "node", id: 3, lat: 51.752, lon: -0.33, tags: { amenity: "cafe" } }, { type: "relation", id: 4, tags: { amenity: "cafe", name: "No position" } }]; };
  const r = await searchCategory(null, CAFE, { ...HERE, lastKnown: true }, WED_NOON, look);
  assert.deepEqual(radii, [1000, 2000]); assert.equal(r.radius, 2000);
  assert.deepEqual(r.places.map((p) => p.name), ["Cafe 1", "Nero", ""]); assert.deepEqual(r.places.map((p) => p.km), [0, 0.1, 0.2]); assert.equal(r.places[2].type, "Cafe");
  assert.deepEqual(r.places[0].info, { hours: "Mo-Su 07:00-19:00", takeaway: "yes", wheelchair: "yes", phone: "+44 1727 123456", open: true, until: "19:00" }); assert.equal("info" in r.places[1], false);
  assert.deepEqual(r.near, { lat: 51.75, lon: -0.33, lastKnown: true }); assert.equal(r.category, "cafe");
  ok("fewer than 5 within 1 km: one wider look at 2 km (two lookups at most); nearest first; open-now worked out; unnamed kept; a last-known position is passed back"); }
{ clearPoiCache(); let calls = 0; const six = Array.from({ length: 6 }, (_, i) => el(i + 1, 51.75 + i * 0.0005));
  const ask = async () => { calls++; return six; };
  let r = await searchCategory(null, CAFE, HERE, WED_NOON, ask); assert.equal(calls, 1); assert.equal(r.radius, 1000); assert.equal("lastKnown" in r.near, false);
  await searchCategory(null, CAFE, { lat: 51.7502, lon: -0.3301 }, T("2026-11-18T12:03:00Z"), ask); assert.equal(calls, 1);
  await searchCategory(null, matchCategory("pub"), HERE, T("2026-11-18T12:03:00Z"), ask); assert.equal(calls, 2);
  await searchCategory(null, CAFE, { lat: 51.80, lon: -0.33 }, T("2026-11-18T12:03:00Z"), ask); assert.equal(calls, 3);
  await searchCategory(null, CAFE, HERE, T("2026-11-18T12:06:00Z"), ask); assert.equal(calls, 4);
  ok("5 or more within 1 km: one lookup; the same kind in the same square kilometre is remembered for 5 minutes, another kind, place or time asks again"); }
{ clearPoiCache(); const many = Array.from({ length: 30 }, (_, i) => el(i + 1, 51.75 + ((i * 7) % 30) * 0.0004));
  const r = await searchCategory(null, CAFE, HERE, WED_NOON, async () => many);
  assert.equal(r.places.length, MAX_PLACES); assert.deepEqual(r.places.map((p) => p.km), [...r.places.map((p) => p.km)].sort((a, b) => a - b));
  ok("at most 15 places, nearest first"); }
{ clearPoiCache(); const r = await searchCategory(null, CAFE, HERE, WED_NOON, async () => []);
  assert.deepEqual(r.places, []); ok("an empty answer is an empty list, not an error"); }

// ---- the route
{ const stub = (status = 200) => ({ fetch: async () => Response.json({ ok: true }, { status }) });
  const req = (body) => new Request("https://x/api/geo/search", { method: "POST", headers: { "x-pg-v": "1", "x-pg-did": "d", "x-pg-ctr": "5", "x-pg-n": "n", "x-pg-s": "s" }, body: JSON.stringify(body) });
  const six = Array.from({ length: 6 }, (_, i) => ({ osm_type: "node", osm_id: 100 + i, lat: String(51.75 + i * 0.0005), lon: "-0.33", category: "amenity", type: "cafe", name: `Cafe ${i}`, extratags: {} }));
  const seen = []; const f = async (u) => { seen.push(u); return u.includes("nominatim") ? json(six) : u.includes("interpreter") ? json({ elements: Array.from({ length: 6 }, (_, i) => el(i + 1, 51.75 + i * 0.0005)) }) : json([{ type: "STOP", name: "Hexham Bus Station", lat: 54.97, lon: -2.096, country: "GB", areas: [{ name: "Northumberland", default: true }] }]); };
  clearPoiCache();
  let r = await handleGeo(req({ q: "Cafes", near: { lat: 51.75, lon: -0.33, lastKnown: true } }), stub(), "search", f), j = await r.json();
  assert.equal(r.status, 200); assert.equal(j.category, "cafe"); assert.equal(j.places.length, 6); assert.equal(j.near.lastKnown, true); assert.equal(seen.length, 1); assert.ok(seen[0].includes("nominatim.openstreetmap.org"));
  seen.length = 0; r = await handleGeo(req({ q: "cafe" }), stub(), "search", f); j = await r.json(); assert.deepEqual(j, { places: [], category: "cafe", error: "need_position" }); assert.equal(seen.length, 0);
  r = await handleGeo(req({ q: "cafe", near: { lat: 40, lon: 0 } }), stub(), "search", f); assert.equal((await r.json()).error, "need_position");
  ok("a kind of place with a position asks Nominatim once; without a position (or outside Britain) it says need_position and asks nobody");
  seen.length = 0; r = await handleGeo(req({ q: "hexham", near: { lat: 54.97, lon: -2.1 } }), stub(), "search", f); j = await r.json(); assert.deepEqual(Object.keys(j), ["places"]); assert.equal(j.places[0].kind, "stop"); assert.ok(seen.every((u) => u.includes("transitous")));
  assert.equal(Object.keys(j.places[0]).sort().join(), "area,kind,km,lat,lon,name"); ok("a name search is exactly as before: {places} only, the same fields, never touching Nominatim or Overpass");
  clearPoiCache(); seen.length = 0; r = await handleGeo(req({ q: "pubs", near: HERE }), stub(), "search", async (u) => { seen.push(u); return u.includes("nominatim") ? json({}, 500) : json({ elements: [el(1, 51.75), el(2, 51.7505), el(3, 51.751), el(4, 51.7515), el(5, 51.752)] }); });
  assert.equal(r.status, 200); assert.equal((await r.json()).places.length, 5); assert.equal(seen.length, 2); assert.ok(seen[1].includes("overpass-api.de"));
  clearPoiCache(); r = await handleGeo(req({ q: "pubs", near: HERE }), stub(), "search", async () => json({}, 503)); assert.equal(r.status, 503); assert.equal((await r.json()).error, "unavailable");
  clearPoiCache(); r = await handleGeo(req({ q: "pubs", near: HERE }), stub(429), "search", f); assert.equal(r.status, 429);
  ok("Nominatim down: Overpass answers instead; both down: 'unavailable' (the phone says it can't search now); the daily limit is passed through");
  const w = parseWeek("Mo-Fr 09:00-17:00"); assert.deepEqual(Object.keys(w), ["0", "1", "2", "3", "4"]); }

console.log(`\n${n} checks passed`);
