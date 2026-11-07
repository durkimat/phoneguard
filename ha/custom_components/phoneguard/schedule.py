"""Turns a child's timetable into the config the controller enforces (see controller Config.kt)."""
from __future__ import annotations

import copy
import hashlib
import json
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

WINDOW_DAYS = 14


def default_child(name: str) -> dict:
    week = {}
    for dow in range(1, 8):
        segs = [[0, 420, "night"]]
        if dow <= 5:
            segs.append([510, 930, "school"])
        segs.append([1260, 1440, "night"])
        week[str(dow)] = segs
    return {
        "name": name,
        "tz": "Europe/London",
        "base": "day",
        "notify": "",  # e.g. notify.mobile_app_dumbphone2, used for the sync-now nudge
        "modes": {
            "day": {"name": "Day", "allowAll": True, "allow": [], "gray": False},
            "school": {"name": "School", "allowAll": False, "allow": [], "gray": True},
            "night": {"name": "Night", "allowAll": False, "allow": [], "gray": False},
        },
        "week": week,
        "holiday": None,  # {"from": "2026-12-19", "until": "2027-01-04"}
        "protect_extra": [],
        "unprotect": [],
        "restrictions": None,
        "known": None,  # packages the parent has already seen; None until the first sync
    }


def _local_ms(tz: ZoneInfo, d: date, minutes: int) -> int:
    if minutes >= 1440:
        dt = datetime.combine(d + timedelta(days=1), time(0, 0), tzinfo=tz)
    else:
        dt = datetime.combine(d, time(minutes // 60, minutes % 60), tzinfo=tz)
    return int(dt.timestamp() * 1000)


def _in_holiday(child: dict, d: date) -> bool:
    h = child.get("holiday")
    if not h:
        return False
    try:
        return date.fromisoformat(h["from"]) <= d <= date.fromisoformat(h["until"])
    except (KeyError, ValueError):
        return False


def build_config(child: dict, now_ms: int) -> dict:
    tz = ZoneInfo(child["tz"])
    today = datetime.fromtimestamp(now_ms / 1000, tz).date()
    segments = []
    for i in range(WINDOW_DAYS):
        d = today + timedelta(days=i)
        for start, end, mode in child["week"].get(str(d.isoweekday()), []):
            if mode == "school" and _in_holiday(child, d):
                mode = "day"
            segments.append({"from": _local_ms(tz, d, start), "to": _local_ms(tz, d, end), "mode": mode})
    modes = {}
    for mid, m in child["modes"].items():
        modes[mid] = {"name": m.get("name", mid), "allowAll": bool(m.get("allowAll")),
                      "allow": sorted(set(m.get("allow", []))), "gray": bool(m.get("gray"))}
    cfg = {
        "base": child.get("base", "day"),
        "modes": modes,
        "segments": segments,
        "windowEnd": _local_ms(tz, today + timedelta(days=WINDOW_DAYS - 1), 1440),
        "pattern": {"tz": child["tz"], "days": copy.deepcopy(child["week"])},
        "protectExtra": sorted(child.get("protect_extra", [])),
        "unprotect": sorted(child.get("unprotect", [])),
    }
    if child.get("restrictions") is not None:
        cfg["restrictions"] = sorted(child["restrictions"])
    digest = hashlib.sha1(json.dumps(cfg, sort_keys=True).encode()).hexdigest()[:12]
    cfg["version"] = int(digest, 16)
    return cfg


def cfg_hash(cfg: dict) -> str:
    return f"{cfg['version']:012x}"


def resolve_mode(cfg: dict, boost: dict | None, now_ms: int) -> str:
    """Mirror of the controller's Engine.scheduledMode, for display only."""
    for s in cfg["segments"]:
        if s["from"] <= now_ms < s["to"]:
            return s["mode"]
    if now_ms >= cfg["windowEnd"] and cfg.get("pattern"):
        p = cfg["pattern"]
        z = datetime.fromtimestamp(now_ms / 1000, ZoneInfo(p["tz"]))
        minute = z.hour * 60 + z.minute
        for start, end, mode in p["days"].get(str(z.isoweekday()), []):
            if start <= minute < end:
                return mode
    return cfg["base"]
