// Wizard API against a LOCAL Worker (start it first: cd ../worker && rm -rf .wrangler/state && npm run dev). Plays the child's phone with node:crypto.
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
const W = process.env.WORKER_BASE ?? "http://127.0.0.1:8787";
const dir = await fs.mkdtemp(os.tmpdir() + "/pgwiz-");
import http from "node:http";
// A fake Cloudflare and GitHub for the update mode (set before the server module loads: it reads these when it starts).
const mock = http.createServer((req, res) => {
  const out = (o) => { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(o)); };
  const u = req.url, base = `http://127.0.0.1:${mock.address().port}`;
  if (u === "/rel") return out({ tag_name: "v0.0.1", html_url: "https://github.com/x/y/releases/tag/v0.0.1", assets: [{ name: "worker-version.json", browser_download_url: base + "/worker-version.json" }, { name: "worker.bundle.js", browser_download_url: base + "/worker.bundle.js" }] });
  if (u === "/worker-version.json" || u === "/direct/worker-version.json") return out(MOCKINFO);
  if (u === "/worker.bundle.js" || u === "/direct/worker.bundle.js") { res.writeHead(200); return res.end(MOCKCODE); }
  if (u.startsWith("/client/v4/accounts?")) { if (String(req.headers.authorization).includes("goodkey")) return out({ success: true, result: [{ id: "a", name: "Family account" }] }); res.writeHead(403, { "content-type": "application/json" }); return res.end(JSON.stringify({ success: false, errors: [{ code: 9109 }] })); }
  if (/^\/client\/v4\/accounts\/a\/workers\/scripts\/testfam\/settings$/.test(u)) return out({ success: true, result: {} });
  res.writeHead(404, { "content-type": "application/json" }); res.end(JSON.stringify({ success: false, errors: [{ code: 10007 }] }));
});
await new Promise((r) => mock.listen(0, "127.0.0.1", r));
const MOCKCODE = "// the same worker as the one running", MOCKINFO = { version: JSON.parse(await fs.readFile(new URL("../dist/worker-version.json", import.meta.url), "utf8")).version, schema: 3, protocol: 1, sha256: crypto.createHash("sha256").update(MOCKCODE).digest("hex"), size: MOCKCODE.length };
process.env.CF_API = `http://127.0.0.1:${mock.address().port}/client/v4`; process.env.PG_RELEASES_URL = `http://127.0.0.1:${mock.address().port}/rel`; process.env.PG_RELEASE_DIRECT = `http://127.0.0.1:${mock.address().port}/direct/`;
await fs.writeFile(`${dir}/family-gonefam.json`, JSON.stringify({ name: "gonefam", account: "a", host: "gone.example.workers.dev", parentKey: "x" }));
await fs.writeFile(`${dir}/family-testfam.json`, JSON.stringify({ name: "testfam", account: "a", host: "wiz.example.workers.dev", parentKey: "dev-admin-token" }));
process.env.NO_OPEN = "1"; process.env.WORKER_BASE = W; process.env.PHONEGUARD_DIR = dir;
const { server, session, hooks, hooksSpotify } = await import("../server.mjs");
import { openSealed } from "../../worker/test/helpers/open-sealed.mjs";
await new Promise((r) => (server.listening ? r() : server.once("listening", r)));
const B = `http://127.0.0.1:${server.address().port}`;
const api = (p, body) => fetch(B + p, { method: body ? "POST" : "GET", headers: { "x-session": session, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ status: r.status, body: await r.json() }));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

assert.equal((await api("/info")).status, 409); ok("nothing works before a setup is chosen");
assert.deepEqual((await api("/families")).body.map((f) => f.name).sort(), ["gonefam", "testfam"]); ok("saved setups are listed without their keys");
assert.equal((await api("/resume", { name: "../etc/passwd" })).status, 404); ok("resume refuses odd names");
assert.equal((await api("/resume", { name: "testfam" })).status, 200); ok("resume loads a saved setup and checks the companion answers");
{ const ui = (await api("/update-info")).body; const t = ui.find((f) => f.name === "testfam"); assert.equal(t.current, MOCKINFO.version); assert.equal(t.latest, MOCKINFO.version); assert.equal(ui.length, 2); ok("update mode lists each saved companion with its version and the newest version, without needing the Cloudflare key"); }
{ assert.equal((await api("/check-key", { token: "short" })).status, 400); const g = (await api("/check-key", { token: "goodkey-".padEnd(40, "x") })).body; assert.deepEqual([g.ok, g.accounts], [true, ["Family account"]]); const bad = (await api("/check-key", { token: "wrongkey-".padEnd(40, "x") })).body; assert.equal(bad.ok, false); assert.match(bad.error, /did not accept that key/); ok("the pasted Cloudflare key is checked before installing: the account it can use is shown, a wrong key is explained"); }
assert.equal((await api("/update", { name: "testfam", token: "short" })).status, 400); assert.equal((await api("/update", { name: "../x", token: "x".repeat(30) })).status, 400); ok("an update needs the key and a sane name");
{ assert.equal((await api("/update", { name: "testfam", token: "x".repeat(40) })).status, 202); let j; for (let i = 0; i < 50; i++) { j = (await api("/status")).body; if (j.done) break; await new Promise((r) => setTimeout(r, 100)); }
  assert.equal(j.error, undefined); assert.equal(j.result.upToDate, true); assert.deepEqual(j.steps.map((x) => x.state).filter((x) => x === "failed"), []); ok("updating a companion that is already current says so and changes nothing");
  assert.equal((await api("/update", { name: "gonefam", token: "x".repeat(40) })).status, 202); for (let i = 0; i < 50; i++) { j = (await api("/status")).body; if (j.done) break; await new Promise((r) => setTimeout(r, 100)); }
  assert.match(j.error, /no companion called gonefam/); ok("a key from the wrong account reports it plainly"); }
assert.equal((await api("/resume", { name: "testfam" })).status, 200);
const info = (await api("/info")).body; assert.match(info.link, /^https:\/\/wiz\.example\.workers\.dev\/#k=/); assert.equal(info.redirectUri, "https://wiz.example.workers.dev/spotify/callback"); assert.match(info.parentQr, /^<svg/); ok("parent link, QR and Spotify redirect URI are built from the host");

const e = (await api("/enrol", {})).body; assert.match(e.code, /^[A-Z0-9]{5}-[A-Z0-9]{5}$/); assert.match(e.qr, /^<svg/); ok("new enrolment code and QR");
const kp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" }), pub = kp.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const pr = await fetch(W + "/api/pair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ token: e.code, pub, encPub: pub, model: "Qin J36", app: "0.3.17", nonce: "abcd1234abcd1234" }) }).then((r) => r.json());
const list = (await api("/pairings")).body; assert.equal(list.length, 1); assert.equal(list[0].model, "Qin J36"); assert.ok(!("match" in list[0])); ok("a waiting phone is listed WITHOUT its 4 digits");
assert.equal((await api("/approve", { id: list[0].id, match: "0000" })).status, 409); ok("wrong digits refused");
const ap = (await api("/approve", { id: list[0].id, match: pr.match })).body; assert.match(ap.recoveryKey, /^[A-Z0-9]{4}(-[A-Z0-9]{4}){5}$/); assert.ok(ap.did); ok("right digits approve the phone and return the recovery key once");
assert.equal((await api("/devices")).body.length, 1); ok("the phone appears in the devices list");

assert.equal((await api("/spotify-client", { did: ap.did, clientId: "short" })).status, 400); ok("a bad Spotify Client ID is refused");
const cid = "a1b2c3d4e5f60718293a4b5c6d7e8f90"; const sp = (await api("/spotify-client", { did: ap.did, clientId: cid })).body;
assert.match(sp.link, new RegExp(`#k=.+&spotify=${ap.did}$`)); ok("Client ID saved and a deep link to the parent page's Spotify box returned");
const st = (await api(`/spotify-status?did=${ap.did}`)).body; assert.deepEqual(st, { connected: false, clientId: cid, appInstalled: null }); ok("status says not connected yet, echoes the saved Client ID, and says the Spotify app on the phone is not known yet");
assert.match(info.waGuide, /\/guide#whatsapp$/); assert.match(info.waQr, /^<svg/); ok("the WhatsApp guide link and its QR are built from the host");
assert.equal((await api("/whatsapp-allowed", { did: ap.did, all: false, allowed: [" 447700900111 ", "g:Family", ""] })).status, 200);
const waCfg = (await fetch(W + `/api/devices/${ap.did}/config`, { headers: { authorization: "Bearer dev-admin-token" } }).then((r) => r.json())).config.whatsapp; assert.deepEqual(waCfg.allowed, ["447700900111", "g:Family"]); assert.equal(waCfg.all, false); ok("who WhatsApp may talk to is saved from the wizard and reaches the phone's config");

// ---- email and calendars (the live checks are replaced: they would talk to real mail servers and calendars)
const calls = []; hooks.testImap = async (a) => { calls.push(["imap", a]); return a.password === "good" ? { ok: true, message: "Signed in." } : { ok: false, message: "The server did not accept that address and password." }; };
hooks.testSmtp = async (a) => { calls.push(["smtp", a]); return { ok: true, message: "Signed in." }; };
hooks.checkCalendarUrl = async (u) => (/ical/.test(u) ? { ok: true, url: String(u).replace(/^webcal/, "https"), name: "Family", events: 12, message: "Found a calendar with 12 events." } : { ok: false, message: "That link did not give a calendar." });
const provs = (await api("/providers")).body; assert.ok(provs.providers.some((x) => x.id === "gmail" && x.imap.host === "imap.gmail.com") && provs.unsupported.length === 1); ok("the provider presets and the not-yet-supported list are served");
const gm = { did: ap.did, address: "kid@gmail.com", password: "abcd efgh ijkl mnop", imap: { host: "imap.gmail.com", port: 993, security: "ssl" }, smtp: { host: "smtp.gmail.com", port: 465, security: "ssl" } };
assert.equal((await api("/email-test", { ...gm, password: "" })).status, 400); const t1 = (await api("/email-test", { ...gm, password: "good" })).body; assert.ok(t1.imap.ok && t1.smtp.ok); assert.equal(calls[0][1].password, "good"); ok("the login check runs for both incoming and outgoing mail"); 
await api("/email-test", gm); assert.equal(calls.at(-1)[1].password, "abcdefghijklmnop"); ok("spaces in a Google app password are ignored");
assert.equal((await api("/email", { ...gm, address: "bad" })).status, 400); const es = await api("/email", gm); assert.equal(es.status, 200);
const wcfg = async () => (await fetch(W + `/api/devices/${ap.did}/config`, { headers: { authorization: "Bearer dev-admin-token" } }).then((r) => r.json())).config; const phonePriv = kp.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
let c1 = await wcfg(); assert.equal(c1.email.address, "kid@gmail.com"); assert.equal(c1.email.secret, "email.password"); assert.equal(openSealed({ encPrivatePkcs8: phonePriv, did: ap.did, name: "email.password", sealed: c1.secrets["email.password"] }), "abcdefghijklmnop"); assert.ok(!JSON.stringify(c1).includes("abcdefgh")); ok("the account reaches the phone's config and its password is sealed so only the phone's key opens it");
const acc = (await api(`/accounts?did=${ap.did}`)).body; assert.equal(acc.email.address, "kid@gmail.com"); assert.equal(acc.email_password_set, true); assert.ok(!JSON.stringify(acc).includes("abcdefgh")); ok("the wizard can show the saved account without ever seeing the password again");
assert.equal((await api("/calendar-add", { did: ap.did, name: "x", url: "https://not.a.calendar/page" })).status, 400); const ca = await api("/calendar-add", { did: ap.did, url: "webcal://cal.example.com/ical/secret-123/basic.ics" }); assert.equal(ca.status, 200); assert.equal(ca.body.name, "Family"); assert.equal(ca.body.events, 12);
c1 = await wcfg(); assert.equal(c1.calendars.length, 1); const slot = c1.calendars[0].secret; assert.equal(openSealed({ encPrivatePkcs8: phonePriv, did: ap.did, name: slot, sealed: c1.secrets[slot] }), "https://cal.example.com/ical/secret-123/basic.ics"); assert.ok(!JSON.stringify(c1.calendars).includes("secret-123")); ok("a calendar is added by name; its private link is sealed to the phone (as https) and checked first");
await api("/calendar-add", { did: ap.did, name: "School terms", url: "https://cal.example.com/ical/terms.ics" }); assert.equal((await api(`/accounts?did=${ap.did}`)).body.calendars.length, 2); ok("more than one calendar can be added");
const rm = (await api(`/accounts?did=${ap.did}`)).body.calendars[0].id; assert.equal((await api("/calendar-remove", { did: ap.did, id: rm })).status, 200); c1 = await wcfg(); assert.equal(c1.calendars.length, 1); assert.ok(!c1.secrets["calendar." + rm]); ok("removing a calendar removes its sealed link");
assert.equal((await api("/email-clear", { did: ap.did })).status, 200); assert.ok(!(await wcfg()).email); ok("clearing email removes the account and its password");
{ process.env.PG_ADB_FAKE = "1"; const seen = []; hooksSpotify.adb = async () => ({ ok: true, out: "" });
  hooksSpotify.run = async ({ say, approve, unapprove }) => { say("Checking the phone"); await approve(["com.aurora.store", "com.spotify.music"]); const v = await wcfg(); seen.push([...(v.modes?.day?.allow ?? [])]); say("Removing Aurora Store"); await unapprove(["com.aurora.store"]); return { installed: true }; };
  assert.equal((await api("/spotify-install", { did: "../x" })).status, 400); assert.equal((await api("/spotify-install", { did: ap.did })).status, 202);
  let j; for (let i = 0; i < 50; i++) { j = (await api("/spotify-install-status")).body; if (j.done) break; await new Promise((r) => setTimeout(r, 100)); }
  assert.deepEqual(j.steps, ["Checking the phone", "Removing Aurora Store"]); assert.equal(j.result.installed, true); const after = await wcfg();
  assert.ok(seen[0].includes("com.spotify.music") && seen[0].includes("com.aurora.store")); assert.ok(after.modes.day.allow.includes("com.spotify.music") && !after.modes.day.allow.includes("com.aurora.store")); ok("the wizard's Spotify install runs the installer, approves Aurora and Spotify on the companion for Day mode, and takes Aurora's approval back afterwards");
  hooksSpotify.run = async () => { throw Object.assign(new Error("The phone is not on Wi-Fi."), { name: "StepError" }); }; assert.equal((await api("/spotify-install", { did: ap.did })).status, 202); for (let i = 0; i < 50; i++) { j = (await api("/spotify-install-status")).body; if (j.done) break; await new Promise((r) => setTimeout(r, 100)); }
  assert.ok(j.error); ok("a failure in the install is reported to the page, not hidden"); delete process.env.PG_ADB_FAKE; }
assert.equal((await fetch(B + "/pairings")).status, 403); ok("every wizard call needs the session key");
server.close(); mock.close(); console.log(`\n${n} checks passed`);
