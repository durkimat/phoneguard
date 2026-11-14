// One phone's screen: what is happening now, the controls, the timetable, apps and modes, settings, Spotify, reports and phone management.
// Every control calls one Worker endpoint and then redraws from the server's answer, so what is shown is always what the phone will be told.
import { h, ago, hm, toMin, left, status, section, field, select, check } from "/ui.js";
import { checkPattern } from "/timetable.js";
import { LOOK } from "/look-options.js";
import { makePkce, authorizeUrl, redirectUri } from "/spotify.js";

const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const RESTRICTIONS = [
  ["no_factory_reset", "Block factory reset", "From Settings. A reset from recovery mode cannot be blocked.", true], ["no_safe_boot", "Block safe mode", "", true],
  ["no_config_date_time", "Lock date and time", "Keeps the clock on network time so schedules cannot be dodged.", true], ["no_add_user", "Block adding users", "", true],
  ["no_add_managed_profile", "Block work profiles", "", true], ["no_config_vpn", "Block VPN changes", "", false], ["disallow_config_private_dns", "Block private DNS changes", "", false],
  ["no_usb_file_transfer", "Block USB file transfer", "", false], ["no_modify_accounts", "Block adding or removing accounts", "", false], ["no_config_credentials", "Block certificate changes", "", false],
  ["no_config_mobile_networks", "Block mobile network settings", "", false], ["no_user_switch", "Block switching users", "", false],
  ["no_control_apps", "Block uninstall, force stop and clear data", "For every app, which also stops you doing it on the phone.", false],
  ["no_install_unknown_sources", "Block installing apps from unknown sources", "", false],
];
const LOC_EVERY = [[0, "Live"], [1, "1 min"], [5, "5 min"], [10, "10 min"], [15, "15 min"], [30, "30 min"], [60, "1 hour"], [240, "4 hours"], [1440, "Daily"]];
const MODE_ROWS = [
  ["Grey screen", "gray", "bool"], ["Home screen", "launcher", [["default", "Normal"], ["nokia", "Nokia screens"]]], ["Touchscreen works", "touch", "bool"], ["Pull-down shade works", "shade", "bool"],
  ["Sound", "sound", [["normal", "Normal"], ["vibrate", "Vibrate"], ["silent", "Silent"]]], ["WhatsApp checks", "wa", [["off", "Off"], ["open", "When opened"], ["30", "Every 30 min"], ["60", "Hourly"]]],
  ["WhatsApp app", "wa_app", [["auto", "Auto"], ["simple", "Our screens"], ["full", "Real app"]]], ["Internet radio", "radio", [["any", "Any station"], ["presets", "My list only"], ["off", "Off"]]],
  ["Radios sleep when locked", "radios_sleep", "bool"], ["Incoming calls allowed", "inbound", "bool"], ["Camera offered", "camera", "bool"],
  ["Location", "location", [["off", "Off"], ["network", "Low accuracy"], ["gps", "GPS"]]], ["Location every", "loc_every", LOC_EVERY],
  ["Notifications", "notes", [["show", "Listed"], ["off", "Cleared"]]], ["Locked to allowed apps", "kiosk", "bool"],
  ["Screen off after", "screen_off_min", [[1, "1 min"], [2, "2 min"], [5, "5 min"], [10, "10 min"], [30, "30 min"], [0, "Never"]]], ["Close idle apps after", "idle_min", [[0, "Never"], [5, "5 min"], [10, "10 min"], [30, "30 min"], [60, "1 hour"]]],
  ["Allow new apps automatically", "auto_new", "bool"],
];
const openSet = new Set(["Right now"]);   // which sections the parent had open survives a redraw

export async function phoneScreen(ctx, did) {
  const { api, back, app } = ctx;
  const v = await api(`/devices/${did}/view`), all = await api("/devices"), fam = await api("/options");
  const modeIds = Object.keys(v.modes), mname = (id) => v.modes[id]?.name ?? id;
  const redraw = () => phoneScreen(ctx, did);
  const act = async (sub, body, st, ok = "Saved.") => { st?.("mut", "Saving..."); try { const r = await api(`/devices/${did}${sub}`, "POST", body); if (st) st("ok", ok); return r; } catch (e) { st?.("bad", e.message); throw e; } };
  const patchChild = (patch, st, ok) => act("/child", { patch }, st, ok).then(redraw, () => {});
  const sec = (title, ...kids) => { const el = section(title, openSet.has(title), ...kids); el.addEventListener("toggle", () => (el.open ? openSet.add(title) : openSet.delete(title))); return el; };
  const lbl = (pkg) => v.labels[pkg] ?? v.apps.find((a) => a.pkg === pkg)?.label ?? pkg;

  // ---- header ------------------------------------------------------------------------------------------------------
  const st0 = v.device.state ?? {}, fix = v.device.fix;
  const header = h("div", {},
    h("div", { class: "row" }, h("button", { on: { click: back } }, "Back"), h("h2", { style: "margin:0" }, v.name)),
    h("div", { class: "card" },
      h("div", {}, h("strong", {}, `Now: ${mname(v.mode_now)}`), v.override ? h("span", { class: "bad" }, ` (forced, ignoring the timetable)`) : null),
      v.boost ? h("div", { class: "ok" }, `Boost: ${mname(v.boost.mode)} for ${left(v.boost.until)} more`) : null,
      h("div", { class: "mut" }, `Seen ${ago(v.device.last_seen)}`, st0.battery != null ? ` · battery ${st0.battery}%` : "", v.device.version ? ` · Phone Guard ${v.device.version}` : "", st0.owner === false ? " · not device owner" : ""),
      fix ? h("div", { class: "mut" }, `Last position ${ago(fix[0])}, within ${Math.round(fix[3])} m: `, h("a", { href: `https://www.openstreetmap.org/?mlat=${fix[1]}&mlon=${fix[2]}#map=16/${fix[1]}/${fix[2]}`, target: "_blank", rel: "noopener noreferrer" }, "open map")) : null));

  // ---- right now: override, boost, holiday -------------------------------------------------------------------------------
  const stOv = status(), stBoost = status(), stHol = status();
  const ovSel = select([["auto", "Follow the timetable"], ...modeIds.map((m) => [m, `Force ${mname(m)}`])], v.override ?? "auto", async (val) => { await act("/override", { mode: val }, stOv, val === "auto" ? "Back to the timetable." : `Forced to ${mname(val)}.`).then(redraw, () => {}); });
  let bMode = modeIds.includes("day") ? "day" : modeIds[0];
  const boostSel = select(modeIds.map((m) => [m, mname(m)]), bMode, (x) => (bMode = x));
  const bBtn = (min, text) => h("button", { on: { click: () => act("/boost", { mode: bMode, minutes: min }, stBoost, "Boost set.").then(redraw, () => {}) } }, text);
  const from = h("input", { type: "date", value: v.holiday?.from ?? "", "aria-label": "Holiday from" }), until = h("input", { type: "date", value: v.holiday?.until ?? "", "aria-label": "Holiday until" });
  const right = sec("Right now",
    field("Mode", ovSel, "Forcing a mode stays until you choose the timetable again."), stOv.el,
    h("div", { class: "fld" }, h("span", {}, "Boost", h("small", {}, "Give a mode for a while, then return to the timetable. Pressing again adds time, up to 3 hours.")), h("div", { class: "row" }, boostSel, bBtn(15, "+15 min"), bBtn(30, "+30 min"), bBtn(60, "+1 hour"), v.boost ? h("button", { on: { click: () => act("/boost/cancel", {}, stBoost, "Boost cancelled.").then(redraw, () => {}) } }, "Cancel boost") : null)), stBoost.el,
    h("div", { class: "fld" }, h("span", {}, "Holiday", h("small", {}, "School becomes Day on these dates, including the end date.")), h("div", { class: "row" }, from, "to", until, h("button", { on: { click: () => act("/holiday", { from: from.value, until: until.value }, stHol, "Holiday saved.").then(redraw, () => {}) } }, "Save"), v.holiday ? h("button", { on: { click: () => act("/holiday", {}, stHol, "Holiday cleared.").then(redraw, () => {}) } }, "Clear") : null)), stHol.el);

  // ---- timetable -------------------------------------------------------------------------------------------------------
  const week = Object.fromEntries(DAYS.map((_, i) => [String(i + 1), structuredClone(v.week[String(i + 1)] ?? [])])), stTt = status(), ttBox = h("div", {});
  const drawTt = () => ttBox.replaceChildren(...DAYS.map((name, i) => { const id = String(i + 1);
    return h("div", { class: "card" }, h("strong", {}, name), week[id].map((b, bi) => h("div", { class: "row" },
      h("input", { type: "time", value: hm(b[0]), "aria-label": name + " start", on: { change: (e) => { b[0] = toMin(e.target.value); } } }), "to",
      h("input", { type: "time", value: hm(b[1] >= 1440 ? 1439 : b[1]), "aria-label": name + " end", on: { change: (e) => { b[1] = toMin(e.target.value); } } }),
      select(modeIds.map((m) => [m, mname(m)]), b[2], (x) => (b[2] = x), { "aria-label": name + " mode" }),
      h("button", { "aria-label": "Remove block", on: { click: () => { week[id].splice(bi, 1); drawTt(); } } }, "Remove"))),
      h("div", { class: "row" }, h("button", { on: { click: () => { week[id].push([0, 60, modeIds[0]]); drawTt(); } } }, "Add block"), i === 0 ? h("button", { on: { click: () => { for (let k = 2; k <= 5; k++) week[String(k)] = structuredClone(week["1"]); drawTt(); } } }, "Copy to Tue to Fri") : null)); }));
  drawTt();
  const baseSel = select(modeIds.map((m) => [m, mname(m)]), v.base, () => {}), tz = h("input", { value: v.tz, "aria-label": "Time zone" });
  const timetable = sec("Timetable", h("p", { class: "mut" }, `Times are in ${v.tz}. A gap uses the base mode.`), field("Base mode", baseSel), field("Time zone", tz, "For example Europe/London"), ttBox,
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => { const days = Object.fromEntries(Object.entries(week).filter(([, b]) => b.length)), problems = checkPattern({ tz: tz.value, days }, v.modes); if (problems.length) return stTt("bad", "Please fix the timetable", problems); patchChild({ week: Object.fromEntries(Object.entries(week).map(([k, b]) => [k, b])), base: baseSel.value, tz: tz.value.trim() }, stTt, "Saved. The phone picks it up at its next check-in."); } } }, "Save timetable")), stTt.el);

  // ---- apps and modes -----------------------------------------------------------------------------------------------------
  const stApps = status();
  const allowed = (m, pkg) => (v.modes[m].allow ?? []).includes(pkg);
  const newApps = h("div", {}, ...v.pending_apps.map((a) => { const picks = Object.fromEntries(modeIds.map((m) => [m, false])), stN = status();
    return h("div", { class: "card warn" }, h("strong", {}, `New app: ${a.label}`), h("div", { class: "mut" }, a.pkg), h("div", { class: "row" }, "Allow in:", modeIds.map((m) => h("label", {}, check(false, (c) => (picks[m] = c)), " " + mname(m)))),
      h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => act("/apps/approve", { pkg: a.pkg, modes: modeIds.filter((m) => picks[m]) }, stN).then(redraw, () => {}) } }, "Approve"), h("button", { on: { click: () => act("/apps/dismiss", { pkg: a.pkg }, stN).then(redraw, () => {}) } }, "Leave blocked")), stN.el); }));
  const matrix = h("table", { class: "mx" }, h("tr", {}, h("th", {}, "App"), modeIds.map((m) => h("th", {}, mname(m)))),
    h("tr", {}, h("td", { class: "mut" }, "Select all"), modeIds.map((m) => h("td", {}, check(v.apps.length && v.apps.every((a) => allowed(m, a.pkg)), (c) => act("/apps/mode-all", { mode: m, allowed: c }, stApps, "Saved.").then(redraw, () => {}), { "aria-label": `Allow every app in ${mname(m)}` })))),
    ...[...v.apps].sort((a, b) => a.label.localeCompare(b.label)).map((a) => h("tr", {}, h("td", {}, a.label, h("small", { class: "mut" }, " " + a.pkg), a.pkg === "io.github.durkimat.phoneguard" ? null : h("button", { class: "small", title: "Ask the phone to remove this app", on: { click: () => { if (confirm(`Remove ${a.label} from the phone?`)) act("/apps/command", { kind: "remove", pkg: a.pkg, name: a.label }, stApps, "Removal queued for the phone.").then(redraw, () => {}); } } }, "Remove")),
      modeIds.map((m) => h("td", {}, check(allowed(m, a.pkg), (c) => act("/apps/mode", { pkg: a.pkg, mode: m, allowed: c }, stApps, "Saved.").then(redraw, () => {}), { "aria-label": `${a.label} in ${mname(m)}` }))))));
  const apps = sec(`Apps and modes${v.pending_apps.length ? ` (${v.pending_apps.length} new)` : ""}`, newApps, v.apps.length ? h("div", { class: "scroll" }, matrix) : h("p", { class: "mut" }, "The phone has not reported its apps yet. They appear after its next check-in."), stApps.el);

  // ---- mode settings ------------------------------------------------------------------------------------------------
  const stModes = status();
  const setMode = (m, key, val) => { const modes = structuredClone(v.modes); modes[m][key] = val; patchChild({ modes }, stModes, "Saved."); };
  const modeTable = h("table", { class: "mx" }, h("tr", {}, h("th", {}), modeIds.map((m) => h("th", {}, mname(m)))), ...MODE_ROWS.map(([label, key, kind]) => h("tr", { class: "opt" }, h("td", {}, label),
    modeIds.map((m) => h("td", {}, kind === "bool" ? check(v.modes[m][key] ?? (key === "auto_new" ? false : true), (c) => setMode(m, key, c), { "aria-label": `${label} in ${mname(m)}` }) : select(kind, v.modes[m][key] ?? kind[0][0], (x) => setMode(m, key, typeof kind[0][0] === "number" ? Number(x) : x), { "aria-label": `${label} in ${mname(m)}`, disabled: key === "loc_every" && (v.modes[m].location ?? "off") === "off" ? true : null }))))));
  const modes = sec("Mode settings", h("div", { class: "scroll" }, modeTable), h("p", { class: "mut" }, "Silent is total silence. Untick incoming calls and the phone hangs up on every call in that mode."), stModes.el);

  // ---- phone restrictions -------------------------------------------------------------------------------------------------
  const rOn = new Set(v.restrictions == null ? RESTRICTIONS.filter((r) => r[3]).map((r) => r[0]) : v.restrictions), stR = status();
  const restr = sec("Phone restrictions", ...RESTRICTIONS.map(([key, label, hint]) => h("label", { class: "fld chk" }, check(rOn.has(key), (c) => (c ? rOn.add(key) : rOn.delete(key))), h("span", {}, label, hint ? h("small", {}, hint) : null))),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => patchChild({ restrictions: [...rOn].sort() }, stR, "Saved.") } }, "Save restrictions"), h("button", { disabled: v.restrictions == null, on: { click: () => patchChild({ restrictions: null }, stR, "Back to the recommended set.") } }, "Back to the recommended set")), stR.el);

  // ---- WhatsApp, radio, Nokia menu -----------------------------------------------------------------------------------------
  const w = v.whatsapp, stW = status(), wAll = check(w.all, () => {}), wList = h("textarea", { rows: "4", "aria-label": "Allowed WhatsApp people and groups" }, (w.allowed ?? []).join("\n")), wMin = h("input", { type: "number", min: 1, max: 60, value: w.perMinute, "aria-label": "Per minute" }), wDay = h("input", { type: "number", min: 1, max: 2000, value: w.perDay, "aria-label": "Per day" });
  const whatsapp = sec("WhatsApp", h("label", { class: "fld chk" }, wAll, h("span", {}, "Allow everyone and every group", h("small", {}, "Without this, only the people and groups listed below."))),
    field("Allowed (one per line)", wList, "A phone number with country code and no plus (447700900111), or g: and a group's name (g:Family)."), h("div", { class: "row" }, "Send at most", wMin, "a minute and", wDay, "a day"),
    h("button", { class: "pri", on: { click: () => patchChild({ whatsapp: { all: wAll.checked, allowed: wList.value.split("\n"), perMinute: Number(wMin.value), perDay: Number(wDay.value) } }, stW, "Saved.") } }, "Save WhatsApp settings"), stW.el, h("p", { class: "mut" }, "Messages stay on the phone. This page never sees them."));
  const stRad = status(), radio = h("textarea", { rows: "6", placeholder: "Heart | https://media-ice.musicradio.com/HeartUKMP3", "aria-label": "Radio stations" }, (v.radio_stations ?? []).map((x) => `${x.name} | ${x.url}`).join("\n"));
  const radioSec = sec("Radio stations", h("p", { class: "mut" }, "One per line: Name | https://stream-address. The first nine are the phone's number keys. Leave empty for the built-in UK list."), radio,
    h("button", { class: "pri", on: { click: () => patchChild({ radio_stations: radio.value.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const [n, ...u] = l.split("|"); return { name: (n ?? "").trim(), url: u.join("|").trim() }; }) }, stRad, "Saved.") } }, "Save radio stations"), stRad.el);
  const pick = (title, key, list, hint) => { const on = new Set(list), stP = status(); return h("div", { class: "fld" }, h("span", {}, title, hint ? h("small", {}, hint) : null), v.apps.length ? h("div", { class: "scroll", style: "max-height:12rem" }, [...v.apps].sort((a, b) => a.label.localeCompare(b.label)).map((a) => h("label", { class: "chk2" }, check(on.has(a.pkg), (c) => (c ? on.add(a.pkg) : on.delete(a.pkg))), " " + a.label))) : h("p", { class: "mut" }, "Appears once the phone has reported its apps."), h("button", { on: { click: () => patchChild({ [key]: [...on] }, stP, "Saved.") } }, "Save"), stP.el); };
  const keepDefault = v.keep_running, stDry = status();
  const nokia = sec("Apps on the Nokia menu", pick("Shown in the main menu", "pinned", v.pinned, "The other allowed apps are under Android Apps."), pick("Notifications cleared as they arrive", "notes_hide", v.notes_hide), pick("Power saver never closes", "keep_running", keepDefault, "Apps that must stay ready to ring, deliver or sync."),
    h("label", { class: "fld chk" }, check(v.idle_dry_run, (c) => patchChild({ idle_dry_run: c }, stDry, "Saved.")), h("span", {}, "Power saver: only report, do not close", h("small", {}, "Shows what it would have closed without closing anything."))), stDry.el);

  // ---- weather location and look -------------------------------------------------------------------------------------------
  const lat = h("input", { type: "number", step: "0.001", value: v.location?.lat ?? "", placeholder: "51.750", "aria-label": "Latitude" }), lon = h("input", { type: "number", step: "0.001", value: v.location?.lon ?? "", placeholder: "-0.340", "aria-label": "Longitude" }), stLoc = status();
  const weather = sec("Weather place", h("p", { class: "mut" }, "The weather on the home screen comes from Open-Meteo for the place below. The phone never asks for its own location for this. Rounded to about 100 m."), h("div", { class: "row" }, lat, lon),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => patchChild({ location: lat.value && lon.value ? { lat: Number(lat.value), lon: Number(lon.value) } : null }, stLoc, "Saved.") } }, "Save"), h("button", { on: { click: () => patchChild({ location: null }, stLoc, "Cleared.") } }, "No weather")), stLoc.el);
  const lookVals = { ...(v.look ?? {}) }, stLook = status();
  const lookFields = [["theme", "Theme"], ["font", "Font"], ["menu_style", "Menu style"], ["windows", "Windows"], ["colour", "Colour"], ["wall", "Wall"], ["shadow", "Shadow"], ["bars", "Bars"], ["font_display", "Display font"], ["font_text", "Text font"], ["font_label", "Label font"]].filter(([k]) => LOOK[k] || (k.startsWith("font_") && LOOK.font));
  const look = sec("Look of the phone", h("p", { class: "mut" }, "A choice here wins over the phone's own for that setting. Leave 'Phone's own' to let the phone decide."),
    ...lookFields.map(([k, label]) => field(label, select([["", "Phone's own"], ...(LOOK[k] ?? LOOK.font)], lookVals[k] ?? "", (x) => (lookVals[k] = x)))), field("Accent colour", h("input", { type: "color", value: lookVals.accent || "#d42a1e", on: { change: (e) => (lookVals.accent = e.target.value) } })),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => patchChild({ look: lookVals }, stLook, "Saved.") } }, "Save look"), h("button", { on: { click: () => patchChild({ look: null }, stLook, "The phone chooses its own look.") } }, "Let the phone choose")), stLook.el);

  // ---- Spotify -------------------------------------------------------------------------------------------------------------
  const cid = h("input", { value: fam.spotify_client_id ?? "", placeholder: "Client ID (32 characters)", "aria-label": "Spotify Client ID", autocomplete: "off" }), stSp = status();
  const spotify = sec("Spotify", h("p", { class: v.spotify.connected ? "ok" : "mut" }, v.spotify.connected ? "Connected" : "Not connected yet"),
    h("ol", { class: "mut" }, h("li", {}, "Go to developer.spotify.com/dashboard (sign in with your Premium account) and create an app."), h("li", {}, "Redirect URI: ", h("code", {}, redirectUri(location.origin))), h("li", {}, "Tick Web API. Under User management add the email of every Spotify account that will be used."), h("li", {}, "Copy the app's Client ID here:")),
    h("div", { class: "row" }, cid, h("button", { class: "pri", on: { click: async () => {
      const clientId = cid.value.trim(); if (!/^[0-9a-f]{32}$/i.test(clientId)) return stSp("bad", "That does not look like a Client ID: it is 32 letters and numbers.");
      try { await api("/options", "POST", { spotify_client_id: clientId }); const { verifier, challenge } = await makePkce(), state = crypto.randomUUID(); sessionStorage.setItem("pgSpotify", JSON.stringify({ verifier, state, clientId: clientId.toLowerCase(), did })); location.href = authorizeUrl({ clientId: clientId.toLowerCase(), origin: location.origin, challenge, state }); } catch (e) { stSp("bad", e.message); } } } }, "Connect Spotify"),
      v.spotify.connected ? h("button", { on: { click: () => act("/secret", { name: "spotify.refresh", sealed: null }, stSp, "Spotify removed from this phone.").then(redraw, () => {}) } }, "Disconnect") : null), stSp.el);

  // ---- reports -------------------------------------------------------------------------------------------------------------
  const days = Object.keys(v.usage).sort().reverse(); let day = days[0];
  const usageBox = h("div", {}), drawUsage = () => { const u = Object.entries(v.usage[day] ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 12), mx = u[0]?.[1] || 1;
    usageBox.replaceChildren(...u.map(([pkg, sec_]) => h("div", { class: "bar" }, h("span", {}, lbl(pkg)), h("span", { class: "mut" }, sec_ >= 3600 ? `${Math.floor(sec_ / 3600)} h ${Math.round((sec_ % 3600) / 60)} min` : `${Math.max(1, Math.round(sec_ / 60))} min`), h("i", { style: `width:${Math.round((sec_ / mx) * 100)}%` })))); if (!u.length) usageBox.append(h("p", { class: "mut" }, "No usage reported yet.")); };
  drawUsage();
  const usage = sec("Screen time", days.length ? select(days.map((d) => [d, d]), day, (x) => { day = x; drawUsage(); }) : null, usageBox, h("p", { class: "mut" }, "Time an app was on screen, as reported by the phone. The home screen is not counted."));
  const P = v.power, bat = [];
  if (P.level != null) bat.push(h("div", {}, h("strong", {}, `${P.level}%`), P.plugged ? " charging" : "", P.hours_left ? ` · about ${P.hours_left} h left at the recent rate` : "", P.full_mah ? ` · battery about ${P.full_mah} mAh` : ""));
  if (P.on_battery?.mah_per_h != null) bat.push(h("div", { class: "mut" }, `Last 24 h on battery: ${P.on_battery.mah_per_h} mAh an hour over ${P.on_battery.hours} h.`));
  for (const [title, obj] of [["By screen", P.by_screen], ["By mode", P.by_mode], ["By location", P.by_location], ["By Wi-Fi", P.by_wifi]]) { const es = Object.entries(obj ?? {}).filter(([, x]) => x.mah_per_h != null); if (es.length) bat.push(h("div", { class: "mut" }, `${title}: `, es.map(([k, x]) => `${k} ${x.mah_per_h} mAh/h`).join(" · "))); }
  const R = v.reaped; if (R.closed || R.would) bat.push(h("div", { class: "mut" }, `Power saver, last 24 h: ${R.closed} closed${R.would ? `, ${R.would} would have been closed` : ""}. `, R.top.map(([n, c]) => `${n} ×${c}`).join(", ")));
  const battery = sec("Battery and power saver", ...(bat.length ? bat : [h("p", { class: "mut" }, "Nothing reported yet.")]));

  // ---- updates and removals ------------------------------------------------------------------------------------------------
  const stUp = status(), U = v.update;
  const jobs = h("div", {}, ...v.app_commands.map((c) => h("div", { class: "row" }, `${c.kind === "remove" ? "Remove" : "Update"} ${c.name}: waiting for the phone`, h("button", { on: { click: () => act("/apps/command/cancel", { id: c.id }, stUp).then(redraw, () => {}) } }, "Cancel"))), ...v.app_log.map((e) => h("div", { class: e[3] === "done" ? "ok" : "bad" }, `${new Date(e[0]).toLocaleString()}: ${e[2]} ${e[1]}: ${e[3]}${e[4] ? " (" + e[4] + ")" : ""}`)));
  const updates = sec("Updates and removals", h("label", { class: "fld chk" }, check(fam.updates_enabled, async (c) => { try { await api("/options", "POST", { updates_enabled: c }); redraw(); } catch (e) { stUp("bad", e.message); } }), h("span", {}, "Allow updating Phone Guard from here", h("small", {}, "Off until you switch it on, so nobody presses it by accident."))),
    U ? h("p", {}, `Newest release ${U.version}; this phone has ${U.installed || "unknown"}. `, U.available ? (U.ready ? h("button", { class: "pri", disabled: !U.enabled, on: { click: () => { if (confirm(`Update Phone Guard to ${U.version} on this phone?`)) act("/update", {}, stUp, "Update queued for the phone.").then(redraw, () => {}); } } }, "Update this phone") : "(no checksum published yet)") : "Up to date.") : h("p", { class: "mut" }, "Press Check for updates to see the newest release."),
    h("div", { class: "row" }, h("button", { on: { click: async () => { stUp("mut", "Asking GitHub..."); try { await api("/check-updates", "POST", {}); redraw(); } catch (e) { stUp("bad", e.message); } } } }, "Check for updates")), jobs, stUp.el);

  // ---- managing the phone ------------------------------------------------------------------------------------------------
  const stM = status(), nm = h("input", { value: v.name, "aria-label": "Phone name" }), others = all.filter((x) => x.did !== did), copyFrom = { id: others[0]?.did, parts: { timetable: true, holiday: false, restrictions: false, mode_options: false, whatsapp: false } };
  const manage = sec("Manage this phone", field("Name", h("div", { class: "row" }, nm, h("button", { on: { click: () => act("/rename", { name: nm.value }, stM, "Renamed.").then(redraw, () => {}) } }, "Rename"))),
    others.length ? h("div", { class: "fld" }, h("span", {}, "Copy settings from another phone", h("small", {}, "App lists are never copied: they depend on what is installed.")), select(others.map((x) => [x.did, x.name ?? x.model]), copyFrom.id, (x) => (copyFrom.id = x)),
      h("div", { class: "row" }, Object.keys(copyFrom.parts).map((p) => h("label", {}, check(copyFrom.parts[p], (c) => (copyFrom.parts[p] = c)), " " + ({ timetable: "Timetable", holiday: "Holiday", restrictions: "Restrictions", mode_options: "Mode settings", whatsapp: "WhatsApp" })[p]))),
      h("button", { on: { click: () => act("/copy", { from: copyFrom.id, parts: Object.keys(copyFrom.parts).filter((p) => copyFrom.parts[p]) }, stM, "Copied.").then(redraw, () => {}) } }, "Copy")) : null,
    h("div", { class: "row" }, h("button", { on: { click: () => { if (confirm("Release this phone? It will switch off all of Phone Guard's controls and stop being managed.")) act("/release", {}, stM, "The phone will be released at its next check-in.").catch(() => {}); } } }, "Release the phone"),
      h("button", { on: { click: async () => { if (confirm("Forget this phone? It is removed from this page and can no longer check in. Release it first if you want it unlocked.")) { await act("/forget", {}, stM).catch(() => {}); back(); } } } }, "Forget the phone")), stM.el);

  app.replaceChildren(header, right, timetable, apps, modes, restr, whatsapp, radioSec, nokia, weather, look, spotify, usage, battery, updates, manage);
}
