// The phone enrolment payload (see PROTOCOL-DRAFT.md "Enrolment payload"), built in the parent's browser. Must agree with setup-tool/engine.mjs and vectors.json.
const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
export async function enrolPayload({ host, token, serverKey }) {
  const t = token.toUpperCase().replace(/[^A-Z0-9]/g, "");
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", Uint8Array.from(atob(serverKey), (c) => c.charCodeAt(0)))).slice(0, 16);
  return { qr: `phoneguard://enrol?v=1&h=${host}&t=${t}&f=${b64url(digest)}`, address: host.endsWith(".workers.dev") ? host.slice(0, -".workers.dev".length) : host, code: `${t.slice(0, 5)}-${t.slice(5)}` };
}
