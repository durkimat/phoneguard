// Local setup wizard: runs on 127.0.0.1 only, opens in the parent's browser, drives engine.mjs and the parent's own Worker.
// Nothing is sent anywhere except to Cloudflare and the parent's own Worker.
import http from "node:http";
import crypto from "node:crypto";
import { readFile, writeFile, mkdir, readdir } from "node:fs/promises";
import { homedir } from "node:os";
import { spawn } from "node:child_process";
import QRCode from "qrcode";
import { setUp, CfError, newEnrolment, waitUntilLive } from "./engine.mjs";

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

async function families() {
  try { return (await Promise.all((await readdir(DIR)).filter((f) => /^family-.*\.json$/.test(f)).map(async (f) => { const j = JSON.parse(await readFile(`${DIR}/${f}`, "utf8")); return { name: j.name, host: j.host }; }))); } catch { return []; }
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
    if (req.method === "GET" && p === "/families") return json(200, await families());
    if (req.method === "POST" && p === "/resume") {
      const f = (await readdir(DIR).catch(() => [])).find((n) => n === `family-${body.name}.json`); if (!f || !/^[\w-]+$/.test(body.name)) return json(404, { error: "That setup was not found on this computer." });
      const j = JSON.parse(await readFile(`${DIR}/${f}`, "utf8")); family = { name: j.name, account: j.account, host: j.host, adminToken: j.parentKey };
      const alive = await fetch(`${base()}/api/info`).then((r) => r.ok, () => false);
      return alive ? json(200, { host: family.host }) : json(502, { error: "That companion did not answer. It may have been deleted from Cloudflare." });
    }
    if (!family) return json(409, { error: "Set up or resume first." });
    if (req.method === "GET" && p === "/info") return json(200, { host: family.host, link: parentLink(), parentQr: await QRCode.toString(parentLink(), { type: "svg", margin: 1 }), redirectUri: `https://${family.host}/spotify/callback`, guide: `https://${family.host}/guide` });
    if (req.method === "POST" && p === "/enrol") { const e = await newEnrolment(family.host, family.adminToken, fetch, base()); return json(200, { address: e.typed.address, code: e.typed.code, expires: e.expires, qr: await QRCode.toString(e.qr, { type: "svg", margin: 1 }) }); }
    if (req.method === "GET" && p === "/pairings") { const r = await wk("/pairings"); return json(r.status, r.ok ? r.body.map(({ id, model, app, created }) => ({ id, model, app, created })) : r.body); }   // the 4 digits are NOT passed on: the parent types what the phone shows
    if (req.method === "POST" && p === "/approve") { const r = await wk(`/pairings/${encodeURIComponent(body.id)}/confirm`, "POST", { match: String(body.match ?? "") }); return json(r.status, r.body); }
    if (req.method === "GET" && p === "/devices") { const r = await wk("/devices"); return json(r.status, r.body); }
    if (req.method === "POST" && p === "/spotify-client") {
      if (!/^[0-9a-f]{32}$/i.test(body.clientId ?? "")) return json(400, { error: "A Client ID is 32 letters and numbers." });
      const r = await wk("/options", "POST", { spotify_client_id: body.clientId.trim().toLowerCase() }); if (!r.ok) return json(r.status, r.body);
      return json(200, { link: parentLink(`&spotify=${encodeURIComponent(body.did)}`) });
    }
    if (req.method === "GET" && p === "/spotify-status") { const c = await wk(`/devices/${encodeURIComponent(url.searchParams.get("did") ?? "")}/config`); return json(200, { connected: Boolean(c.ok && c.body.config?.secrets?.["spotify.refresh"]), clientId: c.ok ? c.body.config?.spotifyClientId ?? "" : "" }); }
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
