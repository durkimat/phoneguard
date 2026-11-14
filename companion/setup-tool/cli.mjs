// Prototype command line for the setup engine.  CF_TOKEN=... node cli.mjs [setup|delete <name>] [--account ID]
import QRCode from "qrcode";
import { writeFile } from "node:fs/promises";
import { setUp, cf, deleteWorker, listAccounts } from "./engine.mjs";

const token = process.env.CF_TOKEN; if (!token) { console.error("Set CF_TOKEN to a Cloudflare API token (Workers Scripts: Edit)."); process.exit(1); }
const [cmd = "setup", arg] = process.argv.slice(2);
const acct = process.argv.includes("--account") ? process.argv[process.argv.indexOf("--account") + 1] : undefined;

if (cmd === "delete") {
  const call = cf(token); const account = acct ?? (await listAccounts(call))[0].id;
  await deleteWorker(call, account, arg); console.log("deleted", arg); process.exit(0);
}
const r = await setUp({ token, account: acct, progress: (s, t) => console.log(`- ${t}`) });
if (r.needAccount) { console.log("More than one account, pass --account:", r.needAccount); process.exit(2); }
const file = `family-${r.name}.json`;
await writeFile(file, JSON.stringify({ account: r.account, name: r.name, host: r.host, parentKey: r.adminToken }, null, 2), { mode: 0o600 });
console.log(`\nYour companion: https://${r.host}\nPrivate parent link (open on YOUR phone, keep it secret): https://${r.host}/#k=${r.adminToken}\nSaved parent key in ${file} (keep it private).\n\nOn the child's phone scan this, or type the address and code:\n`);
console.log(await QRCode.toString(r.qr, { type: "terminal", small: true }));
console.log(`Address: ${r.typed.address}\nCode:    ${r.typed.code}   (valid 10 minutes)\nQR text: ${r.qr}`);
