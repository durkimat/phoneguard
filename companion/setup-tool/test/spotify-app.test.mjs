// The Spotify install, against a simulated phone that shows the same screens as the real one (Aurora Store, then Android's installer).
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseScreen, installSpotifyApp, AURORA, SPOTIFY, StepError } from "../spotify-app.mjs";

let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const fakeApk = Buffer.from("pretend aurora apk"), aurora = { ...AURORA, sha256: createHash("sha256").update(fakeApk).digest("hex"), url: "https://example/aurora.apk" };
const xml = (items) => `<?xml version='1.0'?><hierarchy>${items.map(([t, x, y, d = ""]) => `<node index="0" text="${t}" content-desc="${d}" bounds="[${x - 20},${y - 10}][${x + 20},${y + 10}]" />`).join("")}</hierarchy>`;
const SCREENS = {
  welcome: [["Welcome", 240, 120], ["Skip", 120, 593], ["Next", 356, 593]],
  perms: [["Permissions", 145, 125], ["Back", 124, 593], ["Finish", 356, 593]],
  login: [["Please log in to your Google Play account", 240, 406], ["Google", 240, 463], ["Anonymous", 240, 535]],
  home: [["For you", 80, 149], ["Top charts", 240, 149], ["Categories", 400, 149]],
  prompt: [["Continue to Aurora Store?", 284, 421], ["An external app wants to open Aurora Store", 195, 491], ["com.spotify.music", 92, 523], ["Cancel", 354, 604], ["Open", 437, 604]],
  page: [["Spotify: Music and Podcasts", 240, 150], ["Manual download", 120, 320], ["Install", 356, 320]],
  downloading: [["Spotify: Music and Podcasts", 240, 150], ["Cancel", 120, 320], ["Open", 356, 320]],
  installer: [["Spotify", 120, 280], ["Do you want to install this app?", 240, 330], ["Cancel", 290, 405], ["Install", 389, 405]],
  done: [["Spotify: Music and Podcasts", 240, 150], ["Uninstall", 120, 320], ["Open", 356, 320]],
};
const phone = ({ wifi = true, connected = true, spotify = false, approvalWorks = true, anonymousWorks = true, syncReceiver = true } = {}) => {
  const st = { aurora: false, spotify, synced: false, pgOpen: false, screen: null, dumps: 0, log: [], scales: { window_animation_scale: "1.0", transition_animation_scale: "1.0", animator_duration_scale: "null" }, lastDump: "" };
  const adb = async (args) => {
    const cmd = args[0] === "shell" ? args[1] : args.join(" "); st.log.push(cmd);
    if (args[0] === "devices") return { ok: true, out: "List of devices attached\n" + (connected ? "ABC123\tdevice\n" : "") };
    if (args[0] === "install") { st.aurora = true; return { ok: true, out: "Performing Streamed Install\nSuccess" }; }
    if (args[0] === "uninstall") { st.aurora = false; return { ok: true, out: "Success" }; }
    let m;
    if ((m = /^pm list packages (\S+)/.exec(cmd))) return { ok: true, out: (m[1] === SPOTIFY && st.spotify) || (m[1] === AURORA.pkg && st.aurora) ? `package:${m[1]}\n` : "" };
    if (cmd === "cmd wifi status") return { ok: true, out: wifi ? 'Wifi is enabled\nWifi is connected to "home"' : "Wifi is enabled\nWifi is not connected" };
    if (/^appops|^pm grant/.test(cmd)) return { ok: true, out: "" };
    if ((m = /^settings get global (\w+)/.exec(cmd))) return { ok: true, out: st.scales[m[1]] + "\n" };
    if ((m = /^settings put global (\w+) (\S+)/.exec(cmd))) { st.scales[m[1]] = m[2]; return { ok: true, out: "" }; }
    if ((m = /^settings delete global (\w+)/.exec(cmd))) { st.scales[m[1]] = "null"; return { ok: true, out: "" }; }
    if (/^rm -f/.test(cmd)) { st.lastDump = ""; return { ok: true, out: "" }; }
    if (/SyncReceiver/.test(cmd)) { if (!syncReceiver) return { ok: false, out: "Error: Class not found" }; if (approvalWorks) st.synced = true; return { ok: true, out: "Broadcast completed: result=0" }; }
    if (/MainActivity/.test(cmd)) { st.pgOpen = true; return { ok: true, out: "" }; }
    if (/KEYCODE_ENTER/.test(cmd)) { if (st.pgOpen && approvalWorks) st.synced = true; st.pgOpen = false; return { ok: true, out: "" }; }
    if (/^input keyevent/.test(cmd)) return { ok: true, out: "" };
    if (/aurora.store\/\.ComposeActivity/.test(cmd)) { if (st.aurora && st.synced) { st.screen = "welcome"; return { ok: true, out: "Starting: Intent" }; } return { ok: true, out: "Error: Activity class {com.aurora.store/com.aurora.store.ComposeActivity} does not exist." }; }
    if (/market:\/\/details/.test(cmd)) { if (st.screen === "home") st.screen = "prompt"; return { ok: true, out: "Starting" }; }
    if (/^uiautomator dump/.test(cmd)) { st.dumps++; if (st.screen === "downloading" && st.dumps - st.since >= 3) st.screen = "installer";
      const animating = (st.screen === "page" || st.screen === "downloading") && st.scales.animator_duration_scale !== "0"; if (animating) return { ok: false, out: "ERROR: could not get idle state." };   // like the real Aurora page
      st.lastDump = xml(SCREENS[st.screen] ?? []); return { ok: true, out: "UI hierchary dumped to: /sdcard/pg-ui.xml" }; }
    if (/^cat /.test(cmd)) return { ok: true, out: st.lastDump };
    if (/mCurrentFocus/.test(cmd)) return { ok: true, out: st.screen === "installer" ? "mCurrentFocus=Window{1 u0 com.android.packageinstaller/com.android.packageinstaller.PackageInstallerActivity}" : "mCurrentFocus=Window{2 u0 com.aurora.store/com.aurora.store.ComposeActivity}" };
    if ((m = /^input tap (\d+) (\d+)/.exec(cmd))) {
      const hit = (SCREENS[st.screen] ?? []).find(([, x, y]) => Math.abs(x - +m[1]) <= 20 && Math.abs(y - +m[2]) <= 10)?.[0], s = st.screen;
      if (s === "welcome" && hit === "Next") st.screen = "perms"; else if (s === "perms" && hit === "Finish") st.screen = "login";
      else if (s === "login" && hit === "Anonymous" && anonymousWorks) st.screen = "home";
      else if (s === "prompt" && hit === "Open") st.screen = "page";
      else if (s === "page" && hit === "Install") { st.screen = "downloading"; st.since = st.dumps; }
      else if (s === "installer" && hit === "Install") { st.spotify = true; st.screen = "done"; }
      return { ok: true, out: "" };
    }
    return { ok: true, out: "" };
  };
  return { st, adb };
};
const run = async (p, extra = {}) => { const said = [], calls = { approve: [], unapprove: [] };
  const r = await installSpotifyApp({ adb: p.adb, say: (t) => said.push(t), approve: async (x) => calls.approve.push(x), unapprove: async (x) => calls.unapprove.push(x), fetchImpl: async () => ({ ok: true, status: 200, arrayBuffer: async () => fakeApk }), sleep: async () => {}, cacheDir: mkdtempSync(join(tmpdir(), "pgsp-")), aurora, tries: { ui: 30, install: 40, approval: 3 }, ...extra }); return { r, said, calls }; };
const fails = async (p, re, extra) => { await assert.rejects(run(p, extra), (e) => e instanceof StepError && re.test(e.message)); };

{ const s = parseScreen(xml([["Install", 356, 320], ["", 40, 40, "Navigate up"]])); assert.deepEqual(s.map((x) => [x.text || x.desc, x.x, x.y]), [["Install", 356, 320], ["Navigate up", 40, 40]]); ok("the phone's screen dump is read into buttons with their centre points"); }
{ const p = phone(); const { r, said, calls } = await run(p); assert.deepEqual(r, { installed: true }); assert.ok(p.st.spotify && !p.st.aurora); assert.deepEqual(calls.approve, [[AURORA.pkg, SPOTIFY]]); assert.deepEqual(calls.unapprove, [[AURORA.pkg]]); assert.ok(said.some((t) => /Removing Aurora/.test(t))); assert.deepEqual(p.st.scales, { window_animation_scale: "1.0", transition_animation_scale: "1.0", animator_duration_scale: "null" }); assert.ok(p.st.log.indexOf("uninstall com.aurora.store") > p.st.log.findIndex((c) => c === "am start -a android.intent.action.VIEW -d market://details?id=com.spotify.music -p com.aurora.store")); assert.ok(p.st.log.some((c) => /SyncReceiver/.test(c)) && !p.st.log.some((c) => /MainActivity/.test(c))); ok("a full install, on a phone whose app page never stops animating: animations are switched off while it runs and put back exactly as they were, Aurora is set up anonymously, installs Spotify, and is then removed and un-approved"); }
{ const p = phone({ syncReceiver: false }); const { r } = await run(p); assert.deepEqual(r, { installed: true }); assert.ok(p.st.log.some((c) => /MainActivity/.test(c))); ok("on an older phone app without the sync message it still works, by pressing Sync now on the status page"); }
{ const p = phone({ spotify: true }); const { r } = await run(p); assert.deepEqual(r, { already: true }); assert.ok(!p.st.aurora); assert.ok(!p.st.log.some((c) => c.startsWith("install"))); ok("when Spotify is already on the phone nothing is installed"); }
{ const p = phone({ wifi: false }); await fails(p, /not on Wi-Fi/); assert.ok(!p.st.aurora); ok("not on Wi-Fi: stops before installing anything and says why"); }
{ const p = phone({ connected: false }); await fails(p, /not connected/); ok("no phone on the cable: a plain message"); }
{ const p = phone(); await fails(p, /did not match its checksum/, { fetchImpl: async () => ({ ok: true, status: 200, arrayBuffer: async () => Buffer.from("tampered") }) }); assert.ok(!p.st.aurora); ok("a download that does not match the pinned checksum is never installed"); }
{ const p = phone({ approvalWorks: false }); await fails(p, /did not pick up the approval/); ok("if the phone never picks up the approval, it says how to do it by hand"); }
{ const p = phone({ anonymousWorks: false }); await fails(p, /did not finish setting itself up/, { tries: { ui: 8, install: 5, approval: 3 } }); assert.equal(p.st.scales.animator_duration_scale, "null"); ok("if Aurora's sign-in does not complete, it stops with instructions, and the animation settings are still put back"); }
console.log(`\n${n} checks passed`);
