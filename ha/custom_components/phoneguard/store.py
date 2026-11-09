"""Finding apps to install (the open-source F-Droid store) and the newest Phone Guard release (GitHub).

Pure parsing helpers are tested without Home Assistant; the small async functions only do the web requests.
"""
from __future__ import annotations

import hashlib
import re
from urllib.parse import urlparse

SEARCH_URL = "https://search.f-droid.org/api/search_apps"
INFO_URL = "https://f-droid.org/api/v1/packages/"
APK_URL = "https://f-droid.org/repo/{pkg}_{vc}.apk"
MAX_APK_BYTES = 400 * 1024 * 1024
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


def valid_pkg(pkg: str) -> bool:
    return bool(isinstance(pkg, str) and len(pkg) <= 150 and PKG_RE.match(pkg))


def parse_search(data: dict) -> list[dict]:
    out = []
    for a in (data or {}).get("apps", [])[:20]:
        pkg = urlparse(a.get("url", "")).path.rstrip("/").split("/")[-1]
        if valid_pkg(pkg):
            out.append({"pkg": pkg, "name": str(a.get("name", pkg))[:80], "summary": str(a.get("summary", ""))[:160]})
    return out


def parse_info(data: dict) -> tuple[str, int] | None:
    """(version name, version code) of the version F-Droid suggests."""
    if not isinstance(data, dict) or "suggestedVersionCode" not in data:
        return None
    vc = int(data["suggestedVersionCode"])
    name = next((p.get("versionName", "") for p in data.get("packages", []) if p.get("versionCode") == vc), "")
    return str(name), vc


def apk_url(pkg: str, vc: int) -> str:
    return APK_URL.format(pkg=pkg, vc=vc)


def version_key(v: str) -> tuple:
    """Compare version strings like 0.10.2 and v1.2: numbers first, anything else ignored."""
    return tuple(int(x) for x in re.findall(r"\d+", v or "")[:4]) or (0,)


def is_newer(latest: str, installed: str) -> bool:
    a, b = version_key(latest), version_key(installed)
    n = max(len(a), len(b))
    return a + (0,) * (n - len(a)) > b + (0,) * (n - len(b))


def parse_release(data: dict) -> dict | None:
    """The newest GitHub release: tag, notes, the .apk asset address and its SHA-256 (from a .sha256 asset's text, passed in as data['_sha256'])."""
    if not isinstance(data, dict) or not data.get("tag_name"):
        return None
    apk = next((a for a in data.get("assets", []) if str(a.get("name", "")).endswith(".apk")), None)
    if not apk:
        return None
    return {"tag": data["tag_name"], "version": str(data["tag_name"]).lstrip("v"), "notes": str(data.get("body") or "")[:600],
            "url": apk.get("browser_download_url", ""), "size": int(apk.get("size", 0)),
            "sha256_url": next((a.get("browser_download_url", "") for a in data.get("assets", []) if str(a.get("name", "")).endswith(".sha256")), "")}


def parse_sha256_text(text: str) -> str:
    m = re.search(r"\b[0-9a-fA-F]{64}\b", text or "")
    return m.group(0).lower() if m else ""


async def search(session, q: str) -> list[dict]:
    async with session.get(SEARCH_URL, params={"q": q, "lang": "en"}, timeout=20) as r:
        return parse_search(await r.json(content_type=None))


async def info(session, pkg: str) -> tuple[str, int] | None:
    async with session.get(INFO_URL + pkg, timeout=20) as r:
        if r.status != 200:
            return None
        return parse_info(await r.json(content_type=None))


async def hash_url(session, url: str, max_bytes: int = MAX_APK_BYTES) -> tuple[str, int]:
    """Download once to learn the SHA-256 and size (nothing is kept). The phone checks its own download against it."""
    h = hashlib.sha256()
    total = 0
    async with session.get(url, timeout=300) as r:
        if r.status != 200:
            raise ValueError(f"the store answered HTTP {r.status}")
        async for chunk in r.content.iter_chunked(64 * 1024):
            total += len(chunk)
            if total > max_bytes:
                raise ValueError("that app is too large")
            h.update(chunk)
    return h.hexdigest(), total


# ---- more sources: IzzyOnDroid (a second open repository, same format as F-Droid) and official downloads from the makers

IZZY_INDEX = "https://apt.izzysoft.de/fdroid/repo/index-v1.json"
IZZY_REPO = "https://apt.izzysoft.de/fdroid/repo/"

# apps the makers publish themselves; the lists are short on purpose (each entry was checked)
OFFICIAL = [
    {"pkg": "com.whatsapp", "name": "WhatsApp", "summary": "Messaging. Official download from whatsapp.com", "kind": "url", "url": "https://www.whatsapp.com/android/current/WhatsApp.apk"},
    {"pkg": "org.thoughtcrime.securesms", "name": "Signal", "summary": "Private messaging. Official download from signal.org", "kind": "signal"},
    {"pkg": "org.telegram.messenger.web", "name": "Telegram", "summary": "Messaging. Official download from telegram.org", "kind": "url", "url": "https://telegram.org/dl/android/apk"},
]
SIGNAL_LATEST = "https://updates.signal.org/android/latest.json"


def _localized(a: dict, key: str) -> str:
    if a.get(key):
        return str(a[key])
    loc = a.get("localized") or {}
    for k in ("en-US", "en-GB", "en"):
        if loc.get(k, {}).get(key):
            return str(loc[k][key])
    return next((str(v[key]) for v in loc.values() if v.get(key)), "")


def parse_index(data: dict) -> list[dict]:
    """The apps of an F-Droid style index (index-v1): name, summary and the suggested version's file with its SHA-256."""
    out = []
    for a in (data or {}).get("apps", []):
        pkg = a.get("packageName", "")
        if not valid_pkg(pkg):
            continue
        vc = str(a.get("suggestedVersionCode", ""))
        v = next((x for x in (data.get("packages", {}) or {}).get(pkg, []) if str(x.get("versionCode")) == vc), None)
        if not v or not v.get("apkName") or not re.fullmatch(r"[0-9a-f]{64}", str(v.get("hash", ""))):
            continue
        out.append({"pkg": pkg, "name": _localized(a, "name") or pkg, "summary": _localized(a, "summary")[:160],
                    "version": str(v.get("versionName", "")), "apk": v["apkName"], "sha256": v["hash"], "size": int(v.get("size", 0))})
    return out


def search_entries(entries: list[dict], q: str, limit: int = 12) -> list[dict]:
    """Local search: names that start with the words first, then names that contain them, then summaries."""
    words = [w for w in q.lower().split() if w]
    if not words:
        return []
    scored = []
    for e in entries:
        name, summ = e["name"].lower(), e.get("summary", "").lower()
        if all(w in name for w in words):
            scored.append((0 if name.startswith(words[0]) else 1, name, e))
        elif all(w in name or w in summ for w in words):
            scored.append((2, name, e))
    scored.sort(key=lambda t: (t[0], t[1]))
    return [t[2] for t in scored[:limit]]


def official_matches(q: str) -> list[dict]:
    words = [w for w in q.lower().split() if w]
    return [o for o in OFFICIAL if words and all(w in (o["name"] + " " + o["pkg"]).lower() for w in words)]


async def load_index(session, path: str, max_age_s: int = 24 * 3600) -> list[dict]:
    """The IzzyOnDroid index, kept in a file for a day (about 16 MB to fetch)."""
    import json
    import os
    import time
    if os.path.exists(path) and time.time() - os.path.getmtime(path) < max_age_s:
        with open(path) as f:
            return json.load(f)
    async with session.get(IZZY_INDEX, timeout=120) as r:
        if r.status != 200:
            raise ValueError(f"IzzyOnDroid answered HTTP {r.status}")
        data = await r.json(content_type=None, loads=json.loads)
    entries = parse_index(data)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(entries, f)
    return entries


async def resolve_official(session, o: dict) -> tuple[str, str, str, int]:
    """(version, address, sha256, size) of an official download."""
    if o["kind"] == "signal":
        async with session.get(SIGNAL_LATEST, timeout=20) as r:
            j = await r.json(content_type=None)
        return str(j["versionName"]), j["url"], j["sha256sum"].lower(), 0
    sha, size = await hash_url(session, o["url"])
    return "", o["url"], sha, size

