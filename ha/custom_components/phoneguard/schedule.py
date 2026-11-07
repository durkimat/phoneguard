"""Turns a child's timetable into the config the controller enforces (see controller Config.kt)."""
from __future__ import annotations

import copy
import hashlib
import json
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

WINDOW_DAYS = 14


# What a mode does when the parent has not chosen: School is locked down like a classic Nokia, the rest is open.
LOCKDOWN_KEYS = ("launcher", "touch", "shade", "kiosk", "sound")
SOUNDS = ("normal", "vibrate", "silent")
CLOCK = "com.android.deskclock"


def lockdown_defaults(mode_id: str) -> dict:
    if mode_id == "school":
        return {"launcher": "nokia", "touch": False, "shade": False, "kiosk": True, "sound": "silent"}
    return {"launcher": "default", "touch": True, "shade": True, "kiosk": False, "sound": "normal"}


def mode_with_defaults(mode_id: str, mode: dict) -> dict:
    """A mode with every lockdown setting filled in (older data has none of them)."""
    out = dict(mode)
    for k, v in lockdown_defaults(mode_id).items():
        out.setdefault(k, v)
    return out


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
            "day": {"name": "Day", "allowAll": True, "allow": [], "gray": False, **lockdown_defaults("day")},
            # School leaves the Clock out so no alarm can ring; Night keeps it so the morning alarm works
            "school": {"name": "School", "allowAll": False, "allow": [], "gray": True, **lockdown_defaults("school")},
            "night": {"name": "Night", "allowAll": False, "allow": [CLOCK], "gray": False, **lockdown_defaults("night")},
        },
        "override": None,  # a mode id to force, or None for the timetable
        "week": week,
        "holiday": None,  # {"from": "2026-12-19", "until": "2027-01-04"}
        "protect_extra": [],
        "unprotect": [CLOCK],  # a mode can hide the Clock
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
    for mid, m0 in child["modes"].items():
        m = mode_with_defaults(mid, m0)
        modes[mid] = {"name": m.get("name", mid), "allowAll": bool(m.get("allowAll")),
                      "allow": sorted(set(m.get("allow", []))), "gray": bool(m.get("gray")),
                      "launcher": "nokia" if m["launcher"] == "nokia" else "default",
                      "touch": bool(m["touch"]), "shade": bool(m["shade"]), "kiosk": bool(m["kiosk"]),
                      "sound": m["sound"] if m["sound"] in SOUNDS else "normal"}
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
    if child.get("override") in modes:
        cfg["override"] = child["override"]
    digest = hashlib.sha1(json.dumps(cfg, sort_keys=True).encode()).hexdigest()[:12]
    cfg["version"] = int(digest, 16)
    return cfg


def cfg_hash(cfg: dict) -> str:
    return f"{cfg['version']:012x}"


def resolve_mode(cfg: dict, boost: dict | None, now_ms: int) -> str:
    """Mirror of the controller's Engine.scheduledMode, for display only."""
    if cfg.get("override") in cfg["modes"]:
        return cfg["override"]
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
