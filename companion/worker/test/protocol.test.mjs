// Conformance test: plays the PHONE (Android-style keys: SPKI public key, DER signatures, via node:crypto, independent of the Worker's WebCrypto code)
// and the PARENT (bearer token). Usage: BASE=http://127.0.0.1:8787 ADMIN=dev-admin-token node test/protocol.test.mjs
import crypto from "node:crypto";
import assert from "node:assert/strict";
import { seal } from "../src/seal.js";
import { openSealed } from "./helpers/open-sealed.mjs";

const BASE = process.env.BASE ?? "http://127.0.0.1:8787", ADMIN = process.env.ADMIN ?? "dev-admin-token";
let passed = 0; const ok = (name) => { passed++; console.log("  ok  " + name); };

const api = async (method, path, { body, parent } = {}) => {
  const r = await fetch(BASE + path, { method, headers: { "content-type": "application/json", ...(parent ? { authorization: `Bearer ${parent === true ? ADMIN : parent}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, body: await r.json().catch(() => ({})) };
};

// --- the phone ---
const kp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const pubB64 = kp.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const encKp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const encPubB64 = encKp.publicKey.export({ type: "spki", format: "der" }).toString("base64");
const encPrivB64 = encKp.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
const sign = (msg) => crypto.sign("sha256", Buffer.from(msg), { key: kp.privateKey, dsaEncoding: "der" }).toString("base64");
const verifyServer = (pubSpki, msg, sigB64) => crypto.verify("sha256", Buffer.from(msg), { key: crypto.createPublicKey({ key: Buffer.from(pubSpki, "base64"), format: "der", type: "spki" }), dsaEncoding: "der" }, Buffer.from(sigB64, "base64"));
const matchNumber = (token, pub) => { const h = crypto.createHash("sha256").update(`${token}:${pub}`).digest(); return String(h.readUInt32BE(0)).slice(-4).padStart(4, "0"); };

console.log("Pairing");
const info = (await api("GET", "/api/info")).body;
assert.equal(info.v, 1); assert.ok(info.serverKey); ok("server key published");

assert.equal((await api("POST", "/api/enrol/new")).status, 401); ok("parent endpoints need the parent token");
assert.equal((await api("POST", "/api/enrol/new", { parent: "wrong" })).status, 401); ok("wrong parent token rejected");

const enrol = (await api("POST", "/api/enrol/new", { parent: true })).body;
assert.equal(enrol.token.length, 10); assert.equal(enrol.serverKey, info.serverKey); ok("parent creates a one-time token (10 chars, carries the server key)");

const nonce = crypto.randomBytes(12).toString("hex");
const badTok = await api("POST", "/api/pair", { body: { token: "AAAAAAAAAA", pub: pubB64, nonce } });
assert.equal(badTok.status, 403); ok("unknown token rejected");

const pr = await api("POST", "/api/pair", { body: { token: enrol.token, pub: pubB64, encPub: encPubB64, model: "Qin J36", app: "0.4.0", nonce } });
assert.equal(pr.status, 200); const phoneMatch = matchNumber(enrol.token, pubB64);
assert.equal(pr.body.match, phoneMatch); ok(`phone and server derive the same 4-digit number (${phoneMatch})`);

assert.equal((await api("GET", `/api/pair/${pr.body.pairingId}`)).body.status, "pending"); ok("pairing stays pending until the parent confirms");
const pend = (await api("GET", "/api/pairings", { parent: true })).body;
assert.equal(pend.length, 1); assert.equal(pend[0].model, "Qin J36"); ok("parent sees the pending phone");

assert.equal((await api("POST", `/api/pairings/${pr.body.pairingId}/confirm`, { parent: true, body: { match: "0000" === phoneMatch ? "1111" : "0000" } })).status, 409); ok("wrong match number refused");
const conf = (await api("POST", `/api/pairings/${pr.body.pairingId}/confirm`, { parent: true, body: { match: phoneMatch } })).body;
assert.match(conf.did, /^d[a-z0-9]{10}$/); assert.match(conf.recoveryKey, /^([A-Z0-9]{4}-){5}[A-Z0-9]{4}$/); ok("parent confirms; device id and one-time recovery key issued");

const done = (await api("GET", `/api/pair/${pr.body.pairingId}`)).body;
assert.equal(done.status, "confirmed");
const r = done.reply;
assert.equal(r.n, nonce); assert.equal(r.did, conf.did);
assert.ok(verifyServer(info.serverKey, `${r.v}|${r.did}|${r.n}|${r.t}|${r.p}`, r.s)); ok("phone verifies the server's signed reply (nonce echoed)");
const first = JSON.parse(r.p);
assert.equal(first.recoveryHash, crypto.createHash("sha256").update(conf.recoveryKey).digest("hex")); ok("phone receives only the recovery key's hash");
assert.equal(first.configVersion, 1);

assert.equal((await api("POST", "/api/pair", { body: { token: enrol.token, pub: pubB64, nonce } })).status, 403); ok("token is single-use (second pairing refused)");

console.log("Sync");
let ctr = 0;
const sync = async (state, { c = ++ctr, signWith = sign, did = conf.did } = {}) => {
  const n = crypto.randomBytes(12).toString("hex"), p = JSON.stringify(state);
  const res = await api("POST", "/api/sync", { body: { v: 1, did, ctr: c, n, p, s: signWith(`1|${did}|${c}|${n}|${p}`) } });
  return { ...res, n };
};
const s1 = await sync({ battery: 83, mode: "day", app: "0.4.0" });
assert.equal(s1.status, 200); assert.equal(s1.body.n, s1.n);
assert.ok(verifyServer(info.serverKey, `1|${s1.body.did}|${s1.body.n}|${s1.body.t}|${s1.body.p}`, s1.body.s)); ok("signed sync works; reply signed and nonce-bound");

assert.equal((await sync({ x: 1 }, { c: 1 })).status, 409); ok("replayed counter rejected");
assert.equal((await sync({ x: 1 }, { c: 0 })).status, 409); ok("old counter rejected");
const other = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
assert.equal((await sync({ x: 1 }, { c: 50, signWith: (m) => crypto.sign("sha256", Buffer.from(m), { key: other.privateKey, dsaEncoding: "der" }).toString("base64") })).status, 403); ok("signature from another key rejected");
assert.equal((await sync({ x: 1 }, { c: 51, did: "dnotadevice" })).status, 403); ok("unknown device rejected");
assert.equal((await sync({ x: 1 })).status, 200); ok("counter may skip ahead (phone persists it before sending)");

console.log("Config");
const cfgPost = await api("POST", `/api/devices/${conf.did}/config`, { parent: true, body: { base: "day", modes: { day: { allowAll: true }, school: { name: "School", allow: ["io.github.durkimat.phoneguard"], sound: "silent" } }, override: "school" } });
assert.equal(cfgPost.body.version, 2); ok("parent changes the config (version 2)");
const s2 = await sync({ battery: 80 });
const p2 = JSON.parse(s2.body.p); assert.equal(p2.configVersion, 2); assert.equal(p2.config.override, "school"); assert.equal(p2.config.version, 2); ok("phone picks it up on the next sync (server set the version)");
const devs = (await api("GET", "/api/devices", { parent: true })).body;
assert.equal(devs[0].state.battery, 80); assert.ok(devs[0].last_seen > 0); ok("parent sees last-seen and reported state");

for (const [name, bad] of [["no modes", { modes: {} }], ["bad sound value", { modes: { day: { sound: "loud" } } }], ["mode id with capitals", { modes: { Day: {} } }], ["bad radio url", { modes: { day: {} }, radioStations: [{ name: "x", url: "ftp://x" }] }], ["malformed sealed secret", { modes: { day: {} }, secrets: { "spotify.refresh": { alg: "none" } } }]]) {
  const r = await api("POST", `/api/devices/${conf.did}/config`, { parent: true, body: bad });
  assert.equal(r.status, 400, name); assert.ok(r.body.problems.length > 0);
}
ok("invalid profiles rejected with readable problems (no modes, bad enum, bad mode id, bad URL, malformed secret)");
assert.equal((await api("GET", "/api/devices", { parent: true })).body[0].version, 2); ok("a rejected profile does not bump the version");

const sU = await sync({ battery: 78, cfgVersion: 3 });
const pU = JSON.parse(sU.body.p); assert.equal(pU.unchanged, true); assert.equal(pU.config, undefined); ok("a phone that already has the current version gets 'unchanged' (no profile sent)");
const sOld = await sync({ battery: 77, cfgVersion: 1 });
assert.ok(JSON.parse(sOld.body.p).config); ok("an older phone version gets the full profile");

console.log("Sealed credentials");
const encKey = (await api("GET", `/api/devices/${conf.did}/enckey`, { parent: true })).body.encKey;
assert.equal(encKey, encPubB64); ok("parent can fetch the phone's encryption key (separate from its signing key)");
const secret = "spotify-refresh-token-AQBq9f...long-value-1234567890";
const sealed = await seal({ recipientSpki: encKey, did: conf.did, name: "spotify.refresh", plaintext: secret });
const saved = await api("POST", `/api/devices/${conf.did}/config`, { parent: true, body: { base: "day", modes: { day: { allowAll: true } }, secrets: { "spotify.refresh": sealed } } });
assert.equal(saved.status, 200); ok("parent saves a profile whose secret is sealed in the browser");
const s3 = await sync({ battery: 79 });
const got = JSON.parse(s3.body.p).config.secrets["spotify.refresh"];
assert.ok(!JSON.stringify(s3.body).includes("AQBq9f")); ok("the plaintext never appears in what the Worker stores or relays");
assert.equal(openSealed({ encPrivatePkcs8: encPrivB64, did: conf.did, name: "spotify.refresh", sealed: got }), secret); ok("phone (node:crypto) opens it and gets the exact secret");
assert.throws(() => openSealed({ encPrivatePkcs8: encPrivB64, did: "dsomeoneelse", name: "spotify.refresh", sealed: got })); ok("cannot be replayed onto another device");
assert.throws(() => openSealed({ encPrivatePkcs8: encPrivB64, did: conf.did, name: "email.password", sealed: got })); ok("cannot be moved to another secret slot");
const flipped = { ...got, ct: Buffer.from(got.ct, "base64").map((b, i) => i === 3 ? b ^ 1 : b).toString("base64") };
assert.throws(() => openSealed({ encPrivatePkcs8: encPrivB64, did: conf.did, name: "spotify.refresh", sealed: flipped })); ok("any tampering with the ciphertext is detected");

console.log("Parent page");
const page = await fetch(BASE + "/"); const pageText = await page.text();
assert.equal(page.status, 200); assert.match(page.headers.get("content-type"), /text\/html/); assert.match(pageText, /<script type="module" src="\/app\.js">/); ok("the parent page is served at /");
assert.match(page.headers.get("content-security-policy"), /default-src 'self'/); assert.equal(page.headers.get("referrer-policy"), "no-referrer"); assert.equal(page.headers.get("cache-control"), "no-store"); ok("page has CSP, no-referrer and no-store headers");
for (const f of ["/app.js", "/timetable.js"]) { const r = await fetch(BASE + f); assert.equal(r.status, 200); assert.match(r.headers.get("content-type"), /javascript/); } ok("page scripts are served as JavaScript");
const g = await fetch(BASE + "/guide"); assert.equal(g.status, 200); assert.match(await g.text(), /How Phone Guard works/); ok("the how-it-works guide is served at /guide");
assert.equal((await fetch(BASE + "/nope")).status, 404); ok("unknown paths are 404");
assert.equal((await api("GET", `/api/devices/${conf.did}/config`)).status, 401); ok("reading a phone's settings needs the parent token");
const gc = (await api("GET", `/api/devices/${conf.did}/config`, { parent: true })).body; assert.ok(gc.version >= 1 && gc.config.modes); ok("parent can read a phone's current settings and version");
assert.equal((await api("GET", "/api/devices/dnosuchdevice/config", { parent: true })).status, 404); ok("unknown phone -> 404");

console.log("Token limits");
const e2 = (await api("POST", "/api/enrol/new", { parent: true })).body;
for (let i = 0; i < 3; i++) await api("POST", "/api/pair", { body: { token: e2.token, pub: "not-a-key", nonce } });
assert.equal((await api("POST", "/api/pair", { body: { token: e2.token, pub: pubB64, nonce } })).status, 429); ok("a token allows only a few tries");

console.log(`\n${passed} checks passed`);
