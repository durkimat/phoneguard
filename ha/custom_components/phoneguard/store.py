"""Checking GitHub for the newest Phone Guard release (the only thing HA fetches from the web for the phone).

Pure parsing helpers are tested without Home Assistant.
"""
from __future__ import annotations

import re

PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


def valid_pkg(pkg: str) -> bool:
    return bool(isinstance(pkg, str) and len(pkg) <= 150 and PKG_RE.match(pkg))


def version_key(v: str) -> tuple:
    """Compare version strings like 0.10.2 and v1.2: numbers first, anything else ignored."""
    return tuple(int(x) for x in re.findall(r"\d+", v or "")[:4]) or (0,)


def is_newer(latest: str, installed: str) -> bool:
    a, b = version_key(latest), version_key(installed)
    n = max(len(a), len(b))
    return a + (0,) * (n - len(a)) > b + (0,) * (n - len(b))


def parse_release(data: dict) -> dict | None:
    """The newest GitHub release: tag, notes, the .apk asset address and the address of its .sha256 asset."""
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
