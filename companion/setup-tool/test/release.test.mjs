// The release plan: what stops a bad release before anything is published.
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const { plan } = await import("../release.mjs");
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const dir = mkdtempSync(join(tmpdir(), "pgrel-")), apk = join(dir, "phoneguard-0.4.3-release.apk"); writeFileSync(apk, "apk bytes");
const info = (version, sha = "a".repeat(64), schema = 3) => ({ version, schema, protocol: 1, sha256: sha, size: 10 });
const base = { apk, notes: "Fixes.", bundleInfo: info("0.5.0"), bundleCode: "code", published: { info: info("0.5.0") } };

{ const r = await plan(base); assert.deepEqual(r.problems, []); assert.equal(r.tag, "v0.4.3"); assert.deepEqual(r.files.map((f) => f.name), ["worker.bundle.js", "worker-version.json", "phoneguard-0.4.3-release.apk", "phoneguard-0.4.3-release.apk.sha256", "release.json"]); assert.match(r.files[3].data.toString(), /^[0-9a-f]{64}  phoneguard-0\.4\.3-release\.apk\n$/); assert.equal(JSON.parse(r.files[1].data).notes, "Fixes."); const rj = JSON.parse(r.files[4].data); assert.equal(rj.tag, "v0.4.3"); assert.equal(rj.apk.url, "https://github.com/durkimat/phoneguard/releases/download/v0.4.3/phoneguard-0.4.3-release.apk"); assert.equal(rj.apk.sha256, r.files[3].data.toString().slice(0, 64)); assert.equal(rj.companion.version, "0.5.0"); ok("a normal release is the app, its sha256 file, the companion bundle and its version file, and release.json that summarises them; the tag comes from the APK name"); }
assert.deepEqual((await plan({ ...base, published: null })).problems, []); ok("the very first release (nothing published yet) is fine");
{ const r = await plan({ ...base, bundleInfo: info("0.5.0", "b".repeat(64)) }); assert.match(r.problems.join(), /changed but its version is still 0\.5\.0/); ok("changed companion code with an unchanged version is stopped (the update would never be offered)"); }
{ const r = await plan({ ...base, bundleInfo: info("0.4.0") }); assert.match(r.problems.join(), /0\.5\.0 is already published/); ok("an older companion than the published one is stopped"); }
{ const r = await plan({ ...base, bundleInfo: info("0.6.0", "c".repeat(64), 2), published: { info: info("0.5.0", "a".repeat(64), 3) } }); assert.match(r.problems.join(), /schema went down/); ok("a schema that goes down is stopped"); }
assert.deepEqual((await plan({ ...base, bundleInfo: info("0.6.0", "c".repeat(64), 4) })).problems, []); ok("a newer version with a newer schema goes through");
assert.match((await plan({ ...base, apk: join(dir, "app.apk") })).problems.join(), /should be called phoneguard-X\.Y\.Z-release\.apk/); assert.match((await plan({ ...base, tag: "v9.9.9" })).problems.join(), /does not match the APK name/); assert.match((await plan({ ...base, apk: join(dir, "phoneguard-1.0.0-release.apk") })).problems.join(), /was not found/); ok("the APK name, the tag and the file are checked");
assert.match((await plan({ ...base, tagExists: true })).problems.join(), /already exists/); ok("a tag that already exists is stopped");
{ const r = await plan({ ...base, apk: undefined, companionOnly: true, tag: "v0.5.1" }); assert.deepEqual(r.problems, []); assert.deepEqual(r.files.map((f) => f.name), ["worker.bundle.js", "worker-version.json", "release.json"]); assert.match((await plan({ ...base, apk: undefined, companionOnly: true })).problems.join(), /--tag is needed/); ok("a companion-only release carries just the companion and needs an explicit tag"); }
console.log(`\n${n} checks passed`);
