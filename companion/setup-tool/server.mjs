// Local setup wizard: runs on 127.0.0.1 only, opens in the parent's browser, drives engine.mjs and the parent's own Worker.
// Nothing is sent anywhere except to Cloudflare and the parent's own Worker.
import http from "node:http";
import crypto from "node:crypto";
import { readFile, writeFile, mkdir, readdir } from "node:fs/promises";
import { homedir } from "node:os";
import { spawn } from "node:child_process";
import QRCode from "qrcode";
import { setUp, CfError, newEnrolment, waitUntilLive, updateWorker, verifyFamily, currentVersion, pickBundle, cf, listAccounts } from "./engine.mjs";
import { seal } from "../worker/src/seal.js";
import { PROVIDERS, UNSUPPORTED } from "./providers.mjs";
import { testImap, testSmtp, checkCalendarUrl } from "./checks.mjs";

// The live checks can be replaced in tests (they talk to real mail servers and calendars).
export const hooks = { testImap, testSmtp, checkCalendarUrl };

const session = crypto.randomBytes(16).toString("hex");           // stops other web pages in the browser from calling this local server
const DIR = process.env.PHONEGUARD_DIR ?? `${homedir()}/.phoneguard`;
let job = null;                                                   // one deploy at a time
let family = null;                                                // { name, account, host, adminToken } once set up or resumed
const STEPS = [["account", "Checking your Cloudflare account"], ["subdomain", "Preparing your address"], ["upload", "Installing the companion"], ["wait", "Waiting for it to come online (this can take two minutes)"], ["enrol", "Making the code for the child's phone"]];

const friendly = (e) => e instanceof CfError && [401, 403].includes(e.status) ? "Cloudflare did not accept that key. Please check it was created with the link on this page (Workers Scripts: Edit)." : e.message;
const base = () => process.env.WORKER_BASE ?? `https://${family.host}`;
const wk = async (path, method = "GET", body) => {
  const r = await fetch(`${base()}/api${path}`, { method, headers: { authorization: `Bearer ${family.adminToken}`, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, body: j };
};
const parentLink = (extra = "") => `https://${family.host}/#k=${family.adminToken}${extra}`;

async function start({ token, account }) {
  job = { steps: STEPS.map(([id, text]) => ({ id, text, state: "todo" })), done: false };
  const mark = (id) => { let hit = false; for (const s of job.steps) { if (s.id === id) { s.state = "doing"; hit = true; } else if (!hit) s.state = "done"; } };
  try {
    const r = await setUp({ token, account, progress: (id) => mark(id) });
    if (r.needAccount) { job = { needAccount: r.needAccount, steps: [], done: false }; return; }
    job.steps.forEach((s) => (s.state = "done"));
    await mkdir(DIR, { recursive: true, mode: 0o700 });
    const file = `${DIR}/family-${r.name}.json`;
    await writeFile(file, JSON.stringify({ account: r.account, name: r.name, host: r.host, parentKey: r.adminToken }, null, 2), { mode: 0o600 });
    family = { name: r.name, account: r.account, host: r.host, adminToken: r.adminToken };
    job.result = { host: r.host, saved: file };
    job.done = true;
  } catch (e) { job.error = friendly(e); const cur = job.steps.find((s) => s.state === "doing"); if (cur) cur.state = "failed"; job.done = true; }
}

// Update mode: new companion code for an existing family. Their data and parent key are not touched (engine.updateWorker).
const UPDATE_STEPS = [["check", "Looking at your companion"], ["find", "Finding the newest version"], ["upload", "Installing the new version"], ["wait", "Waiting for it to start"], ["verify", "Checking your data is still there"]];
async function saved(name) { const j = JSON.parse(await readFile(`${DIR}/family-${name}.json`, "utf8")); return { name: j.name, account: j.account, host: j.host, parentKey: j.parentKey }; }
async function startUpdate({ token, name }) {
  job = { kind: "update", steps: UPDATE_STEPS.map(([id, text]) => ({ id, text, state: "todo" })), done: false };
  const mark = (id) => { let hit = false; for (const s of job.steps) { if (s.id === id) { s.state = "doing"; hit = true; } else if (!hit) s.state = "done"; } };
  try {
    const f = await saved(name), r = await updateWorker({ token, family: f, base: process.env.WORKER_BASE, progress: (id) => mark(id) });
    if (!r.upToDate) { mark("verify"); const v = await verifyFamily({ host: f.host, parentKey: f.parentKey, base: process.env.WORKER_BASE }); r.phones = v.phones; }
    job.steps.forEach((s) => (s.state = "done")); job.result = r; family = { name: f.name, account: f.account, host: f.host, adminToken: f.parentKey }; job.done = true;
  } catch (e) { job.error = friendly(e); const cur = job.steps.find((s) => s.state === "doing"); if (cur) cur.state = "failed"; job.done = true; }
}

async function families() {
  try { return (await Promise.all((await readdir(DIR)).filter((f) => /^family-.*\.json$/.test(f)).map(async (f) => { const j = JSON.parse(await readFile(`${DIR}/${f}`, "utf8")); return { name: j.name, host: j.host }; }))); } catch { return []; }
}

// What the parent typed for an email account, cleaned (spaces in app passwords are ignored for Google; the Worker validates the rest).
function mailInput(b) {
  const address = String(b.address ?? "").trim(), password = String(b.password ?? "");
  if (!address || !password) return { error: "Please enter the email address and the app password." };
  const gmail = /@(gmail|googlemail)\.com$/i.test(address), user = String(b.user ?? "").trim() || address;
  return { address, user, password: gmail ? password.replace(/\s+/g, "") : password.trim(), imap: { host: String(b.imap?.host ?? "").trim().toLowerCase(), port: Number(b.imap?.port), security: b.imap?.security ?? "ssl" }, smtp: { host: String(b.smtp?.host ?? "").trim().toLowerCase(), port: Number(b.smtp?.port), security: b.smtp?.security ?? "ssl" } };
}
const readBody = (req) => new Promise((res) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => res(b)); });
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const hostOk = /^(127\.0\.0\.1|localhost):\d+$/.test(req.headers.host ?? "");
  const send = (code, type, body) => { res.writeHead(code, { "content-type": type, "cache-control": "no-store", "content-security-policy": "default-src 'self'; style-src 'unsafe-inline'; img-src data:", "x-content-type-options": "nosniff" }); res.end(body); };
  const json = (code, obj) => send(code, "application/json", JSON.stringify(obj));
  if (!hostOk) return send(403, "text/plain", "forbidden");
  if (req.method === "GET" && url.pathname === "/") return send(200, "text/html; charset=utf-8", (await readFile(new URL("./ui/index.html", import.meta.url), "utf8")).replace("__SESSION__", session));
  if (req.method === "GET" && url.pathname === "/app.js") return send(200, "text/javascript", await readFile(new URL("./ui/app.js", import.meta.url), "utf8"));
  if (req.headers["x-session"] !== session) return json(403, { error: "forbidden" });
  const body = req.method === "POST" ? JSON.parse((await readBody(req)) || "{}") : {};
  const p = url.pathname;
  try {
    if (req.method === "POST" && p === "/run") { if (job && !job.done && !job.needAccount) return json(409, { error: "already running" }); if (typeof body.token !== "string" || body.token.length < 20) return json(400, { error: "Please paste the key from Cloudflare." }); start(body); return json(200, {}); }
    if (req.method === "GET" && p === "/status") return json(200, job ?? { steps: [], done: false });
    if (req.method === "GET" && p === "/update-info") {   // no key needed: asks each saved companion for its version and finds the newest one
      let best = null; try { best = await pickBundle({}); } catch { /* offline: the list still shows versions */ }
      const out = []; for (const f of await families()) out.push({ name: f.name, host: f.host, current: (await currentVersion(f.host, { base: process.env.WORKER_BASE }))?.version ?? null, latest: best?.info.version ?? null, source: best?.source ?? null });
      return json(200, out);
    }
    if (req.method === "POST" && p === "/update") { if (job && !job.done) return json(409, { error: "already running" }); if (typeof body.token !== "string" || body.token.length < 20) return json(400, { error: "Please paste the key from Cloudflare first." }); if (!/^[\w-]+$/.test(String(body.name))) return json(400, { error: "Unknown companion." }); startUpdate({ token: body.token, name: body.name }); return json(202, { started: true }); }
    if (req.method === "POST" && p === "/check-key") {   // does Cloudflare accept the pasted key, and which account will hold the companion?
      if (typeof body.token !== "string" || body.token.trim().length < 20) return json(400, { error: "Paste the key from Cloudflare first." });
      try { const call = cf(body.token.trim()), accounts = await listAccounts(call); if (!accounts.length) return json(200, { ok: false, error: "That key works but cannot see any Cloudflare account. Create it again with the button on this page." }); return json(200, { ok: true, accounts: accounts.map((x) => x.name) }); }
      catch (e) { return json(200, { ok: false, error: friendly(e) }); }
    }
    if (req.method === "GET" && p === "/families") return json(200, await families());
    if (req.method === "POST" && p === "/resume") {
      const f = (await readdir(DIR).catch(() => [])).find((n) => n === `family-${body.name}.json`); if (!f || !/^[\w-]+$/.test(body.name)) return json(404, { error: "That setup was not found on this computer." });
      const j = JSON.parse(await readFile(`${DIR}/${f}`, "utf8")); family = { name: j.name, account: j.account, host: j.host, adminToken: j.parentKey };
      const alive = await fetch(`${base()}/api/info`).then((r) => r.ok, () => false);
      return alive ? json(200, { host: family.host }) : json(502, { error: "That companion did not answer. It may have been deleted from Cloudflare." });
    }
    if (!family) return json(409, { error: "Set up or resume first." });
    if (req.method === "GET" && p === "/info") return json(200, { host: family.host, link: parentLink(), welcomeLink: parentLink("&welcome=1"), parentQr: await QRCode.toString(parentLink(), { type: "svg", margin: 1 }), redirectUri: `https://${family.host}/spotify/callback`, guide: `https://${family.host}/guide`, waGuide: `https://${family.host}/guide#whatsapp`, waQr: await QRCode.toString(`https://${family.host}/guide#whatsapp`, { type: "svg", margin: 1 }) });
    if (req.method === "POST" && p === "/enrol") { const e = await newEnrolment(family.host, family.adminToken, fetch, base()); return json(200, { address: e.typed.address, code: e.typed.code, expires: e.expires, qr: await QRCode.toString(e.qr, { type: "svg", margin: 1 }) }); }
    if (req.method === "GET" && p === "/pairings") { const r = await wk("/pairings"); return json(r.status, r.ok ? r.body.map(({ id, model, app, created }) => ({ id, model, app, created })) : r.body); }   // the 4 digits are NOT passed on: the parent types what the phone shows
    if (req.method === "POST" && p === "/approve") { const r = await wk(`/pairings/${encodeURIComponent(body.id)}/confirm`, "POST", { match: String(body.match ?? "") }); return json(r.status, r.body); }
    if (req.method === "GET" && p === "/devices") { const r = await wk("/devices"); return json(r.status, r.body); }
    if (req.method === "POST" && p === "/spotify-client") {
      if (!/^[0-9a-f]{32}$/i.test(body.clientId ?? "")) return json(400, { error: "A Client ID is 32 letters and numbers." });
      const r = await wk("/options", "POST", { spotify_client_id: body.clientId.trim().toLowerCase() }); if (!r.ok) return json(r.status, r.body);
      return json(200, { link: parentLink(`&spotify=${encodeURIComponent(body.did)}`) });
    }
    if (req.method === "POST" && p === "/whatsapp-allowed") {
      const allowed = (Array.isArray(body.allowed) ? body.allowed : []).map((x) => String(x).trim()).filter(Boolean).slice(0, 200);
      const r = await wk(`/devices/${encodeURIComponent(body.did)}/child`, "POST", { patch: { whatsapp: { all: Boolean(body.all), allowed } } }); return json(r.status, r.body);
    }
    if (req.method === "GET" && p === "/providers") return json(200, { providers: PROVIDERS, unsupported: UNSUPPORTED.map((u) => ({ pattern: u.match.source, flags: u.match.flags, why: u.why })) });
    if (req.method === "POST" && p === "/email-test") { const a = mailInput(body); if (a.error) return json(400, { error: a.error }); const [imap, smtp] = await Promise.all([hooks.testImap({ ...a.imap, user: a.user, password: a.password }), hooks.testSmtp({ ...a.smtp, user: a.user, password: a.password })]); return json(200, { imap, smtp }); }
    if (req.method === "POST" && p === "/email") {
      const a = mailInput(body); if (a.error) return json(400, { error: a.error });
      const k = await wk(`/devices/${encodeURIComponent(body.did)}/enckey`); if (!k.ok) return json(k.status, k.body);
      const sealedPassword = await seal({ recipientSpki: k.body.encKey, did: body.did, name: "email.password", plaintext: a.password });
      const r = await wk(`/devices/${encodeURIComponent(body.did)}/email`, "POST", { email: { address: a.address, user: a.user, imap: a.imap, smtp: a.smtp }, sealedPassword }); return json(r.status, r.body);
    }
    if (req.method === "POST" && p === "/email-clear") { const r = await wk(`/devices/${encodeURIComponent(body.did)}/email/clear`, "POST", {}); return json(r.status, r.body); }
    if (req.method === "POST" && p === "/calendar-check") return json(200, await hooks.checkCalendarUrl(body.url));
    if (req.method === "POST" && p === "/calendar-add") {
      const c = await hooks.checkCalendarUrl(body.url); if (!c.ok) return json(400, { error: c.message });
      const name = String(body.name ?? c.name ?? "").trim().slice(0, 40); if (!name) return json(400, { error: "Please give the calendar a name." });
      const k = await wk(`/devices/${encodeURIComponent(body.did)}/enckey`); if (!k.ok) return json(k.status, k.body);
      const id = crypto.randomBytes(4).toString("hex");
      const sealedUrl = await seal({ recipientSpki: k.body.encKey, did: body.did, name: "calendar." + id, plaintext: c.url });
      const r = await wk(`/devices/${encodeURIComponent(body.did)}/calendar/add`, "POST", { id, name, sealedUrl }); return json(r.status, r.ok ? { ...r.body, name, events: c.events } : r.body);
    }
    if (req.method === "POST" && p === "/calendar-remove") { const r = await wk(`/devices/${encodeURIComponent(body.did)}/calendar/remove`, "POST", { id: body.id }); return json(r.status, r.body); }
    if (req.method === "GET" && p === "/accounts") { const r = await wk(`/devices/${encodeURIComponent(url.searchParams.get("did") ?? "")}/view`); return json(r.status, r.ok ? { email: r.body.email, email_password_set: r.body.email_password_set, calendars: r.body.calendars, mail: r.body.device?.state?.mail ?? null, calendarStatus: r.body.device?.state?.calendars ?? null } : r.body); }
    if (req.method === "GET" && p === "/spotify-status") {
      const did = encodeURIComponent(url.searchParams.get("did") ?? ""), c = await wk(`/devices/${did}/config`), v = await wk(`/devices/${did}/view`);
      // is the Spotify app on the child's phone? The phone lists the apps it has (and newer builds say so in its state); null = not known yet
      const apps = v.ok && Array.isArray(v.body.apps) && v.body.apps.length ? v.body.apps : null, flag = v.ok ? v.body.device?.state?.spotifyInstalled : undefined;
      const appInstalled = typeof flag === "boolean" ? flag : apps ? (apps.some((a) => a.pkg === "com.spotify.music") || (v.body.pending_apps ?? []).some((a) => a.pkg === "com.spotify.music")) : null;
      return json(200, { connected: Boolean(c.ok && c.body.config?.secrets?.["spotify.refresh"]), clientId: c.ok ? c.body.config?.spotifyClientId ?? "" : "", appInstalled });
    }
  } catch (e) { return json(500, { error: e.message }); }
  send(404, "text/plain", "not found");
});

const port = Number(process.env.PORT ?? 0);
server.listen(port, "127.0.0.1", () => {
  const u = `http://127.0.0.1:${server.address().port}/`;
  console.log(`Phone Guard setup is open at ${u}`);
  if (!process.env.NO_OPEN) { const cmd = process.platform === "darwin" ? "open" : process.platform === "win32" ? "cmd" : "xdg-open"; spawn(cmd, process.platform === "win32" ? ["/c", "start", u] : [u], { stdio: "ignore", detached: true }).on("error", () => {}).unref(); }
});
export { server, session };
