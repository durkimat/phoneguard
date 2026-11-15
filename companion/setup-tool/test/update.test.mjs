// The update path of the setup engine, against a fake Cloudflare, GitHub and Worker (no network).
import assert from "node:assert/strict";
import crypto from "node:crypto";
process.env.PG_RELEASES_URL = "https://api.github.com/latest";   // read when the engine is loaded
const { currentVersion, releaseBundle, pickBundle, updateWorker, verifyFamily } = await import("../engine.mjs");

let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const sha = (b) => crypto.createHash("sha256").update(b).digest("hex");
const bundle = (version, schema = 3) => { const code = `// worker ${version}`; return { code, info: { version, schema, protocol: 1, sha256: sha(code), size: Buffer.byteLength(code), notes: "n" }, source: "this computer" }; };
const FAM = { account: "A1", name: "phoneguard-abc123", host: "phoneguard-abc123.x.workers.dev", parentKey: "KEY" };

// One fake world: a Worker that reports a version (and changes it when the code is uploaded), a Cloudflare API and GitHub.
const world = ({ version = "0.5.0", schema = 3, exists = true, release = null, direct = false, reportsAfter = null, noVersionRoute = false, devices = 2, keyOk = true } = {}) => {
  const w = { version, uploads: [], gets: [] };
  w.fetch = async (url, init = {}) => {
    const u = String(url); w.gets.push(u);
    if (u.includes("api.cloudflare.com")) {
      const ok2 = (result) => ({ ok: true, status: 200, json: async () => ({ success: true, result }) });
      if (u.endsWith("/settings")) return exists ? ok2({}) : { ok: false, status: 404, json: async () => ({ success: false, errors: [{ code: 10007 }] }) };
      if (init.method === "PUT" && /\/workers\/scripts\/[\w-]+$/.test(u)) { w.uploads.push({ metadata: JSON.parse(init.body.get("metadata") instanceof Blob ? "{}" : init.body.get("metadata")), form: init.body }); w.version = reportsAfter ?? w.pendingVersion ?? w.version; return ok2({}); }
      if (u.endsWith("/subdomain")) return ok2({ enabled: true });
    }
    if (u.endsWith("/api/version")) return noVersionRoute ? { ok: false, status: 404, json: async () => ({}) } : { ok: true, status: 200, json: async () => ({ version: w.version, schema: w.codeSchema ?? schema, protocol: 1, data_schema: schema, ...(w.storageLag ? { storage_version: w.storageLag(w) } : {}) }) };
    if (u.endsWith("/api/info")) return { ok: true, status: 200, json: async () => ({ v: 1 }) };
    if (u.endsWith("/api/devices")) return keyOk && init.headers?.authorization === "Bearer KEY" ? { ok: true, status: 200, json: async () => Array.from({ length: devices }, (_, i) => ({ did: "d" + i })) } : { ok: false, status: 401, json: async () => ({}) };
    if (u.startsWith("https://github.com/durkimat/phoneguard/releases/latest/download/") && !(release && direct)) return { ok: false, status: 404, json: async () => ({}) };
    if (release && u === "https://api.github.com/latest") return { ok: true, status: 200, json: async () => release.json };
    if (release && u.endsWith("worker-version.json")) return { ok: true, status: 200, json: async () => release.info };
    if (release && u.endsWith("worker.bundle.js")) return { ok: true, status: 200, arrayBuffer: async () => Buffer.from(release.code) };
    return { ok: false, status: 404, json: async () => ({}) };
  };
  return w;
};
const mkRelease = (version, { tamper = false, withCompanion = true } = {}) => { const b = bundle(version); const files = ["phoneguard-9.apk", ...(withCompanion ? ["worker.bundle.js", "worker-version.json"] : [])]; return { json: { tag_name: "v9.9.9", html_url: "https://github.com/x/y/releases/tag/v9.9.9", assets: files.map((name) => ({ name, size: 5, browser_download_url: "https://dl/" + name })) }, info: b.info, code: tamper ? b.code + " tampered" : b.code }; };

{ const w = world(); assert.deepEqual(await currentVersion("h", { fetchImpl: w.fetch }), { version: "0.5.0", schema: 3, codeSchema: 3, dataSchema: 3, storage: null });
  assert.deepEqual(await currentVersion("h", { fetchImpl: world({ noVersionRoute: true }).fetch }), { version: "0.0.0", schema: null }); assert.equal(await currentVersion("h", { fetchImpl: async () => { throw new Error("down"); } }), null); ok("a Worker's version is read; one made before versions existed counts as 0.0.0; an unreachable one is null"); }
{ const w = world({ release: mkRelease("0.6.0") }); const b = await releaseBundle({ fetchImpl: w.fetch, url: "https://api.github.com/latest" }); assert.equal(b.info.version, "0.6.0"); assert.equal(b.code, "// worker 0.6.0"); assert.match(b.source, /release v9\.9\.9/);
  await assert.rejects(releaseBundle({ fetchImpl: world({ release: mkRelease("0.6.0", { tamper: true }) }).fetch, url: "https://api.github.com/latest" }), /checksum/); assert.equal(await releaseBundle({ fetchImpl: world({ release: mkRelease("0.6.0", { withCompanion: false }) }).fetch, url: "https://api.github.com/latest" }), null); ok("a release's companion is downloaded and checked against its sha256; a tampered download is refused; a release without one is skipped"); }
{ const w = world({ release: mkRelease("0.6.0"), direct: true }); const b = await releaseBundle({ fetchImpl: w.fetch, url: "https://api.github.com/latest" }); assert.equal(b.info.version, "0.6.0"); assert.ok(!w.gets.some((u) => u.includes("api.github.com")), "the API was not needed"); assert.ok(w.gets.some((u) => u.includes("/releases/latest/download/worker-version.json")));
  await assert.rejects(releaseBundle({ fetchImpl: world({ release: mkRelease("0.6.0", { tamper: true }), direct: true }).fetch, url: "https://api.github.com/latest" }), /checksum/); ok("the companion is fetched through GitHub's download link without touching the rate-limited API; a tampered file is still refused"); }
{ const rel = world({ release: mkRelease("0.6.0") }).fetch, norel = world().fetch;
  assert.equal((await pickBundle({ fetchImpl: rel, local: bundle("0.5.0") })).info.version, "0.6.0"); assert.equal((await pickBundle({ fetchImpl: rel, local: bundle("0.7.0") })).info.version, "0.7.0"); assert.equal((await pickBundle({ fetchImpl: rel, local: bundle("0.6.0") })).source, "this computer");
  assert.equal((await pickBundle({ source: "local", fetchImpl: rel, local: bundle("0.5.0") })).info.version, "0.5.0"); assert.equal((await pickBundle({ source: "release", fetchImpl: rel, local: bundle("0.9.0") })).info.version, "0.6.0");
  assert.equal((await pickBundle({ fetchImpl: norel, local: bundle("0.5.0") })).info.version, "0.5.0"); assert.equal((await pickBundle({ fetchImpl: async () => { throw new Error("offline"); }, local: bundle("0.5.0") })).info.version, "0.5.0"); await assert.rejects(pickBundle({ fetchImpl: norel, local: null }), /GitHub answered 404|No companion code/); ok("the newer of the published release and this computer's build is used; either can be forced; offline falls back to this computer"); }

{ const w = world({ version: "0.5.0" }); w.pendingVersion = "0.6.0"; const steps = []; const r = await updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), progress: (s) => steps.push(s), sleep: async () => {}, base: "https://" + FAM.host });
  assert.deepEqual([r.from, r.to], ["0.5.0", "0.6.0"]); assert.equal(w.uploads.length, 1); const md = JSON.parse(await new Response(w.uploads[0].form.get("metadata")).text());
  assert.deepEqual(md.keep_bindings, ["secret_text"]); assert.equal(md.migrations, undefined); assert.ok(!md.bindings.some((b) => b.type === "secret_text")); assert.deepEqual(md.bindings, [{ type: "durable_object_namespace", name: "FAMILY", class_name: "Family" }]); assert.deepEqual(steps, ["check", "find", "upload", "wait"]);
  const v = await verifyFamily({ host: FAM.host, parentKey: "KEY", fetchImpl: w.fetch }); assert.equal(v.phones, 2); ok("an update uploads the new code and keeps the parent key and the data: no new secret, no migration entry, secrets kept; the new version is waited for and checked"); }
{ const w = world({ version: "0.5.0" }); w.pendingVersion = "0.6.0"; let polls = 0; w.storageLag = (x) => (++polls < 3 ? "0.5.0" : x.version); const r = await updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), sleep: async () => {}, base: "https://" + FAM.host }); assert.equal(r.to, "0.6.0"); assert.ok(polls >= 3); ok("the update waits until the database part of the Worker has switched to the new code too, not only the front door"); }
{ const w = world({ version: "0.5.0" }); w.pendingVersion = "0.6.0"; w.storageLag = () => "0.5.0"; const r = await updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), sleep: async () => {}, tries: 3, base: "https://" + FAM.host }); assert.equal(r.to, "0.6.0"); assert.equal(r.settling, true); ok("if the database part is slow to switch, the update still succeeds and says it is settling, instead of reporting an error"); }
{ const w = world({ version: "0.6.0" }); const r = await updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), sleep: async () => {}, base: "https://" + FAM.host }); assert.equal(r.upToDate, true); assert.equal(w.uploads.length, 0);
  const w2 = world({ version: "0.9.0" }); assert.equal((await updateWorker({ token: "T", family: FAM, fetchImpl: w2.fetch, local: bundle("0.6.0"), sleep: async () => {}, base: "https://" + FAM.host })).upToDate, true); assert.equal(w2.uploads.length, 0); ok("an equal or newer companion is left alone (nothing is uploaded, so an old tool cannot downgrade it)");
  const w3 = world({ version: "0.6.0" }); w3.pendingVersion = "0.6.0"; const f = await updateWorker({ token: "T", family: FAM, fetchImpl: w3.fetch, local: bundle("0.6.0"), force: true, sleep: async () => {}, base: "https://" + FAM.host }); assert.equal(f.to, "0.6.0"); assert.equal(w3.uploads.length, 1); ok("--force reinstalls the same version"); }
{ const w = world({ version: "0.5.0", schema: 5 }); await assert.rejects(updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0", 3), sleep: async () => {}, base: "https://" + FAM.host }), /data is newer/); assert.equal(w.uploads.length, 0); ok("code whose schema is older than the family's data is refused before anything is uploaded"); }
{ const w = world({ version: "0.5.0", schema: 4 }); w.codeSchema = 3; await assert.rejects(updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.5.0", 3), force: true, sleep: async () => {}, base: "https://" + FAM.host }), /data is newer \(schema 4\)/); assert.equal(w.uploads.length, 0); ok("old code stuck on newer data (the Worker reports its code at schema 3 but its data at 4) is still refused, even with --force"); }
{ const w = world({ exists: false }); await assert.rejects(updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), base: "https://" + FAM.host }), /no companion called phoneguard-abc123/); assert.equal(w.uploads.length, 0); ok("a key from the wrong Cloudflare account says so and uploads nothing"); }
{ const w = world({ version: "0.5.0", reportsAfter: "0.5.0" }); await assert.rejects(updateWorker({ token: "T", family: FAM, fetchImpl: w.fetch, local: bundle("0.6.0"), sleep: async () => {}, tries: 3, base: "https://" + FAM.host }), /still reports 0\.5\.0/); ok("if the new version does not start, the parent is told, not left guessing"); }
{ const w = world({ version: "0.4.1", noVersionRoute: true }); w.pendingVersion = "0.5.0"; w.fetch0 = w.fetch; const f = async (u, i) => { const r = await w.fetch(u, i); return r; };
  // a Worker made before versions existed: /api/version 404 until the upload, then answers
  let uploaded = false; const g = async (u, i = {}) => { if (String(u).endsWith("/api/version")) return uploaded ? { ok: true, status: 200, json: async () => ({ version: "0.5.0", schema: 3 }) } : { ok: false, status: 404, json: async () => ({}) }; if (i.method === "PUT" && !String(u).includes("subdomain")) uploaded = true; return w.fetch(u, i); };
  const r = await updateWorker({ token: "T", family: FAM, fetchImpl: g, local: bundle("0.5.0"), sleep: async () => {}, base: "https://" + FAM.host }); assert.deepEqual([r.from, r.to], ["0.0.0", "0.5.0"]); ok("a companion made before versions existed is updated to the first versioned release"); }
await assert.rejects(verifyFamily({ host: FAM.host, parentKey: "KEY", fetchImpl: world({ keyOk: false }).fetch }), /parent key was not accepted/); ok("if the parent key stopped working after an update, it is reported");
console.log(`\n${n} checks passed`);
