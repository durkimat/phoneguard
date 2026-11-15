// One command to publish a release: the phone app (APK + sha256) and the companion (worker.bundle.js + worker-version.json) together.
//   node release.mjs --apk ~/phoneguard-0.4.3-release.apk [--tag v0.4.3] [--notes-file notes.md | --notes "text"]   shows the plan, changes nothing
//   node release.mjs --apk ... --publish                                                                        creates the GitHub release (needs `gh` logged in)
//   node release.mjs --companion-only --tag v0.5.1 --notes "..." [--publish]                                    a release with no phone app in it
// What it checks first: the companion's version was bumped whenever its code changed, the version is newer than the published one, the tag is new,
// and the APK's file name matches the tag. See docs/releasing.md.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync, statSync } from "node:fs";
import { basename, join } from "node:path";
import { cmpVer, parseReleaseJson } from "../worker/src/hub/store.js";
import { releaseBundle, RELEASES, RELEASE_DIRECT } from "./engine.mjs";

const REPO = "durkimat/phoneguard";
const arg = (n) => { const i = process.argv.indexOf(n); return i >= 0 ? process.argv[i + 1] : undefined; }, flag = (n) => process.argv.includes(n);
const OUT = new URL("./dist/release/", import.meta.url).pathname;
const sha = (buf) => createHash("sha256").update(buf).digest("hex");
const die = (m) => { console.error("\nSTOPPED: " + m); process.exit(1); };

export async function plan({ apk, tag, notes, companionOnly = false, published = undefined, bundleInfo, bundleCode, tagExists = false, force = false }) {
  const problems = [], files = [];
  if (companionOnly) { if (!tag) problems.push("--tag is needed for a companion-only release (for example v0.5.1)."); }
  else {
    if (!apk) problems.push("--apk <file> is needed (or use --companion-only).");
    else { const m = /^phoneguard-(\d+\.\d+\.\d+)-release\.apk$/.exec(basename(apk)); if (!m) problems.push(`The APK should be called phoneguard-X.Y.Z-release.apk, not ${basename(apk)}.`); else { tag ??= "v" + m[1]; if (tag !== "v" + m[1]) problems.push(`The tag ${tag} does not match the APK name (${basename(apk)}).`); } }
  }
  if (tag && !/^v\d+\.\d+\.\d+$/.test(tag)) problems.push(`The tag should look like v1.2.3, not ${tag}.`);
  if (tagExists) problems.push(`The tag ${tag} already exists on GitHub. Pick a new version.`);
  if (published?.info) {
    const d = cmpVer(bundleInfo.version, published.info.version);
    if (d < 0) problems.push(`The companion in this folder is version ${bundleInfo.version} but ${published.info.version} is already published. Update this folder first.`);
    else if (d === 0 && published.info.sha256 !== bundleInfo.sha256) problems.push(`The companion code changed but its version is still ${bundleInfo.version}. Bump COMPANION_VERSION in worker/src/version.js (and add a migration step in worker/src/migrations.js if the database changed), then run npm run bundle.`);
    if (d > 0 && bundleInfo.schema != null && published.info.schema != null && bundleInfo.schema < published.info.schema) problems.push("The schema went down. Migration steps are only ever added.");
  }
  const notesText = String(notes ?? "").trim();
  const info = { ...bundleInfo, notes: notesText.slice(0, 400) };
  files.push({ name: "worker.bundle.js", data: Buffer.from(bundleCode) }, { name: "worker-version.json", data: Buffer.from(JSON.stringify(info, null, 2) + "\n") });
  let apkInfo = null;
  if (!companionOnly && apk && existsSync(apk)) { const data = readFileSync(apk); apkInfo = { version: tag.slice(1), name: basename(apk), size: data.length, url: `https://github.com/${REPO}/releases/download/${tag}/${basename(apk)}`, sha256: sha(data) }; files.push({ name: basename(apk), data }, { name: basename(apk) + ".sha256", data: Buffer.from(`${apkInfo.sha256}  ${basename(apk)}\n`) }); }
  else if (!companionOnly && apk) problems.push(`${apk} was not found.`);
  if (companionOnly && published?.url && published.sha256) apkInfo = { name: published.url.split("/").pop(), size: published.size, url: published.url, sha256: published.sha256, version: published.version };   // keep offering the newest phone app
  // release.json: everything a parent's page needs to know about this release in one small file (read through GitHub's download link, not the API)
  files.push({ name: "release.json", data: Buffer.from(JSON.stringify({ tag, html_url: `https://github.com/${REPO}/releases/tag/${tag}`, notes: notesText.slice(0, 600), apk: apkInfo, companion: info }, null, 2) + "\n") });
  return { tag, problems, files, info, notes: notesText };
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("release.mjs")) {
  const apk = arg("--apk"), companionOnly = flag("--companion-only"), publish = flag("--publish");
  console.log("Building the companion...");
  const b = spawnSync("npm", ["run", "bundle"], { cwd: new URL(".", import.meta.url).pathname, encoding: "utf8" }); if (b.status !== 0) die("npm run bundle failed:\n" + b.stderr);
  const bundleInfo = JSON.parse(readFileSync(new URL("./dist/worker-version.json", import.meta.url), "utf8")), bundleCode = readFileSync(new URL("./dist/worker.bundle.js", import.meta.url), "utf8");
  let published = null; try { published = await releaseBundle({ url: RELEASES }); } catch (e) { console.log(`(Could not read the published release: ${e.message})`); }
  if (published) { try { const prev = parseReleaseJson(await (await fetch(RELEASE_DIRECT + "release.json")).json()); if (prev?.url) Object.assign(published, { version: prev.version, url: prev.url, size: prev.size, sha256: prev.sha256 }); } catch { /* no phone app in the newest release */ } }   // for a companion-only release to carry forward
  const notes = arg("--notes-file") ? readFileSync(arg("--notes-file"), "utf8") : arg("--notes") ?? "";
  const tag = arg("--tag") ?? undefined;
  const tagCheck = (t) => t && spawnSync("gh", ["release", "view", t, "-R", REPO], { encoding: "utf8" }).status === 0;
  let r = await plan({ apk, tag, notes, companionOnly, published, bundleInfo, bundleCode, tagExists: false }); if (r.tag && tagCheck(r.tag)) r = await plan({ apk, tag: r.tag, notes, companionOnly, published, bundleInfo, bundleCode, tagExists: true });
  if (!notes.trim()) r.problems.push("Add release notes with --notes \"...\" or --notes-file <file>.");
  console.log(`\nRelease ${r.tag ?? "(no tag yet)"}\n  companion ${bundleInfo.version} (schema ${bundleInfo.schema})${published ? `, published: ${published.info.version}` : ""}`);
  for (const f of r.files) console.log(`  ${f.name}  ${f.data.length} bytes`);
  if (r.problems.length && !flag("--force")) die(r.problems.join("\n"));
  mkdirSync(OUT, { recursive: true }); const paths = r.files.map((f) => { const p = join(OUT, f.name); writeFileSync(p, f.data); return p; }); writeFileSync(join(OUT, "NOTES.md"), r.notes + "\n");
  if (!publish) { console.log(`\nDry run: nothing was published. Files are in ${OUT}\nTo publish: node release.mjs ${process.argv.slice(2).join(" ")} --publish`); process.exit(0); }
  const g = spawnSync("gh", ["release", "create", r.tag, ...paths, "-R", REPO, "--title", `Phone Guard ${r.tag}`, "--notes-file", join(OUT, "NOTES.md")], { encoding: "utf8" });
  if (g.status !== 0) die("gh could not create the release:\n" + g.stderr); console.log("\nPublished: " + g.stdout.trim());
}
