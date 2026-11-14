// Parent page: confirm new phones, see phones, edit the weekly timetable. All text from the server is set with textContent (phone names are untrusted).
import { resolveSegments, checkPattern } from "/timetable.js";
import { qrSvg } from "/qr.js";
import { enrolPayload } from "/enrol.js";
import { makePkce, authorizeUrl, exchangeCode, storeRefreshToken, redirectUri } from "/spotify.js";

const $app = document.getElementById("app");
const h = (tag, attrs = {}, ...kids) => { const e = document.createElement(tag); for (const [k, v] of Object.entries(attrs)) { if (k === "html") e.innerHTML = v; else if (k === "on") for (const [ev, fn] of Object.entries(v)) e.addEventListener(ev, fn); else if (k === "class") e.className = v; else e.setAttribute(k, v); } for (const c of kids.flat()) if (c != null) e.append(c); return e; };

let key = null, deepSpotify = null;
try { const m = /[#&]k=([\w-]+)/.exec(location.hash); deepSpotify = /[#&]spotify=(\w+)/.exec(location.hash)?.[1] ?? null; if (m) { localStorage.setItem("pgKey", m[1]); history.replaceState(null, "", location.pathname); } key = localStorage.getItem("pgKey"); } catch { /* private mode */ }

async function api(path, method = "GET", body) {
  const r = await fetch("/api" + path, { method, headers: { authorization: "Bearer " + key, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) { try { localStorage.removeItem("pgKey"); } catch {} key = null; render(); throw new Error("Please sign in again"); }
  if (!r.ok) { const e = new Error(j.error ?? "Something went wrong"); e.problems = j.problems; throw e; }
  return j;
}

const ago = (t) => { if (!t) return "never"; const s = Math.max(0, (Date.now() - t) / 1000); return s < 90 ? "just now" : s < 5400 ? Math.round(s / 60) + " min ago" : s < 129600 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " days ago"; };
const hm = (min) => String(Math.floor(min / 60)).padStart(2, "0") + ":" + String(min % 60).padStart(2, "0");
const toMin = (v) => { const [a, b] = v.split(":").map(Number); return a * 60 + b; };
const DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

async function render() {
  $app.replaceChildren();
  if (!key) return $app.append(signIn());
  try {
    const [waiting, phones] = await Promise.all([api("/pairings"), api("/devices")]);
    if (deepSpotify) { const d = phones.find((x) => x.did === deepSpotify); deepSpotify = null; if (d) return edit(d); }
    $app.append(waitingView(waiting), phonesView(phones));
  } catch (e) { $app.append(h("p", { class: "bad" }, e.message)); }
}

function signIn() {
  const i = h("input", { type: "password", placeholder: "Parent key", "aria-label": "Parent key", autocomplete: "off" });
  return h("div", { class: "card" }, h("p", {}, "Open the private link the setup tool gave you, or paste your parent key here."), h("div", { class: "row" }, i, h("button", { class: "pri", on: { click: () => { key = i.value.trim(); try { localStorage.setItem("pgKey", key); } catch {} render(); } } }, "Sign in")));
}

async function addPhone() {
  const e = await api("/enrol/new", "POST"), p = await enrolPayload({ host: location.host, token: e.token, serverKey: e.serverKey });
  document.getElementById("add").replaceChildren(h("div", { class: "card" }, h("strong", {}, "Add a phone"), h("p", { class: "mut" }, "On the child's phone open Phone Guard and scan this, or type the address and code. It works for about 10 minutes."),
    h("div", { class: "qr", html: qrSvg(p.qr) }), h("p", { class: "mut" }, "Address"), h("div", { class: "big" }, p.address), h("p", { class: "mut" }, "Code"), h("div", { class: "big" }, p.code)));
}

function waitingView(list) {
  const add = h("div", { id: "add" }, h("p", {}, h("a", { href: "/guide" }, "How this works and how to set things up")), h("p", {}, h("button", { on: { click: () => addPhone().catch((e) => alert(e.message)) } }, "Add a phone")));
  if (!list.length) return h("div", {}, add);
  return h("div", {}, add, h("h2", {}, "New phone waiting"), list.map((p) => {
    const input = h("input", { class: "code", inputmode: "numeric", maxlength: "4", "aria-label": "Four digits shown on the phone" }), msg = h("p", { class: "mut" });
    return h("div", { class: "card" }, h("div", {}, p.model || "A phone"), h("p", { class: "mut" }, "Type the 4 digits the phone is showing. This checks you are approving the phone in your hand."),
      h("div", { class: "row" }, input, h("button", { class: "pri", on: { click: async () => { try { const r = await api(`/pairings/${p.id}/confirm`, "POST", { match: input.value.trim() }); showRecovery(r.recoveryKey); } catch (e) { msg.textContent = e.message === "numbers do not match" ? "Those digits do not match the phone." : e.message; msg.className = "bad"; } } } }, "Approve")), msg);
  }));
}

function showRecovery(k) {
  $app.replaceChildren(h("h2", {}, "Phone approved"), h("p", {}, "Write down this recovery key and keep it somewhere safe. It is shown only once and lets you take back control if you lose this page."), h("div", { class: "key" }, k), h("button", { class: "pri", on: { click: render } }, "I have saved it"));
}

function phonesView(list) {
  if (!list.length) return h("div", {});
  return h("div", {}, h("h2", {}, "Phones"), list.map((d) => h("div", { class: "card" }, h("div", {}, d.model || "Phone"),
    h("p", { class: "mut" }, `Seen ${ago(d.last_seen)}`, d.state?.battery != null ? ` · battery ${d.state.battery}%` : "", d.state?.mode ? ` · mode ${d.state.mode}` : ""),
    h("button", { on: { click: () => edit(d) } }, "Timetable and settings"))));
}

async function edit(d) {
  const { config } = await api(`/devices/${d.did}/config`);
  const modes = Object.keys(config.modes), tz = config.pattern?.tz ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
  const days = Object.fromEntries(DAYS.map((_, i) => [String(i + 1), structuredClone(config.pattern?.days?.[String(i + 1)] ?? [])]));
  const box = h("div", {}), msg = h("p", {}), adv = h("textarea", { "aria-label": "Advanced settings" });
  const draw = () => box.replaceChildren(...DAYS.map((name, i) => { const id = String(i + 1);
    return h("div", { class: "card" }, h("strong", {}, name), days[id].map((b, bi) => h("div", { class: "row" },
      h("input", { type: "time", value: hm(b[0]), "aria-label": name + " start", on: { change: (e) => { b[0] = toMin(e.target.value); } } }), "to",
      h("input", { type: "time", value: hm(b[1] >= 1440 ? 1439 : b[1]), "aria-label": name + " end", on: { change: (e) => { b[1] = toMin(e.target.value); } } }),
      h("select", { "aria-label": name + " mode", on: { change: (e) => { b[2] = e.target.value; } } }, modes.map((m) => { const o = h("option", { value: m }, config.modes[m].name ?? m); if (m === b[2]) o.selected = true; return o; })),
      h("button", { "aria-label": "Remove block", on: { click: () => { days[id].splice(bi, 1); draw(); } } }, "Remove"))),
      h("div", { class: "row" }, h("button", { on: { click: () => { days[id].push([0, 60, modes[0]]); draw(); } } }, "Add block"), i === 0 ? h("button", { on: { click: () => { for (let k = 2; k <= 5; k++) days[String(k)] = structuredClone(days["1"]); draw(); } } }, "Copy to Tue to Fri") : null)); }));
  draw();
  const save = async () => {
    msg.className = "mut"; msg.textContent = "Saving...";
    try {
      const base = adv.value.trim() ? JSON.parse(adv.value) : structuredClone(config);
      const pattern = { tz, days: Object.fromEntries(Object.entries(days).filter(([, b]) => b.length)) };
      const problems = checkPattern(pattern, base.modes ?? config.modes); if (problems.length) throw Object.assign(new Error("Please fix the timetable"), { problems });
      const next = { ...base, pattern, segments: resolveSegments(pattern), windowEnd: Date.now() + 14 * 86400000 }; delete next.version;
      const r = await api(`/devices/${d.did}/config`, "POST", next);
      msg.className = "ok"; msg.textContent = `Saved (version ${r.version}). The phone picks it up at its next check-in.`;
    } catch (e) { msg.className = "bad"; msg.replaceChildren(e.message, ...(e.problems ?? []).map((p) => h("div", {}, p))); }
  };
  $app.replaceChildren(h("button", { on: { click: render } }, "Back"), h("h2", {}, d.model || "Phone"), h("p", { class: "mut" }, `Times are in ${tz}. Gaps use the base mode "${config.base ?? modes[0]}".`), box,
    spotifyCard(d, config),
    h("details", {}, h("summary", {}, "Advanced: full settings (JSON)"), h("p", { class: "mut" }, "Leave empty to keep everything else as it is."), adv, h("button", { on: { click: () => { adv.value = JSON.stringify(config, null, 2); } } }, "Load current settings")),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: save } }, "Save")), msg);
}

function spotifyCard(d, config) {
  const cid = h("input", { value: config.spotifyClientId ?? "", placeholder: "Client ID (32 characters)", "aria-label": "Spotify Client ID", autocomplete: "off" }), msg = h("p", { class: "bad" });
  const go = async () => {
    const clientId = cid.value.trim();
    if (!/^[0-9a-f]{32}$/i.test(clientId)) { msg.textContent = "That does not look like a Client ID: it is 32 letters and numbers."; return; }
    try {
      const { config: c } = await api(`/devices/${d.did}/config`); const next = { ...c, spotifyClientId: clientId }; delete next.version; await api(`/devices/${d.did}/config`, "POST", next);
      const { verifier, challenge } = await makePkce(), state = crypto.randomUUID();
      sessionStorage.setItem("pgSpotify", JSON.stringify({ verifier, state, clientId, did: d.did }));
      location.href = authorizeUrl({ clientId, origin: location.origin, challenge, state });
    } catch (e) { msg.textContent = e.message; }
  };
  return h("div", { class: "card" }, h("strong", {}, "Spotify"), h("p", { class: config.secrets?.["spotify.refresh"] ? "ok" : "mut" }, config.secrets?.["spotify.refresh"] ? "Connected" : "Not connected yet"),
    h("ol", { class: "mut" }, h("li", {}, "Go to developer.spotify.com/dashboard (sign in with your Premium account) and create an app."), h("li", {}, "Redirect URI: ", h("code", {}, redirectUri(location.origin))), h("li", {}, "Tick Web API. Under Users and Access add the email of every Spotify account that will be used."), h("li", {}, "Copy the app's Client ID here:")),
    h("div", { class: "row" }, cid, h("button", { class: "pri", on: { click: go } }, "Connect Spotify")), msg);
}

async function spotifyCallback() {
  $app.replaceChildren(h("p", { class: "mut" }, "Finishing the Spotify sign-in..."));
  try {
    const q = new URLSearchParams(location.search), saved = JSON.parse(sessionStorage.getItem("pgSpotify") ?? "null");
    if (q.get("error")) throw new Error("Spotify said: " + q.get("error"));
    if (!saved || q.get("state") !== saved.state) throw new Error("This sign-in did not start on this page. Please try again from the phone's settings.");
    const refreshToken = await exchangeCode({ clientId: saved.clientId, origin: location.origin, code: q.get("code"), verifier: saved.verifier });
    await storeRefreshToken({ api, did: saved.did, refreshToken }); sessionStorage.removeItem("pgSpotify");
    $app.replaceChildren(h("h2", {}, "Spotify connected"), h("p", {}, "The key was locked so only the phone can read it. The phone collects it at its next check-in."), h("a", { href: "/" }, "Back to your phones"));
  } catch (e) { $app.replaceChildren(h("h2", {}, "Spotify did not connect"), h("p", { class: "bad" }, e.message), h("a", { href: "/" }, "Back")); }
}

if (location.pathname === "/spotify/callback") { if (key) spotifyCallback(); else $app.append(signIn()); } else render();
setInterval(() => { if (key && document.visibilityState === "visible" && $app.querySelector("h2")?.textContent !== "Phone approved" && !$app.querySelector("textarea") && !document.activeElement?.matches("input,select")) render(); }, 15000);
