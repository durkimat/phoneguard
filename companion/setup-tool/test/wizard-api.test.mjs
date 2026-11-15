// Wizard API against a LOCAL Worker (start it first: cd ../worker && rm -rf .wrangler/state && npm run dev). Plays the child's phone with node:crypto.
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
const W = process.env.WORKER_BASE ?? "http://127.0.0.1:8787";
const dir = await fs.mkdtemp(os.tmpdir() + "/pgwiz-");
await fs.writeFile(`${dir}/family-testfam.json`, JSON.stringify({ name: "testfam", account: "a", host: "wiz.example.workers.dev", parentKey: "dev-admin-token" }));
process.env.NO_OPEN = "1"; process.env.WORKER_BASE = W; process.env.PHONEGUARD_DIR = dir;
const { server, session, hooks } = await import("../server.mjs");
import { openSealed } from "../../worker/test/helpers/open-sealed.mjs";
await new Promise((r) => (server.listening ? r() : server.once("listening", r)));
const B = `http://127.0.0.1:${server.address().port}`;
const api = (p, body) => fetch(B + p, { method: body ? "POST" : "GET", headers: { "x-session": session, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ status: r.status, body: await r.json() }));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

assert.equal((await api("/info")).status, 409); ok("nothing works before a setup is chosen");
assert.deepEqual((await api("/families")).body, [{ name: "testfam", host: "wiz.example.workers.dev" }]); ok("saved setups are listed without their keys");
assert.equal((await api("/resume", { name: "../etc/passwd" })).status, 404); ok("resume refuses odd names");
assert.equal((await api("/resume", { name: "testfam" })).status, 200); ok("resume loads a saved setup and checks the companion answers");
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
const st = (await api(`/spotify-status?did=${ap.did}`)).body; assert.deepEqual(st, { connected: false, clientId: cid }); ok("status says not connected yet and echoes the saved Client ID");
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
assert.equal((await fetch(B + "/pairings")).status, 403); ok("every wizard call needs the session key");
server.close(); console.log(`\n${n} checks passed`);
