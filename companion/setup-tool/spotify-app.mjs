// Puts the Spotify app on the child's phone, from the parent's computer, over the USB cable (adb).
// Spotify only publishes its Android app on Google Play, and the phone has no Google Play, so this uses Aurora Store, an open-source
// Google Play client: install Aurora (a pinned, checksum-verified build from F-Droid), let it sign in anonymously (no Google account),
// install Spotify from it, then remove Aurora again so the child has no app store. The screens are driven by reading the phone's screen
// (uiautomator) and tapping buttons by their text, so every step has a plain-words failure and the manual guide remains the fallback.
import { createHash } from "node:crypto";
import { mkdirSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export const AURORA = { pkg: "com.aurora.store", version: "4.8.4", url: "https://f-droid.org/repo/com.aurora.store_76.apk", sha256: "fd9c75d90d0f4a7c132b9b4a5a2cf1992a45e03b8d8ff988b7dcfbc0db2c4d11", size: 9360088 };
export const SPOTIFY = "com.spotify.music";
const PG = "io.github.durkimat.phoneguard";
const DUMP = "/sdcard/pg-ui.xml";

/** The elements on screen from a uiautomator dump: [{text, desc, x, y}] with x, y the centre. */
export function parseScreen(xml) {
  const out = [];
  for (const m of String(xml).matchAll(/<node\b([^>]*?)\/?>/g)) {
    const a = Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((k) => [k[1], k[2]]));
    const b = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(a.bounds ?? ""); if (!b) continue;
    const text = (a.text ?? "").replace(/&amp;/g, "&").replace(/&#10;/g, " ").trim(), desc = (a["content-desc"] ?? "").trim();
    if (!text && !desc) continue;
    out.push({ text, desc, x: Math.round((+b[1] + +b[3]) / 2), y: Math.round((+b[2] + +b[4]) / 2) });
  }
  return out;
}
const has = (nodes, t) => nodes.some((n) => n.text === t || n.desc === t);
const hasPart = (nodes, t) => nodes.some((n) => n.text.includes(t));
const find = (nodes, t) => nodes.find((n) => n.text === t) ?? nodes.find((n) => n.desc === t);

/** adb as a function: run(args, {timeout}) -> {ok, out}. */
export function adbRunner(adbPath, spawnSync) {
  return async (args, { timeout = 60_000 } = {}) => { const r = spawnSync(adbPath, args, { encoding: "utf8", timeout, maxBuffer: 32 * 1024 * 1024 }); return { ok: r.status === 0, out: `${r.stdout ?? ""}${r.stderr ?? ""}` }; };
}

export class StepError extends Error {}

/**
 * Installs Spotify. `adb` is the runner above; `approve(pkgs)` allows packages for the child's phone on the parent's companion and
 * `unapprove(pkgs)` takes the approval back (both may be left out); `say(text)` reports progress. Returns {already} or {installed}.
 */
export async function installSpotifyApp({ adb, approve = async () => {}, unapprove = async () => {}, say = () => {}, fetchImpl = fetch, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), cacheDir = join(homedir(), ".phoneguard", "cache"), aurora = AURORA, target = SPOTIFY, tries = { ui: 60, install: 72, approval: 12 } }) {   // `target` is only changed by the tests
  const sh = (cmd, o) => adb(["shell", cmd], o);
  const installed = async (pkg) => (await sh(`pm list packages ${pkg}`)).out.split("\n").some((l) => l.trim() === `package:${pkg}`);
  // uiautomator refuses to read a screen that never stops animating (Aurora's app page): so animations are off while this runs, and put back afterwards
  const screen = async () => { for (let k = 0; k < 3; k++) { await sh(`rm -f ${DUMP}`); const d = await sh(`uiautomator dump ${DUMP}`, { timeout: 30_000 }); if (/dumped to/i.test(d.out)) return parseScreen((await sh(`cat ${DUMP}`)).out); await sleep(1500); } return []; };
  const focus = async () => (await sh("dumpsys window | grep mCurrentFocus")).out;
  const tap = (n) => sh(`input tap ${n.x} ${n.y}`);

  say("Checking the phone");
  const dev = (await adb(["devices"])).out.split("\n").slice(1).map((l) => l.trim().split(/\s+/)).filter((p) => p[0] && p[1] === "device");
  if (dev.length !== 1) throw new StepError(dev.length ? "More than one phone is connected: unplug the others." : "The phone is not connected. Plug it into this computer with the USB cable and turn on USB debugging.");
  if (await installed(target)) return { already: true };
  if (!/connected to/i.test((await sh("cmd wifi status")).out)) throw new StepError("The phone is not on Wi-Fi. Connect it to your Wi-Fi first: Spotify is a 137 MB download.");

  say("Getting Aurora Store (an open-source app store) and checking it");
  mkdirSync(cacheDir, { recursive: true }); const file = join(cacheDir, `com.aurora.store_${aurora.version}.apk`);
  const sum = (b) => createHash("sha256").update(b).digest("hex");
  if (!(existsSync(file) && sum(readFileSync(file)) === aurora.sha256)) {
    const r = await fetchImpl(aurora.url); if (!r.ok) throw new StepError(`Downloading Aurora Store failed (${r.status}). Check the internet connection and try again.`);
    const buf = Buffer.from(await r.arrayBuffer()); if (sum(buf) !== aurora.sha256) throw new StepError("The Aurora Store download did not match its checksum, so it was not installed.");
    writeFileSync(file, buf);
  }
  say("Installing Aurora Store on the phone");
  const ins = await adb(["install", "-r", file], { timeout: 180_000 }); if (!/Success/.test(ins.out)) throw new StepError(`The phone refused Aurora Store: ${ins.out.trim().slice(0, 160)}`);
  for (const op of ["REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE"]) await sh(`appops set ${aurora.pkg} ${op} allow`);
  await sh(`pm grant ${aurora.pkg} android.permission.POST_NOTIFICATIONS`);

  say("Allowing Aurora and Spotify on the phone (your parent page controls which apps the child can use)");
  await approve([aurora.pkg, target]);
  const poke = async () => { await sh(`am start -n ${PG}/.MainActivity`); await sleep(2000); for (const k of ["DPAD_DOWN", "DPAD_DOWN", "DPAD_DOWN", "ENTER"]) await sh(`input keyevent KEYCODE_${k}`); await sleep(9000); };   // the status page's "Sync now"
  let started = false;
  for (let i = 0; i < tries.approval && !started; i++) {
    await poke(); await sh("input keyevent KEYCODE_WAKEUP");
    const r = await sh(`am start -n ${aurora.pkg}/.ComposeActivity`); started = !/does not exist|Error/i.test(r.out);
    if (!started) say(`Waiting for the phone to pick up the approval (${i + 1})`);
  }
  if (!started) throw new StepError("The phone did not pick up the approval for Aurora Store. Open your parent page, Apps tab, approve Aurora Store for Day mode, press Sync now on the phone, and run this again.");

  const SCALES = ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"], orig = {};
  for (const k of SCALES) orig[k] = (await sh(`settings get global ${k}`)).out.trim();
  for (const k of SCALES) await sh(`settings put global ${k} 0`);
  try {
    say("Setting up Aurora Store (it signs in anonymously: no Google account is used)");
    let ready = false;
    for (let i = 0; i < tries.ui && !ready; i++) {
      const n = await screen();
      if (has(n, "Top charts") || has(n, "For you")) ready = true;
      else if (has(n, "Anonymous")) await tap(find(n, "Anonymous"));
      else if (has(n, "Finish")) await tap(find(n, "Finish"));
      else if (has(n, "Next")) await tap(find(n, "Next"));
      else await sleep(2000);
      await sleep(1500);
    }
    if (!ready) throw new StepError("Aurora Store did not finish setting itself up. Open it on the phone, choose Anonymous, and run this again.");

    say("Finding Spotify and installing it (about 137 MB; this takes a few minutes)");
    await sh(`am start -a android.intent.action.VIEW -d market://details?id=${target} -p ${aurora.pkg}`);
    let taps = 0, idle = 0;   // the Install button sometimes ignores the first press while the page is still loading: press again if nothing starts
    for (let i = 0; i < tries.install; i++) {
      if (await installed(target)) break;
      const f = await focus();
      if (/packageinstaller/i.test(f)) { const n = await screen(); const b = find(n, "Install"); if (b) await tap(b); await sleep(3000); continue; }
      const n = await screen();
      if (hasPart(n, "Continue to Aurora Store")) { const o = n.filter((x) => x.text === "Open").pop(); if (o) await tap(o); }
      else if (has(n, "Retry")) { throw new StepError("Aurora could not download Spotify. Check the phone's Wi-Fi and run this again."); }
      else if (has(n, "Install") && !has(n, "Cancel")) { if (taps === 0 || (idle >= 2 && taps < 6)) { await tap(find(n, "Install")); taps++; idle = 0; } else idle++; }
      await sleep(5000);
    }
    if (!(await installed(target))) throw new StepError("Spotify did not finish installing. Check the phone's Wi-Fi and run this again, or install it by hand with the guide.");
  } finally {
    for (const k of SCALES) await sh(!orig[k] || orig[k] === "null" ? `settings delete global ${k}` : `settings put global ${k} ${orig[k]}`);
  }

  say("Removing Aurora Store again, so the child has no app store");
  await sh("input keyevent KEYCODE_HOME");
  await adb(["uninstall", aurora.pkg]); await unapprove([aurora.pkg]);
  return { installed: true };
}
