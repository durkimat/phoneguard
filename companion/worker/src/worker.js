// Front door: routes phone calls (signed, no login) and parent calls (bearer token) to the family's Durable Object.
import { safeEqual } from "./crypto.js";
import { INDEX, APP, TIMETABLE, SPOTIFY, SEAL, CRYPTO, QR, ENROL, GUIDE, UI, PHONE as PHONE_JS, LOOKOPT } from "./page-assets.js";
export { Family } from "./family.js";

const PHONE = [/^\/api\/info$/, /^\/api\/pair$/, /^\/api\/pair\/[A-Z0-9]+$/, /^\/api\/sync$/];
const PARENT = [/^\/api\/enrol\/new$/, /^\/api\/pairings(\/[A-Z0-9]+\/confirm)?$/, /^\/api\/devices(\/[a-z0-9]+(\/[a-z0-9-]+(\/[a-z-]+)?)?)?$/, /^\/api\/options$/, /^\/api\/check-updates$/];

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (!url.pathname.startsWith("/api/")) return staticPage(url.pathname);
    const isPhone = PHONE.some((r) => r.test(url.pathname)), isParent = PARENT.some((r) => r.test(url.pathname));
    if (!isPhone && !isParent) return Response.json({ error: "not found" }, { status: 404 });
    if (isParent) {
      const auth = req.headers.get("authorization") ?? "";
      if (!env.ADMIN_TOKEN || !safeEqual(auth, `Bearer ${env.ADMIN_TOKEN}`)) return Response.json({ error: "parent login required" }, { status: 401 });
    }
    const stub = env.FAMILY.get(env.FAMILY.idFromName("family"));
    const inner = new Request("https://family" + url.pathname.slice(4) + url.search, { method: req.method, headers: { "content-type": "application/json" }, body: req.method === "POST" ? await req.text() : undefined });
    return stub.fetch(inner);
  },
};

const SECURITY = { "content-security-policy": "default-src 'self'; style-src 'unsafe-inline'; connect-src 'self' https://accounts.spotify.com; frame-ancestors 'none'", "referrer-policy": "no-referrer", "x-content-type-options": "nosniff", "cache-control": "no-store" };
const JS = "text/javascript; charset=utf-8";
const FILES = { "/": [INDEX, "text/html; charset=utf-8"], "/app.js": [APP, JS], "/spotify/callback": [INDEX, "text/html; charset=utf-8"], "/timetable.js": [TIMETABLE, JS], "/spotify.js": [SPOTIFY, JS], "/seal.js": [SEAL, JS], "/crypto.js": [CRYPTO, JS], "/qr.js": [QR, JS], "/guide": [GUIDE, "text/html; charset=utf-8"], "/enrol.js": [ENROL, JS], "/ui.js": [UI, JS], "/phone.js": [PHONE_JS, JS], "/look-options.js": [LOOKOPT, JS] };
function staticPage(path) {
  const f = FILES[path];
  return f ? new Response(f[0], { headers: { "content-type": f[1], ...SECURITY } }) : new Response("Not found", { status: 404, headers: SECURITY });
}
