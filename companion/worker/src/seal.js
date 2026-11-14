// Sealed credentials: the PARENT'S BROWSER encrypts a secret (Spotify refresh token, email password, ...) so that only the phone can read it.
// The Worker and Cloudflare only ever see ciphertext. Runs unchanged in a browser, a Worker or Node (WebCrypto).
//   alg = "ECDH-P256+HKDF-SHA256+AES-256-GCM"
//   shared = ECDH(ephemeral private, phone ENCRYPTION public key)               (32 bytes, the raw x coordinate)
//   key    = HKDF-SHA256(ikm = shared, salt = random 16 bytes, info = "phoneguard-sealed-v1|<did>|<name>") -> 32 bytes
//   ct     = AES-256-GCM(key, iv = random 12 bytes, aad = "<did>|<name>", plaintext UTF-8), ciphertext followed by the 16-byte tag
// Binding the device id and the secret's name into the key and the AAD means a sealed value cannot be moved to another device or another slot.
import { b64, unb64 } from "./crypto.js";

export const SEAL_ALG = "ECDH-P256+HKDF-SHA256+AES-256-GCM";
const enc = new TextEncoder();
const EC = { name: "ECDH", namedCurve: "P-256" };

export async function seal({ recipientSpki, did, name, plaintext }) {
  const recipient = await crypto.subtle.importKey("spki", unb64(recipientSpki), EC, false, []);
  const eph = await crypto.subtle.generateKey(EC, true, ["deriveBits"]);
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: recipient }, eph.privateKey, 256);
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
  const ikm = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
  const key = await crypto.subtle.deriveKey({ name: "HKDF", hash: "SHA-256", salt, info: enc.encode(`phoneguard-sealed-v1|${did}|${name}`) }, ikm, { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: enc.encode(`${did}|${name}`) }, key, enc.encode(plaintext));
  return { alg: SEAL_ALG, epk: b64(await crypto.subtle.exportKey("spki", eph.publicKey)), salt: b64(salt), iv: b64(iv), ct: b64(ct) };
}
