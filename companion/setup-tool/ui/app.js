// Setup wizard. Each step does what it can automatically; where it cannot (Spotify), it hands off with a link and exact values to copy.
const S = document.querySelector("main").dataset.session, v = document.getElementById("v");
const h = (t, a = {}, ...k) => { const e = document.createElement(t); for (const [x, y] of Object.entries(a)) { if (x === "on") e.addEventListener("click", y); else if (x === "html") e.innerHTML = y; else if (x === "disabled") e.disabled = !!y; else e.setAttribute(x, y); } e.append(...k.flat().filter((c) => c != null)); return e; };
const call = (p, o = {}) => fetch(p, { ...o, headers: { "x-session": S, "content-type": "application/json" } }).then(async (r) => { const j = await r.json().catch(() => ({})); return Array.isArray(j) ? j : { ok: r.ok, status: r.status, ...j }; });
const post = (p, body) => call(p, { method: "POST", body: JSON.stringify(body ?? {}) });
const TOKEN_LINK = "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=phoneguard-setup";

const STEPS = [{ id: "cf", label: "Cloudflare" }, { id: "install", label: "Install" }, { id: "phone", label: "Child's phone" }, { id: "yours", label: "Your phone" }, { id: "spotify", label: "Spotify", optional: true }, { id: "done", label: "Finish" }];
const state = { at: 0, skipped: new Set(), info: null, did: null, timers: [] };
const stop = () => { state.timers.forEach(clearInterval); state.timers = []; };
const every = (fn, ms) => { fn(); state.timers.push(setInterval(fn, ms)); };

function go(id) {
  stop(); state.at = STEPS.findIndex((s) => s.id === id);
  document.getElementById("steps").replaceChildren(...STEPS.map((s, i) => h("li", { class: i === state.at ? "cur" : state.skipped.has(s.id) ? "skip" : i < state.at ? "past" : "" }, s.label)));
  const s = STEPS[state.at]; document.getElementById("stepText").textContent = `Step ${state.at + 1} of ${STEPS.length}${s.optional ? " (optional)" : ""}`;
  v.replaceChildren(); VIEWS[id]();
}
const next = () => go(STEPS[state.at + 1].id);
const skip = () => { state.skipped.add(STEPS[state.at].id); next(); };
const copyRow = (label, text) => { const b = h("button", { class: "sec", on: () => navigator.clipboard?.writeText(text).then(() => { b.textContent = "Copied"; setTimeout(() => (b.textContent = "Copy"), 1500); }) }, "Copy"); return h("div", {}, h("div", { class: "mut" }, label), h("div", { class: "copy" }, h("code", {}, text), b)); };

const VIEWS = {
  async cf() {
    const key = h("input", { type: "password", placeholder: "Paste the key from Cloudflare", autocomplete: "off", "aria-label": "Cloudflare key" }), err = h("p", { class: "bad" });
    const saved = await call("/families");
    v.replaceChildren(...[
      h("p", {}, "This sets up your family's private companion in your own Cloudflare account. It takes about 20 minutes and you only do it once. You can skip the optional steps and do them later."),
      saved.length ? h("div", { class: "card" }, h("strong", {}, "Continue an earlier setup"), h("p", { class: "mut" }, "Already installed your companion? Pick it to carry on from connecting a phone."), ...saved.map((f) => h("p", {}, h("button", { class: "sec", on: async () => { const r = await post("/resume", { name: f.name }); if (!r.ok) return (err.textContent = r.error); await loadInfo(); go("phone"); } }, f.host)))) : null,
      h("div", { class: "card" }, h("strong", {}, "1. Get a key from Cloudflare"), h("p", { class: "mut" }, "Cloudflare holds your family's settings. The free plan is enough. Create a free account first if you do not have one."),
        h("a", { class: "btn", href: TOKEN_LINK, target: "_blank", rel: "noopener noreferrer" }, "Open Cloudflare"), h("p", { class: "mut" }, "On that page press Continue to summary, then Create token, and copy the key it shows.")),
      h("div", { class: "card" }, h("strong", {}, "2. Paste it here"), h("p", { class: "mut" }, "The key stays on this computer and is forgotten when you close this page."), key, err, h("p", {}, h("button", { on: () => install({ token: key.value.trim() }, err) }, "Install my companion")))].filter(Boolean));
  },
  async install() {},
  async phone() { await phoneStep(); },
  async yours() {
    const i = state.info;
    v.replaceChildren(h("h2", {}, "Open your parent page on your own phone"), h("p", {}, "Scan this with your phone's camera. It opens your private parent page, where you can change the timetable, add phones and connect Spotify later."),
      h("p", { class: "mut" }, "Anyone with this link can manage the phones, so keep it private. Do not post or share it."), h("div", { class: "qr", html: i.parentQr }),
      h("p", {}, h("a", { class: "btn sec", href: i.link, target: "_blank", rel: "noopener noreferrer" }, "Or open it on this computer")),
      h("div", { class: "row" }, h("button", { on: next }, "I have opened it"), h("button", { class: "sec", on: skip }, "Skip, I will do this later")));
  },
  async spotify() { await spotifyStep(); },
  async done() {
    const i = state.info, sk = (id) => state.skipped.has(id);
    v.replaceChildren(h("h2", { class: "ok" }, "All set"), h("ul", {},
      h("li", { class: "done" }, "Your companion is installed in your Cloudflare account"),
      h("li", { class: sk("phone") ? "todo" : "done" }, sk("phone") ? "Child's phone: not connected yet. Press Add a phone on your parent page any time." : "Child's phone is connected"),
      h("li", { class: sk("yours") ? "todo" : "done" }, sk("yours") ? "Your phone: open the private link when you are ready (shown below)." : "Your phone has the parent page"),
      h("li", { class: sk("spotify") ? "todo" : "done" }, sk("spotify") ? "Spotify: skipped. On your parent page open Timetable and settings, then the Spotify box." : "Spotify is set up")),
      h("div", { class: "card" }, h("strong", {}, "What next"), h("p", { class: "mut" }, "Set the timetable from your parent page: Timetable and settings, add blocks for each day, Save."),
        h("p", {}, h("a", { class: "btn", href: i.link, target: "_blank", rel: "noopener noreferrer" }, "Open my parent page")), h("p", {}, h("a", { href: i.guide, target: "_blank", rel: "noopener noreferrer" }, "How it all works"))));
  },
};

async function loadInfo() { state.info = await call("/info"); }

async function install(body, err) {
  const r = await post("/run", body); if (!r.ok) { err.textContent = r.error; return; }
  go("install");
  const list = h("ul", {}), out = h("div", {}); v.replaceChildren(h("h2", {}, "Installing"), list, out);
  every(async () => {
    const j = await call("/status");
    if (j.needAccount) { stop(); return v.replaceChildren(h("p", {}, "Your key can see more than one Cloudflare account. Which one should hold the family's companion?"), ...j.needAccount.map((a) => h("p", {}, h("button", { on: () => install({ ...body, account: a.id }, err) }, a.name)))); }
    list.replaceChildren(...j.steps.map((s) => h("li", { class: s.state }, s.text)));
    if (j.error) { stop(); out.replaceChildren(h("p", { class: "bad" }, j.error), h("button", { on: () => go("cf") }, "Try again")); }
    if (j.result) { stop(); await loadInfo(); go("phone"); }
  }, 1500);
}

async function phoneStep() {
  const box = h("div", {}), waiting = h("div", {}), after = h("div", {});
  let ack = false;
  const newCode = async () => { box.replaceChildren(h("p", { class: "mut" }, "Making a code...")); const e = await post("/enrol");
    box.replaceChildren(h("div", { class: "grid" }, h("div", { class: "qr", html: e.qr }), h("div", {}, h("div", { class: "mut" }, "Address"), h("div", { class: "code" }, e.address), h("div", { class: "mut" }, "Code"), h("div", { class: "code" }, e.code))), h("p", { class: "mut", id: "left" }), h("button", { class: "sec", on: newCode }, "Make a new code"));
    const tick = () => { const m = Math.round((e.expires - Date.now()) / 60000); const el = document.getElementById("left"); if (el) el.textContent = m > 0 ? `This code works for about ${m} more minute${m === 1 ? "" : "s"}.` : "This code has expired. Press Make a new code."; }; tick(); state.timers.push(setInterval(tick, 20000)); };
  const finish = h("div", { class: "row" }, h("button", { id: "cont", disabled: true, on: next }, "Continue"), h("button", { class: "sec", on: skip }, "Skip, I will connect a phone later"));
  v.replaceChildren(h("h2", {}, "Connect the child's phone"), h("ol", {}, h("li", {}, "On the child's phone open Phone Guard and press Connect to the family companion."), h("li", {}, "Scan this code, or type the address and code into the two boxes, then press Connect."), h("li", {}, "The phone shows 4 digits. Type them here when asked below.")), box, waiting, after, finish);
  await newCode();
  const shown = new Set();
  every(async () => {
    const ps = await call("/pairings"); const p = Array.isArray(ps) ? ps[0] : null;
    if (p && !shown.has(p.id) && !ack) { shown.add(p.id);
      const digits = h("input", { class: "digits", inputmode: "numeric", maxlength: "4", "aria-label": "Four digits shown on the phone" }), msg = h("p", { class: "bad" });
      waiting.replaceChildren(h("div", { class: "card" }, h("strong", {}, `${p.model || "A phone"} is waiting`), h("p", { class: "mut" }, "Type the 4 digits it is showing. This checks you are approving the phone in your hand."), h("div", { class: "row" }, digits, h("button", { on: async () => { const r = await post("/approve", { id: p.id, match: digits.value.trim() }); if (!r.ok) { msg.textContent = r.error === "numbers do not match" ? "Those digits do not match the phone." : r.error; return; } approved(r); } }, "Approve")), msg));
    }
  }, 2500);
  function approved(r) {
    ack = true; state.did = r.did; waiting.replaceChildren();
    const tick = h("input", { type: "checkbox", id: "ack" }), status = h("p", { class: "mut" }, "Waiting for the phone to check in...");
    after.replaceChildren(h("div", { class: "card warn" }, h("strong", {}, "Write down your recovery key"), h("p", { class: "mut" }, "It is shown only once. It lets you take back control if you lose your private link. Keep it somewhere safe, not on the same phone."), h("div", { class: "key" }, r.recoveryKey),
      h("label", {}, tick, " I have written it down")), status);
    tick.addEventListener("change", () => (document.getElementById("cont").disabled = !tick.checked));
    every(async () => { const d = await call("/devices"); if (Array.isArray(d) && d.find((x) => x.did === r.did)?.last_seen) { status.className = "ok"; status.textContent = "The child's phone is connected and has checked in."; } }, 3000);
  }
}

async function spotifyStep() {
  const i = state.info, devs = await call("/devices"), did = state.did ?? (Array.isArray(devs) && devs[0]?.did);
  const cid = h("input", { placeholder: "Client ID (32 letters and numbers)", autocomplete: "off", "aria-label": "Spotify Client ID" }), ok = h("span", { class: "mut" }), msg = h("p", { class: "bad" }), conn = h("p", { class: "mut" });
  cid.addEventListener("input", () => { const good = /^[0-9a-f]{32}$/i.test(cid.value.trim()); ok.textContent = good ? "Looks right" : cid.value ? "Not 32 letters and numbers yet" : ""; ok.className = good ? "ok" : "mut"; });
  const open = h("button", { on: async () => { if (!did) { msg.textContent = "Connect the child's phone first, then come back to Spotify."; return; } const r = await post("/spotify-client", { did, clientId: cid.value.trim() }); if (!r.ok) { msg.textContent = r.error; return; } msg.textContent = ""; window.open(r.link, "_blank", "noopener,noreferrer"); conn.textContent = "Sign in to Spotify on the page that opened. This page will tick when it is connected."; every(async () => { const s = await call(`/spotify-status?did=${encodeURIComponent(did)}`); if (s.connected) { conn.className = "ok"; conn.textContent = "Spotify is connected. The child's phone collects the key at its next check-in."; document.getElementById("sp-next").disabled = false; } }, 3000); } }, "Save and connect Spotify");
  v.replaceChildren(h("h2", {}, "Spotify (optional)"),
    h("p", {}, "This lets the child's phone play your Spotify. You need a Spotify Premium account. Spotify does not allow tools to create these apps for you, so this takes about five minutes of copying and pasting. You can skip it and do it later."),
    h("div", { class: "card" }, h("strong", {}, "1. Create an app in Spotify"), h("p", { class: "mut" }, "Open the dashboard, sign in with your Premium account, accept the terms and press Create app."), h("a", { class: "btn", href: "https://developer.spotify.com/dashboard", target: "_blank", rel: "noopener noreferrer" }, "Open the Spotify dashboard")),
    h("div", { class: "card" }, h("strong", {}, "2. Fill in the form with these values"), copyRow("App name", "Phone Guard family"), copyRow("App description", "My family's Phone Guard companion"), copyRow("Redirect URI (must match exactly)", i.redirectUri), h("p", { class: "mut" }, "Tick Web API, accept the terms and Save.")),
    h("div", { class: "card" }, h("strong", {}, "3. Add the people who will use it"), h("p", { class: "mut" }, "In the app's Settings open User management and add the email address of each Spotify account that will be used (up to five).")),
    h("div", { class: "card" }, h("strong", {}, "4. Paste the Client ID"), h("p", { class: "mut" }, "In the app's Settings, copy the Client ID and paste it here."), cid, ok, msg, h("p", {}, open), conn),
    h("div", { class: "row" }, h("button", { id: "sp-next", disabled: true, on: next }, "Continue"), h("button", { class: "sec", on: skip }, "Skip Spotify, I will do it later")));
}

go("cf");
