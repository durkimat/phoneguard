// Bundles the companion Worker (../worker/src) into ONE ES module so the tool can upload it through Cloudflare's REST API without wrangler.
import { build } from "../worker/node_modules/esbuild/lib/main.js";
import { mkdirSync, statSync } from "node:fs";
mkdirSync(new URL("./dist", import.meta.url), { recursive: true });
await build({ entryPoints: [new URL("../worker/src/worker.js", import.meta.url).pathname], bundle: true, format: "esm", platform: "neutral", mainFields: ["module", "main"], conditions: ["workerd", "worker", "browser"], external: ["cloudflare:workers"], minify: false, outfile: new URL("./dist/worker.bundle.js", import.meta.url).pathname, logLevel: "warning" });
console.log("dist/worker.bundle.js", statSync(new URL("./dist/worker.bundle.js", import.meta.url)).size, "bytes");

// Next to the bundle: its version, schema and checksum. The update path compares these with what a family's Worker reports, and the release script publishes it as worker-version.json.
import { writeFileSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";
const { COMPANION_VERSION, SCHEMA, PROTOCOL } = await import("../worker/src/version.js");
const code = readFileSync(new URL("./dist/worker.bundle.js", import.meta.url));
const info = { version: COMPANION_VERSION, schema: SCHEMA, protocol: PROTOCOL, sha256: createHash("sha256").update(code).digest("hex"), size: code.length };
writeFileSync(new URL("./dist/worker-version.json", import.meta.url), JSON.stringify(info, null, 2) + "\n");
console.log("dist/worker-version.json", JSON.stringify(info));
