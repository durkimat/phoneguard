// Setup engine: everything the tool does on the parent's Cloudflare account, using only api.cloudflare.com and the Worker's own API.
// No flashing in v1. The UI (local page) and the CLI both call these functions.
import { readFile } from "node:fs/promises";
import crypto from "node:crypto";
import { cmpVer, parseRelease, parseCompanionInfo } from "../worker/src/hub/store.js";

export const WORKER_FILE = new URL("./dist/worker.bundle.js", import.meta.url);
export const WORKER_INFO = new URL("./dist/worker-version.json", import.meta.url);
export const RELEASE_DIRECT = process.env.PG_RELEASE_DIRECT ?? "https://github.com/durkimat/phoneguard/releases/latest/download/";   // overridden only by the tests
export const RELEASES = process.env.PG_RELEASES_URL ?? "https://api.github.com/repos/durkimat/phoneguard/releases/latest";
const API = process.env.CF_API ?? "https://api.cloudflare.com/client/v4";   // overridden only by the tests

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

export async function newEnrolment(host, adminToken, fetchImpl = fetch, base = `https://${host}`) {
  const r = await fetchImpl(`${base}/api/enrol/new`, { method: "POST", headers: { Authorization: `Bearer ${adminToken}` } });
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


// ---- updating a family's companion without touching its data -----------------------------------------------------------------------
// The family's data lives in the Worker's Durable Object and its parent key is a secret binding. Uploading new code with
// keep_bindings leaves both alone; new database steps run by themselves the first time the new code starts (worker/src/migrations.js).

/** What a Worker reports at /api/version. A Worker made before versions existed answers /api/info but not /api/version: it is reported as "0.0.0" so it counts as updatable. */
export async function currentVersion(host, { fetchImpl = fetch, base = `https://${host}` } = {}) {
  try {
    const r = await fetchImpl(`${base}/api/version`); if (r.ok) { const j = await r.json(); if (/^\d+\.\d+\.\d+$/.test(String(j.version))) return { version: j.version, schema: Math.max(j.schema ?? 0, j.data_schema ?? 0) || null, codeSchema: j.schema ?? null, dataSchema: j.data_schema ?? null, storage: j.storage_version ?? null }; }
    const i = await fetchImpl(`${base}/api/info`); if (i.ok) return { version: "0.0.0", schema: null };
  } catch { /* not reachable */ }
  return null;
}

/** The bundle built on this computer (npm run bootstrap), with its version file. */
export async function localBundle() {
  try { const info = JSON.parse(await readFile(WORKER_INFO, "utf8")); return { code: await readFile(WORKER_FILE, "utf8"), info, source: "this computer" }; } catch { return null; }
}

/** The newest release's companion bundle from GitHub, checked against the size and sha256 in its worker-version.json. Null if the newest release carries none. */
export async function releaseBundle({ fetchImpl = fetch, url = RELEASES, direct = RELEASE_DIRECT } = {}) {
  const head = { "user-agent": "phoneguard-setup", accept: "application/vnd.github+json" };
  let infoUrl = direct + "worker-version.json", bundleUrl = direct + "worker.bundle.js", tag = null, html_url = "";
  let ir = await fetchImpl(infoUrl, { headers: head }).catch(() => null);   // the download link first: no API rate limit
  if (!ir?.ok) {   // then the API (older releases, or the download link not answering)
    const r = await fetchImpl(url, { headers: head }); if (!r.ok) throw new Error(`GitHub answered ${r.status} when looking for the newest release.`);
    const rel = parseRelease(await r.json()); if (!rel?.companion_url) return null;
    infoUrl = rel.companion_url; bundleUrl = infoUrl.replace(/worker-version\.json$/, "worker.bundle.js"); tag = rel.tag; html_url = rel.html_url;
    ir = await fetchImpl(infoUrl, { headers: head });
  }
  const info = parseCompanionInfo(await ir.json().catch(() => null)); if (!info || !info.sha256) throw new Error("The newest release's companion file is not valid. Nothing was changed.");
  const b = await fetchImpl(bundleUrl, { headers: head }); if (!b.ok) throw new Error(`Downloading the companion from the release failed (${b.status}).`);
  const code = Buffer.from(await b.arrayBuffer());
  if ((info.size && code.length !== info.size) || crypto.createHash("sha256").update(code).digest("hex") !== info.sha256) throw new Error("The downloaded companion did not match its checksum. Nothing was changed; try again.");
  return { code: code.toString("utf8"), info, source: tag ? `release ${tag}` : "the newest release", tag, html_url };
}

/** Picks the newer of the two sources (this computer wins a tie). `source` can force one: "release" or "local". */
export async function pickBundle({ source = "auto", fetchImpl = fetch, local: given } = {}) {
  const local = source === "release" ? null : given !== undefined ? given : await localBundle();
  let rel = null, relError = null;
  if (source !== "local") { try { rel = await releaseBundle({ fetchImpl }); } catch (e) { relError = e; if (source === "release") throw e; } }
  if (!local && !rel) throw relError ?? new Error("No companion code was found. Run npm run bootstrap, or check your internet connection.");
  if (!local) return rel; if (!rel) return local;
  return cmpVer(rel.info.version, local.info.version) > 0 ? rel : local;
}

/** Updates one family's Worker. Returns {upToDate} or {from, to, host, source}. Never touches the data or the parent key. */
export async function updateWorker({ token, family, source = "auto", force = false, local, progress = () => {}, fetchImpl = fetch, base, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), tries = 30 }) {
  const call = cf(token, fetchImpl), root = base ?? `https://${family.host}`;
  progress("check", "Looking at your companion");
  if (!(await scriptExists(call, family.account, family.name))) throw new Error(`There is no companion called ${family.name} in this Cloudflare account. Check you used the key from the same account.`);
  const now = await currentVersion(family.host, { fetchImpl, base: root });
  progress("find", "Finding the newest version");
  const b = await pickBundle({ source, fetchImpl, ...(local !== undefined ? { local } : {}) });
  if (now && now.schema != null && b.info.schema != null && now.schema > b.info.schema) throw new Error(`Your companion's data is newer (schema ${now.schema}) than this update (schema ${b.info.schema}). Nothing was changed. Use a newer setup tool.`);
  if (now && !force && cmpVer(b.info.version, now.version) <= 0) return { upToDate: true, version: now.version, host: family.host };
  progress("upload", `Installing version ${b.info.version}`);
  await uploadWorker(call, { account: family.account, name: family.name, code: b.code, exists: true });
  progress("wait", "Waiting for the new version to start");
  let seen = null;   // the Worker script and its database object switch to the new code a few seconds apart, so wait for both
  for (let i = 0; i < tries; i++) { seen = await currentVersion(family.host, { fetchImpl, base: root }); if (seen && seen.version === b.info.version && (seen.storage == null || seen.storage === b.info.version)) break; await sleep(2000); }
  if (!seen || seen.version !== b.info.version || (seen.storage != null && seen.storage !== b.info.version)) throw new Error(`The new version was uploaded but the companion still reports ${seen?.version ?? "nothing"}. Cloudflare can take a minute: check your parent page, and run the update again if it still shows the old version.`);
  return { from: now?.version ?? null, to: b.info.version, host: family.host, source: b.source, schema: seen.schema, notes: b.info.notes ?? "" };
}

/** After an update: does the parent key still work, and is the data still there? Returns {phones}. */
export async function verifyFamily({ host, parentKey, fetchImpl = fetch, base = `https://${host}` }) {
  const r = await fetchImpl(`${base}/api/devices`, { headers: { authorization: `Bearer ${parentKey}` } });
  if (!r.ok) throw new Error(`After the update the parent key was not accepted (HTTP ${r.status}).`);
  return { phones: (await r.json()).length };
}

/** Companions in this Cloudflare account made by this tool (workers called phoneguard-xxxxxx), for when the family file is not on this computer. */
export async function discoverWorkers(call, account) {
  const list = await call(`/accounts/${account}/workers/scripts`), sub = (await call(`/accounts/${account}/workers/subdomain`)).subdomain;
  return list.filter((x) => /^phoneguard-[0-9a-f]{6}$/.test(x.id)).map((x) => ({ account, name: x.id, host: `${x.id}.${sub}.workers.dev` }));
}
