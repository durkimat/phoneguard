// Setup wizard. Each step does what it can automatically; where it cannot (Spotify), it hands off with a link and exact values to copy.
const S = document.querySelector("main").dataset.session, v = document.getElementById("v");
const h = (t, a = {}, ...k) => { const e = document.createElement(t); for (const [x, y] of Object.entries(a)) { if (x === "on") e.addEventListener("click", y); else if (x === "html") e.innerHTML = y; else if (x === "disabled") e.disabled = !!y; else e.setAttribute(x, y); } e.append(...k.flat().filter((c) => c != null)); return e; };
const call = (p, o = {}) => fetch(p, { ...o, headers: { "x-session": S, "content-type": "application/json" } }).then(async (r) => { const j = await r.json().catch(() => ({})); return Array.isArray(j) ? j : { ok: r.ok, status: r.status, ...j }; });
const post = (p, body) => call(p, { method: "POST", body: JSON.stringify(body ?? {}) });
const TOKEN_LINK = "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=phoneguard-setup";

const STEPS = [{ id: "cf", label: "Cloudflare" }, { id: "install", label: "Install" }, { id: "phone", label: "Child's phone" }, { id: "yours", label: "Your phone" }, { id: "spotify", label: "Spotify", optional: true }, { id: "email", label: "Email", optional: true }, { id: "calendar", label: "Calendar", optional: true }, { id: "whatsapp", label: "WhatsApp", optional: true }, { id: "done", label: "Finish" }];
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
    const key = h("input", { type: "password", placeholder: "Paste the key from Cloudflare", autocomplete: "off", "aria-label": "Cloudflare key" }), err = h("p", { class: "bad" }), check = h("p", { class: "mut" });
    const saved = await call("/families");
    v.replaceChildren(...[
      h("p", {}, "This sets up your family's private companion in your own Cloudflare account. It takes about 20 minutes and you only do it once. You can skip the optional steps and do them later."),
      saved.length ? h("div", { class: "card" }, h("strong", {}, "Continue an earlier setup"), h("p", { class: "mut" }, "Already installed your companion? Pick it to carry on from connecting a phone."), ...saved.map((f) => h("p", {}, h("button", { class: "sec", on: async () => { const r = await post("/resume", { name: f.name }); if (!r.ok) return (err.textContent = r.error); await loadInfo(); go("phone"); } }, f.host)))) : null,
      saved.length ? await updateCard(saved) : null,
      h("div", { class: "card" }, h("strong", {}, "1. Get a key from Cloudflare"), h("p", { class: "mut" }, "Cloudflare holds your family's settings. The free plan is enough. A Cloudflare account is free: the page below lets you sign in or create one."),
        h("a", { class: "btn", href: TOKEN_LINK, target: "_blank", rel: "noopener noreferrer" }, "Open Cloudflare"),
        h("ol", { class: "how" }, h("li", {}, "Sign in (or create a free account) when Cloudflare asks."), h("li", {}, "You arrive at \"Create API token\" with the permission already chosen (Workers Scripts: Edit). Do not change anything on that page."),
          h("li", {}, "Scroll to the bottom and press ", h("strong", {}, "Continue to summary"), ", then ", h("strong", {}, "Create token"), "."), h("li", {}, "Cloudflare shows the key once. Press ", h("strong", {}, "Copy"), " and come back here.")),
        h("p", { class: "mut" }, "Afterwards you can delete this key in Cloudflare (your profile, API Tokens). It is only needed to install and to update; you make a new one next time.")),
      h("div", { class: "card" }, h("strong", {}, "2. Paste it here"), h("p", { class: "mut" }, "The key stays on this computer and is forgotten when you close this page."), key, check, err,
        h("div", { class: "row" }, h("button", { class: "sec", on: async () => { check.className = "mut"; check.textContent = "Checking..."; const r = await post("/check-key", { token: key.value.trim() }); if (r.ok) { check.className = "ok"; check.textContent = `Key accepted. It can use your Cloudflare account: ${r.accounts.join(", ")}.`; } else { check.className = "bad"; check.textContent = r.error ?? "Cloudflare did not accept that key."; } } }, "Check my key"), h("button", { on: () => install({ token: key.value.trim() }, err) }, "Install my companion")))].filter(Boolean));
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
  async email() { await emailStep(); },
  async calendar() { await calendarStep(); },
  async whatsapp() { await whatsappStep(); },
  async done() {
    const i = state.info, sk = (id) => state.skipped.has(id);
    v.replaceChildren(h("h2", { class: "ok" }, "Setup finished"), h("p", {}, "Your family's companion is installed. From here on you manage the phone from your parent page, not from this tool."), h("ul", {},
      h("li", { class: "done" }, "Your companion is installed in your Cloudflare account"),
      h("li", { class: sk("phone") ? "todo" : "done" }, sk("phone") ? "Child's phone: not connected yet. Press Add a phone on your parent page any time." : "Child's phone is connected"),
      h("li", { class: sk("yours") ? "todo" : "done" }, sk("yours") ? "Your phone: open the private link when you are ready (shown below)." : "Your phone has the parent page"),
      h("li", { class: sk("spotify") ? "todo" : "done" }, sk("spotify") ? "Spotify: skipped. On your parent page open the phone, then the Spotify section." : "Spotify is set up"),
      h("li", { class: sk("email") ? "todo" : "done" }, sk("email") ? "Email: skipped. On your parent page open the phone, then the Email section." : "Email is set up (the phone finishes it at its next check-in)"),
      h("li", { class: sk("calendar") ? "todo" : "done" }, sk("calendar") ? "Calendar: skipped. On your parent page open the phone, then the Calendars section." : "Calendars are set up"),
      h("li", { class: sk("whatsapp") ? "todo" : "done" }, sk("whatsapp") ? "WhatsApp: skipped. The steps are in the guide: https://" + i.host + "/guide#whatsapp" : "WhatsApp is linked (or you chose to finish later)")),
      h("div", { class: "card" }, h("strong", {}, "What next"), h("p", { class: "mut" }, "Open your parent page. It starts with how the phone is doing; set the timetable in its Timetable tab (add blocks for each day and Save). Bookmark it, and keep its link private."),
        h("p", {}, h("a", { class: "btn", href: i.welcomeLink ?? i.link, target: "_blank", rel: "noopener noreferrer" }, "Open my parent page")), h("p", {}, h("a", { href: i.guide, target: "_blank", rel: "noopener noreferrer" }, "How it all works"))));
  },
};

async function loadInfo() { state.info = await call("/info"); }

// Update mode: new companion code for an existing family, keeping its phones, settings and parent link. Needs the Cloudflare key again.
async function updateCard(saved) {
  const info = await call("/update-info"), key = h("input", { type: "password", placeholder: "Paste the Cloudflare key", autocomplete: "off", "aria-label": "Cloudflare key for the update" }), err = h("p", { class: "bad" });
  const rows = (Array.isArray(info) ? info : []).map((f) => {
    const newer = f.current && f.latest && f.current !== f.latest, text = f.current ? (newer ? `version ${f.current}, newest ${f.latest}` : `version ${f.current}, up to date`) : "not answering";
    return h("p", {}, h("strong", {}, f.name), " ", h("span", { class: "mut" }, text), " ", h("button", { class: newer ? "" : "sec", on: () => { if (key.value.trim().length < 20) { err.textContent = "Please paste the Cloudflare key first (the same link as for the install, below)."; return; } updateFlow({ name: f.name, token: key.value.trim() }, err); } }, newer ? "Update" : "Check again"));
  });
  return h("div", { class: "card" }, h("strong", {}, "Update my companion"), h("p", { class: "mut" }, "Installs the newest version. Your phones, settings and parent link are kept. Takes about a minute."), ...rows, key, err);
}
async function updateFlow(body, err) {
  const r = await post("/update", body); if (!r.ok) { err.textContent = r.error; return; }
  const list = h("ul", {}), out = h("div", {}); v.replaceChildren(h("h2", {}, "Updating your companion"), list, out);
  every(async () => {
    const j = await call("/status");
    list.replaceChildren(...(j.steps ?? []).map((s) => h("li", { class: s.state }, s.text)));
    if (j.error) { stop(); out.replaceChildren(h("p", { class: "bad" }, j.error), h("p", { class: "mut" }, "Nothing was lost. You can try again."), h("button", { on: () => go("cf") }, "Back")); }
    if (j.result) {
      stop(); const x = j.result; await loadInfo();
      out.replaceChildren(h("p", { class: "ok" }, x.upToDate ? `Already up to date (version ${x.version}).` : `Updated ${x.from && x.from !== "0.0.0" ? x.from : "your companion"} to ${x.to}. ${x.phones != null ? `${x.phones} phone${x.phones === 1 ? " is" : "s are"} still connected and your parent link still works.` : ""}`),
        h("p", {}, h("a", { class: "btn", href: state.info.link, target: "_blank", rel: "noopener noreferrer" }, "Open my parent page")), h("p", {}, h("button", { class: "sec", on: () => go("cf") }, "Back")));
    }
  }, 1500);
}

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
  const cid = h("input", { placeholder: "Client ID (32 letters and numbers)", autocomplete: "off", "aria-label": "Spotify Client ID" }), ok = h("span", { class: "mut" }), msg = h("p", { class: "bad" }), conn = h("p", { class: "mut" }), appCheck = h("p", { class: "mut" }, "Checking the phone..."), spInst = h("div", {});
  const spotifyInstall = async () => {
    if (!did) { spInst.replaceChildren(h("p", { class: "bad" }, "Connect the child's phone first.")); return; }
    const r = await post("/spotify-install", { did }); if (!r.ok) { spInst.replaceChildren(h("p", { class: "bad" }, r.error)); return; }
    const list = h("ul", {}), out = h("p", {}); spInst.replaceChildren(list, out);
    every(async () => { const j = await call("/spotify-install-status"); list.replaceChildren(...(j.steps ?? []).map((t, k, a) => h("li", { class: k === a.length - 1 && !j.done ? "doing" : "done" }, t)));
      if (j.error) { stop(); out.className = "bad"; out.textContent = j.error; } if (j.result) { stop(); out.className = "ok"; out.textContent = j.result.already ? "The Spotify app is already on the phone. Sign in to it on the phone." : "Spotify is installed. Now sign in to Spotify on the phone itself (its touchscreen)."; } }, 2000);
  };
  cid.addEventListener("input", () => { const good = /^[0-9a-f]{32}$/i.test(cid.value.trim()); ok.textContent = good ? "Looks right" : cid.value ? "Not 32 letters and numbers yet" : ""; ok.className = good ? "ok" : "mut"; });
  const open = h("button", { on: async () => { if (!did) { msg.textContent = "Connect the child's phone first, then come back to Spotify."; return; } const r = await post("/spotify-client", { did, clientId: cid.value.trim() }); if (!r.ok) { msg.textContent = r.error; return; } msg.textContent = ""; window.open(r.link, "_blank", "noopener,noreferrer"); conn.textContent = "Sign in to Spotify on the page that opened. This page will tick when it is connected."; every(async () => { const s = await call(`/spotify-status?did=${encodeURIComponent(did)}`); if (s.connected) { conn.className = "ok"; conn.textContent = "Spotify is connected. The child's phone collects the key at its next check-in."; document.getElementById("sp-next").disabled = false; } }, 3000); } }, "Save and connect Spotify");
  v.replaceChildren(h("h2", {}, "Spotify (optional)"),
    h("p", {}, "This lets the child's phone play your Spotify. You need a Spotify Premium account. Spotify does not allow tools to create these apps for you, so this takes about five minutes of copying and pasting. You can skip it and do it later."),
    h("div", { class: "card" }, h("strong", {}, "1. Create an app in Spotify"), h("p", { class: "mut" }, "Open the dashboard, sign in with your Premium account, accept the terms and press Create app."), h("a", { class: "btn", href: "https://developer.spotify.com/dashboard", target: "_blank", rel: "noopener noreferrer" }, "Open the Spotify dashboard")),
    h("div", { class: "card" }, h("strong", {}, "2. Fill in the form with these values"), copyRow("App name", "Phone Guard family"), copyRow("App description", "My family's Phone Guard companion"), copyRow("Redirect URI (must match exactly)", i.redirectUri), h("p", { class: "mut" }, "Tick Web API, accept the terms and Save.")),
    h("div", { class: "card" }, h("strong", {}, "Spotify app on the child's phone"), appCheck, h("p", { class: "mut" }, "The child's phone plays music through the Spotify app, which Spotify only publishes on Google Play, so it is not in the Phone Guard image. If it is missing, see the guide: ", h("a", { href: "https://github.com/durkimat/phoneguard/blob/main/docs/install/spotify-app.md", target: "_blank", rel: "noopener noreferrer" }, "installing it by hand"), ". You then sign in to Spotify on the phone itself."),
      h("p", {}, h("button", { on: () => spotifyInstall() }, "Install it for me (phone plugged into this computer, on Wi-Fi)")), spInst),
    h("div", { class: "card" }, h("strong", {}, "3. Add the people who will use it"), h("p", { class: "mut" }, "In the app's Settings open User management and add the email address of each Spotify account that will be used (up to five).")),
    h("div", { class: "card" }, h("strong", {}, "4. Paste the Client ID"), h("p", { class: "mut" }, "In the app's Settings, copy the Client ID and paste it here."), cid, ok, msg, h("p", {}, open), conn),
    h("div", { class: "row" }, h("button", { id: "sp-next", disabled: true, on: next }, "Continue"), h("button", { class: "sec", on: skip }, "Skip Spotify, I will do it later")));
  every(async () => { if (!did) return; const x = await call(`/spotify-status?did=${encodeURIComponent(did)}`); appCheck.className = x.appInstalled === true ? "ok" : x.appInstalled === false ? "bad" : "mut";
    appCheck.textContent = x.appInstalled === true ? "The Spotify app is on the phone." : x.appInstalled === false ? "The Spotify app is not on the phone yet." : "Not known yet: the phone tells your companion at its next check-in."; }, 5000);
}

async function whatsappStep() {
  const i = state.info, devs = await call("/devices"), did = state.did ?? (Array.isArray(devs) && devs[0]?.did);
  const all = h("input", { type: "checkbox", id: "waall", checked: "checked" }), list = h("textarea", { rows: "4", "aria-label": "WhatsApp numbers and groups the child may message", placeholder: "447700900111\ng:Family" }), msg = h("p", { class: "mut" }), linked = h("p", { class: "mut" }, "The phone tells your companion when it is linked, at its next check-in (up to 15 minutes; press Sync now on the phone to speed it up).");
  const phoneHas = h("p", { class: "mut" });
  let saved = false, want = null, ready = false;
  const entries = () => list.value.split("\n").map((x) => x.trim()).filter(Boolean);
  const linkBox = h("div", {});
  const setReady = (on, note, cls) => { ready = on; linkBox.style.opacity = on ? "1" : "0.45"; phoneHas.className = cls; phoneHas.textContent = note; };
  const save = async () => {
    if (!did) { msg.className = "bad"; msg.textContent = "Connect the child's phone first, then come back."; return false; }
    const r = await post("/whatsapp-allowed", { did, all: all.checked, allowed: list.value.split("\n") });
    msg.className = r.ok ? "ok" : "bad"; msg.textContent = r.ok ? "Saved." : (r.error ?? "That did not save."); saved = Boolean(r.ok);
    if (saved) { want = { all: all.checked, n: entries().length }; setReady(false, "Waiting for the child's phone to pick this up. Press Sync now on its status page, or wait up to 15 minutes. Link WhatsApp only after this turns green: WhatsApp sends the recent messages once, when linking.", "mut"); }
    return saved;
  };
  const row = (n, text, ...more) => h("div", { class: "card" }, h("strong", {}, n), ...more);
  linkBox.append(
    row("2. On the child's phone", "", h("p", { class: "mut" }, "Open the pairing screen: Messages, then WhatsApp (or Settings, then WhatsApp). Messages only offers WhatsApp when the current mode allows WhatsApp checks. The screen is called Link WhatsApp and asks for Your WhatsApp number: type it with the country code and no plus (for example 447700900123) and press Get code. The phone shows a code.")),
    row("3. On the smartphone that has that WhatsApp account", "", h("p", { class: "mut" }, "Open WhatsApp, then Settings (or the three dots), Linked devices, Link a device, then Link with phone number instead, and type the code from the child's phone."),
      h("details", { class: "plain" }, h("summary", {}, "Doing this on your smartphone? Open these steps there"), h("p", { class: "mut" }, "WhatsApp cannot be opened straight onto that screen. Scan this with the smartphone's camera to open the same steps, with a button to open WhatsApp:"), h("div", { class: "qr", html: i.waQr }), h("p", { class: "link" }, i.waGuide))),
    row("4. The phone says Linked", "", linked));
  linkBox.style.opacity = "0.45";
  v.replaceChildren(h("h2", {}, "WhatsApp (optional)"),
    h("p", {}, "This lets the child's phone read and send WhatsApp messages through its own simple screens, with only the people you choose. Messages stay on the phone. The phone becomes a linked device of one WhatsApp account, like WhatsApp Web. You need the smartphone that has that WhatsApp account (the child's own number, or yours). It takes about five minutes and you can skip it and do it later."),
    h("div", { class: "card" }, h("strong", {}, "1. Who may the child message? (do this first)"),
      h("p", { class: "mut" }, "WhatsApp sends a month of recent chats to the phone only once, at the moment it is linked, and the phone keeps only the chats you allow. If you choose later, the older messages are not there. Nobody is allowed until you choose."),
      h("label", { class: "chk" }, all, h("span", {}, "Allow everyone and every group")),
      h("p", { class: "mut" }, "Or list people instead (leave the tick off): one per line, a phone number with country code and no plus, or g: and a group's name (g:Family)."), list,
      h("p", {}, h("button", { on: save }, "Save who is allowed")), msg, phoneHas),
    linkBox,
    h("div", { class: "row" }, h("button", { on: async () => { if (!saved && !(await save())) return; next(); } }, "Continue"), h("button", { class: "sec", on: skip }, "Skip WhatsApp, I will do it later")));
  every(async () => {
    const d = await call("/devices"), x = Array.isArray(d) ? d.find((q) => q.did === did) : null, wa = x?.state?.wa;
    if (wa?.linked) { linked.className = "ok"; linked.textContent = "The child's phone says it is linked to WhatsApp."; }
    if (saved && !ready && wa) {
      if (typeof wa.all === "boolean") { if (wa.all === want.all && (want.all || wa.allowed === want.n)) setReady(true, "The child's phone has your choice. Now link WhatsApp with steps 2 and 3.", "ok"); }
      else if (x?.app) setReady(true, "This phone does not report which chats it allows, so press Sync now on its status page, wait a few seconds, then link WhatsApp with steps 2 and 3.", "bad");   // an older Phone Guard build
    }
  }, 3000);
}

async function emailStep() {
  const devs = await call("/devices"), did = state.did ?? (Array.isArray(devs) && devs[0]?.did), P = await call("/providers");
  const address = h("input", { type: "email", placeholder: "name@gmail.com", autocomplete: "off", "aria-label": "Email address" }), pw = h("input", { type: "password", placeholder: "App password", autocomplete: "off", "aria-label": "App password" });
  const guide = h("div"), servers = h("details", {}), results = h("div"), msg = h("p"), saved = h("p", { class: "mut" });
  const field = (label, el) => h("label", { style: "display:block;margin:8px 0" }, h("span", { class: "mut" }, label), el);
  const inp = (v, ph, t = "text") => h("input", { type: t, value: v, placeholder: ph }), sec = (v) => { const x = h("select", {}, h("option", { value: "ssl" }, "SSL/TLS (port 993 or 465)"), h("option", { value: "starttls" }, "STARTTLS (port 587 or 143)")); x.value = v; return x; };
  const user = inp("", "Same as the address"), ih = inp("", "imap.example.com"), ip = inp("993", "993", "number"), is = sec("ssl"), sh = inp("", "smtp.example.com"), sp = inp("465", "465", "number"), ss = sec("ssl");
  servers.replaceChildren(h("summary", {}, "Server settings"), field("User name (usually the address)", user), field("Incoming (IMAP) server", ih), h("div", { class: "row" }, field("Port", ip), field("Security", is)), field("Outgoing (SMTP) server", sh), h("div", { class: "row" }, field("Port", sp), field("Security", ss)));
  const send = h("button", { disabled: true }, "Send to the phone"), anyway = h("button", { class: "sec", style: "display:none" }, "Send anyway");
  const find = () => { const a = address.value.trim(), dom = a.split("@")[1]?.toLowerCase() ?? ""; return P.providers.find((p) => p.domains.includes(dom)) ?? P.providers.find((p) => p.id === "other"); };
  const unsupported = () => P.unsupported.find((u) => new RegExp(u.pattern, u.flags).test(address.value.trim()));
  const refresh = () => {
    const p = find(), bad = unsupported();
    if (bad) { guide.replaceChildren(h("div", { class: "card warn" }, h("strong", {}, "This address cannot be used yet"), h("p", { class: "mut" }, bad.why))); return; }
    if (!address.value.includes("@")) { guide.replaceChildren(); return; }
    ih.value = p.imap.host; ip.value = p.imap.port; is.value = p.imap.security; sh.value = p.smtp.host; sp.value = p.smtp.port; ss.value = p.smtp.security; servers.open = p.id === "other";
    guide.replaceChildren(h("div", { class: "card" }, h("strong", {}, `Get an app password (${p.name})`), p.passwordHelp.url ? h("p", {}, h("a", { class: "btn", href: p.passwordHelp.url, target: "_blank", rel: "noopener noreferrer" }, p.passwordHelp.label)) : null, h("ol", { class: "mut" }, p.passwordHelp.steps.map((t) => h("li", {}, t))), h("p", { class: "mut" }, "An app password is a separate password just for this phone. You can cancel it at any time without changing the real password.")));
  };
  address.addEventListener("input", refresh);
  const body = () => ({ did, address: address.value.trim(), user: user.value.trim(), password: pw.value, imap: { host: ih.value, port: Number(ip.value), security: is.value }, smtp: { host: sh.value, port: Number(sp.value), security: ss.value } });
  const line = (label, r) => h("div", { class: r.ok ? "ok" : "bad" }, `${r.ok ? "\u2713" : "\u2717"} ${label}: ${r.message}`);
  const check = h("button", { class: "sec", on: async () => { msg.textContent = ""; if (unsupported()) return; results.replaceChildren(h("p", { class: "mut" }, "Checking (this signs in from this computer)...")); const r = await post("/email-test", body()); if (r.error) { results.replaceChildren(h("p", { class: "bad" }, r.error)); return; } results.replaceChildren(line("Incoming mail", r.imap), line("Outgoing mail", r.smtp)); const good = r.imap.ok && r.smtp.ok; send.disabled = !good; anyway.style.display = good ? "none" : ""; } }, "Check it works");
  const save = async () => { if (!did) { msg.className = "bad"; msg.textContent = "Connect the child's phone first, then come back to email."; return; } const r = await post("/email", body()); msg.className = r.ok ? "ok" : "bad"; msg.textContent = r.ok ? "Sent. The password is locked so only the phone can read it. The phone sets up the account at its next check-in (up to 15 minutes; press Sync now on the phone to speed it up)." : (r.error ?? "That did not save."); if (r.ok) { pw.value = ""; poll(); } };
  send.addEventListener("click", save); anyway.addEventListener("click", save);
  const poll = () => every(async () => { const a = await call(`/accounts?did=${encodeURIComponent(did)}`); if (a.email) { saved.textContent = `Saved for ${a.email.address}.` + (a.mail?.configured ? " The phone has set it up." : a.mail?.error ? ` The phone says: ${a.mail.error}` : " Waiting for the phone to pick it up."); saved.className = a.mail?.configured ? "ok" : "mut"; } }, 4000);
  v.replaceChildren(h("h2", {}, "Email (optional)"),
    h("p", {}, "This lets the child's phone send and receive email with a normal mail account, on its own simple screens. Use an address made for the child and not your own. It takes about five minutes, and you can skip it and do it later."),
    h("div", { class: "card" }, h("strong", {}, "1. The address"), field("Email address", address), guide),
    h("div", { class: "card" }, h("strong", {}, "2. The app password"), h("p", { class: "mut" }, "Paste it here. It stays on this computer until you press Send, and then it is locked so that only the child's phone can read it."), pw, servers),
    h("div", { class: "card" }, h("strong", {}, "3. Check and send"), h("div", { class: "row" }, check, send, anyway), results, msg, saved),
    h("div", { class: "row" }, h("button", { on: next }, "Continue"), h("button", { class: "sec", on: skip }, "Skip email, I will do it later")));
  if (did) { const a = await call(`/accounts?did=${encodeURIComponent(did)}`); if (a.email) { address.value = a.email.address; refresh(); poll(); } }
}

async function calendarStep() {
  const devs = await call("/devices"), did = state.did ?? (Array.isArray(devs) && devs[0]?.did);
  const link = h("input", { placeholder: "https://calendar.google.com/calendar/ical/.../basic.ics", autocomplete: "off", "aria-label": "Calendar link" }), name = h("input", { placeholder: "Name on the phone, for example Family", "aria-label": "Calendar name" });
  const results = h("p", { class: "mut" }), msg = h("p"), list = h("div"), add = h("button", { disabled: true }, "Add this calendar");
  const drawList = async () => { if (!did) return; const a = await call(`/accounts?did=${encodeURIComponent(did)}`); list.replaceChildren(...(a.calendars ?? []).map((c) => h("div", { class: "row" }, h("span", { class: "ok" }, `\u2713 ${c.name}`), h("button", { class: "sec", on: async () => { await post("/calendar-remove", { did, id: c.id }); drawList(); } }, "Remove")))); };
  const check = h("button", { class: "sec", on: async () => { results.className = "mut"; results.textContent = "Reading the link..."; const r = await post("/calendar-check", { url: link.value }); results.className = r.ok ? "ok" : "bad"; results.textContent = r.message; add.disabled = !r.ok; if (r.ok && !name.value && r.name) name.value = r.name; } }, "Check the link");
  add.addEventListener("click", async () => { if (!did) { msg.className = "bad"; msg.textContent = "Connect the child's phone first, then come back to calendars."; return; } const r = await post("/calendar-add", { did, name: name.value, url: link.value }); msg.className = r.ok ? "ok" : "bad"; msg.textContent = r.ok ? `Added ${r.name}. The phone picks it up at its next check-in (up to 15 minutes).` : (r.error ?? "That did not save."); if (r.ok) { link.value = ""; name.value = ""; results.textContent = ""; add.disabled = true; drawList(); } });
  v.replaceChildren(h("h2", {}, "Calendars (optional)"),
    h("p", {}, "This puts your family calendars on the child's phone so they can see what is coming up, for example the family diary or school terms. They are view only: events the child adds on the phone stay on the phone. You can skip this and do it later."),
    h("div", { class: "card" }, h("strong", {}, "1. Get the calendar's private link"), h("p", { class: "mut" }, "Google Calendar, on a computer: open calendar.google.com, then Settings (the cog), then the calendar's name under Settings for my calendars, then scroll to Integrate calendar and copy the Secret address in iCal format. Other calendar services have a similar iCal, ICS or subscribe link."), h("p", { class: "mut" }, "Anyone with this link can read that calendar, so treat it like a password. The wizard locks it so only the child's phone can read it.")),
    h("div", { class: "card" }, h("strong", {}, "2. Paste it and check it"), link, h("div", { class: "row" }, check), results, name),
    h("div", { class: "card" }, h("strong", {}, "3. Add it"), h("div", { class: "row" }, add), msg, h("div", { class: "mut" }, "Calendars on the phone:"), list),
    h("div", { class: "row" }, h("button", { on: next }, "Continue"), h("button", { class: "sec", on: skip }, "Skip calendars, I will do it later")));
  drawList();
}

go("cf");
