// Offline checks of the setup engine against a fake Cloudflare API and a fake Worker. Real-account run: see cli.mjs.
import assert from "node:assert/strict";
import fs from "node:fs";
import { setUp, enrolPayload, keyFingerprint } from "../engine.mjs";

const v = JSON.parse(fs.readFileSync(new URL("../../worker/test/vectors.json", import.meta.url), "utf8"));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

// fingerprint and payload reproduce the shared vectors
assert.equal(keyFingerprint(v.server.publicKeySpki), v.enrolPayload.qr.fp); ok("fingerprint matches the shared vector");
const p = enrolPayload({ host: v.enrolPayload.qr.host, token: v.pairing.token, serverKey: v.server.publicKeySpki });
assert.equal(p.qr, v.enrolPayload.qr.text); assert.deepEqual(p.typed, { address: v.enrolPayload.typed.address, code: v.enrolPayload.typed.code }); ok("QR text and typed code match the shared vectors");

// a fake Cloudflare + Worker
const calls = []; let uploaded = null, scriptThere = false, subdomain = null;
const fake = async (url, init = {}) => {
  url = String(url); const m = init.method ?? "GET"; calls.push(`${m} ${url.replace("https://api.cloudflare.com/client/v4", "")}`);
  const res = (result) => new Response(JSON.stringify({ success: true, result }), { status: 200 });
  const err = (status, code) => new Response(JSON.stringify({ success: false, errors: [{ code }] }), { status });
  if (url.endsWith("/accounts?per_page=50")) return res([{ id: "acc1", name: "Jane" }]);
  if (url.endsWith("/workers/subdomain") && m === "GET") return subdomain ? res({ subdomain }) : err(404, 10007);
  if (url.endsWith("/workers/subdomain") && m === "PUT") { subdomain = JSON.parse(init.body).subdomain; return res({ subdomain }); }
  if (url.includes("/settings")) return scriptThere ? res({}) : err(404, 10007);
  if (m === "PUT" && url.includes("/workers/scripts/")) { uploaded = JSON.parse(await init.body.get("metadata").text()); scriptThere = true; return res({}); }
  if (url.endsWith("/subdomain") && m === "POST") return res({ enabled: true });
  if (url.endsWith("/api/info")) return new Response(JSON.stringify({ v: 1, serverKey: v.server.publicKeySpki }), { status: 200 });
  if (url.endsWith("/api/enrol/new")) { assert.match(init.headers.Authorization, /^Bearer .{20,}/); return new Response(JSON.stringify({ token: v.pairing.token, expires: 1, serverKey: v.server.publicKeySpki }), { status: 200 }); }
  throw new Error("unexpected " + m + " " + url);
};
const steps = []; const r = await setUp({ token: "t", name: "phoneguard-test", fetchImpl: fake, code: "export default {}", progress: (s) => steps.push(s) });
assert.deepEqual(steps, ["account", "subdomain", "upload", "wait", "enrol"]); ok("steps run in order");
assert.ok(uploaded.migrations.new_sqlite_classes.includes("Family") && uploaded.bindings.some(b => b.type === "secret_text" && b.name === "ADMIN_TOKEN" && b.text === r.adminToken)); ok("first upload carries the Durable Object migration and the generated ADMIN_TOKEN");
assert.match(r.host, /^phoneguard-test\.pg-[0-9a-f]{8}\.workers\.dev$/); assert.equal(r.qr.split("&h=")[1].split("&")[0], r.host); ok("host and QR agree");
await setUp({ token: "t", name: "phoneguard-test", fetchImpl: fake, code: "export default {}" });
assert.ok(uploaded.keep_bindings.includes("secret_text") && !uploaded.migrations); ok("an update keeps the secret and sends no migration");
console.log(`\n${n} checks passed`);
