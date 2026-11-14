// Local setup page: runs on 127.0.0.1 only, opens in the parent's browser, drives engine.mjs. Nothing is sent anywhere except to Cloudflare and the parent's own Worker.
import http from "node:http";
import crypto from "node:crypto";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { spawn } from "node:child_process";
import QRCode from "qrcode";
import { setUp, CfError } from "./engine.mjs";

const session = crypto.randomBytes(16).toString("hex");           // stops other web pages in the browser from calling this local server
let job = null;                                                   // one setup at a time
const STEPS = [["account", "Checking your Cloudflare account"], ["subdomain", "Preparing your address"], ["upload", "Installing the companion"], ["wait", "Waiting for it to come online (this can take two minutes)"], ["enrol", "Making the code for the child's phone"]];

const friendly = (e) => e instanceof CfError && [401, 403].includes(e.status) ? "Cloudflare did not accept that key. Please check it was created with the link on this page (Workers Scripts: Edit)." : e.message;

async function start({ token, account }) {
  job = { steps: STEPS.map(([id, text]) => ({ id, text, state: "todo" })), done: false };
  const mark = (id) => { let hit = false; for (const s of job.steps) { if (s.id === id) { s.state = "doing"; hit = true; } else if (!hit) s.state = "done"; } };
  try {
    const r = await setUp({ token, account, progress: (id) => mark(id) });
    if (r.needAccount) { job = { needAccount: r.needAccount, steps: [], done: false }; return; }
    job.steps.forEach((s) => (s.state = "done"));
    const link = `https://${r.host}/#k=${r.adminToken}`;
    const dir = `${homedir()}/.phoneguard`; await mkdir(dir, { recursive: true, mode: 0o700 });
    await writeFile(`${dir}/family-${r.name}.json`, JSON.stringify({ account: r.account, name: r.name, host: r.host, parentKey: r.adminToken }, null, 2), { mode: 0o600 });
    job.result = { host: r.host, link, address: r.typed.address, code: r.typed.code, expires: r.expires, phoneQr: await QRCode.toString(r.qr, { type: "svg", margin: 1 }), parentQr: await QRCode.toString(link, { type: "svg", margin: 1 }), saved: `${dir}/family-${r.name}.json` };
    job.done = true;
  } catch (e) { job.error = friendly(e); const cur = job.steps.find((s) => s.state === "doing"); if (cur) cur.state = "failed"; job.done = true; }
}

const readBody = (req) => new Promise((res) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => res(b)); });
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  const hostOk = /^(127\.0\.0\.1|localhost):\d+$/.test(req.headers.host ?? "");
  const send = (code, type, body) => { res.writeHead(code, { "content-type": type, "cache-control": "no-store", "content-security-policy": "default-src 'self'; style-src 'unsafe-inline'; img-src data:", "x-content-type-options": "nosniff" }); res.end(body); };
  if (!hostOk) return send(403, "text/plain", "forbidden");
  if (req.method === "GET" && url.pathname === "/") return send(200, "text/html; charset=utf-8", (await readFile(new URL("./ui/index.html", import.meta.url), "utf8")).replace("__SESSION__", session));
  if (req.method === "GET" && url.pathname === "/app.js") return send(200, "text/javascript", await readFile(new URL("./ui/app.js", import.meta.url), "utf8"));
  if (req.headers["x-session"] !== session) return send(403, "application/json", '{"error":"forbidden"}');
  if (req.method === "POST" && url.pathname === "/run") { if (job && !job.done && !job.needAccount) return send(409, "application/json", '{"error":"already running"}'); const b = JSON.parse((await readBody(req)) || "{}"); if (typeof b.token !== "string" || b.token.length < 20) return send(400, "application/json", '{"error":"Please paste the key from Cloudflare."}'); start(b); return send(200, "application/json", "{}"); }
  if (req.method === "GET" && url.pathname === "/status") return send(200, "application/json", JSON.stringify(job ?? { steps: [], done: false }));
  send(404, "text/plain", "not found");
});

const port = Number(process.env.PORT ?? 0);
server.listen(port, "127.0.0.1", () => {
  const u = `http://127.0.0.1:${server.address().port}/`;
  console.log(`Phone Guard setup is open at ${u}`);
  if (!process.env.NO_OPEN) { const cmd = process.platform === "darwin" ? "open" : process.platform === "win32" ? "cmd" : "xdg-open"; spawn(cmd, process.platform === "win32" ? ["/c", "start", u] : [u], { stdio: "ignore", detached: true }).on("error", () => {}).unref(); }
});
export { server, session };
