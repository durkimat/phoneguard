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
