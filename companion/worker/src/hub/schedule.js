// Turns a guarded phone's settings ("child") into the config the phone enforces (controller Config.kt).
// A line-by-line port of ha/custom_components/phoneguard/schedule.py; test/golden-schedule.json (made by the Python code) keeps them identical.
import { zonedToUtc } from "../page/timetable.js";

export const WINDOW_DAYS = 14;
export const LOCKDOWN_KEYS = ["launcher", "touch", "shade", "kiosk", "sound", "inbound", "wa", "wa_app", "camera", "location", "loc_every", "notes", "idle_min", "screen_off_min", "radio", "radios_sleep", "voice_typing", "voice_cmds"];
export const NOTE_MODES = ["show", "off"];
export const LOC_MODES = ["off", "network", "gps"];
export const SOUNDS = ["normal", "vibrate", "silent"];
export const WA_CHECKS = ["off", "open", "30", "60"];
export const WA_APPS = ["auto", "simple", "full"];
export const RADIO_MODES = ["off", "presets", "any"];
export const CLOCK = "com.android.deskclock";
export const DEFAULT_KEEP_RUNNING = ["com.android.deskclock", "com.dumbermini.davx", "com.whatsapp"];

export const LOOK_THEMES = ["green", "grey", "amber", "orange", "mac", "macdark"];
export const LOOK_FONTS = ["mono", "sans", "vt323", "pixelify", "inst", "inter", "silk", "dot", "j15", "j10", "tiny", "micro", "hand", "doto", "bit", "ps2p", "bench", "jac", "sixty"];
export const LOOK_MENUS = ["list", "grid", "text"];
export const LOOK_OPTS = { windows: ["stripes", "platinum", "flat"], colour: ["mono", "red", "colour"], wall: ["dither", "plain", "blue", "green"], shadow: ["on", "off"], bars: ["red", "same", "grad", "none"] };
export const LOOK_KEYS = { theme: "theme", font: "font", menu_style: "menuStyle", accent: "accent", windows: "windows", colour: "colour", wall: "wall", shadow: "shadow", bars: "bars", font_display: "fontDisplay", font_text: "fontText", font_label: "fontLabel" };

const clone = (x) => JSON.parse(JSON.stringify(x));
const sortedUnique = (a) => [...new Set(a)].sort();

export function lockdownDefaults(modeId) {
  if (modeId === "school") return { launcher: "nokia", touch: false, shade: false, kiosk: true, sound: "silent", inbound: false, wa: "open", wa_app: "simple", camera: true, location: "off", loc_every: 30, notes: "off", idle_min: 10, screen_off_min: 5, radio: "any", radios_sleep: true, voice_typing: false, voice_cmds: false };
  return { launcher: "nokia", touch: true, shade: true, kiosk: false, sound: "normal", inbound: true, wa: "30", wa_app: "auto", camera: true, location: "off", loc_every: 30, notes: "show", idle_min: 10, screen_off_min: 5, radio: "any", radios_sleep: true, voice_typing: true, voice_cmds: true };
}

export function modeWithDefaults(modeId, mode) {
  const out = { ...mode };
  for (const [k, v] of Object.entries(lockdownDefaults(modeId))) if (!(k in out)) out[k] = v;
  return out;
}

export const WA_SETUPS = ["smartphone", "sim"], WA_WHO = ["people", "group", "all"];
export const whatsappDefaults = () => ({ all: false, allowed: [], perMinute: 5, perDay: 200 });
/** Who the phone talks to, worked out from the settings. Old settings (no "who") keep meaning "all" or "people". Groups-only is stricter: 3 a minute, 100 a day unless set. */
export function whatsappPlan(child) {
  const w = whatsappWithDefaults(child), who = w.who ?? (w.all ? "all" : "people"), explicit = child.whatsapp ?? {}, setup = w.setup ?? "smartphone";
  const groups = (w.groups ?? []).map((x) => String(x).trim()).filter(Boolean);
  const strict = who === "group";
  return {
    who, setup, groups, strict,
    all: who === "all",
    allowed: strict ? groups.map((g) => "g:" + g) : (w.allowed ?? []).map((x) => String(x).trim()).filter(Boolean),
    perMinute: intOr(strict && explicit.perMinute == null ? 3 : w.perMinute, 5), perDay: intOr(strict && explicit.perDay == null ? 100 : w.perDay, 200),
    tagSent: w.tag_sent == null ? who === "group" && setup === "smartphone" : Boolean(w.tag_sent),
  };
}
export const whatsappWithDefaults = (child) => ({ ...whatsappDefaults(), ...(child.whatsapp ?? {}) });

export function defaultChild(name) {
  const week = {};
  for (let dow = 1; dow <= 7; dow++) {
    const segs = [[0, 420, "night"]];
    if (dow <= 5) segs.push([510, 930, "school"]);
    segs.push([1260, 1440, "night"]);
    week[String(dow)] = segs;
  }
  return {
    name, tz: "Europe/London", base: "day", notify: "",
    modes: {
      day: { name: "Day", allowAll: true, allow: [], gray: false, ...lockdownDefaults("day") },
      school: { name: "School/work", allowAll: false, allow: [], gray: true, ...lockdownDefaults("school") },
      night: { name: "Night", allowAll: false, allow: [CLOCK], gray: false, ...lockdownDefaults("night") },
    },
    override: null, week, holiday: null, protect_extra: [], unprotect: [CLOCK], restrictions: null,
    whatsapp: whatsappDefaults(), location: null, notes_hide: [], pinned: [], known: null,
  };
}

// ---- time ---------------------------------------------------------------------------------------

const dayParts = (tz, nowMs) => { const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: tz, year: "numeric", month: "numeric", day: "numeric" }).formatToParts(new Date(nowMs)).map((x) => [x.type, +x.value])); return [p.year, p.month, p.day]; };
const addDays = ([y, m, d], n) => { const t = new Date(Date.UTC(y, m - 1, d + n)); return [t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()]; };
const isoWeekday = ([y, m, d]) => { const w = new Date(Date.UTC(y, m - 1, d)).getUTCDay(); return w === 0 ? 7 : w; };
const iso = ([y, m, d]) => `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
export const localMs = (tz, day, minutes) => minutes >= 1440 ? zonedToUtc(...addDays(day, 1), 0, tz) : zonedToUtc(day[0], day[1], day[2], minutes, tz);

function inHoliday(child, day) {
  const h = child.holiday; if (!h) return false;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(h.from ?? "") || !/^\d{4}-\d{2}-\d{2}$/.test(h.until ?? "")) return false;
  const s = iso(day); return h.from <= s && s <= h.until;
}

// ---- config ---------------------------------------------------------------------------------------

const intOr = (v, d) => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : d);

export function buildConfig(child, nowMs, options = null) {
  const tz = child.tz, today = dayParts(tz, nowMs), segments = [];
  for (let i = 0; i < WINDOW_DAYS; i++) {
    const day = addDays(today, i);
    for (const [start, end, mode0] of child.week[String(isoWeekday(day))] ?? []) {
      const mode = mode0 === "school" && inHoliday(child, day) ? "day" : mode0;
      segments.push({ from: localMs(tz, day, start), to: localMs(tz, day, end), mode });
    }
  }
  const modes = {};
  for (const [mid, m0] of Object.entries(child.modes)) {
    const m = modeWithDefaults(mid, m0);
    modes[mid] = {
      name: m.name ?? mid, allowAll: Boolean(m.allowAll), allow: sortedUnique(m.allow ?? []), gray: Boolean(m.gray),
      launcher: m.launcher === "nokia" ? "nokia" : "default", touch: Boolean(m.touch), shade: Boolean(m.shade), kiosk: Boolean(m.kiosk),
      sound: SOUNDS.includes(m.sound) ? m.sound : "normal", inbound: Boolean(m.inbound),
      wa: WA_CHECKS.includes(m.wa) ? m.wa : "open", waApp: WA_APPS.includes(m.wa_app) ? m.wa_app : "auto", camera: Boolean(m.camera),
      radio: RADIO_MODES.includes(m.radio) ? m.radio : "any", radiosSleep: Boolean(m.radios_sleep), ...(m.data_widget === false ? { dataWidget: false } : {}), voiceTyping: Boolean(m.voice_typing), voiceCmds: Boolean(m.voice_cmds),
      location: LOC_MODES.includes(m.location) ? m.location : "off", locEvery: Math.max(0, intOr(m.loc_every, 30)),
      notes: NOTE_MODES.includes(m.notes) ? m.notes : "show",
      idleMin: Math.max(0, Math.min(intOr(m.idle_min, 10), 1440)), screenOffMin: Math.max(0, Math.min(intOr(m.screen_off_min, 5), 1440)),
    };
  }
  const cfg = {
    base: child.base ?? "day", modes, segments,
    windowEnd: localMs(tz, addDays(today, WINDOW_DAYS - 1), 1440),
    pattern: { tz, days: clone(child.week) },
    protectExtra: sortedUnique(child.protect_extra ?? []), unprotect: sortedUnique(child.unprotect ?? []),
  };
  if (child.restrictions != null) cfg.restrictions = sortedUnique(child.restrictions);
  const w = whatsappPlan(child);
  cfg.whatsapp = { all: w.all, allowed: w.allowed, perMinute: w.perMinute, perDay: w.perDay };
  // the new settings are only sent when they differ from the old behaviour, so an unchanged phone keeps its config (and hash)
  if (w.setup !== "smartphone") cfg.whatsapp.setup = w.setup;
  if (child.whatsapp?.who != null) cfg.whatsapp.who = w.who;
  if (w.tagSent) { cfg.whatsapp.tagSent = true; cfg.whatsapp.tagName = String(child.person || child.name || "").trim().slice(0, 30); }
  if (child.location) cfg.location = { lat: Math.round(Number(child.location.lat) * 1000) / 1000, lon: Math.round(Number(child.location.lon) * 1000) / 1000 };
  if (options?.spotify_client_id) cfg.spotifyClientId = options.spotify_client_id;
  if (child.spotify_refresh) cfg.spotifyRefresh = child.spotify_refresh;
  if (child.secrets && Object.keys(child.secrets).length) cfg.secrets = child.secrets;   // Cloudflare only: sealed to the phone, never readable by the backend
  if (child.email) cfg.email = { address: child.email.address, user: child.email.user, imap: child.email.imap, smtp: child.email.smtp, secret: "email.password" };
  if (child.calendars?.length) cfg.calendars = child.calendars.map((c) => ({ id: c.id, name: c.name, secret: "calendar." + c.id }));
  if (child.lock?.rev) cfg.lock = { rev: child.lock.rev, op: child.lock.op, secret: "lock.pin" };
  const cmds = (child.commands ?? []).filter((c) => c && typeof c === "object");
  if (cmds.length) cfg.appCommands = cmds.map((c) => ({ id: c.id ?? "", kind: c.kind ?? "", pkg: c.pkg ?? "", name: c.name ?? "", url: c.url ?? "", sha256: c.sha256 ?? "", size: intOr(c.size, 0) }));
  const stations = (child.radio_stations ?? []).filter((x) => x && typeof x === "object").map((x) => ({ name: String(x.name ?? "").trim().slice(0, 40), url: String(x.url ?? "").trim() })).filter((x) => x.name && x.url.startsWith("https://"));
  if (stations.length) cfg.radioStations = stations;
  if (child.idle_dry_run) cfg.idleDryRun = true;
  cfg.keepRunning = (child.keep_running ?? DEFAULT_KEEP_RUNNING).map(String).sort();
  cfg.notesHide = (child.notes_hide ?? []).map(String).sort();
  cfg.pinned = (child.pinned ?? []).map(String);
  const look = {}; for (const [k, v] of Object.entries(child.look ?? {})) if (k in LOOK_KEYS && v) look[LOOK_KEYS[k]] = v;
  if (Object.keys(look).length) cfg.look = look;
  if (child.override in modes) cfg.override = child.override;
  // The phone only needs "did anything change": a 48-bit hash of the canonical text. (The Python hub uses SHA-1; the exact value is not part of the protocol.)
  cfg.version = hash48(canon(cfg));
  return cfg;
}

export function canon(v) { return JSON.stringify(v, (_k, x) => (x && typeof x === "object" && !Array.isArray(x) ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, x[k]])) : x)); }
export function hash48(s) { // cyrb53
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); h1 = Math.imul(h1 ^ c, 2654435761); h2 = Math.imul(h2 ^ c, 1597334677); }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return 4294967296 * (0xffff & h2) + (h1 >>> 0);
}
export const cfgHash = (cfg) => cfg.version.toString(16).padStart(12, "0");

export function cleanLook(v) {
  if (v == null) return null;
  if (typeof v !== "object" || Array.isArray(v)) throw new Error("look must be an object");
  const out = {}, fonts = { theme: LOOK_THEMES, font: LOOK_FONTS, menu_style: LOOK_MENUS, font_display: LOOK_FONTS, font_text: LOOK_FONTS, font_label: LOOK_FONTS, ...LOOK_OPTS };
  for (const [k, val] of Object.entries(v)) {
    if (!(k in LOOK_KEYS)) throw new Error(`look has no setting '${k}'`);
    if (val == null || val === "") continue;
    const ok = fonts[k];
    if (ok && !ok.includes(val)) throw new Error(`look ${k} '${val}' is not one of ${ok.join(", ")}`);
    if (k === "accent" && !(typeof val === "string" && /^#[0-9a-fA-F]{6}$/.test(val))) throw new Error("look accent must be a colour like #D42A1E");
    out[k] = val;
  }
  return Object.keys(out).length ? out : null;
}

// Mirror of the controller's Engine.scheduledMode, for display only.
export function resolveMode(cfg, boost, nowMs) {
  if (cfg.override in cfg.modes) return cfg.override;
  for (const s of cfg.segments) if (s.from <= nowMs && nowMs < s.to) return s.mode;
  if (nowMs >= cfg.windowEnd && cfg.pattern) {
    const p = cfg.pattern, parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: p.tz, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric" }).formatToParts(new Date(nowMs)).map((x) => [x.type, +x.value]));
    const minute = parts.hour * 60 + parts.minute;
    for (const [start, end, mode] of p.days[String(isoWeekday([parts.year, parts.month, parts.day]))] ?? []) if (start <= minute && minute < end) return mode;
  }
  return cfg.base;
}
