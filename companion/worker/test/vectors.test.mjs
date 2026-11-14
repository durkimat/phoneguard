// Checks the Worker's own crypto helpers (WebCrypto) against vectors.json produced with node:crypto: two independent implementations must agree.
import assert from "node:assert/strict";
import fs from "node:fs";
import { importPub, verifyDer, matchNumber, derToRaw, rawToDer, unb64, b64 } from "../src/crypto.js";
import nodeCrypto from "node:crypto";
import { seal } from "../src/seal.js";
import { openSealed } from "./helpers/open-sealed.mjs";

const v = JSON.parse(fs.readFileSync(new URL("./vectors.json", import.meta.url), "utf8"));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

assert.equal(await matchNumber(v.pairing.token, v.phone.publicKeySpki), v.pairing.matchNumber); ok("match number rule reproduced");
assert.ok(await verifyDer(await importPub(v.server.publicKeySpki), v.serverReply.envelope.s, v.serverReply.messageToSign)); ok("server reply signature verifies (WebCrypto)");
assert.ok(await verifyDer(await importPub(v.phone.publicKeySpki), v.phoneSync.request.s, v.phoneSync.messageToSign)); ok("phone sync signature verifies (WebCrypto)");
assert.equal(await verifyDer(await importPub(v.phone.publicKeySpki), v.phoneSync.request.s, v.phoneSync.messageToSign + "x"), false); ok("a changed message fails");
assert.equal(await verifyDer(await importPub(v.server.publicKeySpki), v.phoneSync.request.s, v.phoneSync.messageToSign), false); ok("the wrong key fails");
assert.equal(v.serverReply.messageToSign, `1|${v.serverReply.envelope.did}|${v.serverReply.envelope.n}|${v.serverReply.envelope.t}|${v.serverReply.envelope.p}`); ok("signed string is exactly v|did|n|t|p with p as transmitted");
assert.equal(v.phoneSync.messageToSign, `1|${v.phoneSync.request.did}|${v.phoneSync.request.ctr}|${v.phoneSync.request.n}|${v.phoneSync.request.p}`); ok("signed string is exactly v|did|ctr|n|p");
const pin = nodeCrypto.createHash("sha256").update(unb64(v.server.publicKeySpki)).digest().subarray(0, 16).toString("base64");
assert.equal(pin, v.bundle.pin); assert.equal(v.bundle.json.k, pin); ok("bundle pin = first 16 bytes of SHA-256(server SPKI)");
const ep = v.enrolPayload, qu = new URL(ep.qr.text);
assert.equal(qu.protocol, "phoneguard:"); assert.equal(qu.searchParams.get("h"), ep.qr.host); assert.equal(qu.searchParams.get("t"), v.pairing.token);
assert.equal(qu.searchParams.get("f"), ep.qr.fp); assert.equal(ep.qr.fp.length, 22); assert.deepEqual([...Buffer.from(ep.qr.fp, "base64url")], [...Buffer.from(v.bundle.pin, "base64")]); ok("QR text parses; fp = bundle pin as base64url (22 chars)");
assert.equal(ep.typed.address + ".workers.dev", ep.typed.expectedHost); assert.equal(ep.typed.code.replace(/[^A-Za-z0-9]/g, "").toUpperCase(), ep.typed.expectedToken); ok("typed address + code give the same host and token");
assert.equal(ep.invalid.length, 4); ok("invalid QR examples present (v2, http host, short token, missing fp)");
const sms = `pg1:${v.bundle.json.u}:${v.bundle.json.t}`; assert.ok(JSON.stringify(v.bundle.json).length < 200 && sms.length < 160); ok(`bundle fits one SMS (${JSON.stringify(v.bundle.json).length} bytes JSON)`);
for (let i = 0; i < 200; i++) { const raw = nodeCrypto.randomBytes(64); if (i % 5 === 0) { raw[0] = 0; } if (i % 7 === 0) { raw[32] = 0; raw[33] = 0xff; } assert.deepEqual([...derToRaw(rawToDer(raw))], [...raw]); }
ok("DER <-> raw conversion round-trips 200 random signatures (incl. leading zeros and high bits)");
const sv = v.sealed;
assert.equal(openSealed({ encPrivatePkcs8: sv.phoneEncryptionKey.privateKeyPkcs8, did: sv.did, name: sv.name, sealed: sv.sealed }), sv.plaintext); ok("sealed vector decrypts to the plaintext (fixed ephemeral key, salt, iv)");
assert.throws(() => openSealed({ encPrivatePkcs8: sv.phoneEncryptionKey.privateKeyPkcs8, did: sv.did, name: "x", sealed: sv.sealed })); ok("sealed vector is bound to its slot name");
const fresh = await seal({ recipientSpki: sv.phoneEncryptionKey.publicKeySpki, did: sv.did, name: sv.name, plaintext: "round trip \u00e9\u4e2d" });
assert.equal(openSealed({ encPrivatePkcs8: sv.phoneEncryptionKey.privateKeyPkcs8, did: sv.did, name: sv.name, sealed: fresh }), "round trip \u00e9\u4e2d"); ok("WebCrypto seal() output opens with node:crypto (UTF-8 survives)");
console.log(`\n${n} checks passed`);
