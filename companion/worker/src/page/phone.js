// One phone's screen: what is happening now, the controls, the timetable, apps and modes, settings, Spotify, reports and phone management.
// Every control calls one Worker endpoint and then redraws from the server's answer, so what is shown is always what the phone will be told.
import { h, ago, hm, toMin, left, status, section, field, select, check } from "/ui.js";
import { checkPattern } from "/timetable.js";
import { LOOK } from "/look-options.js";
import { makePkce, authorizeUrl, redirectUri } from "/spotify.js";
import { seal } from "/seal.js";
import { PROVIDERS, UNSUPPORTED, providerFor } from "/providers.js";

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
  ["Radios sleep when locked", "radios_sleep", "bool"], ["Mobile data left on the home screen", "data_widget", "bool"], ["Incoming calls allowed", "inbound", "bool"], ["Camera offered", "camera", "bool"], ["Voice typing (microphone in text boxes)", "voice_typing", "bool"], ["Voice commands", "voice_cmds", "bool"],
  ["Location", "location", [["off", "Off"], ["network", "Low accuracy"], ["gps", "GPS"]]], ["Location every", "loc_every", LOC_EVERY],
  ["Notifications", "notes", [["show", "Listed"], ["off", "Cleared"]]], ["Locked to allowed apps", "kiosk", "bool"],
  ["Screen off after", "screen_off_min", [[1, "1 min"], [2, "2 min"], [5, "5 min"], [10, "10 min"], [30, "30 min"], [0, "Never"]]], ["Close idle apps after", "idle_min", [[0, "Never"], [5, "5 min"], [10, "10 min"], [30, "30 min"], [60, "1 hour"]]],
  ["Allow new apps automatically", "auto_new", "bool"],
];
const closedSet = new Set();   // sections the parent closed stay closed after a redraw (everything else is open)
let openTab = "today";

export async function phoneScreen(ctx, did) {
  const { api, back, app } = ctx;
  const v = await api(`/devices/${did}/view`), all = await api("/devices"), fam = await api("/options");
  const modeIds = Object.keys(v.modes), mname = (id) => v.modes[id]?.name ?? id;
  // Redrawing rebuilds the page, which would jump back to the top: keep the page and inner scroll boxes where they were.
  const redraw = async () => {
    const y = window.scrollY, boxes = [...app.querySelectorAll(".scroll")].map((e) => [e.scrollTop, e.scrollLeft]);
    await phoneScreen(ctx, did);
    app.querySelectorAll(".scroll").forEach((e, i) => { if (boxes[i]) { e.scrollTop = boxes[i][0]; e.scrollLeft = boxes[i][1]; } });
    window.scrollTo(0, y);
  };
  const act = async (sub, body, st, ok = "Saved.") => { st?.("mut", "Saving..."); try { const r = await api(`/devices/${did}${sub}`, "POST", body); if (st) st("ok", ok); return r; } catch (e) { st?.("bad", e.message); throw e; } };
  const patchChild = (patch, st, ok) => act("/child", { patch }, st, ok).then(redraw, () => {});
  const sec = (title, ...kids) => { const el = section(title, !closedSet.has(title), ...kids); el.addEventListener("toggle", () => (el.open ? closedSet.delete(title) : closedSet.add(title))); return el; };
  const lbl = (pkg) => v.labels[pkg] ?? v.apps.find((a) => a.pkg === pkg)?.label ?? pkg;

  // ---- header: the phone's status at a glance (what a parent looks at day to day) ----------------------------------------------
  const st0 = v.device.state ?? {}, fix = v.device.fix, pw = v.power ?? {};
  const hmin = (sec_) => (sec_ >= 3600 ? `${Math.floor(sec_ / 3600)} h ${Math.round((sec_ % 3600) / 60)} min` : `${Math.max(1, Math.round(sec_ / 60))} min`);
  let todayKey = ""; try { todayKey = new Date().toLocaleDateString("en-CA", { timeZone: v.tz || undefined }); } catch { todayKey = new Date().toLocaleDateString("en-CA"); }
  const dataGb = (b) => (b >= 1e9 ? `${Math.round(b / 1e8) / 10} GB` : `${Math.round(b / 1e6)} MB`), dataLeft = (d) => dataGb(Math.max(0, d.allowance - d.used));
  const todaySec = Object.values(v.usage?.[todayKey] ?? {}).reduce((x, y) => x + y, 0);
  const tile = (label, value, sub, cls = "") => h("div", { class: "tile" }, h("div", { class: "mut" }, label), h("div", { class: "tv " + cls }, value), sub ? h("div", { class: "mut" }, sub) : null);
  const mapLink = (f) => h("a", { href: `https://www.openstreetmap.org/?mlat=${f[1]}&mlon=${f[2]}#map=16/${f[1]}/${f[2]}`, target: "_blank", rel: "noopener noreferrer" }, "Open the map");
  const tiles = h("div", { class: "tiles" },
    tile("Now", mname(v.mode_now), v.override ? "forced, ignoring the timetable" : v.boost ? `boost for ${left(v.boost.until)} more` : "following the timetable", v.override ? "bad" : v.boost ? "ok" : ""),
    tile("Battery", st0.battery != null ? `${st0.battery}%` : "unknown", pw.plugged ? "charging" : pw.hours_left ? `about ${pw.hours_left} h left` : "", st0.battery != null && st0.battery <= 15 && !pw.plugged ? "bad" : ""),
    tile("Last seen", ago(v.device.last_seen), v.device.version ? `Phone Guard ${v.device.version}${st0.owner === false ? ", not managed" : ""}` : ""),
    h("div", { class: "tile" }, h("div", { class: "mut" }, "Location"), h("div", { class: "tv" }, fix ? ago(fix[0]) : "Not shared"), h("div", { class: "mut" }, fix ? ["within ", Math.round(fix[3]), " m. ", mapLink(fix)] : "Switch on in Mode settings, Location")),
    st0.data?.allowance > 0 ? tile("Mobile data", `${dataLeft(st0.data)} left`, `${dataGb(st0.data.used)} of ${dataGb(st0.data.allowance)} used`, st0.data.used >= st0.data.allowance * 0.9 ? "bad" : "") : null,
    tile("Screen time today", todaySec ? hmin(todaySec) : "none yet", "as reported by the phone"),
    tile("WhatsApp", st0.wa?.linked ? "Linked" : "Not linked", st0.wa?.linked ? "" : "Set up in the Services tab"));
  const header = h("div", {},
    h("div", { class: "row topbar" }, !ctx.single ? h("button", { on: { click: back } }, "All phones") : null, h("h2", { style: "margin:0;flex:1" }, v.name), h("button", { on: { click: () => ctx.add?.() } }, "Add a phone"), h("a", { href: "/guide", class: "mut" }, "Guide")),
    tiles);

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
    h("div", { class: "fld" }, h("span", {}, "Holiday", h("small", {}, "School/work becomes Day on these dates, including the end date.")), h("div", { class: "row" }, from, "to", until, h("button", { on: { click: () => act("/holiday", { from: from.value, until: until.value }, stHol, "Holiday saved.").then(redraw, () => {}) } }, "Save"), v.holiday ? h("button", { on: { click: () => act("/holiday", {}, stHol, "Holiday cleared.").then(redraw, () => {}) } }, "Clear") : null)), stHol.el);

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
  const w = v.whatsapp, stW = status(), wAll = check(w.all, () => {}), wList = h("textarea", { rows: "4", "aria-label": "Allowed WhatsApp people and groups" }, (w.allowed ?? []).filter((x) => w.who !== "group" || !String(x).startsWith("g:")).join("\n")), wMin = h("input", { type: "number", min: 1, max: 60, value: w.perMinute, "aria-label": "Per minute" }), wDay = h("input", { type: "number", min: 1, max: 2000, value: w.perDay, "aria-label": "Per day" });
  // how the phone gets WhatsApp (setup) and who it talks to (who); the groups-only choice keeps the phone to groups the person managing it belongs to
  const wWho = w.who ?? (w.all ? "all" : "people"), wSetup = { v: w.setup ?? "smartphone", who: wWho }, wGroups = h("textarea", { rows: "3", "aria-label": "WhatsApp group names" }, (w.groups ?? []).join("\n")), wTag = check(w.tag_sent ?? (wWho === "group" && (w.setup ?? "smartphone") === "smartphone"), () => {});
  const waState = v.device.state?.wa, ws = waState?.setup, off = waState?.setup?.official, stS = status(), stI = status();
  const setupWin = (patch, ok) => act("/child", { patch: { wa_setup: patch } }, stS, ok).then(redraw, () => {});
  const allowedNow = (v.wa_setup?.allowed_until ?? 0) > Date.now();
  const simBox = h("div", {}, h("p", { class: "mut" }, "The phone gets the official WhatsApp, registered on its own SIM number. Phone Guard then links to it, hides it and keeps it quiet. Nothing is registered until a person on the phone starts the setup."),
    off?.installed ? h("p", { class: "ok" }, "Official WhatsApp is on the phone" + (off.version ? " (version " + off.version + ")" : "") + ".") : h("div", { class: "row" }, h("button", { on: { click: () => act("/apps/command", { kind: "storeinstall", pkg: "com.whatsapp" }, stI, "Asked the phone to fetch WhatsApp.").then(redraw, () => {}) } }, "Install WhatsApp on the phone"), stI.el),
    allowedNow ? h("p", { class: "ok" }, ws?.active ? "Setup is running on the phone" + (ws.until ? ", " + left(ws.until) + " left" : "") + "." : "Setup is allowed for 24 hours. On the phone: Settings, WhatsApp, Set up, then Start.") : h("p", { class: "mut" }, "Setup is not allowed on the phone yet."),
    h("div", { class: "row" }, allowedNow ? h("button", { on: { click: () => setupWin({ allow: false }, "Setup is no longer allowed.") } }, "Cancel") : h("button", { class: "pri", on: { click: () => setupWin({ allow: true }, "Setup is allowed for 24 hours.") } }, "Allow setup on the phone"),
      ws?.active ? [h("button", { on: { click: () => setupWin({ extend: true }, "Added 15 minutes.") } }, "Add 15 minutes"), h("button", { on: { click: () => setupWin({ end: true }, "Ending the setup.") } }, "End now")] : null), stS.el);
  const whoSel = select([["group", "Groups only"], ["people", "People and groups I choose"], ["all", "Everyone"]], wWho, (x) => (wSetup.who = x)), setupSel = select([["smartphone", "A smartphone I link it to"], ["sim", "The phone's own SIM number"]], wSetup.v, (x) => (wSetup.v = x));
  const whatsapp = sec("WhatsApp", h("p", { class: waState?.linked ? "ok" : "mut" }, waState ? (waState.linked ? "Linked to WhatsApp" : "Not linked to WhatsApp yet") : "Link status appears after the phone's next check-in.", " ", h("a", { href: "/guide#whatsapp", target: "_blank", rel: "noopener noreferrer" }, "How to link")),
    field("How the phone gets WhatsApp", setupSel, "Save, then pick the SIM number to see its setup steps."), (w.setup ?? "smartphone") === "sim" ? simBox : null,
    field("Who the phone talks to", whoSel, "Groups only: you make one or more WhatsApp groups and are a member of each. Everything in those groups shows on the phone and nothing else does. No individual people. Stricter limits unless you change them."),
    field("Groups (one name per line)", wGroups, "Used for Groups only."),
    h("label", { class: "fld chk" }, wTag, h("span", {}, "Put the child's name in front of what the phone sends", h("small", {}, "So the group can tell who wrote it. Needed when the phone sends as you."))),
    field("People and groups (one per line)", wList, "Used for People and groups. A phone number with country code and no plus (447700900111), or g: and a group's name (g:Family)."), h("div", { class: "row" }, "Send at most", wMin, "a minute and", wDay, "a day"),
    h("button", { class: "pri", on: { click: () => { if (wSetup.who === "group" && !wGroups.value.split("\n").some((x) => x.trim())) return stW("bad", "Add at least one group name."); if ((w.setup ?? "smartphone") !== "sim" && wSetup.v === "sim" && waState?.linked && prompt("Moving WhatsApp to the phone's own SIM number logs the other phone out of this WhatsApp. Type SWITCH to go ahead.") !== "SWITCH") return stW("bad", "Not changed."); patchChild({ whatsapp: { setup: wSetup.v, who: wSetup.who, all: wSetup.who === "all", groups: wGroups.value.split("\n"), tag_sent: wTag.checked, allowed: wList.value.split("\n"), perMinute: Number(wMin.value), perDay: Number(wDay.value) } }, stW, "Saved."); } } }, "Save WhatsApp settings"), stW.el, h("p", { class: "mut" }, "Messages stay on the phone. This page never sees them."),
    (w.setup ?? "smartphone") === "sim" ? h("div", { class: "row" }, h("button", { on: { click: () => { if (confirm("Remove WhatsApp from the phone? First, on the phone: Settings, WhatsApp, Remove WhatsApp, which unlinks it and deletes the account inside WhatsApp (needs setup allowed above). This then uninstalls the app.")) act("/apps/command", { kind: "remove", pkg: "com.whatsapp" }, stI, "Asked the phone to remove WhatsApp.").then(redraw, () => {}); } } }, "Remove WhatsApp from the phone")) : null);
  const stRad = status(), radio = h("textarea", { rows: "6", placeholder: "Heart | https://media-ice.musicradio.com/HeartUKMP3", "aria-label": "Radio stations" }, (v.radio_stations ?? []).map((x) => `${x.name} | ${x.url}`).join("\n"));
  const radioSec = sec("Radio stations", h("p", { class: "mut" }, "One per line: Name | https://stream-address. The first nine are the phone's number keys. Leave empty for the built-in UK list."), radio,
    h("button", { class: "pri", on: { click: () => patchChild({ radio_stations: radio.value.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => { const [n, ...u] = l.split("|"); return { name: (n ?? "").trim(), url: u.join("|").trim() }; }) }, stRad, "Saved.") } }, "Save radio stations"), stRad.el);
  const pick = (title, key, list, hint) => { const on = new Set(list), stP = status(); return h("div", { class: "fld" }, h("span", {}, title, hint ? h("small", {}, hint) : null), v.apps.length ? h("div", { class: "scroll", style: "max-height:12rem" }, [...v.apps].sort((a, b) => a.label.localeCompare(b.label)).map((a) => h("label", { class: "chk2" }, check(on.has(a.pkg), (c) => (c ? on.add(a.pkg) : on.delete(a.pkg))), " " + a.label))) : h("p", { class: "mut" }, "Appears once the phone has reported its apps."), h("button", { on: { click: () => patchChild({ [key]: [...on] }, stP, "Saved.") } }, "Save"), stP.el); };
  const keepDefault = v.keep_running, stDry = status();
  // ---- the app store (Aurora): installed from here, shown only in the modes the parent ticks ---------------------------------------------------------
  const stStore = status(), store = v.store;
  // ---- overnight updates of the apps the store installed ------------------------------------------------------------------
  const au = v.auto_update ?? { on: true, data: false, at: "03:00", exclude: [] }, stU = status(), upd = v.device.state?.updates, DAY = 86400000;
  const uOn = check(au.on !== false, () => {}), uData = check(au.data === true, () => {}), uAt = h("input", { type: "time", value: au.at ?? "03:00", "aria-label": "Update time" }), uEx = h("textarea", { rows: "3", "aria-label": "Apps left out" }, (au.exclude ?? []).join("\n"));
  const uRes = upd?.results ?? [], uFail = uRes.filter((r) => r.status === "failed"), uDone = uRes.filter((r) => r.status === "updated");
  const noWifiWeek = upd && upd.why === "no Wi-Fi" && Date.now() - (upd.lastWifi || upd.at || Date.now()) > 7 * DAY;
  const updSec = sec("Update apps overnight", h("p", { class: "mut" }, "At a quiet time the phone wakes up and updates the apps the app store installed (Spotify, WhatsApp and so on) when nobody is using them. The app store itself and Phone Guard are never updated this way; Phone Guard has its own update. If an update changes how an app looks, steps the phone does by itself (for example downloading a Spotify playlist) may stop working: a failure shows up here and nothing else is affected."),
    h("label", { class: "fld chk" }, uOn, h("span", {}, "Update apps overnight", h("small", {}, "Untick to pause updates for this phone."))),
    h("label", { class: "fld chk" }, uData, h("span", {}, "Allow mobile data", h("small", {}, "Off: Wi-Fi only. Updates can be large."))),
    field("Time", uAt, "The phone's local time. Default 03:00."), field("Leave these apps out (one app name per line)", uEx, "For example com.spotify.music. Package names as shown under Apps."),
    h("button", { class: "pri", on: { click: () => patchChild({ auto_update: { on: uOn.checked, data: uData.checked, at: uAt.value || "03:00", exclude: uEx.value.split("\n").map((x) => x.trim()).filter(Boolean) } }, stU, "Saved.") } }, "Save"), stU.el,
    !upd?.at ? h("p", { class: "mut" }, "No overnight check has run yet.") : h("p", { class: "mut" }, "Last check " + ago(upd.at) + (upd.why ? ": " + upd.why : ".") + (uDone.length ? " Updated " + uDone.length + "." : "")),
    noWifiWeek ? h("p", { class: "bad" }, "No Wi-Fi for a week, so nothing has updated. Allow mobile data above, or check the Wi-Fi.") : null,
    uFail.length ? h("p", { class: "bad" }, "Could not update: " + uFail.map((r) => `${r.name} (${r.detail})`).join("; ")) : null,
    uRes.length ? h("ul", { class: "mut" }, uRes.map((r) => h("li", {}, `${r.name}: ` + (r.status === "updated" ? `${r.from} to ${r.to}` : r.status === "current" ? `up to date (${r.to})` : r.status === "skipped" ? `skipped, ${r.detail}` : `failed, ${r.detail} (${r.from})`)))) : null);
  const storeSec = sec("App store (Aurora Store)", h("p", { class: "mut" }, "Aurora lets apps be installed from Google Play, with no Google account. It runs as its own app, not in the Phone Guard look. It stays hidden until you tick it under Apps and modes for the modes where you want it (for example free time); any app it installs also starts hidden until you tick it."),
    h("p", {}, store.installed ? `Aurora Store is on this phone (version ${store.version} was sent).` : store.waiting ? "Waiting for the phone to install it (it needs Wi-Fi and will do it at a check-in)." : "Aurora Store is not on this phone."),
    store.installed || store.waiting ? null : h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => act("/apps/command", { kind: "install", pkg: "com.aurora.store" }, stStore, "Sent. The phone installs it at its next check-in.").then(redraw, () => {}) } }, "Install Aurora Store on this phone")),
    h("label", { class: "fld chk" }, check(store.auto, async (c) => { try { await api("/options", "POST", { app_store_auto: c }); stStore("ok", "Saved."); } catch (e) { stStore("bad", e.message); } }), h("span", {}, "Install it automatically on new phones")),
    h("p", { class: "mut" }, "First time only: once Aurora is allowed, open it on the phone (in a mode with touch) and follow its welcome screens: pick the session installer, allow notifications, then choose Anonymous to sign in."), stStore.el);
  const nokia = sec("Apps on the Nokia menu", pick("Shown in the main menu", "pinned", v.pinned, "The other allowed apps are under Android Apps."), pick("Notifications cleared as they arrive", "notes_hide", v.notes_hide), pick("Power saver never closes", "keep_running", keepDefault, "Apps that must stay ready to ring, deliver or sync."),
    h("label", { class: "fld chk" }, check(v.idle_dry_run, (c) => patchChild({ idle_dry_run: c }, stDry, "Saved.")), h("span", {}, "Power saver: only report, do not close", h("small", {}, "Shows what it would have closed without closing anything."))), stDry.el);

  // ---- weather location and look -------------------------------------------------------------------------------------------
  const lat = h("input", { type: "number", step: "0.001", value: v.location?.lat ?? "", placeholder: "51.750", "aria-label": "Latitude" }), lon = h("input", { type: "number", step: "0.001", value: v.location?.lon ?? "", placeholder: "-0.340", "aria-label": "Longitude" }), stLoc = status();
  const place = h("input", { placeholder: "Postcode (AL1 3AB) or town", "aria-label": "Postcode or town", autocomplete: "off" }), placeOut = h("div", {});
  const setPlace = (name, la, lo) => { lat.value = Number(la).toFixed(3); lon.value = Number(lo).toFixed(3); placeOut.replaceChildren(h("p", { class: "ok" }, `${name}: ${lat.value}, ${lon.value}. Press Save to use it.`)); };
  const search = async () => {
    const q = place.value.trim(); if (!q) return; placeOut.replaceChildren(h("p", { class: "mut" }, "Looking..."));
    try {
      if (/^[A-Za-z]{1,2}\d[A-Za-z\d]?\s*\d[A-Za-z]{2}$/.test(q)) {   // a UK postcode: postcodes.io, asked from this browser
        const r = await fetch("https://api.postcodes.io/postcodes/" + encodeURIComponent(q.replace(/\s+/g, ""))), j = await r.json(); if (!r.ok || !j.result) throw new Error("That postcode was not found.");
        return setPlace(`${j.result.postcode} (${j.result.admin_district ?? j.result.region ?? "UK"})`, j.result.latitude, j.result.longitude);
      }
      const r = await fetch("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=" + encodeURIComponent(q)), j = await r.json(); if (!j.results?.length) throw new Error("No place found. Try a postcode or a bigger town nearby.");
      placeOut.replaceChildren(h("p", { class: "mut" }, "Pick the right one:"), ...j.results.map((x) => h("div", { class: "row" }, h("button", { on: { click: () => setPlace([x.name, x.admin1, x.country].filter(Boolean).join(", "), x.latitude, x.longitude) } }, [x.name, x.admin1, x.country].filter(Boolean).join(", ")))));
    } catch (e) { placeOut.replaceChildren(h("p", { class: "bad" }, e.message)); }
  };
  place.addEventListener("keydown", (e) => { if (e.key === "Enter") search(); });
  const weather = sec("Weather place", h("p", { class: "mut" }, "The weather on the home screen comes from Open-Meteo for the place below. The phone never asks for its own location for this."),
    h("div", { class: "row" }, place, h("button", { on: { click: search } }, "Find")), placeOut,
    h("p", { class: "mut" }, "Your browser looks the postcode up on postcodes.io (towns on Open-Meteo); nothing goes through your companion. The place is stored to about 100 m."),
    h("details", {}, h("summary", {}, "Or type the coordinates"), h("div", { class: "row" }, lat, lon)),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => patchChild({ location: lat.value && lon.value ? { lat: Number(lat.value), lon: Number(lon.value) } : null }, stLoc, "Saved.") } }, "Save"), h("button", { on: { click: () => patchChild({ location: null }, stLoc, "Cleared.") } }, "No weather")), stLoc.el, v.location ? h("p", { class: "mut" }, `Now: ${v.location.lat}, ${v.location.lon}`) : null);
  const lookVals = { ...(v.look ?? {}) }, stLook = status();
  const lookFields = [["theme", "Theme"], ["font", "Font"], ["menu_style", "Menu style"], ["windows", "Windows"], ["colour", "Colour"], ["wall", "Wall"], ["shadow", "Shadow"], ["bars", "Bars"], ["font_display", "Display font"], ["font_text", "Text font"], ["font_label", "Label font"]].filter(([k]) => LOOK[k] || (k.startsWith("font_") && LOOK.font));
  // ---- screen lock: a PIN the parent can set, change or clear; the child can also set their own on the phone ----------------
  const stLock = status(), lockPin = h("input", { type: "password", inputmode: "numeric", autocomplete: "off", maxlength: 8, placeholder: "4 to 8 digits", "aria-label": "New PIN" });
  const lk = v.lock?.phone, sent = v.lock?.sent, waiting = sent && lk?.rev !== sent.rev;
  const lockNow = !lk ? "The phone has not reported its lock yet." : lk.error ? `Problem: ${lk.error}` : lk.set ? (lk.by === "child" ? "A PIN is set (chosen on the phone)." : "A PIN is set (by you).") : "No PIN is set.";
  const setLockOp = async (op) => {
    try {
      let sealed; if (op === "set") { if (!/^\d{4,8}$/.test(lockPin.value)) return stLock("bad", "The PIN must be 4 to 8 digits."); const { encKey } = await api(`/devices/${did}/enckey`); sealed = await seal({ recipientSpki: encKey, did, name: "lock.pin", plaintext: lockPin.value }); }
      stLock("mut", "Saving..."); await api(`/devices/${did}/lock`, "POST", { op, ...(sealed ? { sealed } : {}) }); lockPin.value = ""; stLock("ok", op === "set" ? "Sent. The PIN is locked so only the phone can read it. It applies at the phone's next check-in." : "Sent. The PIN is removed at the phone's next check-in."); redraw();
    } catch (e) { stLock("bad", e.message); }
  };
  const lockSec = sec("Screen lock (PIN)", h("p", { class: "mut" }, "Your child can set their own PIN on the phone (Phone Guard settings). A PIN you set here replaces theirs, and clearing it removes any PIN, so you can always get back in."),
    h("p", {}, lockNow, waiting ? " A change is waiting for the phone to check in." : ""),
    lk && lk.supported === false ? h("p", { class: "mut" }, "This phone cannot be controlled from here (it is an Open phone). Its PIN is set on the phone only, and a forgotten PIN means erasing it.") : [
      field("New PIN", lockPin, "Numbers only. The phone asks for this to unlock."),
      h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => setLockOp("set") } }, "Set this PIN"), h("button", { on: { click: () => { if (confirm("Remove the PIN from this phone?")) setLockOp("clear"); } } }, "Remove the PIN"))], stLock.el);
  const look = sec("Look of the phone", h("p", { class: "mut" }, "A choice here wins over the phone's own for that setting. Leave 'Phone's own' to let the phone decide."),
    ...lookFields.map(([k, label]) => field(label, select([["", "Phone's own"], ...(LOOK[k] ?? LOOK.font)], lookVals[k] ?? "", (x) => (lookVals[k] = x)))), field("Accent colour", h("input", { type: "color", value: lookVals.accent || "#d42a1e", on: { change: (e) => (lookVals.accent = e.target.value) } })),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => patchChild({ look: lookVals }, stLook, "Saved.") } }, "Save look"), h("button", { on: { click: () => patchChild({ look: null }, stLook, "The phone chooses its own look.") } }, "Let the phone choose")), stLook.el);

  // ---- Spotify -------------------------------------------------------------------------------------------------------------
  const cid = h("input", { value: fam.spotify_client_id ?? "", placeholder: "Client ID (32 characters)", "aria-label": "Spotify Client ID", autocomplete: "off" }), stSp = status();
  const sp = v.spotify, stApp = status();
  const appLine = sp.installed ? ["ok", "The Spotify app is on the phone."] : sp.waiting ? ["mut", "Waiting for the phone to fetch the Spotify app through the app store (it needs Wi-Fi and a check-in)."]
    : !sp.store ? ["mut", "The Spotify app is not on the phone yet, and the app store has to be put on the phone first (see the Apps tab)."]
    : !sp.can ? ["mut", "The Spotify app is not on the phone yet. This phone's app is too old to fetch it by itself: update Phone Guard first."]
    : ["mut", sp.connected ? "The Spotify app is not on the phone yet." : "The Spotify app will be put on the phone once Spotify is connected."];
  const fail = !sp.installed && !sp.waiting && sp.last && !sp.last.ok ? h("p", { class: "bad" }, "The last try stopped: " + sp.last.detail) : null;
  const spotify = sec("Spotify", h("p", { class: sp.connected ? "ok" : "mut" }, sp.connected ? "Signed in: connected" : "Not connected yet"), h("p", { class: appLine[0] }, appLine[1]), fail,
    !sp.installed && !sp.waiting && sp.store && sp.can ? h("div", { class: "row" }, h("button", { on: { click: () => act("/apps/command", { kind: "storeinstall", pkg: "com.spotify.music" }, stApp, "Asked the phone to fetch Spotify.").then(redraw, () => {}) } }, "Put the Spotify app on the phone")) : null,
    h("label", { class: "fld chk" }, check(sp.auto, async (c) => { try { await api("/options", "POST", { spotify_app_auto: c }); stApp("ok", "Saved."); } catch (e) { stApp("bad", e.message); } }), h("span", {}, "Put the Spotify app on the phone by itself once Spotify is connected")), stApp.el,
    h("p", { class: "mut" }, "Spotify then needs one sign-in on the phone itself with your Premium account (touchscreen, once)."),
    h("ol", { class: "mut" }, h("li", {}, "Go to developer.spotify.com/dashboard (sign in with your Premium account) and create an app."), h("li", {}, "Redirect URI: ", h("code", {}, redirectUri(location.origin))), h("li", {}, "Tick Web API. Under User management add the email of every Spotify account that will be used."), h("li", {}, "Copy the app's Client ID here:")),
    h("div", { class: "row" }, cid, h("button", { class: "pri", on: { click: async () => {
      const clientId = cid.value.trim(); if (!/^[0-9a-f]{32}$/i.test(clientId)) return stSp("bad", "That does not look like a Client ID: it is 32 letters and numbers.");
      try { await api("/options", "POST", { spotify_client_id: clientId }); const { verifier, challenge } = await makePkce(), state = crypto.randomUUID(); sessionStorage.setItem("pgSpotify", JSON.stringify({ verifier, state, clientId: clientId.toLowerCase(), did })); location.href = authorizeUrl({ clientId: clientId.toLowerCase(), origin: location.origin, challenge, state }); } catch (e) { stSp("bad", e.message); } } } }, "Connect Spotify"),
      v.spotify.connected ? h("button", { on: { click: () => act("/secret", { name: "spotify.refresh", sealed: null }, stSp, "Spotify removed from this phone.").then(redraw, () => {}) } }, "Disconnect") : null), stSp.el);

  const histSec = sec("Location history", h("p", { class: "mut" }, "See where the phone has been and play a day back on a map: quiet stretches (school, home) play fast and journeys play slowly. Location must be switched on in Mode settings."), h("div", { class: "row" }, h("button", { class: "pri", on: { click: () => ctx.track(did, v.name, v.tz) } }, "Open the map and playback")));

  // ---- email and calendars ----------------------------------------------------------------------------------------------
  const stMail = status(), mailState = v.device.state?.mail, E = v.email;
  const mAddr = h("input", { type: "email", value: E?.address ?? "", placeholder: "name@gmail.com", autocomplete: "off", "aria-label": "Email address" }), mPw = h("input", { type: "password", placeholder: v.email_password_set ? "Password set (type a new one to replace it)" : "App password", autocomplete: "off", "aria-label": "App password" });
  const mUser = h("input", { value: E && E.user !== E.address ? E.user : "", placeholder: "Same as the address" }), mIh = h("input", { value: E?.imap.host ?? "", placeholder: "imap.example.com" }), mIp = h("input", { type: "number", value: E?.imap.port ?? 993 }), mIs = select([["ssl", "SSL/TLS"], ["starttls", "STARTTLS"]], E?.imap.security ?? "ssl", () => {});
  const mSh = h("input", { value: E?.smtp.host ?? "", placeholder: "smtp.example.com" }), mSp = h("input", { type: "number", value: E?.smtp.port ?? 465 }), mSs = select([["ssl", "SSL/TLS"], ["starttls", "STARTTLS"]], E?.smtp.security ?? "ssl", () => {});
  const mGuide = h("div", {}), fillFor = () => { const p = providerFor(mAddr.value.trim()), bad = UNSUPPORTED.find((u) => u.match.test(mAddr.value.trim())); mGuide.replaceChildren(); if (bad) { mGuide.append(h("p", { class: "bad" }, bad.why)); return; } if (!p) return; mIh.value = p.imap.host; mIp.value = p.imap.port; mIs.value = p.imap.security; mSh.value = p.smtp.host; mSp.value = p.smtp.port; mSs.value = p.smtp.security; mGuide.append(h("p", { class: "mut" }, `${p.name}: `, h("a", { href: p.passwordHelp.url, target: "_blank", rel: "noopener noreferrer" }, p.passwordHelp.label), ". ", p.passwordHelp.steps[0])); };
  mAddr.addEventListener("input", fillFor);
  const saveMail = async () => {
    try {
      const address = mAddr.value.trim(); if (!address) return stMail("bad", "Enter the email address.");
      const email = { address, user: mUser.value.trim() || address, imap: { host: mIh.value.trim(), port: Number(mIp.value), security: mIs.value }, smtp: { host: mSh.value.trim(), port: Number(mSp.value), security: mSs.value } };
      let sealedPassword; if (mPw.value) { const { encKey } = await api(`/devices/${did}/enckey`); sealedPassword = await seal({ recipientSpki: encKey, did, name: "email.password", plaintext: /@(gmail|googlemail)\.com$/i.test(address) ? mPw.value.replace(/\s+/g, "") : mPw.value.trim() }); } else if (!v.email_password_set) return stMail("bad", "Enter the app password.");
      stMail("mut", "Saving..."); await api(`/devices/${did}/email`, "POST", { email, ...(sealedPassword ? { sealedPassword } : {}) }); stMail("ok", "Saved. The password is locked so only the phone can read it. The phone sets the account up at its next check-in."); mPw.value = ""; redraw();
    } catch (e) { stMail("bad", e.message); }
  };
  const emailSec = sec("Email", h("p", { class: E ? "ok" : "mut" }, E ? `Account: ${E.address}${v.email_password_set ? "" : " (no password yet)"}. ${mailState ? (mailState.configured ? "The phone has set it up." : mailState.error ? "The phone says: " + mailState.error : "Waiting for the phone.") : "Waiting for the phone's next check-in."}` : "No email account yet."),
    h("p", { class: "mut" }, "Use an address made for the child, with an app password (not the normal password). The wizard on your computer can also test the login first; here the check happens on the phone."), field("Email address", mAddr), mGuide, field("App password", mPw),
    h("details", {}, h("summary", {}, "Server settings"), field("User name", mUser), field("Incoming (IMAP) server", mIh), h("div", { class: "row" }, mIp, mIs), field("Outgoing (SMTP) server", mSh), h("div", { class: "row" }, mSp, mSs)),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: saveMail } }, E ? "Save changes" : "Save and send to the phone"), E ? h("button", { on: { click: async () => { if (confirm("Remove the email account from this phone?")) { await act("/email/clear", {}, stMail, "Removed.").then(redraw, () => {}); } } } }, "Remove the account") : null), stMail.el);
  const stCal = status(), cName = h("input", { placeholder: "Name on the phone, for example Family", "aria-label": "Calendar name" }), cLink = h("input", { placeholder: "https://calendar.google.com/calendar/ical/.../basic.ics", autocomplete: "off", "aria-label": "Calendar link" });
  const calSec = sec("Calendars", h("p", { class: "mut" }, "Family calendars shown on the phone, view only. Use a calendar's private iCal link (Google Calendar: Settings, the calendar, Integrate calendar, Secret address in iCal format). The link is locked so only the phone can read it."),
    ...(v.calendars.length ? v.calendars.map((c) => h("div", { class: "row" }, h("span", { class: c.url_set ? "ok" : "bad" }, `${c.url_set ? "\u2713" : "\u2717"} ${c.name}`), h("button", { on: { click: () => { if (confirm(`Remove ${c.name} from the phone?`)) act("/calendar/remove", { id: c.id }, stCal, "Removed.").then(redraw, () => {}); } } }, "Remove"))) : [h("p", { class: "mut" }, "No calendars yet.")]),
    field("Name", cName), field("Private link", cLink), h("div", { class: "row" }, h("button", { class: "pri", on: { click: async () => {
      try { const url = cLink.value.trim().replace(/^webcal:\/\//i, "https://"), name = cName.value.trim(); if (!/^https:\/\//i.test(url)) return stCal("bad", "The link must start with https:// or webcal://."); if (!name) return stCal("bad", "Give the calendar a name."); const id = [...crypto.getRandomValues(new Uint8Array(4))].map((b) => b.toString(16).padStart(2, "0")).join(""), { encKey } = await api(`/devices/${did}/enckey`); stCal("mut", "Saving...");
        await api(`/devices/${did}/calendar/add`, "POST", { id, name, sealedUrl: await seal({ recipientSpki: encKey, did, name: "calendar." + id, plaintext: url }) }); stCal("ok", "Added. The phone picks it up at its next check-in."); redraw(); } catch (e) { stCal("bad", e.message); } } } }, "Add calendar")), stCal.el);

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
    h("p", { class: "mut" }, `Companion version ${fam.companion?.version ?? "unknown"}`, fam.latest?.companion ? `; newest release ${fam.latest.companion.version}` : "", ". To update the companion, run the setup tool and choose Update my companion."),
    U ? h("p", {}, `Newest release ${U.version}; this phone has ${U.installed || "unknown"}. `, U.available ? (U.ready ? h("button", { class: "pri", disabled: !U.enabled, on: { click: () => { if (confirm(`Update Phone Guard to ${U.version} on this phone?`)) act("/update", {}, stUp, "Update queued for the phone.").then(redraw, () => {}); } } }, "Update this phone") : "(no checksum published yet)") : "Up to date.") : h("p", { class: "mut" }, "Press Check for updates to see the newest release."),
    h("div", { class: "row" }, h("button", { on: { click: async () => { stUp("mut", "Asking GitHub..."); try { await api("/check-updates", "POST", {}); redraw(); } catch (e) { stUp("bad", e.message); } } } }, "Check for updates")), jobs, stUp.el);

  // ---- managing the phone ------------------------------------------------------------------------------------------------
  const stM = status(), nm = h("input", { value: v.name, "aria-label": "Phone name" }), others = all.filter((x) => x.did !== did), copyFrom = { id: others[0]?.did, parts: { timetable: true, holiday: false, restrictions: false, mode_options: false, whatsapp: false } };
  const manage = sec("Manage this phone", field("Name", h("div", { class: "row" }, nm, h("button", { on: { click: () => act("/rename", { name: nm.value }, stM, "Renamed.").then(redraw, () => {}) } }, "Rename"))),
    others.length ? h("div", { class: "fld" }, h("span", {}, "Copy settings from another phone", h("small", {}, "App lists are never copied: they depend on what is installed.")), select(others.map((x) => [x.did, x.name ?? x.model]), copyFrom.id, (x) => (copyFrom.id = x)),
      h("div", { class: "row" }, Object.keys(copyFrom.parts).map((p) => h("label", {}, check(copyFrom.parts[p], (c) => (copyFrom.parts[p] = c)), " " + ({ timetable: "Timetable", holiday: "Holiday", restrictions: "Restrictions", mode_options: "Mode settings", whatsapp: "WhatsApp" })[p]))),
      h("button", { on: { click: () => act("/copy", { from: copyFrom.id, parts: Object.keys(copyFrom.parts).filter((p) => copyFrom.parts[p]) }, stM, "Copied.").then(redraw, () => {}) } }, "Copy")) : null,
    h("div", { class: "row" }, h("button", { on: { click: () => { if (confirm("Release this phone? It will switch off all of Phone Guard's controls and stop being managed.")) act("/release", {}, stM, "The phone will be released at its next check-in.").catch(() => {}); } } }, "Release the phone"),
      h("button", { on: { click: async () => { if (confirm("Forget this phone? It is removed from this page and can no longer check in. Release it first if you want it unlocked.")) { await act("/forget", {}, stM).catch(() => {}); (back ?? (() => location.reload()))(); } } } }, "Forget the phone")), stM.el);

  // ---- what is still to set up (until the parent dismisses it) ----------------------------------------------------------------
  let tabShow = () => {};
  const todo = [];
  if (!Object.values(v.week ?? {}).some((d) => d?.length)) todo.push(["Timetable", "timetable", "Add the school and night times."]);
  if (!st0.wa?.linked) todo.push(["WhatsApp", "services", "Link the child's WhatsApp."]);
  if (!v.email) todo.push(["Email", "services", "Add the child's mail account."]);
  if (!v.calendars?.length) todo.push(["Calendars", "services", "Add the family calendars."]);
  let hideTodo = false; try { hideTodo = localStorage.getItem("pgTodoHidden") === "1"; } catch { /* private mode */ }
  const todoCard = todo.length && !hideTodo ? h("div", { class: "card" }, h("strong", {}, "Still to set up"), h("p", { class: "mut" }, "Optional, any time:"), ...todo.map(([name, tab, text]) => h("div", { class: "row" }, h("button", { class: "small", on: { click: () => tabShow(tab) } }, name), h("span", { class: "mut" }, text))),
    h("div", { class: "row" }, h("button", { class: "small", on: { click: () => { try { localStorage.setItem("pgTodoHidden", "1"); } catch { /* private mode */ } todoCard.remove(); } } }, "Hide this"))) : null;

  // ---- tabs: day-to-day first, set-up things behind their own tabs -------------------------------------------------------------
  const TABS = [["today", "Today", [todoCard, right, histSec, usage, battery]], ["timetable", "Timetable", [timetable, modes, restr]], ["apps", "Apps", [apps, storeSec, updSec, nokia]], ["services", "Services", [whatsapp, spotify, emailSec, calSec, radioSec, weather]], ["phone", "The phone", [look, lockSec, updates, manage]]];
  const body = h("div", { class: "cols" }), bar = h("div", { class: "tabs", role: "tablist" }, TABS.map(([id, label]) => h("button", { class: "tab", role: "tab", "data-tab": id, on: { click: () => tabShow(id) } }, label)));
  tabShow = (id) => { if (!TABS.some((t) => t[0] === id)) id = "today"; openTab = id; for (const b of bar.children) { const on = b.dataset.tab === id; b.classList.toggle("on", on); b.setAttribute("aria-selected", String(on)); } body.replaceChildren(...TABS.find((t) => t[0] === id)[2].filter(Boolean)); };
  tabShow(openTab);
  app.replaceChildren(...(ctx.top ? [ctx.top] : []), header, bar, body);
}
