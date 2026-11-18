// "Navigate" search by TYPE of place ("cafe", "pub", "post office", "pizza"): the words are matched against a small table, and a match
// is looked up around the phone on OpenStreetMap: Nominatim first (one request, tags included, at most one a second), Overpass only as a
// fallback when Nominatim fails. Only words from the table ever reach a query, never what was typed.
// The network calls are nominatim() and overpass(); everything else is pure, so the tests fake `lookup` (or `f`).
import { km } from "./geo.js";

export const NOMINATIM = "https://nominatim.openstreetmap.org/search";
export const NOMINATIM_GAP_MS = 1000;   // the usage policy: at most one request a second
export const OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"];
const UA = { "user-agent": "phoneguard-companion (family parental-control helper)" };
export const RADIUS_M = 1000, WIDE_M = 2000, WIDEN_BELOW = 5, MAX_PLACES = 15, CACHE_MS = 5 * 60_000, CACHE_MAX = 50;

const tag = (k, v) => `["${k}"="${v}"]`;
const amen = (...v) => (v.length === 1 ? tag("amenity", v[0]) : `["amenity"~"^(${v.join("|")})$"]`);
const shop = (v) => tag("shop", v);
// words: the typed phrases (singular; plurals are folded). filters: Overpass tag filters (any one matches). label: the type shown when the tags say nothing more.
export const CATEGORIES = [
  { id: "cafe", words: ["cafe", "coffee shop", "coffee", "tea room"], filters: [amen("cafe")], label: "Cafe" },
  { id: "restaurant", words: ["restaurant", "place to eat", "somewhere to eat", "food", "dinner", "lunch"], filters: [amen("restaurant")], label: "Restaurant" },
  { id: "pub", words: ["pub", "bar", "inn"], filters: [amen("pub", "bar")], label: "Pub" },
  { id: "takeaway", words: ["takeaway", "take away", "fast food", "chippy", "chip shop"], filters: [amen("fast_food")], label: "Takeaway" },
  { id: "post_office", words: ["post office"], filters: [amen("post_office")], label: "Post office" },
  { id: "post_box", words: ["post box", "postbox", "letter box", "pillar box"], filters: [amen("post_box")], label: "Post box" },
  { id: "pharmacy", words: ["chemist", "pharmacy"], filters: [amen("pharmacy")], label: "Chemist" },
  { id: "atm", words: ["cash machine", "atm", "cashpoint", "cash point", "hole in the wall"], filters: [amen("atm")], label: "Cash machine" },
  { id: "toilets", words: ["toilet", "loo", "public toilet", "bathroom", "lavatory"], filters: [amen("toilets")], label: "Toilets" },
  { id: "doctors", words: ["doctor", "gp", "surgery", "doctors"], filters: [amen("doctors")], label: "Doctor" },
  { id: "dentist", words: ["dentist"], filters: [amen("dentist")], label: "Dentist" },
  { id: "library", words: ["library"], filters: [amen("library")], label: "Library" },
  { id: "fuel", words: ["petrol station", "petrol", "garage", "filling station"], filters: [amen("fuel")], label: "Petrol station" },
  { id: "parking", words: ["car park", "parking"], filters: [amen("parking")], label: "Car park" },
  { id: "playground", words: ["playground", "park play area", "swings"], filters: [tag("leisure", "playground")], label: "Playground" },
  { id: "butcher", words: ["butcher"], filters: [shop("butcher")], label: "Butcher" },
  { id: "bakery", words: ["bakery", "baker"], filters: [shop("bakery")], label: "Bakery" },
  { id: "greengrocer", words: ["greengrocer", "fruit and veg", "green grocer"], filters: [shop("greengrocer")], label: "Greengrocer" },
  { id: "newsagent", words: ["newsagent", "paper shop"], filters: [shop("newsagent")], label: "Newsagent" },
  { id: "supermarket", words: ["supermarket", "grocery", "grocer"], filters: [shop("supermarket")], label: "Supermarket" },
  { id: "convenience", words: ["convenience store", "corner shop", "shop"], filters: [shop("convenience")], label: "Shop" },
  { id: "hairdresser", words: ["hairdresser", "barber", "hair salon"], filters: [shop("hairdresser")], label: "Hairdresser" },
  { id: "florist", words: ["florist"], filters: [shop("florist")], label: "Florist" },
  { id: "bank", words: ["bank"], filters: [amen("bank")], label: "Bank" },
];
// Cuisine words search restaurants and takeaways whose cuisine tag mentions the word.
export const CUISINES = { pizza: "pizza", italian: "italian", chinese: "chinese", indian: "indian", curry: "indian", thai: "thai", burger: "burger", kebab: "kebab", sushi: "sushi|japanese", japanese: "japanese", mexican: "mexican", greek: "greek", turkish: "turkish", chicken: "chicken", "fish and chips": "fish_and_chips", vegan: "vegan", vegetarian: "vegetarian" };

const AMENITY_NAMES = { cafe: "cafe", restaurant: "restaurant", pub: "pub", bar: "bar", fast_food: "takeaway", post_office: "post office", post_box: "post box", pharmacy: "chemist", atm: "cash machine", toilets: "toilets", doctors: "doctor", dentist: "dentist", library: "library", fuel: "petrol station", parking: "car park", bank: "bank" };
const FOODY = new Set(["restaurant", "fast_food", "cafe"]);
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const clean = (x, n) => String(x ?? "").replace(/\s+/g, " ").trim().slice(0, n);

const FILLER_START = /^(?:(?:the )?(?:nearest|nearby|closest|local|find|a|an|the|me a|me the|any)\s+)+/;
const FILLER_END = /\s+(?:near me|nearby|around here|close by|near here|open now)$/;
const fold = (w) => w.replace(/ies$/, "y").replace(/(?:ches|shes|ses|xes)$/, (m) => m.slice(0, -2)).replace(/s$/, "");
const norm = (t) => String(t ?? "").toLowerCase().replace(/[^a-z' ]+/g, " ").replace(/'/g, "").replace(/\s+/g, " ").trim().replace(FILLER_START, "").replace(FILLER_END, "").trim();

/** The category (or cuisine) a typed phrase means, or null when it is just a name. Whole phrase only: "cafe royal" is a name, "cafes near me" is not. */
export function matchCategory(text) {
  const t = norm(text); if (!t) return null;
  const words = t.split(" ");
  const tries = new Set([t, words.slice(0, -1).concat(fold(words.at(-1))).join(" "), words.slice(0, -1).concat(words.at(-1).replace(/s$/, "")).join(" "), t.replace(/s$/, "")]);
  for (const x of tries) {
    for (const c of CATEGORIES) if (c.words.includes(x) || c.id === x) return { id: c.id, filters: c.filters, label: c.label };
    if (CUISINES[x]) return { id: `cuisine:${x}`, filters: [`["amenity"~"^(restaurant|fast_food)$"]["cuisine"~"${CUISINES[x]}"]`], label: "Restaurant", cuisine: x };
  }
  return null;
}

/** The Overpass question: every matching node, way or relation within `radius` metres, tags and a centre point only. */
export function buildQuery(cat, lat, lon, radius, seconds = 10) {
  const around = `(around:${radius},${Number(lat).toFixed(5)},${Number(lon).toFixed(5)})`;
  return `[out:json][timeout:${seconds}];(${cat.filters.map((f) => `nwr${f}${around};`).join("")});out tags center 80;`;
}

// ---- opening hours: "Mo-Fr 09:00-17:00; Sa 10:00-16:00", the common shapes only; anything else is shown as written and never guessed at.
const DAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"];
const DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su)", SPAN = `${DAY}(?:-${DAY})?`, DAYSPEC = `${SPAN}(?:\\s*,\\s*${SPAN})*`;
const TIME = "\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}", TIMES = `${TIME}(?:\\s*,\\s*${TIME})*`;
const RULE = new RegExp(`^(?:(${DAYSPEC})\\s+)?(off|closed|${TIMES})$`, "i");
const mins = (h, m) => Number(h) * 60 + Number(m);
const hhmm = (m) => { const x = ((m % 1440) + 1440) % 1440; return `${String(Math.floor(x / 60)).padStart(2, "0")}:${String(x % 60).padStart(2, "0")}`; };

function daysOf(spec) {
  const out = [];
  for (const part of spec.split(",")) {
    const [a, b] = part.trim().split("-"), i = DAYS.indexOf(a);
    if (!b) { out.push(i); continue; }
    for (let d = i, j = DAYS.indexOf(b); ; d = (d + 1) % 7) { out.push(d); if (d === j) break; }
  }
  return out;
}

/** The week as {day 0..6 (Mo=0): [[from, to] minutes]} (to may pass 1440 for a night shift), or null when the text is anything unusual. */
export function parseWeek(text) {
  const s = String(text ?? "").trim();
  if (s === "24/7") return Object.fromEntries(DAYS.map((_, d) => [d, [[0, 1440]]]));
  const week = {};
  for (const raw of s.split(";")) {
    const r = raw.trim(); if (!r) continue;
    if (/^PH\b/i.test(r)) continue;   // public holidays are not known here
    const m = RULE.exec(r); if (!m) return null;
    const days = m[1] ? daysOf(m[1]) : [0, 1, 2, 3, 4, 5, 6];
    const iv = /^(off|closed)$/i.test(m[2]) ? [] : m[2].split(",").map((t) => { const [a, b] = t.trim().split("-"), [h1, m1] = a.split(":"), [h2, m2] = b.split(":"); let to = mins(h2, m2); const from = mins(h1, m1); if (to <= from) to += 1440; return [from, to]; });
    if (iv.some(([f, t]) => f > 1440 || t > 2880)) return null;
    for (const d of days) week[d] = iv;
  }
  return Object.keys(week).length ? week : null;
}

const londonNow = (now) => {
  const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/London", weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(now).map((x) => [x.type, x.value]));
  return { day: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].indexOf(p.weekday), min: mins(p.hour, p.minute) };
};

/** {open, until?} / {open:false, opens?} for the hours text at `now`; null when the text is not understood (show it as written). A hint, not a promise. */
export function openNow(text, now = new Date()) {
  const week = parseWeek(text); if (!week) return null;
  const { day, min } = londonNow(now), today = week[day] ?? [], yday = week[(day + 6) % 7] ?? [];
  for (const [, to] of yday) if (to > 1440 && min < to - 1440) return { open: true, until: hhmm(to) };
  for (const [from, to] of today) if (min >= from && min < to) return { open: true, ...(to - from >= 1440 ? {} : { until: hhmm(to) }) };
  const later = today.filter(([from]) => from > min).sort((a, b) => a[0] - b[0])[0];
  return { open: false, ...(later ? { opens: hhmm(later[0]) } : {}) };
}

// ---- one Overpass answer to one tidy place
const YES = new Set(["yes", "no", "only", "limited"]);
const phoneOf = (t) => { const p = String(t.phone ?? t["contact:phone"] ?? "").replace(/[^0-9+() -]/g, "").trim(); return p.length >= 6 ? p.slice(0, 22) : ""; };
const cuisineWord = (t) => { const c = String(t.cuisine ?? "").split(";")[0].trim().toLowerCase(); return /^[a-z_]{2,24}$/.test(c) ? c.replace(/_/g, " ") : ""; };

/** The short type under a name: "Italian restaurant", "Post office", "Butcher". */
export function typeLabel(t, cat) {
  const a = t.amenity, base = (a && AMENITY_NAMES[a]) || (t.shop ? t.shop.replace(/_/g, " ") : "") || (t.leisure ? t.leisure.replace(/_/g, " ") : "") || cat.label.toLowerCase();
  const cu = FOODY.has(a) ? cuisineWord(t) : "";
  if (cu && !base.toLowerCase().startsWith(cu)) return cap(clean(`${cu} ${base}`, 40));
  return cap(clean(base, 40));
}

/** What an element becomes before the hours are judged (so a cached answer stays right as the day goes on). */
export function tidy(el, cat) {
  const t = el.tags ?? {}, lat = el.lat ?? el.center?.lat, lon = el.lon ?? el.center?.lon;
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const diet = ["vegan", "vegetarian"].filter((d) => t[`diet:${d}`] === "yes");
  const info = {};
  if (t.opening_hours) info.hours = clean(t.opening_hours, 120);
  if (YES.has(t.takeaway)) info.takeaway = t.takeaway;
  if (diet.length) info.diet = diet;
  if (YES.has(t.wheelchair)) info.wheelchair = t.wheelchair;
  const ph = phoneOf(t); if (ph) info.phone = ph;
  return { name: clean(t.name, 70), kind: "poi", type: typeLabel(t, cat), info, lat: Math.round(lat * 1e5) / 1e5, lon: Math.round(lon * 1e5) / 1e5, osm: `${(el.type ?? "n")[0]}${el.id}` };
}

/** The place as the phone gets it: distance from `here`, and the hours worked out for `now`. */
export function present(p, here, now) {
  const info = { ...p.info }, o = info.hours ? openNow(info.hours, now) : null;
  if (o) { info.open = o.open; if (o.until) info.until = o.until; if (o.opens) info.opens = o.opens; }
  return { name: p.name, kind: p.kind, type: p.type, ...(Object.keys(info).length ? { info } : {}), lat: p.lat, lon: p.lon, km: Math.round(km(here, p) * 10) / 10, osm: p.osm };
}

/** The ONE network call. Tries the main Overpass server and, only for a network error, 429 or 5xx, the second one; never retries beyond that. */
export async function overpass(f, query, endpoints = OVERPASS, timeoutMs = 12_000) {
  let last;
  for (const url of endpoints) {
    try {
      const r = await f(url, { method: "POST", headers: { ...UA, "content-type": "application/x-www-form-urlencoded" }, body: `data=${encodeURIComponent(query)}`, signal: AbortSignal.timeout(timeoutMs) });
      if (r.ok) return await r.json();
      last = new Error(`${new URL(url).host} answered ${r.status}`);
      if (r.status !== 429 && r.status < 500) throw Object.assign(last, { final: true });   // our question is wrong: a second server would say the same
    } catch (e) { if (e.final) throw e; last = e; }
  }
  throw last ?? new Error("overpass failed");
}

// ---- Nominatim: the first choice (one request returns the places with their tags)
const FOLD_Q = { takeaway: "fast food", pharmacy: "pharmacy", atm: "atm", fuel: "petrol station", parking: "car park", doctors: "doctors", convenience: "convenience store", post_box: "post box", post_office: "post office", playground: "playground" };
/** What Nominatim is asked for: the category word, or "<cuisine> restaurant" for a cuisine word. */
export function nominatimText(cat) {
  if (cat.cuisine) return cat.cuisine === "fish and chips" ? "fish and chip shop" : `${cat.cuisine} restaurant`;
  return FOLD_Q[cat.id] ?? cat.id.replace(/_/g, " ");
}
/** The [key, value] pairs a result must carry to count (read from the category's own Overpass filters), and the cuisine pattern if it has one. */
export function wanted(cat) {
  const pairs = [];
  for (const f of cat.filters) {
    const m = /^\["([a-z_]+)"(?:="([a-z_]+)"|~"\^\(([a-z_|]+)\)\$")\]/.exec(f); if (!m) continue;
    for (const v of (m[2] ?? m[3]).split("|")) pairs.push([m[1], v]);
  }
  const c = /\["cuisine"~"([a-z_|]+)"\]/.exec(cat.filters[0] ?? "");
  return { pairs, cuisine: c ? new RegExp(`(^|;)(${c[1]})(;|$)`) : null };
}
/** The viewbox for Nominatim: a square of +-radius metres around the point, as left,top,right,bottom. */
export function viewbox(lat, lon, radius) {
  const dLat = radius / 110_540, dLon = radius / (111_320 * Math.cos(lat * Math.PI / 180));
  return [lon - dLon, lat + dLat, lon + dLon, lat - dLat].map((x) => x.toFixed(5)).join(",");
}
export function nominatimUrl(cat, lat, lon, radius) {
  return `${NOMINATIM}?${new URLSearchParams({ q: nominatimText(cat), format: "jsonv2", limit: "40", extratags: "1", addressdetails: "0", "accept-language": "en", bounded: "1", viewbox: viewbox(lat, lon, radius) })}`;
}

let nextAt = 0;   // when the next Nominatim request may start (per Worker instance)
/** Spaces Nominatim requests at least NOMINATIM_GAP_MS apart, queueing callers that arrive together. */
export async function pace(now = Date.now, sleep = (ms) => new Promise((r) => setTimeout(r, ms))) {
  const t = now(), wait = Math.max(0, nextAt - t);
  nextAt = Math.max(t, nextAt) + NOMINATIM_GAP_MS;
  if (wait) await sleep(wait);
}

/** Nominatim's answer turned into the same element shape Overpass gives (tags and a position), keeping only results of the asked-for kind. */
export function nominatimElements(list, cat) {
  const { pairs, cuisine } = wanted(cat), out = [];
  for (const r of Array.isArray(list) ? list : []) {
    const lat = Number(r.lat), lon = Number(r.lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || !r.osm_id || !pairs.some(([k, v]) => r.category === k && r.type === v)) continue;
    const tags = { ...(r.extratags ?? {}), [r.category]: r.type };
    if (r.name) tags.name = r.name;
    if (cuisine && !cuisine.test(String(tags.cuisine ?? "").toLowerCase().replace(/ /g, ""))) continue;
    out.push({ type: r.osm_type, id: r.osm_id, lat, lon, tags });
  }
  return out;
}

/** One Nominatim request (paced, 8 s limit, our User-Agent). Throws on a network error or any non-OK answer. */
export async function nominatim(f, cat, lat, lon, radius, clock = {}) {
  await pace(clock.now, clock.sleep);
  const r = await f(nominatimUrl(cat, lat, lon, radius), { headers: UA, signal: AbortSignal.timeout(8_000) });
  if (!r.ok) throw new Error(`nominatim answered ${r.status}`);
  return nominatimElements(await r.json(), cat);
}

/** Elements of this category within `radius` of the point: Nominatim; and if that fails (not if it merely finds nothing), one short try at Overpass. */
export async function lookup(f, cat, lat, lon, radius) {
  try { return await nominatim(f, cat, lat, lon, radius); }
  catch { return (await overpass(f, buildQuery(cat, lat, lon, radius, 5), [OVERPASS[0]], 5_000))?.elements ?? []; }
}

const memo = new Map();   // per Worker instance: "cat|lat|lon" -> {at, places}; a short-lived courtesy to the public server, not a store
const cellKey = (id, p) => `${id}|${p.lat.toFixed(2)}|${p.lon.toFixed(2)}`;
export const clearPoiCache = () => { memo.clear(); nextAt = 0; };

/** Places of this category around `near`: 1 km, widening once to 2 km when fewer than 5 are found. At most two lookups per search (none when remembered). */
export async function searchCategory(f, cat, near, now = new Date(), look = lookup) {
  const key = cellKey(cat.id, near), hit = memo.get(key);
  let places, radius;
  if (hit && now.getTime() - hit.at < CACHE_MS) ({ places, radius } = hit);
  else {
    radius = RADIUS_M;
    const get = async (r) => (await look(f, cat, near.lat, near.lon, r)).map((e) => tidy(e, cat)).filter(Boolean);
    places = await get(radius);
    if (places.length < WIDEN_BELOW) { radius = WIDE_M; places = await get(radius); }
    if (memo.size >= CACHE_MAX) memo.delete(memo.keys().next().value);
    memo.set(key, { at: now.getTime(), places, radius });
  }
  const out = places.map((p) => present(p, near, now)).sort((a, b) => a.km - b.km).slice(0, MAX_PLACES);
  return { places: out, category: cat.id, radius, near: { lat: near.lat, lon: near.lon, ...(near.lastKnown ? { lastKnown: true } : {}) } };
}
