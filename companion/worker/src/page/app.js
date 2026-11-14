// Parent page: confirm new phones, see phones, edit the weekly timetable. All text from the server is set with textContent (phone names are untrusted).
import { qrSvg } from "/qr.js";
import { enrolPayload } from "/enrol.js";
import { exchangeCode, storeRefreshToken } from "/spotify.js";
import { h, ago, left } from "/ui.js";
import { phoneScreen } from "/phone.js";

const $app = document.getElementById("app");

let key = null, deepSpotify = null;
try { const m = /[#&]k=([\w-]+)/.exec(location.hash); deepSpotify = /[#&]spotify=(\w+)/.exec(location.hash)?.[1] ?? null; if (m) { localStorage.setItem("pgKey", m[1]); history.replaceState(null, "", location.pathname); } key = localStorage.getItem("pgKey"); } catch { /* private mode */ }

async function api(path, method = "GET", body) {
  const r = await fetch("/api" + path, { method, headers: { authorization: "Bearer " + key, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) { try { localStorage.removeItem("pgKey"); } catch {} key = null; render(); throw new Error("Please sign in again"); }
  if (!r.ok) { const e = new Error(j.error ?? "Something went wrong"); e.problems = j.problems; throw e; }
  return j;
}


async function render() {
  $app.replaceChildren();
  if (!key) return $app.append(signIn());
  try {
    const [waiting, phones] = await Promise.all([api("/pairings"), api("/devices")]);
    if (deepSpotify) { const d = phones.find((x) => x.did === deepSpotify); deepSpotify = null; if (d) return open(d.did); }
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
  return h("div", {}, h("h2", {}, "Phones"), list.map((d) => h("div", { class: "card" }, h("div", {}, h("strong", {}, d.name ?? d.model ?? "Phone")),
    h("p", { class: "mut" }, d.mode_now ? `Now ${d.mode_now}` : "", d.override ? " (forced)" : "", d.boost ? ` · boost ${left(d.boost.until)} left` : "", ` · seen ${ago(d.last_seen)}`, d.state?.battery != null ? ` · battery ${d.state.battery}%` : ""),
    h("button", { on: { click: () => open(d.did) } }, "Open"))));
}

async function open(did) { try { await phoneScreen({ api, back: render, app: $app }, did); window.scrollTo(0, 0); } catch (e) { $app.replaceChildren(h("button", { on: { click: render } }, "Back"), h("p", { class: "bad" }, e.message)); } }

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
setInterval(() => { if (key && document.visibilityState === "visible" && $app.querySelector("h2")?.textContent !== "Phone approved" && !$app.querySelector("details") && !$app.querySelector("textarea") && !document.activeElement?.matches("input,select")) render(); }, 15000);
