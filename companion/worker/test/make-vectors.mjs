// Generates vectors.json: fixed keys, the exact byte strings that are signed, valid DER signatures, the match number and the bundle pin.
// Anyone (the Kotlin client, a future Home Assistant backend) must reproduce `match`, `pin`, and verify every `signature`.
import crypto from "node:crypto";
import fs from "node:fs";

const keys = () => { const kp = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" }); return { priv: kp.privateKey, privPkcs8: kp.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"), pubSpki: kp.publicKey.export({ type: "spki", format: "der" }).toString("base64") }; };
const sign = (k, msg) => crypto.sign("sha256", Buffer.from(msg, "utf8"), { key: k.priv, dsaEncoding: "der" }).toString("base64");
// Keys are reused from the existing vectors.json when present, so the published example values stay stable across regenerations.
const file = new URL("./vectors.json", import.meta.url);
const prev = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : null;
const load = (priv, pub) => ({ priv: crypto.createPrivateKey({ key: Buffer.from(priv, "base64"), format: "der", type: "pkcs8" }), privPkcs8: priv, pubSpki: pub });
const phone = prev ? load(prev.phone.privateKeyPkcs8, prev.phone.publicKeySpki) : keys();
const server = prev ? load(prev.server.privateKeyPkcs8, prev.server.publicKeySpki) : keys();
const phoneEnc = prev?.sealed ? load(prev.sealed.phoneEncryptionKey.privateKeyPkcs8, prev.sealed.phoneEncryptionKey.publicKeySpki) : keys();
const eph = prev?.sealed?.ephemeralKey ? load(prev.sealed.ephemeralKey.privateKeyPkcs8, prev.sealed.ephemeralKey.publicKeySpki) : keys();

const token = "7K3QM9XD2P";                       // as shown to the parent; the server normalises to upper case without separators
const matchNumber = (() => { const h = crypto.createHash("sha256").update(`${token}:${phone.pubSpki}`, "utf8").digest(); return String(h.readUInt32BE(0)).slice(-4).padStart(4, "0"); })();
const pin = crypto.createHash("sha256").update(Buffer.from(server.pubSpki, "base64")).digest().subarray(0, 16).toString("base64");
const bundle = { v: 1, u: "https://pg-example.family.workers.dev", t: token, k: pin };

const qrHost = "pg-example.family.workers.dev", qrFp = Buffer.from(pin, "base64").toString("base64url");
const qrText = `phoneguard://enrol?v=1&h=${qrHost}&t=${token}&f=${qrFp}`;

const did = "dq7m2k9x4a", nonce = "a3f1c0de5b7a49e2b6d81f04c2a7e9b3", t = 1794678920285;
const replyP = JSON.stringify({ did, recoveryHash: crypto.createHash("sha256").update("ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2").digest("hex"), configVersion: 1, config: { mode: "day", modes: {} } });
const replyMsg = `1|${did}|${nonce}|${t}|${replyP}`;
const syncP = '{"battery":83,"mode":"day","app":"0.4.0"}', ctr = 7, syncN = "5e8b9a1c7d3f4a60b2c8e9d1f0a37c44";
const syncMsg = `1|${did}|${ctr}|${syncN}|${syncP}`;

const sealDid = did, sealName = "spotify.refresh", sealPlain = "AQBq9f-example-spotify-refresh-token", salt = Buffer.from("00112233445566778899aabbccddeeff", "hex"), iv = Buffer.from("a0a1a2a3a4a5a6a7a8a9aaab", "hex");
const shared = crypto.diffieHellman({ privateKey: eph.priv, publicKey: crypto.createPublicKey({ key: Buffer.from(phoneEnc.pubSpki, "base64"), format: "der", type: "spki" }) });
const aesKey = Buffer.from(crypto.hkdfSync("sha256", shared, salt, Buffer.from(`phoneguard-sealed-v1|${sealDid}|${sealName}`, "utf8"), 32));
const gcm = crypto.createCipheriv("aes-256-gcm", aesKey, iv); gcm.setAAD(Buffer.from(`${sealDid}|${sealName}`, "utf8"));
const sealCt = Buffer.concat([gcm.update(sealPlain, "utf8"), gcm.final(), gcm.getAuthTag()]);
const out = {
  _about: "Signing: ECDSA P-256 with SHA-256, signature DER-encoded then base64 (Android SHA256withECDSA). Messages are UTF-8 strings joined with '|'; `p` is the exact JSON text transmitted, never re-serialised. Public keys are X.509 SubjectPublicKeyInfo, base64. Signatures are randomised: verify them, do not compare bytes.",
  phone: { privateKeyPkcs8: phone.privPkcs8, publicKeySpki: phone.pubSpki },
  server: { privateKeyPkcs8: server.privPkcs8, publicKeySpki: server.pubSpki },
  pairing: { token, tokenNormalisation: "upper-case, strip everything outside A-Z0-9", matchNumberRule: "SHA-256(utf8(token + ':' + phoneSpkiBase64)); first 4 bytes big-endian unsigned; decimal; last 4 digits, zero-padded", matchNumber },
  bundle: { json: bundle, pinRule: "base64(first 16 bytes of SHA-256(DER bytes of the server SPKI)); the phone fetches the full key from GET /api/info over HTTPS and rejects it unless its pin equals `k`", pin },
  enrolPayload: { rule: "QR text = phoneguard://enrol?v=1&h=<host>&t=<token>&f=<fp>; h is a plain DNS name (the phone uses https://<h>/api/..); t is 10 chars A-Z0-9 without dashes; f is base64url (22 chars, no padding; padding tolerated) of the first 16 bytes of SHA-256(server SPKI DER), i.e. the bundle pin re-encoded. Typed route: address box (the phone appends '.workers.dev' unless already present) and code box (strip non-alphanumerics, upper-case); no fingerprint is typed.", qr: { text: qrText, host: qrHost, token, fp: qrFp }, typed: { address: "pg-example.family", code: `${token.slice(0, 5)}-${token.slice(5)}`, expectedHost: qrHost, expectedToken: token }, invalid: ["phoneguard://enrol?v=2&h=" + qrHost + "&t=" + token + "&f=" + qrFp, "phoneguard://enrol?v=1&h=http://" + qrHost + "&t=" + token + "&f=" + qrFp, "phoneguard://enrol?v=1&h=" + qrHost + "&t=" + token.slice(0, 9) + "&f=" + qrFp, "phoneguard://enrol?v=1&h=" + qrHost + "&t=" + token], },
  sealed: { rule: "alg ECDH-P256+HKDF-SHA256+AES-256-GCM: shared = ECDH(ephemeral private, phone ENCRYPTION public) as 32 raw bytes; key = HKDF-SHA256(ikm = shared, salt, info = utf8('phoneguard-sealed-v1|' + did + '|' + name)) -> 32 bytes; ct = AES-256-GCM(key, iv 12 bytes, aad = utf8(did + '|' + name), plaintext utf8), ciphertext followed by the 16-byte tag; all fields base64", phoneEncryptionKey: { privateKeyPkcs8: phoneEnc.privPkcs8, publicKeySpki: phoneEnc.pubSpki }, ephemeralKey: { privateKeyPkcs8: eph.privPkcs8, publicKeySpki: eph.pubSpki }, did: sealDid, name: sealName, plaintext: sealPlain, sealed: { alg: "ECDH-P256+HKDF-SHA256+AES-256-GCM", epk: eph.pubSpki, salt: salt.toString("base64"), iv: iv.toString("base64"), ct: sealCt.toString("base64") } },
  serverReply: { messageToSign: replyMsg, envelope: { v: 1, did, n: nonce, t, p: replyP, s: sign(server, replyMsg) }, rule: "signed by the SERVER over `v|did|n|t|p`; n echoes the phone's nonce; t is advisory (the phone clock may be wrong)" },
  phoneSync: { messageToSign: syncMsg, request: { v: 1, did, ctr, n: syncN, p: syncP, s: sign(phone, syncMsg) }, rule: "signed by the PHONE over `v|did|ctr|n|p`; ctr must exceed the last accepted counter (persist it before sending, gaps are fine)" },
};
fs.writeFileSync(file, JSON.stringify(out, null, 2) + "\n");
console.log("vectors.json written; match", matchNumber, "pin", pin);
