// Passkey sign-in: a software authenticator (node:crypto, written independently of src/passkey.js) registers and signs in through the real routes.
import assert from "node:assert/strict";
import crypto from "node:crypto";
let DatabaseSync; try { ({ DatabaseSync } = await import("node:sqlite")); } catch { console.log("skipped: these tests use Node's built-in SQLite (Node 22.13 or newer)"); process.exit(0); }
import { migrate } from "../src/migrations.js";
import { passkeyRoute, sessionValid } from "../src/passkey-routes.js";
import { cbor } from "../src/passkey.js";

let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const mk = () => { const db = new DatabaseSync(":memory:"); const read = /^\s*(SELECT|PRAGMA)/i; const sql = { exec(t, ...p) { if (read.test(t)) return { toArray: () => db.prepare(t).all(...p) }; if (p.length) db.prepare(t).run(...p); else db.exec(t); return { toArray: () => [] }; } }; migrate(sql); return sql; };
const ORIGIN = "https://fam.example.workers.dev", RP = "fam.example.workers.dev";
const b64u = (b) => Buffer.from(b).toString("base64url"), unb = (s) => Buffer.from(s, "base64url");
const sha = (x) => crypto.createHash("sha256").update(x).digest();

// ---- a tiny CBOR writer and a software authenticator ----------------------------------------------------------------------------
const head = (mt, n) => n < 24 ? [(mt << 5) | n] : n < 256 ? [(mt << 5) | 24, n] : [(mt << 5) | 25, n >> 8, n & 255];
const enc = (v) => Buffer.from(v instanceof Uint8Array ? [...head(2, v.length), ...v] : typeof v === "string" ? [...head(3, Buffer.byteLength(v)), ...Buffer.from(v)] : typeof v === "number" ? (v >= 0 ? head(0, v) : head(1, -1 - v)) : v instanceof Map ? [...head(5, v.size), ...[...v].flatMap(([k, x]) => [...enc(k), ...enc(x)])] : null);
function authenticator({ uv = true, rp = RP } = {}) {
  const kp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" }), jwk = kp.publicKey.export({ format: "jwk" }), credId = crypto.randomBytes(16); let counter = 0;
  const flags = (at) => (1 | (uv ? 4 : 0) | (at ? 0x40 : 0));
  const cd = (type, challenge, origin = ORIGIN) => Buffer.from(JSON.stringify({ type, challenge, origin, crossOrigin: false }));
  return {
    credId,
    create(challenge, origin) {
      const cose = new Map([[1, 2], [3, -7], [-1, 1], [-2, unb(jwk.x)], [-3, unb(jwk.y)]]);
      const ad = Buffer.concat([sha(rp), Buffer.from([flags(true)]), Buffer.alloc(4), Buffer.alloc(16), Buffer.from([credId.length >> 8, credId.length & 255]), credId, enc(cose)]);
      return { clientDataJSON: b64u(cd("webauthn.create", challenge, origin)), attestationObject: b64u(enc(new Map([["fmt", "none"], ["attStmt", new Map()], ["authData", ad]]))) };
    },
    get(challenge, origin) {
      counter++; const ad = Buffer.concat([sha(rp), Buffer.from([flags(false)]), Buffer.from([0, 0, counter >> 8, counter & 255])]), c = cd("webauthn.get", challenge, origin);
      return { id: b64u(credId), clientDataJSON: b64u(c), authenticatorData: b64u(ad), signature: b64u(crypto.sign("sha256", Buffer.concat([ad, sha(c)]), kp.privateKey)) };   // DER, as a real authenticator sends
    },
  };
}
const call = async (sql, method, path, body = {}, bearer = "") => { const r = await passkeyRoute({ sql, path, method, body, origin: ORIGIN, bearer, now: Date.now() }); return { status: r.status, body: await r.json() }; };

{ const [v] = cbor(enc(new Map([["a", 1], ["b", new Uint8Array([1, 2])], ["c", -7]]))); assert.equal(v.get("a"), 1); assert.deepEqual([...v.get("b")], [1, 2]); assert.equal(v.get("c"), -7); ok("the CBOR reader reads what a real authenticator sends"); }

// register a passkey from a signed-in browser, then sign in with it
{ const sql = mk(), dev = authenticator();
  const o = (await call(sql, "POST", "/passkey/register/options", { label: "Laptop" })).body.publicKey; assert.equal(o.rp.id, RP); assert.equal(o.authenticatorSelection.userVerification, "required"); assert.ok(o.pubKeyCredParams.some((p) => p.alg === -7));
  assert.equal((await call(sql, "POST", "/passkey/register", { ...dev.create(o.challenge), label: "Laptop" })).body.ok, true);
  const list = (await call(sql, "GET", "/passkeys")).body; assert.equal(list.passkeys.length, 1); assert.equal(list.passkeys[0].name, "Laptop"); assert.ok(!JSON.stringify(list).includes("jwk")); ok("a signed-in parent registers a passkey and it is listed by name");
  const lo = (await call(sql, "POST", "/passkey/login/options")).body.publicKey; assert.equal(lo.rpId, RP);
  const s = await call(sql, "POST", "/passkey/login", { ...dev.get(lo.challenge), label: "Chrome" }); assert.equal(s.status, 200); assert.match(s.body.session ?? s.body.token, /^s\./);
  const token = s.body.token; assert.equal(await sessionValid(sql, token, Date.now()), true); assert.equal(await sessionValid(sql, "s.nonsense", Date.now()), false); assert.equal(await sessionValid(sql, "", Date.now()), false);
  assert.ok(!JSON.stringify(sql.exec("SELECT * FROM sessions").toArray()).includes(token)); ok("signing in with the passkey gives a session; only its hash is stored");
  assert.equal((await call(sql, "POST", "/passkey/login", { ...dev.get(lo.challenge), label: "x" })).status, 401); ok("a sign-in request cannot be replayed");
  const lo2 = (await call(sql, "POST", "/passkey/login/options")).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/login", { ...dev.get(lo2.challenge, "https://evil.example"), label: "x" })).status, 401); ok("a sign-in made for another address is refused");
  const other = authenticator(), lo3 = (await call(sql, "POST", "/passkey/login/options")).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/login", { ...other.get(lo3.challenge) })).status, 401); ok("a passkey that was never registered here is refused");
  const lo4 = (await call(sql, "POST", "/passkey/login/options")).body.publicKey, forged = dev.get(lo4.challenge); forged.signature = b64u(crypto.randomBytes(70)); assert.equal((await call(sql, "POST", "/passkey/login", forged)).status, 401); ok("a bad signature is refused");
  const me = (await call(sql, "GET", "/passkeys", {}, token)).body.sessions; assert.equal(me.length, 1); assert.equal(me[0].current, true);
  await call(sql, "POST", "/session/signout", {}, token); assert.equal(await sessionValid(sql, token, Date.now()), false); ok("signing out ends the session");
  const s2 = (await call(sql, "POST", "/passkey/login", { ...dev.get((await call(sql, "POST", "/passkey/login/options")).body.publicKey.challenge) })).body.token; await call(sql, "POST", "/session/signout-all"); assert.equal(await sessionValid(sql, s2, Date.now()), false); ok("sign out of all browsers ends every session");
  await call(sql, "POST", "/passkey/remove", { id: b64u(dev.credId) }); const lo5 = (await call(sql, "POST", "/passkey/login/options")).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/login", { ...dev.get(lo5.challenge) })).status, 401); ok("a removed passkey no longer signs in"); }

// registration checks
{ const sql = mk(), o = (await call(sql, "POST", "/passkey/register/options", {})).body.publicKey;
  assert.equal((await call(sql, "POST", "/passkey/register", { ...authenticator({ uv: false }).create(o.challenge) })).status, 400); ok("a device that did not check who you are is refused");
  const o2 = (await call(sql, "POST", "/passkey/register/options", {})).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/register", { ...authenticator({ rp: "other.example" }).create(o2.challenge) })).status, 400); ok("a passkey made for another site is refused");
  const o3 = (await call(sql, "POST", "/passkey/register/options", {})).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/register", { ...authenticator().create(o3.challenge, "https://evil.example") })).status, 400); ok("a passkey made on another address is refused");
  assert.equal((await call(sql, "POST", "/passkey/register", { ...authenticator().create("not-a-challenge") })).status, 400); ok("a passkey with an unknown challenge is refused"); }

// add another device with a one-time link, which also signs that device in
{ const sql = mk(), inv = (await call(sql, "POST", "/passkey/invite")).body; assert.ok(inv.token.length >= 20); assert.ok(inv.expires > Date.now());
  assert.ok(!JSON.stringify(sql.exec("SELECT * FROM challenges").toArray()).includes(inv.token)); ok("an add-a-device link is stored only as a hash");
  assert.equal((await call(sql, "POST", "/passkey/invite/options", { invite: "wrong" })).status, 403);
  const dev = authenticator(), o = (await call(sql, "POST", "/passkey/invite/options", { invite: inv.token, label: "Phone" })).body.publicKey;
  const r = await call(sql, "POST", "/passkey/invite/register", { invite: inv.token, ...dev.create(o.challenge), label: "Phone" }); assert.equal(r.status, 200); assert.equal(await sessionValid(sql, r.body.token, Date.now()), true); ok("the link registers a passkey on the new device and signs it in");
  assert.equal((await call(sql, "POST", "/passkey/invite/options", { invite: inv.token })).status, 403); ok("the link works once");
  const inv2 = (await call(sql, "POST", "/passkey/invite")).body, o4 = (await call(sql, "POST", "/passkey/invite/options", { invite: inv2.token })).body.publicKey; sql.exec("UPDATE challenges SET expires=1 WHERE kind='invite'");
  assert.equal((await call(sql, "POST", "/passkey/invite/register", { invite: inv2.token, ...authenticator().create(o4.challenge) })).status, 403); ok("an expired link is refused");
  const inv3 = (await call(sql, "POST", "/passkey/invite")).body, o5 = (await call(sql, "POST", "/passkey/register/options", {})).body.publicKey;
  assert.equal((await call(sql, "POST", "/passkey/invite/register", { invite: inv3.token, ...authenticator().create(o5.challenge) })).status, 403); ok("a sign-in challenge cannot be used to claim a link"); }

// limits
{ const sql = mk(); for (let i = 0; i < 20; i++) { const d = authenticator(), o = (await call(sql, "POST", "/passkey/register/options", {})).body.publicKey; assert.equal((await call(sql, "POST", "/passkey/register", { ...d.create(o.challenge) })).status, 200); }
  assert.equal((await call(sql, "POST", "/passkey/register/options", {})).status, 400); ok("at most 20 passkeys"); }
{ const sql = mk(), sess = []; for (let i = 0; i < 3; i++) { const d = authenticator(), inv = (await call(sql, "POST", "/passkey/invite")).body, o = (await call(sql, "POST", "/passkey/invite/options", { invite: inv.token })).body.publicKey; sess.push((await call(sql, "POST", "/passkey/invite/register", { invite: inv.token, ...d.create(o.challenge) })).body.token); }
  sql.exec("UPDATE sessions SET expires = 1 WHERE rowid = 1"); assert.equal(await sessionValid(sql, sess[0], Date.now()), false); assert.equal(await sessionValid(sql, sess[1], Date.now()), true); ok("a session expires"); }

console.log(`\n${n} checks passed`);
