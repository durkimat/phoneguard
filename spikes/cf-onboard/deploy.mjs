// Spike (UNTESTED against a real account): deploy src/worker.js to a Cloudflare account using only the REST API and a bearer token,
// the way a parent-facing setup page would. No wrangler, no GitHub.
//   CF_TOKEN=... CF_ACCOUNT=... node deploy.mjs [script-name]
// The token needs: Account > Workers Scripts > Edit (create it from the pre-filled link in README.md).
import { readFile } from "node:fs/promises";

const token = process.env.CF_TOKEN, account = process.env.CF_ACCOUNT, name = process.argv[2] ?? "phoneguard-spike";
if (!token || !account) { console.error("set CF_TOKEN and CF_ACCOUNT"); process.exit(1); }
const api = (path, init = {}) => fetch(`https://api.cloudflare.com/client/v4/accounts/${account}${path}`, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) } }).then(async r => { const j = await r.json().catch(() => ({})); if (!j.success) throw new Error(`${path}: ${r.status} ${JSON.stringify(j.errors ?? j)}`); return j.result; });

const code = await readFile(new URL("./src/worker.js", import.meta.url), "utf8");
const metadata = {
  main_module: "worker.js",
  compatibility_date: "2025-09-01",
  bindings: [{ type: "durable_object_namespace", name: "HUB", class_name: "Hub" }],
  migrations: { new_tag: "v1", new_sqlite_classes: ["Hub"] },   // only on the first upload; later uploads send {old_tag:"v1", new_tag:"v2", ...} or omit
};
const form = new FormData();
form.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
form.append("worker.js", new Blob([code], { type: "application/javascript+module" }), "worker.js");

console.log("uploading...");
await api(`/workers/scripts/${name}`, { method: "PUT", body: form });
await api(`/workers/scripts/${name}/subdomain`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: true }) });
const { subdomain } = await api(`/workers/subdomain`);
console.log(`deployed: https://${name}.${subdomain}.workers.dev`);
