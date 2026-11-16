// "Where to?": place search and journeys for the phone. The Worker asks two free public services on the phone's behalf (Transitous for
// places, buses, trains and walking routes; postcodes.io for UK postcodes) and hands back a small, tidy answer. Nothing is stored.
import { hex } from "./crypto.js";

export const GEO_CAP = 500;                    // requests per phone per day (search and route together)
export const WHAT = ["search", "route"];
const MAX_BODY = 2000;
const TRANSITOUS = "https://api.transitous.org/api";
const UA = { "user-agent": "phoneguard-companion (family parental-control helper)" };
const WALK_FIRST_MINUTES = 45, WALK_FIRST_METRES = 3219;   // 2 miles; beyond either, public transport leads

const OUTCODE = /^[A-Z]{1,2}[0-9][A-Z0-9]?$/;
const POSTCODE = /^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$/;
export const postcodeOf = (q) => { const c = String(q ?? "").toUpperCase().replace(/\s+/g, ""); return POSTCODE.test(c) ? `${c.slice(0, -3)} ${c.slice(-3)}` : null; };
export const outcodeOf = (q) => { const c = String(q ?? "").toUpperCase().replace(/\s+/g, ""); return OUTCODE.test(c) ? c : null; };

const clean = (x, n) => String(x ?? "").replace(/\s+/g, " ").trim().slice(0, n);
const inBritain = (p) => p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && p.lat > 49 && p.lat < 61.5 && p.lon > -9 && p.lon < 2.5;

export function km(a, b) {
  const r = Math.PI / 180, dLat = (b.lat - a.lat) * r, dLon = (b.lon - a.lon) * r;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(dLon / 2) ** 2;
  return 12742 * Math.asin(Math.sqrt(h));
}

const KIND = { STOP: "stop", ADDRESS: "address", PLACE: "place" };
const areaOf = (r) => (r.areas ?? []).filter((a) => a.default).map((a) => a.name).slice(-2).join(", ") || (r.areas ?? []).slice(-1).map((a) => a.name).join("");

const get = async (f, url) => {
  const r = await f(url, { headers: UA, signal: AbortSignal.timeout(15_000) });
  if (!r.ok) throw new Error(`${new URL(url).host} answered ${r.status}`);
  return r.json();
};

/** Places matching what was typed or said, best first: a UK postcode (or the start of one) first, then named places, addresses and stops in Britain. */
export async function searchPlaces(f, qRaw, near = null) {
  const q = clean(qRaw, 80), out = [];
  if (q.length < 2) return out;
  const here = inBritain(near) ? near : null;
  const pc = postcodeOf(q), oc = pc ? null : outcodeOf(q);
  const jobs = [];
  if (pc) jobs.push(get(f, `https://api.postcodes.io/postcodes/${encodeURIComponent(pc.replace(" ", ""))}`).then((j) => { const r = j.result; if (r && inBritain({ lat: r.latitude, lon: r.longitude })) out.push({ name: r.postcode, kind: "postcode", area: [r.admin_ward, r.admin_district].filter(Boolean).join(", "), lat: r.latitude, lon: r.longitude }); }, () => {}));
  if (oc) jobs.push(get(f, `https://api.postcodes.io/outcodes/${oc}`).then((j) => { const r = j.result; if (r && inBritain({ lat: r.latitude, lon: r.longitude })) out.push({ name: r.outcode, kind: "postcode", area: [].concat(r.admin_district ?? []).join(", "), lat: r.latitude, lon: r.longitude }); }, () => {}));
  const places = [];
  jobs.push(get(f, `${TRANSITOUS}/v1/geocode?text=${encodeURIComponent(q)}&language=en${here ? `&place=${here.lat},${here.lon}&placeBias=5` : ""}`).then((list) => {
    for (const r of Array.isArray(list) ? list : []) if (r.country === "GB" && KIND[r.type] && Number.isFinite(r.lat) && Number.isFinite(r.lon)) places.push({ name: clean(r.name, 70), kind: KIND[r.type], area: areaOf(r), lat: r.lat, lon: r.lon });
  }));
  const settled = await Promise.allSettled(jobs);
  if (!places.length && !out.length && settled.some((s) => s.status === "rejected")) throw new Error("search failed");
  const seen = new Set(out.map((p) => p.name));
  for (const p of places) { const key = `${p.name}|${p.lat.toFixed(3)}|${p.lon.toFixed(3)}`; if (!seen.has(key)) { seen.add(key); out.push(p); } }
  return out.slice(0, 8).map((p) => ({ ...p, lat: Math.round(p.lat * 1e5) / 1e5, lon: Math.round(p.lon * 1e5) / 1e5, ...(here ? { km: Math.round(km(here, p) * 10) / 10 } : {}) }));
}

const MODES = { WALK: "walk", BUS: "bus", COACH: "bus", TRAM: "tram", SUBWAY: "underground", METRO: "underground", FERRY: "ferry", RAIL: "train", HIGHSPEED_RAIL: "train", LONG_DISTANCE: "train", NIGHT_RAIL: "train", REGIONAL_FAST_RAIL: "train", REGIONAL_RAIL: "train", SUBURBAN: "train" };
const point = (p) => ({ name: clean(p?.name, 60), lat: Math.round(p?.lat * 1e5) / 1e5, lon: Math.round(p?.lon * 1e5) / 1e5 });
const leg = (l) => ({ mode: MODES[l.mode] ?? "other", line: clean(l.routeShortName || l.displayName || "", 20), headsign: clean(l.headsign, 50), from: { ...point(l.from), time: l.startTime }, to: { ...point(l.to), time: l.endTime },
  minutes: Math.max(1, Math.round((l.duration ?? 0) / 60)), ...(Number.isFinite(l.distance) ? { metres: Math.round(l.distance) } : {}), live: Boolean(l.realTime), stops: Array.isArray(l.intermediateStops) ? l.intermediateStops.length + 1 : undefined, geometry: l.legGeometry ? { points: l.legGeometry.points, precision: l.legGeometry.precision } : undefined });

/** Journeys from one place to another: the walk (when it is sensible), and public transport options, with which to lead with. */
export async function planRoute(f, from, to, timeIso) {
  if (!inBritain(from) || !inBritain(to)) throw new RangeError("both places must be in Britain");
  const t = Number.isFinite(Date.parse(timeIso)) ? new Date(timeIso).toISOString() : new Date().toISOString();
  const d = await get(f, `${TRANSITOUS}/v5/plan?fromPlace=${from.lat},${from.lon}&toPlace=${to.lat},${to.lon}&time=${encodeURIComponent(t)}&numItineraries=4&directModes=WALK&maxDirectTime=10800`);
  const w = (d.direct ?? []).find((i) => i.legs?.length === 1 && i.legs[0].mode === "WALK");
  const walk = w ? { minutes: Math.max(1, Math.round(w.duration / 60)), metres: Math.round(w.legs[0].distance ?? 0), geometry: w.legs[0].legGeometry ? { points: w.legs[0].legGeometry.points, precision: w.legs[0].legGeometry.precision } : undefined } : null;
  const options = (d.itineraries ?? []).slice(0, 4).map((i) => ({ minutes: Math.max(1, Math.round(i.duration / 60)), start: i.startTime, end: i.endTime, transfers: i.transfers ?? 0, live: i.legs.some((l) => l.realTime), legs: i.legs.map(leg) }));
  const prefer = walk && (walk.minutes <= WALK_FIRST_MINUTES || walk.metres <= WALK_FIRST_METRES) ? "walk" : options.length ? "transit" : walk ? "walk" : "none";
  return { prefer, walk, options, searched: t };
}

const err = (error, status) => Response.json({ error }, { status });

/** POST /api/geo/search and /api/geo/route: a small JSON body, signed like voice (see github-docs/where-to-protocol.md). */
export async function handleGeo(req, stub, what, f = fetch) {
  const text = await req.text();
  if (text.length > MAX_BODY) return err("too_long", 413);
  const h = req.headers, bytes = new TextEncoder().encode(text);
  const hash = hex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));
  const auth = await stub.fetch(new Request("https://family/geo/auth", { method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ v: Number(h.get("x-pg-v")), did: h.get("x-pg-did") ?? "", ctr: Number(h.get("x-pg-ctr")), n: h.get("x-pg-n") ?? "", s: h.get("x-pg-s") ?? "", what, hash }) }));
  if (!auth.ok) return new Response(auth.body, { status: auth.status, headers: { "content-type": "application/json" } });
  let b; try { b = JSON.parse(text); } catch { return err("bad request", 400); }
  if (!b || typeof b !== "object") return err("bad request", 400);
  try {
    if (what === "search") {
      if (clean(b.q, 80).length < 2) return err("bad request", 400);
      return Response.json({ places: await searchPlaces(f, b.q, b.near) }, { headers: { "cache-control": "no-store" } });
    }
    if (!inBritain(b.from) || !inBritain(b.to)) return err("bad request", 400);
    return Response.json(await planRoute(f, b.from, b.to, b.time), { headers: { "cache-control": "no-store" } });
  } catch { return err("unavailable", 503); }   // one of the public services is down or slow
}
