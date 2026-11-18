// Parent actions and sync merging for one guarded phone: a port of ha/custom_components/phoneguard/hub.py.
// Pure functions over two plain objects: `child` (what the parent sets) and `rt` (what the phone reported and short-lived state such as a boost).
import * as S from "./schedule.js";
import * as PW from "./power.js";
import { validPkg, isNewer } from "./store.js";

// The one app store a parent can install from here. The address and checksum are pinned in this file: a caller can never choose them.
/** Apps the phone fetches itself through the app store (Aurora): it opens the store page and presses Install. Needs a phone app that reports `storeInstall`. */
export const STORE_INSTALLS = { "com.spotify.music": { name: "Spotify" }, "com.whatsapp": { name: "WhatsApp" } };
export const STORE_APPS = { "com.aurora.store": { name: "Aurora Store", version: "4.8.4", url: "https://f-droid.org/repo/com.aurora.store_76.apk", sha256: "fd9c75d90d0f4a7c132b9b4a5a2cf1992a45e03b8d8ff988b7dcfbc0db2c4d11", size: 9360088 } };
export const BOOST_CAP_MIN = 180, USAGE_DAYS = 30, OWN_PACKAGE = "io.github.durkimat.phoneguard";
const clone = (x) => JSON.parse(JSON.stringify(x));
const isInt = (x) => Number.isInteger(x);
const fail = (m) => { throw new Error(m); };

export const UPDATABLE = ["name", "person", "override", "tz", "base", "notify", "modes", "week", "holiday", "protect_extra", "unprotect", "restrictions", "whatsapp", "wa_setup", "pinned", "location", "notes_hide", "keep_running", "idle_dry_run", "radio_stations", "look", "email", "calendars"];

/**
 * The WhatsApp setup window (SIM method). The companion only ALLOWS it (valid 24 h, cancellable) and can add 15 minutes or end it; the phone
 * starts it. {allowed_until, extra_min, end_rev} are all the phone needs: it remembers extra_min and end_rev when it starts, and ends the window
 * when end_rev goes up or the time (30 min + the extra added since) runs out. Anything else in the patch is ignored.
 */
export const WA_SETUP_ALLOW_MS = 24 * 3600_000;
export function cleanWaSetup(v, prev, nowMs) {
  if (v == null) return null;
  if (typeof v !== "object" || Array.isArray(v)) fail("wa_setup must be an object");
  const old = prev ?? {}, out = { allowed_until: Number(old.allowed_until) || 0, extra_min: Number(old.extra_min) || 0, end_rev: Number(old.end_rev) || 0 };
  if ("allow" in v) out.allowed_until = v.allow ? nowMs + WA_SETUP_ALLOW_MS : 0;
  if (v.extend) out.extra_min += 15;
  if (v.end) out.end_rev += 1;
  return out;
}

export function newRuntime() { return { apps: [], state: {}, cfgh: "", usage: {}, power: [], reaped: [], labels: {}, fix: null, boost: null, release: false, last_seen: null }; }

// ---- editing a phone's settings ----------------------------------------------------------------------------

export function updateChild(ch, patch, nowMs, options = null) {
  const before = {}; for (const k of UPDATABLE) if (k in ch) before[k] = clone(ch[k]);
  for (let [k, v] of Object.entries(patch)) {
    if (k === "whatsapp" && v && typeof v === "object" && !Array.isArray(v)) {
      v = { ...v };
      if (Array.isArray(v.groups)) v.groups = [...new Set(v.groups.map((x) => (typeof x === "string" ? x.trim() : x)).filter(Boolean))];
      if (Array.isArray(v.allowed)) { const seen = []; for (let x of v.allowed) { x = typeof x === "string" ? x.trim() : x; if (x && !seen.includes(x)) seen.push(x); } v.allowed = seen; }
    }
    if (k === "wa_setup") v = cleanWaSetup(v, ch.wa_setup, nowMs);
    if (k === "radio_stations") {
      const clean = [], urls = new Set();
      for (const x of v ?? []) {
        const name = x && typeof x === "object" ? String(x.name ?? "").trim().slice(0, 40) : "", url = x && typeof x === "object" ? String(x.url ?? "").trim() : "";
        if (!name || !url.startsWith("https://")) fail(`radio station ${name || url} needs a name and an https:// address`);
        if (!urls.has(url)) { urls.add(url); clean.push({ name, url }); }
      }
      v = clean;
    }
    if (k === "location" && v != null) {
      const lat = Math.round(Number(v?.lat) * 1000) / 1000, lon = Math.round(Number(v?.lon) * 1000) / 1000;
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) fail("location needs a lat and a lon");
      if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) fail("location is out of range");
      v = { lat, lon };
    }
    if (k === "notes_hide" && Array.isArray(v)) v = [...new Set(v.filter((x) => typeof x === "string" && x))];
    if (k === "look") v = S.cleanLook(v);
    if (k === "email") v = cleanEmail(v);
    if (k === "calendars") v = cleanCalendars(v);
    if (k === "pinned" && Array.isArray(v)) v = [...new Set(v.filter((x) => typeof x === "string" && x))];
    if (UPDATABLE.includes(k)) ch[k] = v;
  }
  try { validateChild(ch, nowMs, options); }
  catch (e) { for (const k of UPDATABLE) delete ch[k]; Object.assign(ch, before); throw e; }   // a bad change is never kept, let alone sent to the phone
}

// Email and calendars are Cloudflare-side extensions (the Home Assistant hub has neither). Passwords and private calendar links are never here:
// they travel as sealed secrets ("email.password", "calendar.<id>") that only the phone can open.
const HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
const cleanServer = (x, what) => {
  if (!x || typeof x !== "object") fail(`${what} server settings are missing`);
  const host = String(x.host ?? "").trim().toLowerCase(), port = Number(x.port), security = x.security ?? "ssl";
  if (!HOST.test(host)) fail(`${what} server name '${host}' is not a valid address`);
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail(`${what} port must be between 1 and 65535`);
  if (!["ssl", "starttls"].includes(security)) fail(`${what} security must be ssl or starttls`);
  return { host, port, security };
};
export function cleanEmail(v) {
  if (v == null) return null;
  if (typeof v !== "object" || Array.isArray(v)) fail("email must be an object");
  const address = String(v.address ?? "").trim();
  if (!/^[^\s@]{1,64}@[^\s@]{1,190}$/.test(address) || !address.split("@")[1].includes(".")) fail("that does not look like an email address");
  const user = String(v.user ?? address).trim().slice(0, 128) || address;
  return { address, user, imap: cleanServer(v.imap, "Incoming mail (IMAP)"), smtp: cleanServer(v.smtp, "Outgoing mail (SMTP)") };
}
export function cleanCalendars(v) {
  if (v == null) return [];
  if (!Array.isArray(v) || v.length > 8) fail("a phone can follow at most 8 calendars");
  const ids = new Set();
  return v.map((c) => {
    const id = String(c?.id ?? ""), name = String(c?.name ?? "").trim().slice(0, 40);
    if (!/^[a-z0-9]{1,12}$/.test(id)) fail("a calendar id is 1 to 12 lower-case letters and digits");
    if (!name) fail("every calendar needs a name");
    if (ids.has(id)) fail("two calendars have the same id"); ids.add(id);
    return { id, name };
  });
}

const intIn = (x, lo, hi) => isInt(x) && x >= lo && x <= hi;

export function validateChild(ch, nowMs, options = null) {
  try { S.buildConfig(ch, nowMs, options); } catch (e) { fail("bad timezone or timetable: " + e.message); }
  for (const segs of Object.values(ch.week)) for (const [, , mode] of segs) if (!(mode in ch.modes)) fail(`the timetable uses mode '${mode}', which this device does not have`);
  for (const [mid, m] of Object.entries(ch.modes)) {
    if (!S.WA_CHECKS.includes(m.wa ?? "open")) fail(`mode '${mid}' has an unknown WhatsApp check setting '${m.wa}'`);
    if (!S.WA_APPS.includes(m.wa_app ?? "auto")) fail(`mode '${mid}' has an unknown WhatsApp app setting '${m.wa_app}'`);
    if ("radios_sleep" in m && typeof m.radios_sleep !== "boolean") fail(`mode '${mid}': radios_sleep must be true or false`);
    if ("data_widget" in m && typeof m.data_widget !== "boolean") fail(`mode '${mid}': data_widget must be true or false`);
    for (const k of ["voice_typing", "voice_cmds"]) if (k in m && typeof m[k] !== "boolean") fail(`mode '${mid}': ${k} must be true or false`);
    if (!S.RADIO_MODES.includes(m.radio ?? "any")) fail(`mode '${mid}' has an unknown radio setting '${m.radio}'`);
    if (!S.NOTE_MODES.includes(m.notes ?? "show")) fail(`mode '${mid}' has an unknown notifications setting '${m.notes}'`);
    if (!S.LOC_MODES.includes(m.location ?? "off")) fail(`mode '${mid}' has an unknown location setting '${m.location}'`);
    if (!intIn(m.loc_every ?? 30, 0, 1440)) fail(`mode '${mid}' needs location minutes between 0 (live) and 1440`);
    if (!intIn(m.screen_off_min ?? 5, 0, 1440)) fail(`mode '${mid}' needs screen-off minutes between 0 (never) and 1440`);
    if (!intIn(m.idle_min ?? 10, 0, 1440)) fail(`mode '${mid}' needs idle minutes between 0 (never) and 1440`);
    if (!S.SOUNDS.includes(m.sound ?? "normal")) fail(`mode '${mid}' has an unknown sound setting '${m.sound}'`);
  }
  const w = S.whatsappWithDefaults(ch);
  if (!Array.isArray(w.allowed) || !w.allowed.every((x) => typeof x === "string")) fail("the WhatsApp allowed list must be a list of numbers and group names");
  if (w.setup != null && !S.WA_SETUPS.includes(w.setup)) fail("WhatsApp setup must be 'sim' or 'smartphone'");
  if (w.who != null && !S.WA_WHO.includes(w.who)) fail("WhatsApp who must be 'group', 'people' or 'all'");
  if (w.groups != null && (!Array.isArray(w.groups) || !w.groups.every((x) => typeof x === "string"))) fail("the WhatsApp groups must be a list of group names");
  if (w.who === "group" && !(w.groups ?? []).some((x) => x.trim())) fail("groups-only WhatsApp needs at least one group name");
  if (w.tag_sent != null && typeof w.tag_sent !== "boolean") fail("tag_sent must be true or false");
  if (!(1 <= Number(w.perMinute) && Number(w.perMinute) <= 60) || !(1 <= Number(w.perDay) && Number(w.perDay) <= 2000)) fail("WhatsApp send limits must be 1 to 60 a minute and 1 to 2000 a day");
  if ("idle_dry_run" in ch && typeof ch.idle_dry_run !== "boolean") fail("idle_dry_run must be true or false");
  if ("keep_running" in ch && !(Array.isArray(ch.keep_running) && ch.keep_running.every((x) => typeof x === "string"))) fail("the keep-running list must be a list of app names");
  if (!(ch.base in ch.modes)) fail(`base mode '${ch.base}' does not exist`);
  if (ch.override != null && !(ch.override in ch.modes)) fail(`mode '${ch.override}' does not exist`);
}

export const setOverride = (ch, mode, nowMs, options) => updateChild(ch, { override: mode == null || mode === "" || mode === "auto" ? null : mode }, nowMs, options);
export const setHoliday = (ch, from, until) => { ch.holiday = from && until ? { from, until } : null; };

export const COPY_PARTS = ["timetable", "holiday", "restrictions", "mode_options", "whatsapp"];
export function copySettings(src, dst, parts, nowMs, options) {
  const done = [], patch = {};
  for (const part of parts) {
    if (part === "timetable") { patch.week = clone(src.week); patch.base = src.base; patch.tz = src.tz; }
    else if (part === "holiday") patch.holiday = clone(src.holiday);
    else if (part === "restrictions") patch.restrictions = clone(src.restrictions ?? null);
    else if (part === "whatsapp") patch.whatsapp = clone(S.whatsappWithDefaults(src));
    else if (part === "mode_options") {
      const modes = clone(dst.modes);
      for (const [mid, m0] of Object.entries(src.modes)) if (mid in modes) { const m = S.modeWithDefaults(mid, m0); modes[mid].gray = m.gray ?? false; modes[mid].auto_new = Boolean(m.auto_new); for (const k of S.LOCKDOWN_KEYS) modes[mid][k] = m[k]; }
      patch.modes = modes;
    } else fail(`unknown part '${part}'`);
    done.push(part);
  }
  updateChild(dst, patch, nowMs, options);
  return done;
}

// ---- apps: who may see what, what is new, jobs for the phone ------------------------------------------------------

export function dismissApp(ch, pkg) { ch.known = [...new Set([...(ch.known ?? []), pkg])].sort(); }
export function setAppMode(ch, pkg, mode, allowed) { const s = new Set(ch.modes[mode].allow ?? []); allowed ? s.add(pkg) : s.delete(pkg); ch.modes[mode].allow = [...s].sort(); dismissApp(ch, pkg); }
export function approveApp(ch, pkg, modes) { for (const m of modes) setAppMode(ch, pkg, m, true); dismissApp(ch, pkg); }
export function setModeAll(ch, rt, mode, allowed) { const pkgs = [...new Set(rt.apps.map((a) => a.pkg))].sort(), m = ch.modes[mode], keep = new Set(m.allow ?? []); m.allow = allowed ? [...new Set([...keep, ...pkgs])].sort() : [...keep].filter((p) => !pkgs.includes(p)).sort(); }

// Run on every report of the phone's apps. The matrix is the only authority on what is visible.
export function applyAppPolicy(ch, pkgs) {
  for (const m of Object.values(ch.modes)) if (m.allowAll) { m.allow = [...new Set([...(m.allow ?? []), ...pkgs])].sort(); m.allowAll = false; m.auto_new ??= false; }
  if (ch.seen == null) { ch.seen = [...pkgs].sort(); return; }
  const fresh = [...pkgs].filter((p) => !ch.seen.includes(p));
  if (fresh.length) { for (const m of Object.values(ch.modes)) if (m.auto_new) m.allow = [...new Set([...(m.allow ?? []), ...fresh])].sort(); ch.seen = [...new Set([...ch.seen, ...pkgs])].sort(); }
}

export function queueApp(ch, kind, pkg, name, nowMs, { url = "", sha256 = "", size = 0 } = {}, newId = () => crypto.randomUUID().replace(/-/g, "").slice(0, 12)) {
  if (kind === "remove") { if (!validPkg(pkg) || pkg === OWN_PACKAGE) fail("that app cannot be removed from here"); url = sha256 = ""; }
  else if (kind === "update" && pkg === OWN_PACKAGE) { if (!url.startsWith("https://")) fail("the download address must be https"); if (!/^[0-9a-f]{64}$/.test(sha256 ?? "")) fail("the checksum is missing"); }
  else if (kind === "install" && STORE_APPS[pkg]) { const s = STORE_APPS[pkg]; url = s.url; sha256 = s.sha256; size = s.size; name = s.name; }
  else if (kind === "storeinstall" && STORE_INSTALLS[pkg]) { name = STORE_INSTALLS[pkg].name; url = sha256 = ""; size = 0; }
  else fail("only Phone Guard can be updated, the app store installed, and other apps removed, from here");
  const cmds = (ch.commands ??= []);
  if (cmds.some((c) => c.kind === kind && c.pkg === pkg)) fail("that is already waiting for the phone");
  const cmd = { id: newId(), kind, pkg, name: (name ?? "").slice(0, 60) || pkg, url, sha256, size: Math.trunc(size), at: nowMs };
  cmds.push(cmd); return cmd;
}
/** Queues the app store (Aurora) for a phone unless it is already there or already waiting. */
export function queueStore(ch, rt, nowMs) {
  const pkg = Object.keys(STORE_APPS)[0];
  if (rt.apps.some((a) => a.pkg === pkg)) fail("the app store is already on the phone");
  return queueApp(ch, "install", pkg, STORE_APPS[pkg].name, nowMs);
}
/** Queues an install through the app store (Spotify) when the store is on the phone and the phone app can drive it. */
export function queueStoreInstall(ch, rt, pkg, nowMs) {
  if (!STORE_INSTALLS[pkg]) fail("that app cannot be installed from here");
  if (rt.apps.some((a) => a.pkg === pkg)) fail(STORE_INSTALLS[pkg].name + " is already on the phone");
  if (!rt.apps.some((a) => a.pkg === "com.aurora.store")) fail("the app store has to be on the phone first");
  if (rt.state?.storeInstall !== true) fail("this phone's app is too old to install apps from the store; update Phone Guard first");
  return queueApp(ch, "storeinstall", pkg, STORE_INSTALLS[pkg].name, nowMs);
}
/** Once Spotify is connected, puts the Spotify app on the phone by itself (at most one try every six hours). Returns the queued command or null. */
export function autoSpotify(ch, rt, options, nowMs) {
  const pkg = "com.spotify.music";
  if (options?.spotify_app_auto === false || !ch.secrets?.["spotify.refresh"]) return null;
  if (rt.apps.some((a) => a.pkg === pkg) || (ch.commands ?? []).some((c) => c.kind === "storeinstall" && c.pkg === pkg)) return null;
  if (nowMs - (ch.spotify_try ?? 0) < 6 * 3600e3) return null;
  try { const c = queueStoreInstall(ch, rt, pkg, nowMs); ch.spotify_try = nowMs; return c; } catch { return null; }
}
export const cancelApp = (ch, id) => { ch.commands = (ch.commands ?? []).filter((c) => c.id !== id); };

// The phone reports each finished job as [id, "done" or "failed", detail, time].
export function mergeAppResults(ch, results) {
  if (!Array.isArray(results)) return;
  const log = (ch.app_log ??= []);
  for (const r of results) {
    if (!(Array.isArray(r) && r.length >= 4 && typeof r[0] === "string" && (r[1] === "done" || r[1] === "failed"))) continue;
    const cmd = (ch.commands ?? []).find((c) => c.id === r[0]); if (!cmd) continue;
    log.push([Math.trunc(r[3]), cmd.name, cmd.kind, r[1], String(r[2]).slice(0, 120)]);
    ch.commands = ch.commands.filter((c) => c.id !== r[0]);
  }
  ch.app_log = log.slice(-40);
}

export function updateFor(rt, latest, options) {
  if (!latest) return null;
  const have = String(rt.state?.version ?? "");
  return { version: latest.version, notes: latest.notes ?? "", installed: have, available: Boolean(latest.url) && isNewer(latest.version, have), ready: Boolean(latest.sha256), enabled: Boolean(options?.updates_enabled) };
}
export function queueUpdate(ch, rt, latest, options, nowMs, override = null) {
  if (!options?.updates_enabled) fail("updates are switched off (the switch is in the Updates and removals section)");
  if (override) return queueApp(ch, "update", OWN_PACKAGE, "Phone Guard " + (override.version ?? "test build"), nowMs, { url: override.url, sha256: override.sha256, size: override.size ?? 0 });
  const u = updateFor(rt, latest, options);
  if (!u || !u.available || !latest?.sha256) fail("there is no update ready for that phone");
  return queueApp(ch, "update", OWN_PACKAGE, "Phone Guard " + latest.version, nowMs, { url: latest.url, sha256: latest.sha256, size: latest.size ?? 0 });
}

// ---- sealed secrets, email and calendars ------------------------------------------------------------------------------

export const SECRET_NAME = /^[a-z][a-z0-9_.]{0,39}$/;
export function setSecret(ch, name, sealed) {
  if (!SECRET_NAME.test(name)) fail("bad secret name");
  if (sealed == null) { if (ch.secrets) delete ch.secrets[name]; return; }
  if (sealed?.alg !== "ECDH-P256+HKDF-SHA256+AES-256-GCM" || typeof sealed.epk !== "string" || typeof sealed.ct !== "string") fail("not a sealed secret");
  (ch.secrets ??= {})[name] = sealed;
}
export function setEmail(ch, email, sealedPassword, nowMs, options) {
  updateChild(ch, { email }, nowMs, options);
  if (sealedPassword !== undefined) setSecret(ch, "email.password", sealedPassword);
}
export function clearEmail(ch, nowMs, options) { updateChild(ch, { email: null }, nowMs, options); setSecret(ch, "email.password", null); }
// The screen-lock PIN: the parent's PIN is sealed to the phone (name "lock.pin"); cfg.lock.rev tells the phone it is new. A later rev always wins over a PIN the child chose.
export function setLock(ch, op, sealedPin) {
  if (op !== "set" && op !== "clear") fail("lock must be set or clear");
  if (op === "set") { if (sealedPin == null) fail("a PIN is needed"); setSecret(ch, "lock.pin", sealedPin); } else setSecret(ch, "lock.pin", null);
  ch.lock = { rev: (ch.lock?.rev ?? 0) + 1, op };
}
export function addCalendar(ch, name, sealedUrl, nowMs, options, newId = () => crypto.randomUUID().replace(/-/g, "").slice(0, 8)) {
  const id = newId(); updateChild(ch, { calendars: [...(ch.calendars ?? []), { id, name }] }, nowMs, options); setSecret(ch, "calendar." + id, sealedUrl); return id;
}
export function removeCalendar(ch, id, nowMs, options) {
  if (!(ch.calendars ?? []).some((c) => c.id === id)) fail("no such calendar");
  updateChild(ch, { calendars: ch.calendars.filter((c) => c.id !== id) }, nowMs, options); setSecret(ch, "calendar." + id, null);
}

// ---- boost ------------------------------------------------------------------------------------------------------

export function activeBoost(rt, nowMs) { const b = rt.boost; if (b && b.until > nowMs) return b; rt.boost = null; return null; }
export function boost(rt, mode, minutes, nowMs, extend = true) {
  const cur = activeBoost(rt, nowMs); minutes = Math.max(1, Math.min(Math.trunc(minutes), BOOST_CAP_MIN));
  const until = cur && extend && cur.mode === mode ? Math.min(cur.until + minutes * 60_000, nowMs + BOOST_CAP_MIN * 60_000) : nowMs + minutes * 60_000;
  return (rt.boost = { mode, until });
}
export const cancelBoost = (rt) => { rt.boost = null; };

// ---- what the phone reports on every check-in -------------------------------------------------------------------------

export function mergeFix(rt, fixes) {
  if (!Array.isArray(fixes)) return;
  const num = (x) => typeof x === "number" && Number.isFinite(x);
  for (const f of fixes) if (Array.isArray(f) && f.length >= 5 && f.slice(0, 4).every(num) && f[1] >= -90 && f[1] <= 90 && f[2] >= -180 && f[2] <= 180 && f[3] >= 0 && f[0] > (rt.fix?.[0] ?? 0)) rt.fix = f.slice(0, 6);
}
// The valid positions in a report, oldest first: [time ms, lat, lon, accuracy m, provider, mode].
export function validFixes(fixes) {
  if (!Array.isArray(fixes)) return [];
  const num = (x) => typeof x === "number" && Number.isFinite(x);
  return fixes.filter((f) => Array.isArray(f) && f.length >= 5 && f.slice(0, 4).every(num) && f[1] >= -90 && f[1] <= 90 && f[2] >= -180 && f[2] <= 180 && f[3] >= 0 && f[0] > 0)
    .map((f) => [Math.trunc(f[0]), Math.round(f[1] * 1e5) / 1e5, Math.round(f[2] * 1e5) / 1e5, Math.round(f[3]), String(f[4] ?? "").slice(0, 12), String(f[5] ?? "").slice(0, 24)]).sort((a, b) => a[0] - b[0]);
}

export function mergeUsage(rt, usage) {
  if (!usage || typeof usage !== "object" || Array.isArray(usage)) return;
  const store = (rt.usage ??= {});
  for (const [day, apps] of Object.entries(usage)) if (day.length === 10 && apps && typeof apps === "object" && !Array.isArray(apps)) store[day] = Object.fromEntries(Object.entries(apps).filter(([, v]) => typeof v === "number").map(([k, v]) => [k, Math.trunc(v)]));
  for (const old of Object.keys(store).sort().slice(0, -USAGE_DAYS)) delete store[old];
}

// Everything in a phone's sync payload except the config decision. Mutates child and rt.
export function mergeSync(ch, rt, payload, nowMs) {
  rt.last_seen = nowMs;
  rt.state = payload.state && typeof payload.state === "object" ? payload.state : {};
  rt.apps = Array.isArray(payload.apps) ? payload.apps.filter((a) => a && typeof a.pkg === "string").map((a) => ({ pkg: a.pkg, label: String(a.label ?? a.pkg).slice(0, 80), hidden: Boolean(a.hidden) })) : [];
  if (typeof payload.cfgh === "string") rt.cfgh = payload.cfgh;
  mergeUsage(rt, payload.usage);
  rt.power = PW.merge(rt.power ?? [], payload.power, nowMs);
  rt.reaped = PW.mergeReaped(rt.reaped ?? [], payload.reaped);
  mergeAppResults(ch, payload.appResults);
  mergeFix(rt, payload.loc);
  if (payload.labels && typeof payload.labels === "object") for (const [k, v] of Object.entries(payload.labels)) if (typeof v === "string") (rt.labels ??= {})[k] = v.slice(0, 80);
  if (ch.known == null && rt.apps.length) ch.known = rt.apps.map((a) => a.pkg).sort();
  if (rt.apps.length) applyAppPolicy(ch, new Set(rt.apps.map((a) => a.pkg)));
}

// ---- what the parent page shows ---------------------------------------------------------------------------------------

function spotifyView(ch, rt, options) {
  const pkg = "com.spotify.music", last = [...(ch.app_log ?? [])].reverse().find((r) => r[2] === "storeinstall" && r[1] === "Spotify");
  return { connected: Boolean(ch.secrets?.["spotify.refresh"]), installed: rt.apps.some((a) => a.pkg === pkg), waiting: (ch.commands ?? []).some((c) => c.kind === "storeinstall" && c.pkg === pkg),
    store: rt.apps.some((a) => a.pkg === "com.aurora.store"), can: rt.state?.storeInstall === true, auto: options?.spotify_app_auto !== false, last: last ? { ok: last[3] === "done", detail: last[4], at: last[0] } : null };
}

export function view(ch, rt, options, latest, nowMs) {
  const cfg = S.buildConfig(ch, nowMs, options), b = activeBoost(rt, nowMs), known = new Set(ch.known ?? []);
  return {
    name: ch.name, person: ch.person || ch.name, tz: ch.tz, base: ch.base, notify: ch.notify ?? "",
    modes: Object.fromEntries(Object.entries(ch.modes).map(([id, m]) => [id, S.modeWithDefaults(id, m)])),
    override: ch.override in ch.modes ? ch.override : null, week: ch.week, holiday: ch.holiday ?? null,
    mode_now: S.resolveMode(cfg, b, nowMs), boost: b, now: nowMs,
    restrictions: ch.restrictions ?? null, whatsapp: S.whatsappWithDefaults(ch), radio_stations: ch.radio_stations ?? [], pinned: ch.pinned ?? [], look: ch.look ?? {},
    notes_hide: ch.notes_hide ?? [], keep_running: ch.keep_running ?? S.DEFAULT_KEEP_RUNNING, idle_dry_run: Boolean(ch.idle_dry_run), location: ch.location ?? null,
    options: { ...(options ?? {}) },
    device: { last_seen: rt.last_seen, state: rt.state, cfgh: rt.cfgh, version: rt.state?.version ?? "", fix: rt.fix },
    apps: rt.apps.map((a) => ({ ...a })), pending_apps: rt.apps.filter((a) => !known.has(a.pkg)).map((a) => ({ ...a })),
    usage: rt.usage ?? {}, labels: rt.labels ?? {},
    app_commands: (ch.commands ?? []).map((c) => ({ id: c.id, kind: c.kind, pkg: c.pkg, name: c.name })), app_log: [...(ch.app_log ?? [])].reverse().slice(0, 8),
    update: updateFor(rt, latest, options),
    power: PW.summarize(rt.power ?? [], nowMs), reaped: PW.summarizeReaped(rt.reaped ?? [], nowMs),
    lock: { sent: ch.lock ?? null, phone: rt.state?.lock ?? null },
    store: { name: STORE_APPS["com.aurora.store"].name, version: STORE_APPS["com.aurora.store"].version, installed: rt.apps.some((a) => a.pkg === "com.aurora.store"), waiting: (ch.commands ?? []).some((c) => c.kind === "install" && c.pkg === "com.aurora.store"), auto: options?.app_store_auto !== false },
    spotify: spotifyView(ch, rt, options),
    email: ch.email ?? null, email_password_set: Boolean(ch.secrets?.["email.password"]), calendars: (ch.calendars ?? []).map((c) => ({ ...c, url_set: Boolean(ch.secrets?.["calendar." + c.id]) })),
  };
}
