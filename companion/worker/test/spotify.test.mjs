// Spotify page helpers: PKCE, authorize URL, token exchange (fake Spotify) and sealing the refresh token to a phone key.
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { readFileSync } from "node:fs";
import { openSealed } from "./helpers/open-sealed.mjs";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
// the page module imports "/seal.js" (an absolute URL path); serve it from src for Node by rewriting the specifier
const src = readFileSync(new URL("../src/page/spotify.js", import.meta.url), "utf8").replace('"/seal.js"', JSON.stringify(new URL("../src/seal.js", import.meta.url).href));
const S = await import("data:text/javascript;base64," + Buffer.from(src).toString("base64"));

const { verifier, challenge } = await S.makePkce();
assert.ok(verifier.length >= 43 && verifier.length <= 128 && /^[A-Za-z0-9_-]+$/.test(verifier)); assert.equal(challenge, crypto.createHash("sha256").update(verifier).digest("base64url")); ok("PKCE verifier length and S256 challenge are correct");
const u = new URL(S.authorizeUrl({ clientId: "c".repeat(32), origin: "https://pg.example.workers.dev", challenge, state: "st" }));
assert.equal(u.host, "accounts.spotify.com"); assert.equal(u.searchParams.get("redirect_uri"), "https://pg.example.workers.dev/spotify/callback"); assert.equal(u.searchParams.get("code_challenge_method"), "S256"); assert.equal(u.searchParams.get("response_type"), "code"); assert.ok(!u.searchParams.has("client_secret")); ok("authorize URL has HTTPS redirect, S256, no client secret");
let seen; const fakeSpotify = async (url, init) => { seen = { url, body: new URLSearchParams(init.body) }; return new Response(JSON.stringify({ access_token: "a", refresh_token: "REFRESH-123" }), { status: 200 }); };
assert.equal(await S.exchangeCode({ clientId: "cid", origin: "https://x.workers.dev", code: "abc", verifier, fetchImpl: fakeSpotify }), "REFRESH-123");
assert.equal(seen.url, "https://accounts.spotify.com/api/token"); assert.equal(seen.body.get("code_verifier"), verifier); assert.equal(seen.body.get("grant_type"), "authorization_code"); ok("code exchange sends the verifier and returns the refresh token");
await assert.rejects(S.exchangeCode({ clientId: "cid", origin: "https://x", code: "a", verifier, fetchImpl: async () => new Response(JSON.stringify({ error: "invalid_grant", error_description: "Invalid authorization code" }), { status: 400 }) }), /Invalid authorization code/); ok("a Spotify refusal becomes a readable error");

// sealing round trip with a phone key pair, fake parent API
const enc = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const encPub = enc.publicKey.export({ type: "spki", format: "der" }).toString("base64"), encPriv = enc.privateKey.export({ type: "pkcs8", format: "der" }).toString("base64");
const saved = []; const api = async (path, method = "GET", body) => {
  if (path.endsWith("/enckey")) return { encKey: encPub };
  saved.push({ path, method, body }); return { ok: true, version: 4 };
};
await S.storeRefreshToken({ api, did: "dtest1", refreshToken: "REFRESH-123" });
const out = saved[0]; assert.equal(out.path, "/devices/dtest1/secret"); assert.equal(out.method, "POST"); assert.equal(out.body.name, "spotify.refresh"); ok("the sealed token is stored as that phone's spotify.refresh secret (nothing else is touched)");
assert.ok(!JSON.stringify(out).includes("REFRESH-123")); ok("the refresh token never appears in plain text in what is sent");
assert.equal(openSealed({ encPrivatePkcs8: encPriv, did: "dtest1", name: S.SLOT, sealed: out.body.sealed }), "REFRESH-123"); ok("the phone's key opens the sealed Spotify token");
console.log(`\n${n} checks passed`);
