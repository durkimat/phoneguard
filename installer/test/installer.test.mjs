// Tests the installer against fake adb and fastboot programs that record every command (no phone needed).
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, readFileSync, existsSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { parseArgs, makeTools, preflight, verifyKit, backup, flashSteps, main } from "../pg-install.mjs";

const FAKES = fileURLToPath(new URL("./fakebin", import.meta.url));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const work = mkdtempSync(join(tmpdir(), "pginst-"));
const env = (o = {}) => { for (const k of ["FAKE_MODEL", "FAKE_BATT", "FAKE_UNLOCKED", "FAKE_FAIL_FLASH", "FAKE_FASTBOOT", "FAKE_NO_PULL", "FAKE_ADB_STATE", "FAKE_USERSPACE"]) delete process.env[k]; Object.assign(process.env, o); const log = join(work, "log-" + Math.random().toString(36).slice(2) + ".txt"); process.env.FAKE_LOG = log; writeFileSync(log, ""); return log; };
const cmds = (log) => readFileSync(log, "utf8").split("\n").filter(Boolean);
const mkKit = (bad = false) => { const k = mkdtempSync(join(work, "kit-")); const img = Buffer.from("pretend system image ".repeat(1000)); writeFileSync(join(k, "system.img"), img); writeFileSync(join(k, "SHA256SUMS"), `${createHash("sha256").update(bad ? "x" : img).digest("hex")}  system.img\n`); return k; };
const quiet = [], out = (s) => quiet.push(s), wait = { fastboot: async () => true, adb: async () => true };
const ALLOWED = new Set(["system_a", "userdata", "md_udc"]);
const writes = (log) => cmds(log).filter((c) => /^fastboot (flash|erase|resize-logical-partition|format|write|oem|flashing)/.test(c));

assert.deepEqual(parseArgs(["flash", "--kit", "/k", "--dry-run", "--yes"]), { cmd: "flash", kit: "/k", dryRun: true, yes: true, skipBackup: false, keepData: false, backupDir: null }); assert.throws(() => parseArgs(["--nope"])); assert.throws(() => parseArgs(["explode"])); ok("command line options are parsed and unknown ones refused");

{ env(); const r = preflight(makeTools({ dir: FAKES })); assert.ok(r.ok && r.mode === "adb"); ok("preflight passes for a J36 with enough battery"); }
{ env({ FAKE_MODEL: "Pixel 7" }); const r = preflight(makeTools({ dir: FAKES })); assert.ok(!r.ok); assert.ok(r.checks.some((c) => c.name.includes("J36") && !c.ok)); ok("another phone model is refused"); }
{ env({ FAKE_BATT: "30" }); assert.ok(!preflight(makeTools({ dir: FAKES })).ok); ok("a low battery is refused"); }
{ env({ FAKE_ADB_STATE: "unauthorized" }); const r = preflight(makeTools({ dir: FAKES })); assert.ok(!r.ok); assert.match(r.checks.find((c) => !c.ok).detail, /Allow/); ok("an unauthorised phone says what to do"); }
{ env({ FAKE_FASTBOOT: "1", FAKE_ADB_STATE: "offline" }); const r = preflight(makeTools({ dir: FAKES })); assert.equal(r.mode, "fastboot"); assert.ok(r.ok); ok("a phone in fastboot mode is recognised and its unlock state checked"); env({ FAKE_FASTBOOT: "1", FAKE_ADB_STATE: "offline", FAKE_UNLOCKED: "no" }); assert.ok(!preflight(makeTools({ dir: FAKES })).ok); ok("a locked bootloader is refused"); }
{ const p = process.env.PATH; process.env.PATH = ""; const r = preflight(makeTools({ dir: null })); process.env.PATH = p; assert.ok(!r.ok); assert.match(r.checks[0].fix, /platform-tools/); ok("missing adb or fastboot gives clear instructions"); }

{ const good = await verifyKit(mkKit()); assert.ok(good.ok); const bad = await verifyKit(mkKit(true)); assert.ok(!bad.ok && bad.lines.some((l) => l.includes("FAILED"))); assert.ok(!(await verifyKit(mkdtempSync(join(work, "empty-")))).ok); ok("the image checksum is verified; a wrong or missing one is refused"); }

{ const log = env(), dir = join(work, "bk1"), t = makeTools({ dir: FAKES }); mkdirSync(dir); const r = await backup(t, dir, { manifest: { files: [{ path: "/system/app/ImsService/ImsService.apk" }] } });
  for (const f of ["getprop.txt", "packages.txt", "fastboot-getvar-all.txt", "SHA256SUMS"]) assert.ok(existsSync(join(dir, f)), f); assert.ok(readFileSync(join(dir, "SHA256SUMS"), "utf8").includes("stock-files/")); assert.ok(r.notes.some((x) => x.includes("ImsService.apk: saved"))); assert.deepEqual(writes(log), []); ok("the backup saves the phone's details and the listed stock files, with checksums, and writes nothing to the phone"); }
{ env({ FAKE_NO_PULL: "1" }); const dir = join(work, "bk2"); mkdirSync(dir); const r = await backup(makeTools({ dir: FAKES }), dir, { manifest: { files: [{ path: "/system/missing.apk" }] } }); assert.ok(r.notes.some((x) => x.includes("NOT FOUND"))); ok("a stock file that is not on the phone is reported, not hidden"); }

{ const s = flashSteps({ systemBytes: 123, keepData: false }).map((x) => x.args.join(" ")); assert.deepEqual(s, ["reboot fastboot", "resize-logical-partition system_a 123", "flash system_a system.img", "erase userdata", "erase md_udc", "reboot"]); assert.ok(!flashSteps({ systemBytes: 1, keepData: true }).some((x) => x.args[0] === "erase")); ok("the flash steps are exactly the documented ones; keep-data skips the erases"); }

{ const log = env(), kit = mkKit(), code = await main(["all", "--kit", kit, "--yes", "--backup-dir", join(work, "run1")], { out, toolDir: FAKES, wait }); const c = cmds(log);
  assert.equal(code, 0); const w = writes(log); assert.deepEqual(w, [`fastboot resize-logical-partition system_a ${readFileSync(join(kit, "system.img")).length}`, `fastboot flash system_a ${join(kit, "system.img")}`, "fastboot erase userdata", "fastboot erase md_udc"]);
  for (const x of w) { const target = x.split(" ").filter((p) => !p.startsWith("-"))[2]; assert.ok(ALLOWED.has(target) || target?.endsWith("system.img") || /^\d+$/.test(target), x); } ok("a full run writes only system_a, userdata and md_udc, in the documented order");
  assert.ok(c.indexOf("fastboot reboot fastboot") < c.findIndex((x) => x.startsWith("fastboot flash")) && c.findIndex((x) => x.startsWith("fastboot getvar unlocked")) < c.findIndex((x) => x.startsWith("fastboot flash"))); ok("it checks the unlock state before writing and uses fastbootd"); assert.ok(existsSync(join(work, "run1", "backup", "SHA256SUMS")) && existsSync(join(work, "run1", "install.log"))); ok("a backup folder and a log are left behind"); }
{ const log = env(), kit = mkKit(); const code = await main(["flash", "--kit", kit, "--yes", "--skip-backup", "--keep-data", "--backup-dir", join(work, "run2")], { out, toolDir: FAKES, wait }); assert.equal(code, 0); assert.ok(!cmds(log).some((x) => x.startsWith("fastboot erase"))); ok("--keep-data updates the system and erases nothing"); }
{ const log = env({ FAKE_UNLOCKED: "no", FAKE_FASTBOOT: "1", FAKE_ADB_STATE: "offline" }); const code = await main(["flash", "--kit", mkKit(), "--yes", "--skip-backup", "--backup-dir", join(work, "run3")], { out, toolDir: FAKES, wait }); assert.equal(code, 1); assert.deepEqual(writes(log), []); ok("a locked bootloader stops everything before any write"); }
{ const log = env(); const code = await main(["flash", "--kit", mkKit(true), "--yes", "--skip-backup", "--backup-dir", join(work, "run4")], { out, toolDir: FAKES, wait }); assert.equal(code, 1); assert.deepEqual(writes(log), []); ok("an image that fails its checksum is never flashed"); }
{ const log = env({ FAKE_FAIL_FLASH: "1" }); const code = await main(["flash", "--kit", mkKit(), "--yes", "--skip-backup", "--backup-dir", join(work, "run5")], { out, toolDir: FAKES, wait }); assert.equal(code, 1); assert.ok(!cmds(log).some((x) => x.startsWith("fastboot erase"))); assert.ok(quiet.some((l) => l.includes("STOPPED"))); ok("if writing the image fails, nothing is erased and the reason is shown"); }
{ const log = env({ FAKE_USERSPACE: "no" }); const code = await main(["flash", "--kit", mkKit(), "--yes", "--skip-backup", "--backup-dir", join(work, "run6")], { out, toolDir: FAKES, wait }); assert.equal(code, 1); assert.ok(!cmds(log).some((x) => x.startsWith("fastboot flash"))); ok("if the phone is not in fastbootd, it stops before writing"); }
{ const log = env(); const code = await main(["all", "--kit", mkKit(), "--yes", "--dry-run", "--backup-dir", join(work, "run7")], { out, toolDir: FAKES, wait }); assert.equal(code, 0); assert.deepEqual(writes(log), []); assert.ok(!cmds(log).some((x) => x.startsWith("adb reboot") || x === "fastboot reboot")); ok("a dry run prints the plan and changes nothing on the phone"); }
{ const log = env({ FAKE_MODEL: "Pixel 7" }); const code = await main(["all", "--kit", mkKit(), "--yes", "--backup-dir", join(work, "run8")], { out, toolDir: FAKES, wait }); assert.equal(code, 1); assert.deepEqual(writes(log), []); ok("the wrong phone model stops the whole run"); }
console.log(`\n${n} checks passed`);
