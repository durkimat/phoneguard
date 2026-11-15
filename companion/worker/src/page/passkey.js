// Browser side of passkeys: sign in with one, add one to this device, and use an add-a-device link.
// A passkey is the fingerprint, face or screen PIN the device already has; nothing is typed or pasted.
const b64u = (buf) => { let s = ""; for (const x of new Uint8Array(buf)) s += String.fromCharCode(x); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); };
const unb = (s) => { s = s.replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; return Uint8Array.from(atob(s), (c) => c.charCodeAt(0)); };

export const supported = () => Boolean(window.PublicKeyCredential && navigator.credentials?.create);

/** A short name for this browser, shown in the lists ("Chrome on Linux"). */
export function deviceLabel() {
  const ua = navigator.userAgent, b = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /OPR\/|Opera/.test(ua) ? "Opera" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const o = /Android/.test(ua) ? "Android" : /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Windows/.test(ua) ? "Windows" : /Mac OS/.test(ua) ? "Mac" : /Linux|X11/.test(ua) ? "Linux" : "";
  return o ? `${b} on ${o}` : b;
}

async function post(path, body = {}) {
  const r = await fetch("/api" + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }), j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error ?? "Something went wrong");
  return j;
}
/** Turns the browser's own refusals into plain words. */
const friendly = (e) => e?.name === "NotAllowedError" ? new Error("Cancelled, or it took too long. Try again.") : e?.name === "InvalidStateError" ? new Error("This device already has a passkey here.") : e;
const forCreate = (o) => ({ publicKey: { ...o.publicKey, challenge: unb(o.publicKey.challenge), user: { ...o.publicKey.user, id: unb(o.publicKey.user.id) }, excludeCredentials: (o.publicKey.excludeCredentials ?? []).map((c) => ({ ...c, id: unb(c.id) })) } });
const attestation = (cred, label) => ({ clientDataJSON: b64u(cred.response.clientDataJSON), attestationObject: b64u(cred.response.attestationObject), label });
const remember = () => { try { localStorage.setItem("pgPasskey", "1"); } catch { /* private mode */ } };

/** Signs in with a passkey; resolves to the session token the page then uses like the parent key. */
export async function signInWithPasskey() {
  try {
    const o = await post("/passkey/login/options");
    const cred = await navigator.credentials.get({ publicKey: { ...o.publicKey, challenge: unb(o.publicKey.challenge) } });
    if (!cred) throw new Error("No passkey was chosen.");
    const r = await post("/passkey/login", { id: b64u(cred.rawId), clientDataJSON: b64u(cred.response.clientDataJSON), authenticatorData: b64u(cred.response.authenticatorData), signature: b64u(cred.response.signature), label: deviceLabel() });
    remember(); return r.token;
  } catch (e) { throw friendly(e); }
}

/** Adds a passkey for this device (the browser is already signed in; `api` carries the sign-in). */
export async function addPasskey(api) {
  try {
    const label = deviceLabel(), o = await api("/passkey/register/options", "POST", { label });
    const cred = await navigator.credentials.create(forCreate(o));
    await api("/passkey/register", "POST", attestation(cred, label)); remember();
  } catch (e) { throw friendly(e); }
}

/** Adds this device with a one-time link from another signed-in browser; resolves to this device's session token. */
export async function addWithInvite(invite) {
  try {
    const label = deviceLabel(), o = await post("/passkey/invite/options", { invite, label });
    const cred = await navigator.credentials.create(forCreate(o));
    const r = await post("/passkey/invite/register", { invite, ...attestation(cred, label) }); remember(); return r.token;
  } catch (e) { throw friendly(e); }
}
