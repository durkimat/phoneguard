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

console.log("Config and parent controls");
const D = `/api/devices/${conf.did}`, post = (sub, body) => api("POST", `${D}/${sub}`, { parent: true, body });
const ov = await post("override", { mode: "school" });
assert.equal(ov.status, 200); assert.equal(ov.body.version, 2); ok("parent forces a mode (override): config version 2");
const s2 = await sync({ battery: 80 });
const p2 = JSON.parse(s2.body.p); assert.equal(p2.configVersion, 2); assert.equal(p2.config.override, "school"); assert.equal(p2.config.version, 2); assert.equal(p2.cfgh.length, 12); ok("phone picks it up on the next sync (server set the version and a hash)");
assert.deepEqual(p2.cmds, []); assert.equal(p2.boost, null); ok("sync reply carries boost (none) and commands (none)");
const devs = (await api("GET", "/api/devices", { parent: true })).body;
assert.equal(devs[0].state.battery, 80); assert.ok(devs[0].last_seen > 0); assert.equal(devs[0].override, "school"); assert.equal(devs[0].mode_now, "school"); ok("parent sees last-seen, reported state, override and the mode now");
assert.equal((await post("override", { mode: "nonsense" })).status, 400); assert.equal((await post("override", { mode: "auto" })).status, 200); ok("an unknown mode is refused; 'auto' returns to the timetable");

const b1 = await post("boost", { mode: "day", minutes: 15 }); assert.equal(b1.status, 200); assert.equal(b1.body.boost.mode, "day");
const sB = JSON.parse((await sync({ battery: 79 })).body.p); assert.equal(sB.boost.mode, "day"); assert.ok(sB.boost.until > Date.now()); ok("boost reaches the phone in the sync reply (mode and expiry)");
const b2 = await post("boost", { mode: "day", minutes: 15 }); assert.ok(b2.body.boost.until >= b1.body.boost.until + 14 * 60_000); ok("pressing boost again adds time");
assert.equal((await post("boost", { mode: "nope", minutes: 5 })).status, 400); await post("boost/cancel", {}); assert.equal(JSON.parse((await sync({ battery: 78 })).body.p).boost, null); ok("boost cancel and unknown boost mode");

assert.equal((await post("holiday", { from: "2026-12-19", until: "2027-01-04" })).status, 200); assert.equal((await api("GET", `${D}/view`, { parent: true })).body.holiday.until, "2027-01-04"); ok("holiday dates saved");
const patch = await post("child", { patch: { restrictions: ["no_config_vpn"], whatsapp: { all: false, allowed: ["+441234"] }, radio_stations: [{ name: "BBC", url: "https://x/y" }] } });
assert.equal(patch.status, 200); const cfgNow = JSON.parse((await sync({ battery: 77 })).body.p).config; assert.deepEqual(cfgNow.restrictions, ["no_config_vpn"]); assert.deepEqual(cfgNow.whatsapp.allowed, ["+441234"]); assert.equal(cfgNow.radioStations[0].name, "BBC"); ok("settings edits reach the phone's config");
const badPatch = await post("child", { patch: { week: { 1: [[0, 100, "ghost"]] } } }); assert.equal(badPatch.status, 400); assert.match(badPatch.body.error, /does not have/);
assert.equal((await post("child", { patch: { modes: { day: { wa: "weird" } } } })).status, 400); ok("invalid settings are refused with a readable reason");
const verBefore = (await api("GET", "/api/devices", { parent: true })).body[0].version; await post("child", { patch: { tz: "Mars/Olympus" } }); assert.equal((await api("GET", "/api/devices", { parent: true })).body[0].version, verBefore); ok("a refused change does not bump the version");

const sA = JSON.parse((await sync({ battery: 76, cfgVersion: 9999 }, {})).body.p); assert.equal(sA.config ?? null, null); assert.equal(sA.unchanged, true); ok("nothing changed: 'unchanged' (no config re-sent)");
const sFull1 = JSON.parse((await sync({ state: { battery: 70, version: "0.3.18", mode: "day" }, apps: [{ pkg: "a.one", label: "One" }, { pkg: "a.two", label: "Two" }], cfgh: "stale" })).body.p); assert.ok(sFull1.config); ok("full report: a different config hash gets the config");
const v1 = (await api("GET", `${D}/view`, { parent: true })).body; assert.equal(v1.apps.length, 2); assert.deepEqual(v1.pending_apps, []); assert.equal(v1.device.version, "0.3.18"); ok("the phone's apps are listed and not 'new' at the first report");
await sync({ state: { battery: 70, version: "0.3.18" }, apps: [{ pkg: "a.one", label: "One" }, { pkg: "a.two", label: "Two" }, { pkg: "a.new", label: "New" }], usage: { "2026-11-14": { "a.one": 120 } }, labels: { "a.one": "One" }, loc: [[Date.now(), 51.75, -0.34, 20, "gps", "day"]], power: [[Date.now(), 80, 2000000, 0, 0, 1, "day", 4, 1, 0, 300, 0, "t"]] });
const v2 = (await api("GET", `${D}/view`, { parent: true })).body; assert.deepEqual(v2.pending_apps.map((a) => a.pkg), ["a.new"]); assert.equal(v2.usage["2026-11-14"]["a.one"], 120); assert.equal(v2.device.fix[1], 51.75); assert.equal(v2.power.level, 80); ok("a new app is pending; usage, position and battery are kept");
assert.equal((await post("apps/approve", { pkg: "a.new", modes: ["day", "night"] })).status, 200); const v3 = (await api("GET", `${D}/view`, { parent: true })).body; assert.deepEqual(v3.pending_apps, []); assert.ok(v3.modes.night.allow.includes("a.new")); ok("approving an app ticks it in the chosen modes");
await post("apps/mode", { pkg: "a.two", mode: "school", allowed: true }); assert.ok((await api("GET", `${D}/view`, { parent: true })).body.modes.school.allow.includes("a.two"));
await post("apps/mode-all", { mode: "school", allowed: false }); assert.ok(!(await api("GET", `${D}/view`, { parent: true })).body.modes.school.allow.includes("a.two")); await post("apps/dismiss", { pkg: "a.one" }); ok("per-app, select-all and dismiss work");
const cmd = await post("apps/command", { kind: "remove", pkg: "com.bad.app", name: "Bad" }); assert.equal(cmd.status, 200); const sC = JSON.parse((await sync({ state: { battery: 70 }, cfgh: "x" })).body.p); assert.equal(sC.config.appCommands[0].pkg, "com.bad.app"); ok("an app removal reaches the phone's config");
await sync({ state: { battery: 70 }, appResults: [[cmd.body.command.id, "done", "removed", Date.now()]], cfgh: "x" }); const v4 = (await api("GET", `${D}/view`, { parent: true })).body; assert.equal(v4.app_commands.length, 0); assert.equal(v4.app_log[0][3], "done"); ok("the phone's result finishes the job and is logged");
{ const w = await post("apps/command", { kind: "remove", pkg: "com.cancel.me", name: "Cancel me" }); assert.equal(w.status, 200); const id = w.body.command.id; const c = await post("apps/command/cancel", { id }); assert.equal(c.status, 200, JSON.stringify(c.body)); const v = (await api("GET", `${D}/view`, { parent: true })).body; assert.ok(!v.app_commands.some((x) => x.id === id)); ok("a waiting app job can be cancelled from the parent page (the route has three parts)"); }
assert.equal((await post("apps/command", { kind: "remove", pkg: "io.github.durkimat.phoneguard", name: "x" })).status, 400); ok("Phone Guard itself cannot be removed from here");
assert.equal((await api("POST", "/api/options", { parent: true, body: { spotify_client_id: "nope" } })).status, 400); assert.equal((await api("POST", "/api/options", { parent: true, body: { spotify_client_id: "A1B2C3D4E5F60718293A4B5C6D7E8F90" } })).body.spotify_client_id, "a1b2c3d4e5f60718293a4b5c6d7e8f90"); assert.equal(JSON.parse((await sync({ state: { battery: 70 }, cfgh: "x" })).body.p).config.spotifyClientId, "a1b2c3d4e5f60718293a4b5c6d7e8f90"); ok("the Spotify Client ID is a family option and reaches every phone's config");
assert.equal((await post("update", {})).status, 400); await api("POST", "/api/options", { parent: true, body: { updates_enabled: true } }); assert.equal((await post("update", {})).status, 400); ok("updates are off until switched on, and need a known release");
assert.equal((await post("rename", { name: "Elowen's phone" })).status, 200); assert.equal((await api("GET", "/api/devices", { parent: true })).body[0].name, "Elowen's phone"); ok("rename");

const sU = await sync({ battery: 78, cfgVersion: 9999 });
const pU = JSON.parse(sU.body.p); assert.equal(pU.unchanged, true); assert.equal(pU.config, undefined); ok("an older-style phone that already has the version gets 'unchanged'");
const sOld = await sync({ battery: 77, cfgVersion: 1 });
assert.ok(JSON.parse(sOld.body.p).config); ok("an older phone version gets the full config");

console.log("Sealed credentials");
const encKey = (await api("GET", `/api/devices/${conf.did}/enckey`, { parent: true })).body.encKey;
assert.equal(encKey, encPubB64); ok("parent can fetch the phone's encryption key (separate from its signing key)");
const secret = "spotify-refresh-token-AQBq9f...long-value-1234567890";
const sealed = await seal({ recipientSpki: encKey, did: conf.did, name: "spotify.refresh", plaintext: secret });
const saved = await post("secret", { name: "spotify.refresh", sealed });
assert.equal(saved.status, 200); assert.equal((await post("secret", { name: "spotify.refresh", sealed: { alg: "none" } })).status, 400); ok("parent stores a secret sealed in the browser (a malformed one is refused)");
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
const gc = (await api("GET", `/api/devices/${conf.did}/config`, { parent: true })).body; assert.ok(gc.version >= 1 && gc.config.modes && gc.config.segments); ok("parent can read a phone's current settings and version");
assert.equal((await api("GET", "/api/devices/dnosuchdevice/config", { parent: true })).status, 404); ok("unknown phone -> 404");

console.log("Token limits");
const e2 = (await api("POST", "/api/enrol/new", { parent: true })).body;
for (let i = 0; i < 3; i++) await api("POST", "/api/pair", { body: { token: e2.token, pub: "not-a-key", nonce } });
assert.equal((await api("POST", "/api/pair", { body: { token: e2.token, pub: pubB64, nonce } })).status, 429); ok("a token allows only a few tries");

console.log("Location history");
await post("track/clear", {});   // earlier checks already stored a position
const nowT = Date.now(), fx = (minAgo, lat, lon, acc = 20) => [nowT - minAgo * 60_000, lat, lon, acc, "gps", "day"];
await sync({ state: { battery: 60 }, loc: [fx(30, 51.75, -0.34), fx(20, 51.751, -0.341), fx(10, 51.752, -0.342), fx(5, 999, 0), fx(4, 51.7, -0.3, -5), ["bad"], [nowT - 8 * 86400_000, 51.0, 0.0, 10, "gps", "day"]], cfgh: "x" });
const tr = (await api("GET", `${D}/track`, { parent: true })).body;
assert.equal(tr.points.length, 3); assert.ok(tr.points.every((q, i) => i === 0 || q[0] > tr.points[i - 1][0])); assert.equal(tr.retention_days, 7); ok("every valid position is stored (not just the newest); bad ones and ones older than the retention are dropped");
await sync({ state: { battery: 60 }, loc: [fx(20, 51.751, -0.341), fx(2, 51.753, -0.343)], cfgh: "x" }); assert.equal((await api("GET", `${D}/track`, { parent: true })).body.points.length, 4); ok("a repeated position is not stored twice");
const win = (await api("GET", `${D}/track?from=${nowT - 12 * 60_000}&to=${nowT}`, { parent: true })).body; assert.equal(win.points.length, 2); ok("the history can be asked for a time window");
const thin = (await api("GET", `${D}/track?max=2`, { parent: true })).body; assert.ok(thin.points.length < 4 && thin.points.length >= 2); assert.equal(thin.thinned, true); assert.equal(thin.points.at(-1)[0], tr.points.at(-1)[0] === thin.points.at(-1)[0] ? tr.points.at(-1)[0] : (await api("GET", `${D}/track`, { parent: true })).body.points.at(-1)[0]); ok("a long history is thinned for the map, always keeping the last position");
assert.equal((await api("POST", "/api/options", { parent: true, body: { history_days: 5 } })).status, 400); ok("only 0, 1, 3, 7 or 14 days can be chosen");
await api("POST", "/api/options", { parent: true, body: { history_days: 1 } }); assert.equal((await api("GET", `${D}/track`, { parent: true })).body.retention_days, 1); ok("the retention can be shortened");
assert.equal((await post("track/clear", {})).status, 200); assert.equal((await api("GET", `${D}/track`, { parent: true })).body.points.length, 0); ok("clear deletes the stored history");
await api("POST", "/api/options", { parent: true, body: { history_days: 0 } }); await sync({ state: { battery: 60 }, loc: [fx(1, 51.75, -0.34)], cfgh: "x" }); assert.equal((await api("GET", `${D}/track`, { parent: true })).body.points.length, 0); assert.equal((await api("GET", `${D}/view`, { parent: true })).body.device.fix[1], 51.75); ok("with history off nothing is stored, but the latest position is still shown");
await api("POST", "/api/options", { parent: true, body: { history_days: 7 } });
assert.equal((await api("GET", `${D}/track`)).status, 401); ok("the history needs the parent key");

console.log("Email and calendars");
{ const open2 = (sealed, name) => openSealed({ encPrivatePkcs8: encPrivB64, did: conf.did, name, sealed });
  const gm = { address: "parent@example.com", imap: { host: "imap.gmail.com", port: 993, security: "ssl" }, smtp: { host: "smtp.gmail.com", port: 465, security: "ssl" } };
  const sealedPw = await seal({ recipientSpki: encPubB64, did: conf.did, name: "email.password", plaintext: "abcd efgh ijkl mnop" });
  assert.equal((await post("email", { email: gm, sealedPassword: sealedPw })).status, 200);
  const cfgE = JSON.parse((await sync({ battery: 50, cfgVersion: 0 })).body.p).config; assert.equal(cfgE.email.address, "parent@example.com"); assert.equal(cfgE.email.secret, "email.password"); assert.equal(open2(cfgE.secrets["email.password"], "email.password"), "abcd efgh ijkl mnop"); ok("an email account reaches the phone with its password sealed; the phone's key opens it");
  assert.ok(!JSON.stringify((await api("GET", `${D}/view`, { parent: true })).body).includes("abcd efgh")); assert.equal((await api("GET", `${D}/view`, { parent: true })).body.email_password_set, true); ok("the parent view never contains the password, only that one is set");
  assert.equal((await post("email", { email: { ...gm, address: "nope" } })).status, 400); ok("a bad email setting is refused");
  const sealedUrl = await seal({ recipientSpki: encPubB64, did: conf.did, name: "calendar.x", plaintext: "https://calendar.google.com/calendar/ical/abc/private-123/basic.ics" });
  assert.equal((await post("calendar/add", { name: "Family", sealedUrl })).status, 200);
  const cfgC = JSON.parse((await sync({ battery: 50, cfgVersion: 0 })).body.p).config; assert.equal(cfgC.calendars.length, 1); assert.equal(cfgC.calendars[0].name, "Family"); assert.ok(!JSON.stringify(cfgC.calendars).includes("calendar.google.com")); ok("a calendar is listed by name only; its private link is sealed");
  const wrongSlot = await seal({ recipientSpki: encPubB64, did: conf.did, name: "calendar.other", plaintext: "x" }); assert.throws(() => open2(wrongSlot, "calendar." + cfgC.calendars[0].id)); ok("a link sealed for one slot cannot be opened as another");
  const cid = cfgC.calendars[0].id; assert.equal((await post("calendar/remove", { id: cid })).status, 200); const cfgC2 = JSON.parse((await sync({ battery: 50, cfgVersion: 0 })).body.p).config; assert.ok(!cfgC2.calendars && !cfgC2.secrets?.["calendar." + cid]); ok("removing a calendar removes its sealed link from the config");
  assert.equal((await post("email/clear", {})).status, 200); assert.ok(!JSON.parse((await sync({ battery: 50, cfgVersion: 0 })).body.p).config.email); ok("clearing email removes the account");
  assert.equal((await post("secret", { name: "email.password", sealed: { alg: "none" } })).status, 400); }

console.log("Release and forget");
assert.equal((await post("release", {})).status, 200);
const rel = JSON.parse((await sync({ battery: 50, cfgVersion: 9999 })).body.p); assert.deepEqual(rel.cmds, [{ type: "release" }]); ok("a release request reaches the phone as a signed release command");
assert.equal((await sync({ battery: 50 })).status, 403); assert.equal((await api("GET", "/api/devices", { parent: true })).body.length, 0); ok("a released phone is forgotten and can no longer check in");

console.log("Versions");
{ const v = await api("GET", "/api/version"); assert.equal(v.status, 200); assert.match(v.body.version, /^\d+\.\d+\.\d+$/); assert.ok(Number.isInteger(v.body.schema) && Number.isInteger(v.body.protocol)); assert.equal(v.body.data_schema, v.body.schema); assert.equal(v.body.storage_version, v.body.version); assert.equal(v.body.needs_newer_code, undefined); ok("/api/version is public and reports the companion version, schema, protocol and the schema of the stored data");
  const o = await api("GET", "/api/options", { parent: true }); assert.deepEqual(o.body.companion, { version: v.body.version, schema: v.body.schema, protocol: v.body.protocol }); ok("the parent's options carry the same companion version, so the page can say 'update available'"); }

console.log(`\n${passed} checks passed`);
