// The local page's guard rails: wrong host, missing session key, bad key text.
import assert from "node:assert/strict";
process.env.NO_OPEN = "1";
const { server, session } = await import("../server.mjs");
await new Promise((r) => (server.listening ? r() : server.once("listening", r)));
const base = `http://127.0.0.1:${server.address().port}`;
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const page = await fetch(base + "/"); const html = await page.text();
assert.equal(page.status, 200); assert.ok(html.includes(session) && !html.includes("__SESSION__")); ok("page served with the session key filled in");
assert.equal((await fetch(base + "/status")).status, 403); ok("status needs the session key");
assert.equal((await fetch(base + "/run", { method: "POST", body: "{}" })).status, 403); ok("run needs the session key");
assert.equal((await fetch(base + "/run", { method: "POST", headers: { "x-session": session }, body: JSON.stringify({ token: "short" }) })).status, 400); ok("an obviously wrong key is rejected before calling Cloudflare");
import http from "node:http";
const rebind = await new Promise((res) => http.get({ host: "127.0.0.1", port: server.address().port, path: "/", headers: { host: "evil.example:80" } }, (r) => { r.resume(); res(r.statusCode); }));
assert.equal(rebind, 403); ok("a foreign Host header (DNS rebinding) is refused");
const s = await (await fetch(base + "/status", { headers: { "x-session": session } })).json(); assert.deepEqual(s.steps, []); ok("status empty before a run");
server.close(); console.log(`\n${n} checks passed`);
