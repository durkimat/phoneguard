// Parent page: confirm new phones, see phones, edit the weekly timetable. All text from the server is set with textContent (phone names are untrusted).
import { qrSvg } from "/qr.js";
import { enrolPayload } from "/enrol.js";
import { exchangeCode, storeRefreshToken } from "/spotify.js";
import { h, ago, left, cmpVer, section } from "/ui.js";
import { supported, signInWithPasskey, addPasskey, addWithInvite } from "/passkey.js";
import { phoneScreen } from "/phone.js";
import { trackScreen } from "/track-view.js";

const $app = document.getElementById("app");

let key = null, deepSpotify = null, onHome = false, welcome = false, invite = null;   // the quiet refresh below only runs on the home view
try { const m = /[#&]k=([\w-]+)/.exec(location.hash); deepSpotify = /[#&]spotify=(\w+)/.exec(location.hash)?.[1] ?? null; if (/[#&]welcome=1/.test(location.hash)) { welcome = true; localStorage.removeItem("pgWelcomeDone"); } if (m) { localStorage.setItem("pgKey", m[1]); history.replaceState(null, "", location.pathname); } const iv = /[#&]add=([\w-]+)/.exec(location.hash); if (iv) { invite = iv[1]; history.replaceState(null, "", location.pathname); } key = localStorage.getItem("pgKey"); } catch { /* private mode */ }

async function api(path, method = "GET", body) {
  const r = await fetch("/api" + path, { method, headers: { authorization: "Bearer " + key, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  if (r.status === 401) { try { localStorage.removeItem("pgKey"); } catch {} key = null; render(); throw new Error("Please sign in again"); }
  if (!r.ok) { const e = new Error(j.error ?? "Something went wrong"); e.problems = j.problems; throw e; }
  return j;
}


async function render() {
  onHome = true;
  $app.replaceChildren();
  if (invite) return $app.append(addDeviceView());
  if (!key) return $app.append(signIn());
  try {
    const [waiting, phones, fam] = await Promise.all([api("/pairings"), api("/devices"), api("/options")]);
    if (deepSpotify) { const d = phones.find((x) => x.did === deepSpotify); deepSpotify = null; if (d) return open(d.did); }
    const top = h("div", {}, welcomeCard(), updateNotice(fam), passkeyPrompt(), accountSection());
    if (phones.length === 1 && !waiting.length) return open(phones[0].did, top, true);   // one phone: its page is the home page
    $app.append(top, waitingView(waiting), phonesView(phones));
  } catch (e) { $app.append(h("p", { class: "bad" }, e.message)); }
}

// Shown once after the wizard: the move from setting up to looking after the phone.
function welcomeCard() {
  let done = false; try { done = localStorage.getItem("pgWelcomeDone") === "1"; } catch { /* private mode */ }
  if (!welcome || done) return h("div", {});
  const box = h("div", { class: "card welcome" }, h("strong", {}, "Setup finished"), h("p", {}, "Your family's companion is set up and the phone is connected. From now on this is your control page."),
    h("p", { class: "mut" }, "At the top you can see how the phone is doing: what it is doing now, battery, where it was last seen and screen time. The tabs below hold the settings; anything you skipped in the wizard is under Still to set up. Bookmark this page, and keep the link private."),
    h("button", { class: "pri", on: { click: () => { welcome = false; try { localStorage.setItem("pgWelcomeDone", "1"); } catch { /* private mode */ } box.remove(); } } }, "Got it"));
  return box;
}

// "A newer companion is available": compares this Worker's version with the newest GitHub release (checked at most every 12 hours when the page is opened).
function updateNotice(fam) {
  const box = h("div", {}), show = (f) => {
    const c = f?.latest?.companion, mine = f?.companion?.version; if (!c || !mine || cmpVer(c.version, mine) <= 0) return box.replaceChildren();
    const more = String(f.latest.html_url ?? "").startsWith("https://github.com/") ? h("a", { href: f.latest.html_url, target: "_blank", rel: "noopener" }, "What is new") : null;
    box.replaceChildren(h("div", { class: "card" }, h("strong", {}, `A newer companion is available: ${c.version} (this one is ${mine})`), h("p", { class: "mut" }, "Nothing is lost when you update. On the computer you set this up with, run the setup tool and choose ", h("strong", {}, "Update my companion"), ". It takes about a minute. ", more)));
  };
  show(fam);
  if (!fam.latest?.checked || Date.now() - fam.latest.checked > 12 * 3600_000) api("/check-updates", "POST", {}).then(() => api("/options")).then(show).catch(() => {});
  return box;
}

function signIn() {
  const i = h("input", { type: "password", placeholder: "Parent key", "aria-label": "Parent key", autocomplete: "off" }), msg = h("p", { class: "bad" });
  const save = (k) => { key = k; try { localStorage.setItem("pgKey", key); } catch { /* private mode */ } render(); };
  const pass = supported() ? h("div", {}, h("p", {}, "Use this device's fingerprint, face or screen PIN."), h("div", { class: "row" }, h("button", { class: "pri", on: { click: async () => { msg.textContent = ""; try { save(await signInWithPasskey()); } catch (e) { msg.textContent = e.message; } } } }, "Sign in with a passkey")), h("p", { class: "mut" }, "No passkey on this device yet? Use your parent key below, then add one from the page.")) : null;
  return h("div", { class: "card" }, pass, h("p", {}, "Open the private link the setup tool gave you, or paste your parent key here."), h("div", { class: "row" }, i, h("button", { class: pass ? "" : "pri", on: { click: () => save(i.value.trim()) } }, "Sign in")), msg);
}

// Opened from another signed-in browser's "Add another device" link (one use, 10 minutes).
function addDeviceView() {
  const msg = h("p", { class: "bad" });
  if (!supported()) return h("div", { class: "card" }, h("strong", {}, "Add this device"), h("p", {}, "This browser cannot make passkeys. Try the device's main browser, or sign in with your parent key."));
  return h("div", { class: "card" }, h("strong", {}, "Add this device"), h("p", {}, "You were sent here from a device that is already signed in. Press the button and use this device's fingerprint, face or screen PIN. After that you can sign in here without the long parent key."),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: async () => { msg.textContent = ""; try { key = await addWithInvite(invite); try { localStorage.setItem("pgKey", key); } catch { /* private mode */ } invite = null; render(); } catch (e) { msg.textContent = e.message; } } } }, "Add this device")), msg);
}

// Offered once on a browser that signed in with the long key and has no passkey yet.
function passkeyPrompt() {
  let have = false, hide = false; try { have = localStorage.getItem("pgPasskey") === "1"; hide = localStorage.getItem("pgPasskeyHide") === "1"; } catch { /* private mode */ }
  if (!supported() || have || hide) return h("div", {});
  const msg = h("p", { class: "bad" }), box = h("div", { class: "card" }, h("strong", {}, "Sign in with your fingerprint or face"), h("p", { class: "mut" }, "Set up a passkey on this device so you do not need the long parent key here. The parent key keeps working as a way back in."),
    h("div", { class: "row" }, h("button", { class: "pri", on: { click: async () => { try { await addPasskey(api); box.replaceChildren(h("strong", {}, "Passkey added."), h("p", { class: "mut" }, "Next time, press Sign in with a passkey.")); } catch (e) { msg.textContent = e.message; } } } }, "Set up a passkey"),
      h("button", { on: { click: () => { try { localStorage.setItem("pgPasskeyHide", "1"); } catch { /* private mode */ } box.remove(); } } }, "Not now")), msg);
  return box;
}

async function signOut() {
  if (key?.startsWith("s.")) { try { await api("/session/signout", "POST", {}); } catch { /* already signed out */ } }
  try { localStorage.removeItem("pgKey"); } catch { /* private mode */ } key = null; render();
}

// Under "The phone": passkeys, other signed-in browsers and the add-a-device link.
function accountSection() {
  const body = h("div", {}, h("p", { class: "mut" }, "Loading...")), sec = section("Account: sign-in and passkeys", false, body), msg = h("p", { class: "bad" });
  const when = (t) => (t ? ago(t) : "never"), fail = (e) => { msg.textContent = e.message; };
  async function draw(invited) {
    const d = await api("/passkeys");
    const invite_ = invited ? h("div", { class: "card" }, h("strong", {}, "Add another device"), h("p", { class: "mut" }, "On the other device open this link (or scan the code). It works once, for 10 minutes."), h("div", { class: "qr", html: qrSvg(invited) }), h("input", { readonly: true, value: invited, "aria-label": "One-time link", on: { click: (e) => e.target.select() } })) : null;
    body.replaceChildren(...[h("p", { class: "mut" }, "A passkey is a device's fingerprint, face or screen PIN. Your parent key keeps working too, so you cannot lock yourself out."),
      d.passkeys.length ? h("div", {}, d.passkeys.map((p) => h("div", { class: "row" }, h("span", {}, `${p.name}: added ${when(p.created)}, last used ${when(p.last_used)}`), h("button", { on: { click: async () => { if (!confirm(`Remove the passkey for ${p.name}?`)) return; try { await api("/passkey/remove", "POST", { id: p.id }); draw(); } catch (e) { fail(e); } } } }, "Remove")))) : h("p", { class: "mut" }, "No passkeys yet."),
      h("div", { class: "row" }, supported() ? h("button", { class: "pri", on: { click: async () => { msg.textContent = ""; try { await addPasskey(api); draw(); } catch (e) { fail(e); } } } }, "Add a passkey on this device") : null,
        h("button", { on: { click: async () => { msg.textContent = ""; try { const r = await api("/passkey/invite", "POST", {}); draw(`${location.origin}/#add=${r.token}`); } catch (e) { fail(e); } } } }, "Add another device")), invite_,
      h("p", { class: "mut" }, `Signed-in browsers: ${d.sessions.length}${d.sessions.length ? " (" + d.sessions.map((x) => `${x.name}${x.current ? ", this one" : ""}`).join("; ") + ")" : ""}.`),
      h("div", { class: "row" }, h("button", { on: { click: () => { if (confirm("Sign out of this browser? You will need a passkey or your parent link to get back in.")) signOut(); } } }, "Sign out of this browser"),
        h("button", { on: { click: async () => { if (!confirm("Sign out of every browser that used a passkey? Your parent key and the link still work.")) return; try { await api("/session/signout-all", "POST", {}); draw(); } catch (e) { fail(e); } } } }, "Sign out everywhere")), msg].filter(Boolean));
  }
  sec.addEventListener("toggle", () => { if (sec.open) draw().catch(fail); });
  return sec;
}

async function addPhone() {
  const e = await api("/enrol/new", "POST"), p = await enrolPayload({ host: location.host, token: e.token, serverKey: e.serverKey });
  let slot = document.getElementById("add"); if (!slot) { slot = h("div", { id: "add" }); $app.prepend(slot); }
  slot.replaceChildren(h("div", { class: "card" }, h("strong", {}, "Add a phone"), h("p", { class: "mut" }, "On the child's phone open Phone Guard and scan this, or type the address and code. It works for about 10 minutes."),
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
  onHome = false;
  $app.replaceChildren(h("h2", {}, "Phone approved"), h("p", {}, "Write down this recovery key and keep it somewhere safe. It is shown only once and lets you take back control if you lose this page."), h("div", { class: "key" }, k), h("button", { class: "pri", on: { click: render } }, "I have saved it"));
}

function phonesView(list) {
  if (!list.length) return h("div", {});
  return h("div", {}, h("h2", {}, "Phones"), list.map((d) => h("div", { class: "card" }, h("div", {}, h("strong", {}, d.name ?? d.model ?? "Phone")),
    h("p", { class: "mut" }, d.mode_now ? `Now ${d.mode_now}` : "", d.override ? " (forced)" : "", d.boost ? ` · boost ${left(d.boost.until)} left` : "", ` · seen ${ago(d.last_seen)}`, d.state?.battery != null ? ` · battery ${d.state.battery}%` : ""),
    h("button", { on: { click: () => open(d.did) } }, "Open"))));
}

async function open(did, top = null, single = false) { onHome = false; try { await phoneScreen({ api, back: render, app: $app, top: top ?? h("div", {}, accountSection()), single, add: addPhone, track: (id, name, tz) => trackScreen({ api, app: $app, back: () => open(id) }, id, name, tz) }, did); window.scrollTo(0, 0); } catch (e) { $app.replaceChildren(h("button", { on: { click: render } }, "Back"), h("p", { class: "bad" }, e.message)); } }

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

// A one-time add-a-device link pasted into a tab that is already open: the address only changes after the #, so the page does not reload by itself.
addEventListener("hashchange", () => { const iv = /[#&]add=([\w-]+)/.exec(location.hash); if (iv) { invite = iv[1]; history.replaceState(null, "", location.pathname); render(); } });
if (location.pathname === "/spotify/callback") { if (key) spotifyCallback(); else $app.append(signIn()); } else render();
setInterval(() => { if (key && onHome && document.visibilityState === "visible" && !document.activeElement?.matches("input,select,textarea") && !document.querySelector("details.sec[open]")) render(); }, 15000);
