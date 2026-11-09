"""The phone's battery diary: keep it and boil it down. Pure, so it is tested without Home Assistant.

A sample is the list the phone sends: [time ms, level %, charge counter µAh, current now µA, plugged, screen, mode, signal, wifi,
location, temperature (tenths of a degree), our app's bytes, reason]. The charge counter is what is left in the battery, so the drop
between two samples (not charging) is what was used.
"""
from __future__ import annotations

from typing import Any

KEEP_MS = 14 * 24 * 3600 * 1000
MAX_SAMPLES = 8000
T, LEVEL, UAH, CUR, PLUG, SCREEN, MODE, SIG, WIFI, LOC, TEMP, BYTES, WHY = range(13)
MAX_GAP_MS = 3 * 3600 * 1000  # a longer gap means the phone was off or out of touch: say nothing about it
MIN_GAP_MS = 30 * 1000


def _valid(s: Any) -> bool:
    return isinstance(s, list) and len(s) >= 12 and all(isinstance(x, (int, float)) for x in s[:6]) and isinstance(s[MODE], str)


def merge(store: list, new: Any, now_ms: int) -> list:
    """Add new samples (oldest first, none repeated), drop old ones, cap the size."""
    last = store[-1][T] if store else 0
    if isinstance(new, list):
        for s in new:
            if _valid(s) and s[T] > last:
                store.append(s)
                last = s[T]
    cut = now_ms - KEEP_MS
    out = [s for s in store if s[T] >= cut]
    return out[-MAX_SAMPLES:]


def _bucket(d: dict, key: str, hours: float, mah: float) -> None:
    b = d.setdefault(key, {"hours": 0.0, "mah": 0.0})
    b["hours"] += hours
    b["mah"] += mah


def _finish(b: dict) -> dict:
    h = b["hours"]
    return {"hours": round(h, 2), "mah_per_h": round(b["mah"] / h, 1) if h > 0 else None}


def summarize(samples: list, now_ms: int, hours: int = 24) -> dict:
    """What the last [hours] hours looked like on battery, by screen, mode, location and Wi-Fi, plus a thin line for a chart."""
    since = now_ms - hours * 3600 * 1000
    recent = [s for s in samples if s[T] >= since]
    total = {"hours": 0.0, "mah": 0.0}
    screen: dict = {}; mode: dict = {}; loc: dict = {}; wifi: dict = {}
    for a, b in zip(recent, recent[1:]):
        dt = b[T] - a[T]
        if dt < MIN_GAP_MS or dt > MAX_GAP_MS or a[PLUG] or b[PLUG]:
            continue
        used = (a[UAH] - b[UAH]) / 1000.0  # mAh; negative would mean it charged, which we ignore
        if used < -1:
            continue
        used = max(used, 0.0)
        h = dt / 3600000.0
        total["hours"] += h; total["mah"] += used
        _bucket(screen, "on" if a[SCREEN] else "off", h, used)
        _bucket(mode, str(a[MODE]), h, used)
        _bucket(loc, "on" if a[LOC] else "off", h, used)
        _bucket(wifi, "on" if a[WIFI] else "off", h, used)
    out: dict = {"window_hours": hours, "on_battery": _finish(total),
                 "by_screen": {k: _finish(v) for k, v in screen.items()}, "by_mode": {k: _finish(v) for k, v in mode.items()},
                 "by_location": {k: _finish(v) for k, v in loc.items()}, "by_wifi": {k: _finish(v) for k, v in wifi.items()}}
    if samples:
        last = samples[-1]
        out["level"] = last[LEVEL]; out["plugged"] = bool(last[PLUG]); out["temp_c"] = round(last[TEMP] / 10.0, 1)
        if last[LEVEL] > 0 and last[UAH] > 0:
            full = last[UAH] / 1000.0 / (last[LEVEL] / 100.0)  # the full-battery size the phone's own counter implies
            out["full_mah"] = round(full)
            rate = out["on_battery"]["mah_per_h"]
            if rate and not last[PLUG]:
                out["hours_left"] = round(last[UAH] / 1000.0 / rate, 1)
        out["seen_ms"] = last[T]
    step = max(1, len(recent) // 240)
    out["chart"] = [[s[T], s[LEVEL], s[PLUG], s[SCREEN], s[MODE]] for s in recent[::step]]
    return out


# ---- what the phone's power saver closed: [time ms, package, app name, reason, 1 if it only would have]

REAPED_KEEP = 300


def merge_reaped(old: list, new: object) -> list:
    """Append the phone's new entries (de-duplicated by time and package), newest last, capped."""
    seen = {(e[0], e[1]) for e in old}
    out = list(old)
    if isinstance(new, list):
        for e in new:
            if (isinstance(e, list) and len(e) >= 5 and isinstance(e[0], int) and isinstance(e[1], str) and isinstance(e[2], str)
                    and isinstance(e[3], str) and (e[0], e[1]) not in seen):
                out.append([e[0], e[1][:80], e[2][:60], e[3][:20], 1 if e[4] else 0])
                seen.add((e[0], e[1]))
    out.sort(key=lambda e: e[0])
    return out[-REAPED_KEEP:]


def summarize_reaped(entries: list, now_ms: int, hours: int = 24) -> dict:
    """The last day: how many apps were closed (or only reported, in a dry run), why, which most, and the latest few."""
    recent = [e for e in entries if e[0] >= now_ms - hours * 3_600_000]
    real = [e for e in recent if not e[4]]
    would = [e for e in recent if e[4]]
    by_reason: dict[str, int] = {}
    for e in real:
        by_reason[e[3]] = by_reason.get(e[3], 0) + 1
    names: dict[str, int] = {}
    for e in (real or would):
        names[e[2]] = names.get(e[2], 0) + 1
    return {
        "closed": len(real), "would": len(would), "by_reason": by_reason,
        "top": sorted(names.items(), key=lambda kv: (-kv[1], kv[0]))[:6],
        "latest": [[e[0], e[2], e[3], e[4]] for e in recent[-8:]][::-1],
    }
