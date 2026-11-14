// Wizard API against a LOCAL Worker (start it first: cd ../worker && rm -rf .wrangler/state && npm run dev). Plays the child's phone with node:crypto.
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
const W = process.env.WORKER_BASE ?? "http://127.0.0.1:8787";
const dir = await fs.mkdtemp(os.tmpdir() + "/pgwiz-");
await fs.writeFile(`${dir}/family-testfam.json`, JSON.stringify({ name: "testfam", account: "a", host: "wiz.example.workers.dev", parentKey: "dev-admin-token" }));
process.env.NO_OPEN = "1"; process.env.WORKER_BASE = W; process.env.PHONEGUARD_DIR = dir;
const { server, session } = await import("../server.mjs");
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
assert.equal((await fetch(B + "/pairings")).status, 403); ok("every wizard call needs the session key");
server.close(); console.log(`\n${n} checks passed`);
