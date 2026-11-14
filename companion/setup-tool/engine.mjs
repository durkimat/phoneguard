// Setup engine: everything the tool does on the parent's Cloudflare account, using only api.cloudflare.com and the Worker's own API.
// No flashing in v1. The UI (local page) and the CLI both call these functions.
import { readFile } from "node:fs/promises";
import crypto from "node:crypto";

export const WORKER_FILE = new URL("./dist/worker.bundle.js", import.meta.url);
const API = "https://api.cloudflare.com/client/v4";

export class CfError extends Error { constructor(path, status, errors) { super(`${path}: HTTP ${status} ${JSON.stringify(errors)}`); this.status = status; this.errors = errors; } }

export function cf(token, fetchImpl = fetch) {
  return async (path, init = {}) => {
    const r = await fetchImpl(API + path, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) } });
    const j = await r.json().catch(() => ({}));
    if (!j.success) throw new CfError(path, r.status, j.errors ?? j);
    return j.result;
  };
}

export const listAccounts = (call) => call("/accounts?per_page=50").then(a => a.map(({ id, name }) => ({ id, name })));

// The account's workers.dev subdomain, created on first use (Cloudflare requires one before any Worker is reachable).
export async function ensureSubdomain(call, account, wanted) {
  try { return (await call(`/accounts/${account}/workers/subdomain`)).subdomain; }
  catch (e) { if (!(e instanceof CfError) || ![404, 400].includes(e.status)) throw e; }
  return (await call(`/accounts/${account}/workers/subdomain`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ subdomain: wanted }) })).subdomain;
}

export const scriptExists = (call, account, name) => call(`/accounts/${account}/workers/scripts/${name}/settings`).then(() => true, e => { if (e instanceof CfError && e.status === 404) return false; throw e; });

// Upload (or update) the Worker. A first upload carries the Durable Object migration and a fresh ADMIN_TOKEN secret; an update keeps both.
export async function uploadWorker(call, { account, name, code, adminToken, exists }) {
  const metadata = { main_module: "worker.js", compatibility_date: "2025-09-01" };
  metadata.bindings = [{ type: "durable_object_namespace", name: "FAMILY", class_name: "Family" }];
  if (exists) metadata.keep_bindings = ["secret_text"];
  else { metadata.bindings.push({ type: "secret_text", name: "ADMIN_TOKEN", text: adminToken }); metadata.migrations = { new_tag: "v1", new_sqlite_classes: ["Family"] }; }
  const form = new FormData();
  form.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
  form.append("worker.js", new Blob([code], { type: "application/javascript+module" }), "worker.js");
  await call(`/accounts/${account}/workers/scripts/${name}`, { method: "PUT", body: form });
  await call(`/accounts/${account}/workers/scripts/${name}/subdomain`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: true }) });
}

export async function deleteWorker(call, account, name) { await call(`/accounts/${account}/workers/scripts/${name}?force=true`, { method: "DELETE" }); }

// A new workers.dev hostname can take a minute or two to get its certificate; poll until the Worker answers.
export async function waitUntilLive(host, { tries = 60, delayMs = 4000, fetchImpl = fetch, sleep = (ms) => new Promise(r => setTimeout(r, ms)) } = {}) {
  for (let i = 0; i < tries; i++) {
    try { const r = await fetchImpl(`https://${host}/api/info`); if (r.ok) return await r.json(); } catch { /* TLS not ready yet */ }
    await sleep(delayMs);
  }
  throw new Error(`The Worker at ${host} did not answer in time`);
}

const b64url = (buf) => Buffer.from(buf).toString("base64url");
export const keyFingerprint = (spkiB64) => b64url(crypto.createHash("sha256").update(Buffer.from(spkiB64, "base64")).digest().subarray(0, 16));

// Everything the parent needs to put on the child's phone: the QR text and the typed fallback (see PROTOCOL-DRAFT.md "Enrolment payload").
export function enrolPayload({ host, token, serverKey }) {
  const t = token.toUpperCase().replace(/[^A-Z0-9]/g, "");
  const text = `phoneguard://enrol?v=1&h=${host}&t=${t}&f=${keyFingerprint(serverKey)}`;
  const address = host.endsWith(".workers.dev") ? host.slice(0, -".workers.dev".length) : host;
  return { qr: text, typed: { address, code: `${t.slice(0, 5)}-${t.slice(5)}` } };
}

export async function newEnrolment(host, adminToken, fetchImpl = fetch) {
  const r = await fetchImpl(`https://${host}/api/enrol/new`, { method: "POST", headers: { Authorization: `Bearer ${adminToken}` } });
  if (!r.ok) throw new Error(`enrol/new failed: HTTP ${r.status}`);
  const j = await r.json();
  return { ...enrolPayload({ host, token: j.token, serverKey: j.serverKey }), expires: j.expires };
}

export const makeAdminToken = () => crypto.randomBytes(24).toString("base64url");
export const workerName = () => "phoneguard-" + crypto.randomBytes(3).toString("hex");

// One call for the whole job. `progress(step, text)` lets the UI show a live checklist.
export async function setUp({ token, account, name = workerName(), subdomainWanted, progress = () => {}, fetchImpl = fetch, code }) {
  const call = cf(token, fetchImpl);
  code ??= await readFile(WORKER_FILE, "utf8");
  progress("account", "Checking your Cloudflare account");
  if (!account) { const a = await listAccounts(call); if (a.length !== 1) return { needAccount: a }; account = a[0].id; }
  progress("subdomain", "Preparing your address");
  const sub = await ensureSubdomain(call, account, subdomainWanted ?? "pg-" + crypto.randomBytes(4).toString("hex"));
  progress("upload", "Installing the companion");
  const adminToken = makeAdminToken();
  const exists = await scriptExists(call, account, name);
  await uploadWorker(call, { account, name, code, adminToken, exists });
  const host = `${name}.${sub}.workers.dev`;
  progress("wait", "Waiting for it to come online (up to two minutes)");
  await waitUntilLive(host, { fetchImpl });
  progress("enrol", "Making the code for the child's phone");
  const enrol = await newEnrolment(host, adminToken, fetchImpl);
  return { account, name, host, adminToken, ...enrol };
}
