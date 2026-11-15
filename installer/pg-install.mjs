#!/usr/bin/env node
// Phone Guard installer: preflight, backup, verify, flash. Works on Windows, Linux and macOS with Node 20+.
//   node pg-install.mjs [check|backup|download|verify|flash|all] [--kit DIR] [--backup-dir DIR] [--dry-run] [--yes] [--skip-backup] [--keep-data]
// It only ever writes system_a, userdata and md_udc (see FLASH_STEPS). It never touches the radio, identity or boot partitions.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { openSync, readSync, closeSync, createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync, statSync, statfsSync, appendFileSync, readdirSync, rmSync, chmodSync } from "node:fs";
import { createGunzip, inflateRawSync } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { join, resolve, basename, dirname, sep } from "node:path";
import { homedir, platform } from "node:os";
import readline from "node:readline/promises";

const EXPECTED_MODEL = /J36/i;
const REPO = "durkimat/phoneguard";          // where image releases live (tags starting "image-")
const MIN_BATTERY = 50;

export function parseArgs(argv) {
  const o = { cmd: "all", kit: process.cwd(), dryRun: false, yes: false, skipBackup: false, keepData: false, backupDir: null, download: false };
  const a = [...argv];
  if (a[0] && !a[0].startsWith("--")) o.cmd = a.shift();
  while (a.length) {
    const k = a.shift();
    if (k === "--kit") o.kit = resolve(a.shift()); else if (k === "--backup-dir") o.backupDir = resolve(a.shift());
    else if (k === "--dry-run") o.dryRun = true; else if (k === "--yes") o.yes = true; else if (k === "--skip-backup") o.skipBackup = true; else if (k === "--keep-data") o.keepData = true; else if (k === "--download") o.download = true;
    else throw new Error(`unknown option ${k}`);
  }
  if (!["check", "backup", "verify", "flash", "all", "download"].includes(o.cmd)) throw new Error(`unknown command ${o.cmd}`);
  return o;
}

// ---- running the Android tools --------------------------------------------------------------------------------------------------
export function makeTools({ dir = null, log = () => {}, dryRun = false } = {}) {
  const st = { dir };   // can be changed once adb and fastboot have been downloaded
  const exe = (n) => (st.dir && existsSync(join(st.dir, n + (platform() === "win32" ? ".exe" : ""))) ? join(st.dir, n) : n);
  const run = (tool, args, { mutates = false, timeout = 90_000 } = {}) => {
    const line = `${tool} ${args.join(" ")}`;
    if (mutates && dryRun) { log(`DRY RUN: ${line}`); return { ok: true, out: "", dry: true }; }
    log(`+ ${line}`);
    const r = spawnSync(exe(tool), args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, timeout });   // a real fastboot waits forever when no phone is in fastboot mode
    const out = `${r.stdout ?? ""}${r.stderr ?? ""}`;   // fastboot prints its answers on stderr
    if (r.error) return { ok: false, out: r.error.code === "ENOENT" ? `${tool} was not found` : r.error.code === "ETIMEDOUT" ? `${tool} timed out (is the phone in the right mode?)` : r.error.message, missing: r.error.code === "ENOENT" };
    return { ok: r.status === 0, out };
  };
  return { adb: (args, o) => run("adb", args, o), fastboot: (args, o) => run("fastboot", args, o), setDir: (d) => { st.dir = d; } };
}

// ---- getting adb and fastboot (Google's platform-tools) and, on Windows, Google's USB driver -----------------------------------
// Everything comes from dl.google.com over HTTPS; the size and SHA-1 are checked against what Google's own package list says.
const GOOGLE = "https://dl.google.com/android/repository/";
const hostOs = (p) => ({ win32: "windows", darwin: "macosx", linux: "linux" })[p];

// Looks in Google's package list (XML) for the stable release of `pkgPath` for one operating system.
export function findArchive(xml, pkgPath, os) {
  for (const m of xml.matchAll(/<remotePackage[^>]*path="([^"]+)"[^>]*>([\s\S]*?)<\/remotePackage>/g)) {
    if (m[1] !== pkgPath || !m[2].includes('<channelRef ref="channel-0"/>')) continue;
    for (const a of m[2].matchAll(/<archive>([\s\S]*?)<\/archive>/g)) {
      if (!a[1].includes(`<host-os>${os}</host-os>`)) continue;
      const g = (r) => r.exec(a[1])?.[1];
      const f = { url: g(/<url>([^<]+)<\/url>/), size: Number(g(/<size>(\d+)<\/size>/)), sha1: g(/<checksum[^>]*>([0-9a-f]{40})<\/checksum>/) };
      if (f.url && f.size && f.sha1) return f;
    }
  }
  return null;
}

// Unpacks a zip (stored or deflated entries; no zip64 needed for these small files). Refuses names that would land outside `dest`.
export function unzip(buf, dest) {
  let e = buf.length - 22; while (e >= 0 && buf.readUInt32LE(e) !== 0x06054b50) e--;
  if (e < 0) throw new Error("the download is not a zip file");
  const root = resolve(dest), files = []; let p = buf.readUInt32LE(e + 16);
  for (let i = 0, n = buf.readUInt16LE(e + 10); i < n; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error("the zip file is damaged");
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20), usize = buf.readUInt32LE(p + 24), nl = buf.readUInt16LE(p + 28), xl = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32), attr = buf.readUInt32LE(p + 38) >>> 16, lho = buf.readUInt32LE(p + 42);
    const name = buf.toString("utf8", p + 46, p + 46 + nl); p += 46 + nl + xl + cl;
    const target = resolve(root, name); if (target !== root && !target.startsWith(root + sep)) throw new Error(`the zip file has an unsafe path (${name})`);
    if (name.endsWith("/")) { mkdirSync(target, { recursive: true }); continue; }
    const start = lho + 30 + buf.readUInt16LE(lho + 26) + buf.readUInt16LE(lho + 28), raw = buf.subarray(start, start + csize);
    const data = method === 0 ? raw : method === 8 ? inflateRawSync(raw) : null;
    if (!data || data.length !== usize) throw new Error(`could not unpack ${name} from the zip file`);
    mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, data);
    if (attr & 0o111) chmodSync(target, attr & 0o777);   // keeps adb and fastboot runnable on Linux and macOS
    files.push(name);
  }
  return files;
}

async function fetchGoogle(pkgListFile, pkgPath, os, { fetchImpl, out, what }) {
  const list = await fetchImpl(GOOGLE + pkgListFile, { headers: { "user-agent": "phoneguard-installer" } });
  if (!list.ok) throw new Error(`Google's package list answered ${list.status}. Check your internet connection and try again.`);
  const a = findArchive(await list.text(), pkgPath, os); if (!a) throw new Error(`Google lists no ${what} for this kind of computer.`);
  out(`Downloading ${what} (${Math.round(a.size / 1e6)} MB) from dl.google.com...`);
  const r = await fetchImpl(GOOGLE + a.url, { headers: { "user-agent": "phoneguard-installer" } });
  if (!r.ok) throw new Error(`Downloading ${what} failed (${r.status}).`);
  const buf = Buffer.from(await r.arrayBuffer());
  if (buf.length !== a.size || createHash("sha1").update(buf).digest("hex") !== a.sha1) throw new Error(`The ${what} download did not match Google's checksum. Try again.`);
  return buf;
}

// Puts adb and fastboot in <dest>/platform-tools and returns that folder.
export async function getPlatformTools(dest, { fetchImpl = fetch, out = () => {}, plat = platform() } = {}) {
  const os = hostOs(plat); if (!os) throw new Error(`There is no automatic download of adb and fastboot for ${plat}. Install platform-tools yourself (docs/install/computer-setup.md).`);
  unzip(await fetchGoogle("repository2-3.xml", "platform-tools", os, { fetchImpl, out, what: "Google's platform-tools (adb and fastboot)" }), dest);
  return join(dest, "platform-tools");
}

// Windows only. Reads what Windows knows about the phone's USB connection (the J36 shows as USB VID_0E8D PID_201C in both Android and fastboot mode).
const psRun = (cmd) => { const r = spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command", cmd], { encoding: "utf8", timeout: 30_000 }); return r.status === 0 ? r.stdout : null; };
export function windowsUsbDevices(run = psRun) {
  const txt = run("Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\\VID_0E8D&PID_201C*' } | Select-Object Status,Class,FriendlyName,InstanceId | ConvertTo-Json -Compress");
  if (txt == null) return null;   // PowerShell could not be used: say nothing rather than guess
  const t = txt.trim(); if (!t) return [];
  const j = JSON.parse(t); return Array.isArray(j) ? j : [j];
}
export function windowsAdvice(devs) {
  if (devs == null) return [];
  if (!devs.length) return ["Windows does not see the phone at all. Try another USB cable (it must carry data, not just charge), another USB port on the computer itself (not a hub), and check the phone is switched on."];
  const bad = devs.filter((d) => d.Status !== "OK");
  if (!bad.length) return ["Windows sees the phone and its driver is working. On the phone, look for a USB debugging prompt and tap Allow; if there is none, switch USB debugging off and on again."];
  return [`Windows sees the phone but has no working driver for: ${bad.map((d) => d.FriendlyName || d.InstanceId).join(", ")}.`];
}
// Google's driver only lists Google's own phones, so Windows would refuse it for the J36. This adds the J36's USB ID to a copy of the list.
export function patchInf(text, hwid = "USB\\VID_0E8D&PID_201C") {
  const add = `\r\n;Qin J36 (added by the Phone Guard installer)\r\n%SingleAdbInterface%        = USB_Install, ${hwid}\r\n%CompositeAdbInterface%     = USB_Install, ${hwid}&MI_00\r\n%CompositeAdbInterface%     = USB_Install, ${hwid}&MI_01\r\n`;
  let n = 0; const r = text.replace(/^\[Google\.NT(?:x86|amd64)\][^\r\n]*/gm, (h) => { n++; return h + add.replace(/\r?\n$/, ""); });
  if (n !== 2) throw new Error("Google's driver list has an unexpected layout; it was not changed.");
  return r;
}
export async function getUsbDriver(dest, { fetchImpl = fetch, out = () => {} } = {}) {
  unzip(await fetchGoogle("addon2-1.xml", "extras;google;usb_driver", "windows", { fetchImpl, out, what: "Google's USB driver" }), dest);
  const dir = join(dest, "usb_driver"), inf = join(dir, "android_winusb.inf");
  if (existsSync(inf)) writeFileSync(join(dir, "phoneguard_j36.inf"), patchInf(readFileSync(inf, "latin1")), "latin1");
  return dir;
}

const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));
// Real waiting for the phone to come back in a given mode; tests replace these.
export const realWait = (t, { seconds = 120 } = {}) => ({
  async fastboot() { for (let i = 0; i < seconds; i++) { if (t.fastboot(["devices"]).out.includes("fastboot")) return true; await sleepMs(1000); } return false; },
  async adb() { for (let i = 0; i < seconds; i++) { if (/\tdevice\b/.test(t.adb(["devices"]).out)) return true; await sleepMs(1000); } return false; },
});

const sha256 = (file) => new Promise((res, rej) => { const h = createHash("sha256"); createReadStream(file).on("data", (d) => h.update(d)).on("end", () => res(h.digest("hex"))).on("error", rej); });
const getvar = (t, name) => (new RegExp(`${name}:\\s*(\\S+)`).exec(t.fastboot(["getvar", name]).out)?.[1] ?? "");

// ---- stages ---------------------------------------------------------------------------------------------------------------------
export function preflight(t) {
  const checks = [];
  const add = (name, ok, detail, fix = "") => checks.push({ name, ok, detail, fix });
  const a = t.adb(["version"]), f = t.fastboot(["--version"]);
  add("adb is installed", a.ok, a.ok ? a.out.split("\n")[0] : "not found", "Install Google's platform-tools and put them in the PATH or in the 'platform-tools' folder next to this script (docs/install/computer-setup.md).");
  add("fastboot is installed", f.ok, f.ok ? f.out.split("\n")[0] : "not found", "Same as above.");
  if (!a.ok || !f.ok) return { checks, mode: null, ok: false };
  const ad = t.adb(["devices"]).out.split("\n").map((l) => l.trim().split(/\s+/)).filter((p) => p.length === 2 && p[0] !== "*" && p[1] !== "attached"), fd = t.fastboot(["devices"]).out.split("\n").map((l) => l.trim().split(/\s+/)).filter((p) => p[0] && p[1] === "fastboot");
  let mode = null;
  if (ad.length === 1 && ad[0][1] === "device") mode = "adb"; else if (fd.length === 1) mode = "fastboot";
  add("exactly one phone is connected", mode != null, mode ? `${mode} mode` : (ad.length + fd.length > 1 ? "more than one device: unplug the others" : ad[0]?.[1] === "unauthorized" ? "the phone says unauthorized: tap Allow on its screen" : "none found"), "Plug in the phone with a data cable, turn on USB debugging and allow this computer (docs/install/computer-setup.md).");
  if (!mode) return { checks, mode, ok: false };
  if (mode === "adb") {
    const model = t.adb(["shell", "getprop", "ro.product.vendor.model"]).out.trim() || t.adb(["shell", "getprop", "ro.product.model"]).out.trim();
    add("the phone is a Qin J36", EXPECTED_MODEL.test(model), model || "unknown", "This installer is only for the Qin J36. Do not continue with another model.");
    const level = Number(/level:\s*(\d+)/.exec(t.adb(["shell", "dumpsys", "battery"]).out)?.[1] ?? NaN);
    add(`battery is ${MIN_BATTERY}% or more`, level >= MIN_BATTERY, Number.isFinite(level) ? `${level}%` : "unknown", "Charge the phone first.");
  } else {
    const unlocked = getvar(t, "unlocked");
    add("the bootloader is unlocked", unlocked === "yes", unlocked || "unknown", "Unlock it first (docs/install/unlock-bootloader.md).");
  }
  return { checks, mode, ok: checks.every((c) => c.ok) };
}

export async function verifyKit(kit) {
  const sums = join(kit, "SHA256SUMS");
  if (!existsSync(sums)) return { ok: false, lines: [`SHA256SUMS not found in ${kit}`] };
  const lines = []; let ok = true;
  for (const l of readFileSync(sums, "utf8").split("\n").map((x) => x.trim()).filter(Boolean)) {
    const m = /^([0-9a-fA-F]{64})\s+\*?(.+)$/.exec(l); if (!m) continue;
    const f = join(kit, m[2]); if (m[2].endsWith(".gz") && !existsSync(f)) continue;   // the compressed copy may be deleted once unpacked
    const good = existsSync(f) && (await sha256(f)) === m[1].toLowerCase();
    lines.push(`${m[2]}: ${good ? "OK" : "FAILED"}`); ok &&= good;
  }
  if (!existsSync(join(kit, "system.img"))) { ok = false; lines.push("system.img: missing"); }
  return { ok, lines };
}

const freeBytes = (dir) => { try { const s = statfsSync(dir); return Number(s.bavail) * Number(s.bsize); } catch { return Infinity; } };

// Unpacks system.img.gz (the form the image is published in) next to it. Nothing is overwritten if system.img is already there.
export async function prepareKit(kit, { out = () => {} } = {}) {
  const img = join(kit, "system.img"), gz = join(kit, "system.img.gz");
  if (existsSync(img) || !existsSync(gz)) return { unpacked: false };
  const fd = openSync(gz, "r"), tail = Buffer.alloc(4); readSync(fd, tail, 0, 4, statSync(gz).size - 4); closeSync(fd);
  const need = tail.readUInt32LE(0) + 256 * 1024 ** 2;   // a gzip file ends with the unpacked size (the image is under 4 GB)
  if (freeBytes(kit) < need) throw new Error(`There is not enough free disk space to unpack the image (about ${Math.ceil(need / 1024 ** 3 * 10) / 10} GB is needed).`);
  out("Unpacking system.img.gz (about 2.4 GB; this takes a minute)...");
  try { await pipeline(createReadStream(gz), createGunzip(), createWriteStream(img)); } catch (e) { rmSync(img, { force: true }); throw new Error(`Could not unpack the image: ${e.message}. Download it again.`); }
  return { unpacked: true };
}

// Finds the newest "image-" release on GitHub and saves its system.img.gz and SHA256SUMS into `dir`.
export async function downloadImage(dir, { fetchImpl = fetch, out = () => {}, repo = REPO, reserve = 3 * 1024 ** 3 } = {}) {
  mkdirSync(dir, { recursive: true });
  const r = await fetchImpl(`https://api.github.com/repos/${repo}/releases?per_page=30`, { headers: { accept: "application/vnd.github+json", "user-agent": "phoneguard-installer" } });
  if (!r.ok) throw new Error(`GitHub answered ${r.status} when looking for the image. Check your internet connection and try again.`);
  const rel = (await r.json()).filter((x) => !x.draft && String(x.tag_name).startsWith("image-")).sort((a, b) => (a.published_at < b.published_at ? 1 : -1))[0];
  if (!rel) throw new Error("No image has been published yet. Ask the project owner for the image kit (docs/install/get-the-image.md).");
  const need = ["system.img.gz", "SHA256SUMS"], assets = need.map((n) => rel.assets.find((a) => a.name === n));
  if (assets.some((a) => !a)) throw new Error(`The release ${rel.tag_name} is missing ${need.filter((_, i) => !assets[i]).join(" and ")}.`);
  if (freeBytes(dir) < assets[0].size + reserve) throw new Error("There is not enough free disk space for the download and the unpacked image (about 5 GB).");
  out(`Downloading ${rel.tag_name}: ${rel.name ?? ""}`);
  for (const [i, a] of assets.entries()) {
    const res = await fetchImpl(a.browser_download_url, { headers: { "user-agent": "phoneguard-installer" } });
    if (!res.ok) throw new Error(`Downloading ${a.name} failed (${res.status}).`);
    let got = 0, lastPct = -1;
    const counter = async function* (src) { for await (const chunk of src) { got += chunk.length; const pct = Math.floor((got / a.size) * 10) * 10; if (pct !== lastPct && a.size > 1e6) { lastPct = pct; out(`  ${a.name}: ${pct}%`); } yield chunk; } };
    await pipeline(Readable.fromWeb(res.body), counter, createWriteStream(join(dir, a.name)));
  }
  return { tag: rel.tag_name, dir };
}

// Reads from the phone only. Writes a dated folder with what is needed to understand or undo a mistake, and a checksum list.
export async function backup(t, dir, { manifest = null, wait = { fastboot: async () => true, adb: async () => true } } = {}) {
  mkdirSync(dir, { recursive: true });
  const notes = [];
  const save = (name, text) => { writeFileSync(join(dir, name), text); notes.push(name); };
  save("getprop.txt", t.adb(["shell", "getprop"]).out);
  save("packages.txt", t.adb(["shell", "pm", "list", "packages", "-f"]).out);
  save("battery.txt", t.adb(["shell", "dumpsys", "battery"]).out);
  if (!t.dryRun) {
    t.adb(["reboot", "bootloader"], { mutates: true });
    if (!(await wait.fastboot())) throw new Error("The phone did not appear in fastboot mode.");
    save("fastboot-getvar-all.txt", t.fastboot(["getvar", "all"], { timeout: 30_000 }).out);
    t.fastboot(["reboot"], { mutates: true });
    if (!(await wait.adb())) throw new Error("The phone did not come back after the backup. Unlock it and check USB debugging, then run again.");
  }
  if (manifest) {
    mkdirSync(join(dir, "stock-files"), { recursive: true });
    for (const f of manifest.files ?? []) { const dest = join(dir, "stock-files", f.path.replace(/^\/+/, "").replace(/[\\/]/g, "__")); const r = t.adb(["pull", f.path, dest]); notes.push(`${f.path}: ${r.ok ? "saved" : "NOT FOUND (" + r.out.trim().slice(0, 80) + ")"}`); }
  }
  const files = readdirSync(dir, { recursive: true, withFileTypes: true }).filter((e) => e.isFile()).map((e) => join(e.parentPath ?? e.path, e.name));
  const sums = []; for (const f of files) if (basename(f) !== "SHA256SUMS") sums.push(`${await sha256(f)}  ${f.slice(dir.length + 1).replace(/\\/g, "/")}`);
  writeFileSync(join(dir, "SHA256SUMS"), sums.join("\n") + "\n");
  return { dir, notes };
}

// The ONLY partitions this installer writes. Order matters: fastbootd first, then room, then the image, then a clean start.
export function flashSteps({ systemBytes, keepData }) {
  const steps = [
    { title: "Restart into fastbootd", args: ["reboot", "fastboot"], wait: 8, expect: ["is-userspace", "yes"] },
    { title: "Make room for the image", args: ["resize-logical-partition", "system_a", String(systemBytes)] },
    { title: "Write the system image (several minutes, do not unplug)", args: ["flash", "system_a", "system.img"] },
  ];
  if (!keepData) steps.push({ title: "Erase user data", args: ["erase", "userdata"] }, { title: "Erase /metadata (md_udc on this phone)", args: ["erase", "md_udc"] });
  steps.push({ title: "Restart", args: ["reboot"] });
  return steps;
}

export async function flash(t, kit, { keepData, ask, wait = { fastboot: async () => true } }) {
  const img = join(kit, "system.img"), bytes = statSync(img).size;
  if (!t.dryRun) { const pre = getvar(t, "unlocked"); if (pre !== "yes") throw new Error(`The bootloader is not unlocked (fastboot says unlocked: ${pre || "unknown"}).`); }
  await ask(keepData ? "This REPLACES the system image and KEEPS your data." : "This REPLACES the system image and ERASES everything on the phone.");
  for (const s of flashSteps({ systemBytes: bytes, keepData })) {
    const args = s.args.map((x) => (x === "system.img" ? img : x));
    const r = t.fastboot(args, { mutates: true, timeout: args[0] === "flash" ? 40 * 60_000 : 5 * 60_000 });
    if (!r.ok && !r.dry) throw new Error(`${s.title} failed: ${r.out.trim().slice(0, 300)}`);
    if (s.expect && !t.dryRun) {
      if (!(await wait.fastboot())) throw new Error("The phone did not come back in fastbootd.");
      const v = getvar(t, s.expect[0]); if (v !== s.expect[1]) throw new Error(`Expected ${s.expect[0]}: ${s.expect[1]} but the phone says ${v || "nothing"}.`);
    }
  }
}

// ---- command line ----------------------------------------------------------------------------------------------------------------
export async function main(argv, { out = console.log, input = process.stdin, toolDir = null, wait = null, fetchImpl = fetch, reserve, plat = platform(), winUsb = windowsUsbDevices } = {}) {
  const o = parseArgs(argv), stamp = new Date().toISOString().replace(/[:T]/g, "-").slice(0, 19), logDir = o.backupDir ?? join(homedir(), "phoneguard-install", stamp);
  mkdirSync(logDir, { recursive: true });
  const logFile = join(logDir, "install.log"), log = (m) => { appendFileSync(logFile, m + "\n"); if (m.startsWith("DRY RUN:") && !m.startsWith("DRY RUN: nothing")) out("  would run: " + m.slice(9)); };
  const rl = o.yes ? null : readline.createInterface({ input, output: process.stdout });
  const ask = async (q) => { out(`\n${q}`); if (o.yes) return; const a = await rl.question("Type yes to continue: "); if (a.trim().toLowerCase() !== "yes") throw new Error("Stopped by you. Nothing more was written."); };
  const confirm = async (q) => { out(`\n${q}`); if (o.yes) return true; return (await rl.question("Type yes to continue: ")).trim().toLowerCase() === "yes"; };
  const t = makeTools({ dir: toolDir ?? (existsSync(join(o.kit, "platform-tools")) ? join(o.kit, "platform-tools") : null), log, dryRun: o.dryRun }); t.dryRun = o.dryRun; wait ??= realWait(t);
  const show = (r) => { for (const c of r.checks) out(`${c.ok ? "  OK  " : " FAIL "} ${c.name}${c.detail ? ": " + c.detail : ""}${c.ok ? "" : "\n        -> " + c.fix}`); };
  try {
    if (o.dryRun) out("DRY RUN: nothing will be written to the phone.");
    if (["check", "backup", "flash", "all"].includes(o.cmd) && !(t.adb(["version"]).ok && t.fastboot(["--version"]).ok)) {   // no adb or fastboot yet: fetch Google's
      out("\n== Getting adb and fastboot");
      if (!(await confirm(`adb and fastboot (Google's "platform-tools") were not found. Download them from Google (about 10 to 17 MB) into ${join(o.kit, "platform-tools")}?`))) throw new Error("adb and fastboot are needed. Install them yourself (docs/install/computer-setup.md) and run again.");
      t.setDir(await getPlatformTools(o.kit, { fetchImpl, out: (m) => out("  " + m), plat })); out("  Done.");
    }
    if (o.cmd === "check" || o.cmd === "all") { out("\n== Checking your computer and phone"); const r = preflight(t); show(r);
      if (!r.ok && !r.mode && plat === "win32" && r.checks.every((c) => c.ok || c.name.startsWith("exactly"))) {   // Windows and no phone seen: look at the USB driver
        const devs = winUsb(); for (const l of windowsAdvice(devs)) out("  " + l);
        if (devs?.some((d) => d.Status !== "OK") && await confirm("Download Google's USB driver so you can install it?")) {
          const dir = await getUsbDriver(o.kit, { fetchImpl, out: (m) => out("  " + m) });
          out(`  Saved to ${dir}. To install it: open Device Manager (Windows key, type "device manager"), right-click the phone under "Other devices" or "Portable devices", choose Update driver, Browse my computer for drivers, Let me pick from a list, Have Disk, then choose phoneguard_j36.inf in that folder (Google's own list does not include this phone, so the installer made a copy with it added). Windows may warn that it cannot verify the publisher: choose to install anyway. Then unplug and replug the phone and run this again.`);
          out("  This has not been tried on a real Windows computer yet. If it does not work, see docs/install/windows-usb.md.");
        }
      } if (!r.ok && o.cmd === "check") return 1; if (!r.ok) throw new Error("Fix the problems above and run again."); }
    const have = existsSync(join(o.kit, "system.img")) || existsSync(join(o.kit, "system.img.gz"));
    if (o.cmd === "download" || (!have && (o.cmd === "all" || o.cmd === "flash"))) {
      if (!o.download && o.cmd !== "download") throw new Error(`No image was found in ${o.kit}. Put the kit there, or add --download to fetch the published image.`);
      out("\n== Getting the image"); if (o.dryRun) out("  would download the newest image release from GitHub"); else await downloadImage(o.kit, { fetchImpl, out: (m) => out("  " + m), ...(reserve != null ? { reserve } : {}) });
      if (o.cmd === "download") { await prepareKit(o.kit, { out }); const v = await verifyKit(o.kit); for (const l of v.lines) out("  " + l); if (!v.ok) throw new Error("The downloaded image did not pass its check. Try again."); return 0; }
    }
    if (o.cmd === "backup" || (o.cmd === "all" && !o.skipBackup)) {
      out("\n== Backing up (reads from the phone only)");
      if (preflight(t).mode !== "adb") { if (!o.dryRun) throw new Error("Put the phone in normal Android mode with USB debugging on, then run again."); }
      const manifestFile = join(o.kit, "proprietary-manifest.json"), manifest = existsSync(manifestFile) ? JSON.parse(readFileSync(manifestFile, "utf8")) : null;
      const b = await backup(t, join(logDir, "backup"), { manifest, wait }); out(`Saved to ${b.dir}`); for (const n of b.notes) out(`  ${n}`);
      out("\nFor the phone's IMEI and radio calibration, a deeper read-only backup with mtkclient is strongly recommended and takes about half an hour: docs/recovery-guide.md section 4.");
      if (!o.yes) await ask("Have you done that (or decided to go without it)?");
    }
    if (o.cmd === "verify" || o.cmd === "flash" || o.cmd === "all") { out("\n== Checking the image"); if (!o.dryRun || existsSync(join(o.kit, "system.img.gz"))) await prepareKit(o.kit, { out }); const v = await verifyKit(o.kit); for (const l of v.lines) out("  " + l); if (!v.ok) throw new Error("The image did not pass its check. Download the kit again; do not flash it."); }
    if (o.cmd === "flash" || o.cmd === "all") {
      if (o.cmd === "flash" && !o.skipBackup && !existsSync(join(logDir, "backup")) && !o.yes) await ask("No backup was made by this run. Flash without one?");
      out("\n== Flashing");
      if (!o.dryRun && preflight(t).mode === "adb") { t.adb(["reboot", "bootloader"], { mutates: true }); if (!(await wait.fastboot())) throw new Error("The phone did not appear in fastboot mode."); }
      await flash(t, o.kit, { keepData: o.keepData, ask, wait });
      out(o.dryRun ? "\nDry run finished: nothing was changed on the phone." : "\nDone. The first start takes several minutes. When the phone shows its first-run screen, choose Open phone or Family phone. For a Family phone, continue with the setup wizard (docs/family-setup.md).");
    }
    out(`\nA log of everything this run did is in ${logFile}`);
    return 0;
  } catch (e) { out(`\nSTOPPED: ${e.message}`); out(`Log: ${logFile}`); return 1; }
  finally { rl?.close(); }
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("pg-install.mjs")) process.exitCode = await main(process.argv.slice(2));
