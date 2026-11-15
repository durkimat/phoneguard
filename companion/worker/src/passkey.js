// Passkeys (WebAuthn) for the parent page: checks a new passkey and a sign-in with nothing but WebCrypto, so no outside service is involved.
// We ask for no attestation ("none"), accept ES256 and RS256 keys, and require the device to have verified the person (fingerprint, face or screen PIN).
import { derToRaw } from "./crypto.js";

const enc = new TextEncoder(), dec = new TextDecoder();
export const b64u = (buf) => { let s = ""; for (const x of new Uint8Array(buf)) s += String.fromCharCode(x); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); };
export const unb64u = (s) => { s = String(s).replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; return Uint8Array.from(atob(s), (c) => c.charCodeAt(0)); };
const same = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);
const sha = async (data) => new Uint8Array(await crypto.subtle.digest("SHA-256", data));

/** A small CBOR reader: integers, byte and text strings, arrays, maps, true/false/null. Returns [value, nextIndex]. */
export function cbor(u8, i = 0, depth = 0) {
  if (depth > 8 || i >= u8.length) throw new Error("bad passkey data");
  const b = u8[i++], mt = b >> 5, ai = b & 31; let n = ai;
  if (ai === 24) n = u8[i++]; else if (ai === 25) { n = (u8[i] << 8) | u8[i + 1]; i += 2; }
  else if (ai === 26) { n = ((u8[i] << 24) | (u8[i + 1] << 16) | (u8[i + 2] << 8) | u8[i + 3]) >>> 0; i += 4; }
  else if (ai > 24 && !(mt === 7)) throw new Error("bad passkey data");
  if (mt === 0) return [n, i];
  if (mt === 1) return [-1 - n, i];
  if (mt === 2 || mt === 3) { if (i + n > u8.length) throw new Error("bad passkey data"); const s = u8.slice(i, i + n); return [mt === 2 ? s : dec.decode(s), i + n]; }
  if (mt === 4) { const out = []; for (let k = 0; k < n; k++) { let v; [v, i] = cbor(u8, i, depth + 1); out.push(v); } return [out, i]; }
  if (mt === 5) { const out = new Map(); for (let k = 0; k < n; k++) { let key, v; [key, i] = cbor(u8, i, depth + 1); [v, i] = cbor(u8, i, depth + 1); out.set(key, v); } return [out, i]; }
  if (mt === 7) return [ai === 20 ? false : ai === 21 ? true : null, i];
  throw new Error("bad passkey data");
}

export function parseAuthData(a) {
  if (a.length < 37) throw new Error("bad passkey data");
  const flags = a[32];
  return { rpIdHash: a.slice(0, 32), flags, up: !!(flags & 1), uv: !!(flags & 4), at: !!(flags & 0x40), counter: new DataView(a.buffer, a.byteOffset + 33, 4).getUint32(0), rest: a.slice(37) };
}

function clientData(raw, type, challenge, origin) {
  let c; try { c = JSON.parse(dec.decode(raw)); } catch { throw new Error("bad passkey data"); }
  if (c.type !== type) throw new Error("wrong kind of passkey request");
  if (c.challenge !== challenge) throw new Error("this passkey request has expired; try again");
  if (c.origin !== origin || c.crossOrigin === true) throw new Error("this passkey was made for a different address");
  return c;
}

/** Checks a new passkey. Returns {id, alg, jwk, counter} to store. */
export async function verifyRegistration({ clientDataJSON, attestationObject }, { challenge, origin, rpId }) {
  const cd = unb64u(clientDataJSON); clientData(cd, "webauthn.create", challenge, origin);
  const [att] = cbor(unb64u(attestationObject)); const authData = att instanceof Map ? att.get("authData") : null;
  if (!(authData instanceof Uint8Array)) throw new Error("bad passkey data");
  const a = parseAuthData(authData);
  if (!same(a.rpIdHash, await sha(enc.encode(rpId)))) throw new Error("this passkey was made for a different address");
  if (!a.up) throw new Error("the passkey was not confirmed on the device");
  if (!a.uv) throw new Error("this device did not check who you are; set a screen lock (or a fingerprint) on it and try again");
  if (!a.at || a.rest.length < 19) throw new Error("bad passkey data");
  const len = (a.rest[16] << 8) | a.rest[17], id = a.rest.slice(18, 18 + len);
  if (!len || id.length !== len || len > 1023) throw new Error("bad passkey data");
  const [cose] = cbor(a.rest, 18 + len); if (!(cose instanceof Map)) throw new Error("bad passkey data");
  const kty = cose.get(1), alg = cose.get(3); let jwk;
  if (kty === 2 && alg === -7 && cose.get(-1) === 1 && cose.get(-2) instanceof Uint8Array && cose.get(-3) instanceof Uint8Array) jwk = { kty: "EC", crv: "P-256", x: b64u(cose.get(-2)), y: b64u(cose.get(-3)) };
  else if (kty === 3 && alg === -257 && cose.get(-1) instanceof Uint8Array && cose.get(-2) instanceof Uint8Array) jwk = { kty: "RSA", n: b64u(cose.get(-1)), e: b64u(cose.get(-2)), alg: "RS256" };
  else throw new Error("this device made a kind of passkey we do not support");
  await keyFor(alg, jwk);   // refuses a malformed key now rather than at the first sign-in
  return { id: b64u(id), alg, jwk, counter: a.counter };
}

const keyFor = (alg, jwk) => alg === -7 ? crypto.subtle.importKey("jwk", jwk, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]) : crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);

/** Checks a sign-in. `stored` is {alg, jwk, counter}. Returns the new counter. */
export async function verifyAssertion({ clientDataJSON, authenticatorData, signature }, { challenge, origin, rpId }, stored) {
  const cd = unb64u(clientDataJSON); clientData(cd, "webauthn.get", challenge, origin);
  const ad = unb64u(authenticatorData), a = parseAuthData(ad);
  if (!same(a.rpIdHash, await sha(enc.encode(rpId)))) throw new Error("this passkey was made for a different address");
  if (!a.up || !a.uv) throw new Error("the device did not confirm it was you");
  const data = new Uint8Array(ad.length + 32); data.set(ad, 0); data.set(await sha(cd), ad.length);
  const sig = unb64u(signature), key = await keyFor(stored.alg, stored.jwk);
  const good = stored.alg === -7 ? await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, derToRaw(sig), data) : await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, sig, data);
  if (!good) throw new Error("the passkey did not check out");
  if (stored.counter > 0 && a.counter <= stored.counter) throw new Error("this passkey looks copied, so it was refused");   // synced passkeys keep the counter at 0 and are never refused
  return a.counter;
}
