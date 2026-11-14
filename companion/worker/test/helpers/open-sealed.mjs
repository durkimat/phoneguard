// The PHONE side of a sealed credential, written with node:crypto (independent of src/seal.js, as Android's Keystore/JCA would do it).
import crypto from "node:crypto";
export function openSealed({ encPrivatePkcs8, did, name, sealed }) {
  if (sealed.alg !== "ECDH-P256+HKDF-SHA256+AES-256-GCM") throw new Error("unknown alg");
  const priv = crypto.createPrivateKey({ key: Buffer.from(encPrivatePkcs8, "base64"), format: "der", type: "pkcs8" });
  const epk = crypto.createPublicKey({ key: Buffer.from(sealed.epk, "base64"), format: "der", type: "spki" });
  const shared = crypto.diffieHellman({ privateKey: priv, publicKey: epk });
  const key = Buffer.from(crypto.hkdfSync("sha256", shared, Buffer.from(sealed.salt, "base64"), Buffer.from(`phoneguard-sealed-v1|${did}|${name}`, "utf8"), 32));
  const ct = Buffer.from(sealed.ct, "base64"), tag = ct.subarray(ct.length - 16), body = ct.subarray(0, ct.length - 16);
  const d = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(sealed.iv, "base64")); d.setAAD(Buffer.from(`${did}|${name}`, "utf8")); d.setAuthTag(tag);
  return Buffer.concat([d.update(body), d.final()]).toString("utf8");
}
