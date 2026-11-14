// Signature and encoding helpers shared by the Worker. Wire formats follow what Android produces and expects:
// public keys are X.509 SubjectPublicKeyInfo (base64), signatures are DER-encoded ECDSA P-256 / SHA-256 (base64).
// WebCrypto uses the raw r||s form, so convert at the edges.

const enc = new TextEncoder();
const EC = { name: "ECDSA", namedCurve: "P-256" };

export const b64 = (buf) => { let s = ""; for (const x of new Uint8Array(buf)) s += String.fromCharCode(x); return btoa(s); };
export const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const sha256 = async (text) => new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
export const hex = (u8) => [...u8].map((x) => x.toString(16).padStart(2, "0")).join("");

/** Constant-time string comparison (parent token check). */
export function safeEqual(a, b) {
  const x = enc.encode(a), y = enc.encode(b);
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return d === 0;
}

/** 4-digit number both sides derive from the pairing token and the phone's public key; the parent confirms they match. */
export async function matchNumber(token, pubB64) {
  const h = await sha256(`${token}:${pubB64}`);
  return String(((h[0] << 24) | (h[1] << 16) | (h[2] << 8) | h[3]) >>> 0).slice(-4).padStart(4, "0");
}

export function derToRaw(der) {
  const d = Uint8Array.from(der);
  if (d[0] !== 0x30) throw new Error("not DER");
  let i = 2; if (d[1] & 0x80) i = 2 + (d[1] & 0x7f);
  const read = () => { if (d[i++] !== 0x02) throw new Error("not INTEGER"); let len = d[i++]; let v = d.slice(i, i + len); i += len; while (v.length > 32 && v[0] === 0) v = v.slice(1); const out = new Uint8Array(32); out.set(v, 32 - v.length); return out; };
  const r = read(), s = read();
  const raw = new Uint8Array(64); raw.set(r, 0); raw.set(s, 32); return raw;
}

export function rawToDer(raw) {
  const int = (b) => { let v = Uint8Array.from(b); while (v.length > 1 && v[0] === 0 && !(v[1] & 0x80)) v = v.slice(1); if (v[0] & 0x80) v = Uint8Array.from([0, ...v]); return [0x02, v.length, ...v]; };
  const body = [...int(raw.slice(0, 32)), ...int(raw.slice(32))];
  return Uint8Array.from([0x30, body.length, ...body]);
}

export async function importPub(spkiB64) { return crypto.subtle.importKey("spki", unb64(spkiB64), EC, true, ["verify"]); }

export async function verifyDer(pubKey, sigB64, message) {
  try { return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pubKey, derToRaw(unb64(sigB64)), enc.encode(message)); }
  catch { return false; }
}

export async function signDer(privKey, message) {
  const raw = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privKey, enc.encode(message)));
  return b64(rawToDer(raw));
}

export async function newServerKeys() {
  const kp = await crypto.subtle.generateKey(EC, true, ["sign", "verify"]);
  return { priv: await crypto.subtle.exportKey("jwk", kp.privateKey), pub: b64(await crypto.subtle.exportKey("spki", kp.publicKey)) };
}
export const importPriv = (jwk) => crypto.subtle.importKey("jwk", jwk, EC, false, ["sign"]);

const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // no 0 O 1 I L
export function randomCode(n) { const u = crypto.getRandomValues(new Uint8Array(n)); return [...u].map((x) => ALPHABET[x % ALPHABET.length]).join(""); }
