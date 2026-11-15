// Command line for the setup engine.  CF_TOKEN=... node cli.mjs [setup|update [name] [--source local|release] [--force]|delete <name>] [--account ID]
import QRCode from "qrcode";
import { writeFile } from "node:fs/promises";
import { setUp, cf, deleteWorker, listAccounts, updateWorker, verifyFamily, discoverWorkers } from "./engine.mjs";
import { readdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";

const token = process.env.CF_TOKEN; if (!token) { console.error("Set CF_TOKEN to a Cloudflare API token (Workers Scripts: Edit)."); process.exit(1); }
const [cmd = "setup", arg] = process.argv.slice(2);
const acct = process.argv.includes("--account") ? process.argv[process.argv.indexOf("--account") + 1] : undefined;

if (cmd === "delete") {
  const call = cf(token); const account = acct ?? (await listAccounts(call))[0].id;
  await deleteWorker(call, account, arg); console.log("deleted", arg); process.exit(0);
}
if (cmd === "update") {
  const call = cf(token), arg2 = (f) => (process.argv.includes(f) ? process.argv[process.argv.indexOf(f) + 1] : undefined), DIR = process.env.PHONEGUARD_DIR ?? `${homedir()}/.phoneguard`;
  const saved = []; for (const f of (await readdir(DIR).catch(() => [])).filter((n) => /^family-.*\.json$/.test(n))) { const j = JSON.parse(await readFile(`${DIR}/${f}`, "utf8")); saved.push({ account: j.account, name: j.name, host: j.host, parentKey: j.parentKey }); }
  let all = saved; if (!all.length) { const account = acct ?? (await listAccounts(call))[0].id; all = await discoverWorkers(call, account); }
  const pick = arg && !arg.startsWith("--") ? all.filter((f) => f.name === arg) : all;
  if (!pick.length) { console.error(arg ? `No companion called ${arg} found.` : "No companion found in this account."); process.exit(1); }
  let failed = false;
  for (const f of pick) {
    console.log(`\n${f.name} (${f.host})`);
    let r; try { r = await updateWorker({ token, family: f, source: arg2("--source") ?? "auto", force: process.argv.includes("--force"), progress: (s, t) => console.log(`- ${t}`) }); } catch (e) { console.error(`STOPPED: ${e.message}`); failed = true; continue; }
    if (r.upToDate) { console.log(`Already up to date (version ${r.version}).`); continue; }
    console.log(`Updated ${r.from ?? "an older version"} -> ${r.to} (from ${r.source}).`);
    if (f.parentKey) { try { const v = await verifyFamily({ host: f.host, parentKey: f.parentKey }); console.log(`Checked: your parent key works and ${v.phones} phone(s) are still there.`); } catch (e) { console.error(`WARNING: ${e.message}`); failed = true; } }
  }
  process.exit(failed ? 1 : 0);
}
const r = await setUp({ token, account: acct, progress: (s, t) => console.log(`- ${t}`) });
if (r.needAccount) { console.log("More than one account, pass --account:", r.needAccount); process.exit(2); }
const file = `family-${r.name}.json`;
await writeFile(file, JSON.stringify({ account: r.account, name: r.name, host: r.host, parentKey: r.adminToken }, null, 2), { mode: 0o600 });
console.log(`\nYour companion: https://${r.host}\nPrivate parent link (open on YOUR phone, keep it secret): https://${r.host}/#k=${r.adminToken}\nSaved parent key in ${file} (keep it private).\n\nOn the child's phone scan this, or type the address and code:\n`);
console.log(await QRCode.toString(r.qr, { type: "terminal", small: true }));
console.log(`Address: ${r.typed.address}\nCode:    ${r.typed.code}   (valid 10 minutes)\nQR text: ${r.qr}`);
