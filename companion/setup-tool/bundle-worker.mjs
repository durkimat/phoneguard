// Bundles the companion Worker (../worker/src) into ONE ES module so the tool can upload it through Cloudflare's REST API without wrangler.
import { build } from "../worker/node_modules/esbuild/lib/main.js";
import { mkdirSync, statSync } from "node:fs";
mkdirSync(new URL("./dist", import.meta.url), { recursive: true });
await build({ entryPoints: [new URL("../worker/src/worker.js", import.meta.url).pathname], bundle: true, format: "esm", platform: "neutral", mainFields: ["module", "main"], conditions: ["workerd", "worker", "browser"], external: ["cloudflare:workers"], minify: false, outfile: new URL("./dist/worker.bundle.js", import.meta.url).pathname, logLevel: "warning" });
console.log("dist/worker.bundle.js", statSync(new URL("./dist/worker.bundle.js", import.meta.url)).size, "bytes");
