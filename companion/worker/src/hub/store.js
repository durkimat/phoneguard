// Package-name and version helpers (port of ha/.../store.py).
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
export const validPkg = (pkg) => typeof pkg === "string" && pkg.length <= 150 && PKG_RE.test(pkg);
const versionKey = (v) => { const n = (String(v ?? "").match(/\d+/g) ?? []).slice(0, 4).map(Number); return n.length ? n : [0]; };
export function isNewer(latest, installed) {
  const a = versionKey(latest), b = versionKey(installed), n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) { const x = a[i] ?? 0, y = b[i] ?? 0; if (x !== y) return x > y; }
  return false;
}

// The newest GitHub release: tag, notes, the .apk asset address and the address of its .sha256 asset.
export function parseRelease(data) {
  if (!data || typeof data !== "object" || !data.tag_name) return null;
  const assets = Array.isArray(data.assets) ? data.assets : [], apk = assets.find((a) => String(a.name ?? "").endsWith(".apk")), comp = assets.find((a) => a.name === "worker-version.json");
  if (!apk && !comp) return null;   // a release can carry the phone app, the companion, or both
  return { tag: data.tag_name, version: String(data.tag_name).replace(/^v/, ""), notes: String(data.body ?? "").slice(0, 600), url: apk?.browser_download_url ?? "", size: Math.trunc(apk?.size ?? 0), sha256_url: assets.find((a) => String(a.name ?? "").endsWith(".sha256"))?.browser_download_url ?? "", companion_url: comp?.browser_download_url ?? "", html_url: String(data.html_url ?? "") };
}
/** The newest release as ONE small file, release.json, fetched through GitHub's normal download link (no API, so no API rate limit):
 *  {tag, html_url, notes, apk: {name, size, url, sha256} | null, companion: {version, schema, protocol, sha256, size, notes} | null}. Anything malformed is ignored. */
export function parseReleaseJson(j, repo = "https://github.com/durkimat/phoneguard/") {
  if (!j || typeof j !== "object" || !/^v\d+\.\d+\.\d+$/.test(String(j.tag))) return null;
  const own = (u) => typeof u === "string" && u.startsWith(repo), apk = j.apk && own(j.apk.url) && /^[0-9a-f]{64}$/.test(String(j.apk.sha256)) ? j.apk : null, companion = parseCompanionInfo(j.companion);
  if (!apk && !companion) return null;
  return { tag: j.tag, version: j.tag.slice(1), notes: String(j.notes ?? "").slice(0, 600), url: apk?.url ?? "", size: Math.trunc(apk?.size ?? 0), sha256: apk?.sha256 ?? "", sha256_url: "", companion_url: "", companion, html_url: own(j.html_url) ? j.html_url : "" };
}
/** The companion's own entry in a release: worker-version.json = {version, schema, protocol, sha256, size, notes}. Anything malformed is ignored. */
export function parseCompanionInfo(j) {
  if (!j || typeof j !== "object" || !/^\d+\.\d+\.\d+$/.test(String(j.version))) return null;
  return { version: j.version, schema: Number.isInteger(j.schema) ? j.schema : null, protocol: Number.isInteger(j.protocol) ? j.protocol : null, sha256: /^[0-9a-f]{64}$/.test(String(j.sha256)) ? j.sha256 : "", size: Math.trunc(Number(j.size) || 0), notes: String(j.notes ?? "").slice(0, 400) };
}
/** Compares x.y.z version strings: negative if a is older than b, 0 if equal, positive if newer. Non-numeric parts count as 0. */
export const cmpVer = (a, b) => { const p = (v) => String(v ?? "0").replace(/^v/, "").split(".").map((n) => parseInt(n, 10) || 0); const x = p(a), y = p(b); for (let i = 0; i < Math.max(x.length, y.length, 3); i++) { const d = (x[i] ?? 0) - (y[i] ?? 0); if (d) return d; } return 0; };
export const parseSha256Text = (text) => (/\b[0-9a-fA-F]{64}\b/.exec(text ?? "")?.[0] ?? "").toLowerCase();
