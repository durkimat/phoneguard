// Spotify sign-in for the family's OWN Spotify developer app (PKCE: no client secret). Runs in the parent's browser; the refresh token is
// sealed to the phone before it leaves the browser, so the Worker and Cloudflare only ever hold ciphertext.
import { seal } from "/seal.js";

export const SCOPES = ["user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing", "streaming", "playlist-read-private", "user-library-read"];
export const SLOT = "spotify.refresh";
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export async function makePkce(rand = crypto.getRandomValues.bind(crypto)) {
  const verifier = b64url(rand(new Uint8Array(48)));              // 64 characters, inside the 43-128 PKCE range
  const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  return { verifier, challenge };
}

export const redirectUri = (origin) => `${origin}/spotify/callback`;

export function authorizeUrl({ clientId, origin, challenge, state, scopes = SCOPES }) {
  const u = new URL("https://accounts.spotify.com/authorize");
  u.search = new URLSearchParams({ client_id: clientId, response_type: "code", redirect_uri: redirectUri(origin), code_challenge_method: "S256", code_challenge: challenge, state, scope: scopes.join(" ") }).toString();
  return u.toString();
}

export async function exchangeCode({ clientId, origin, code, verifier, fetchImpl = fetch }) {
  const r = await fetchImpl("https://accounts.spotify.com/api/token", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirectUri(origin), client_id: clientId, code_verifier: verifier }).toString() });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.refresh_token) throw new Error(j.error_description ?? j.error ?? "Spotify did not accept the sign-in");
  return j.refresh_token;
}

// Seals the refresh token to the phone and stores it as that phone's `spotify.refresh` secret (nothing else about the phone changes).
export async function storeRefreshToken({ api, did, refreshToken }) {
  const { encKey } = await api(`/devices/${did}/enckey`);
  const sealed = await seal({ recipientSpki: encKey, did, name: SLOT, plaintext: refreshToken });
  return api(`/devices/${did}/secret`, "POST", { name: SLOT, sealed });
}
