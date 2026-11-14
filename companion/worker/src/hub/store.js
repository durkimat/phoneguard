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
  const assets = Array.isArray(data.assets) ? data.assets : [], apk = assets.find((a) => String(a.name ?? "").endsWith(".apk")); if (!apk) return null;
  return { tag: data.tag_name, version: String(data.tag_name).replace(/^v/, ""), notes: String(data.body ?? "").slice(0, 600), url: apk.browser_download_url ?? "", size: Math.trunc(apk.size ?? 0), sha256_url: assets.find((a) => String(a.name ?? "").endsWith(".sha256"))?.browser_download_url ?? "" };
}
export const parseSha256Text = (text) => (/\b[0-9a-fA-F]{64}\b/.exec(text ?? "")?.[0] ?? "").toLowerCase();
