#!/usr/bin/env python3
"""Writes a 14-day timetable config for the controller rig, starting Mon 9 Nov 2026 (Europe/London).
School Mon-Fri 08:30-15:30 (camera only, grey), night 21:00-07:00 (gallery as stand-in music app), day otherwise."""
import json, sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

tz = ZoneInfo("Europe/London")
start = datetime(2026, 11, 9, 0, 0, tzinfo=tz)
CAMERA, GALLERY = "org.lineageos.aperture", "org.lineageos.glimpse"

def ms(dt): return int(dt.timestamp() * 1000)

segs = []
for d in range(14):
    day = start + timedelta(days=d)
    dow = day.isoweekday()
    def at(h, m=0): return day.replace(hour=h, minute=m)
    segs.append({"from": ms(at(0)), "to": ms(at(7)), "mode": "night"})
    if dow <= 5:
        segs.append({"from": ms(at(8, 30)), "to": ms(at(15, 30)), "mode": "school"})
    segs.append({"from": ms(at(21)), "to": ms(day + timedelta(days=1)), "mode": "night"})

days = {}
for dow in range(1, 8):
    s = [[0, 420, "night"]]
    if dow <= 5: s.append([510, 930, "school"])
    s.append([1260, 1440, "night"])
    days[str(dow)] = s

lockdown = len(sys.argv) > 2 and sys.argv[2] == "lockdown"
cfg = {
    "version": int(sys.argv[1]) if len(sys.argv) > 1 else 1,
    "base": "day",
    "windowEnd": ms(start + timedelta(days=14)),
    "modes": {
        "day": {"name": "Day", "allowAll": True},
        "school": {"name": "School", "allow": [CAMERA], "gray": True},
        "night": {"name": "Night", "allow": [GALLERY]},
    },
    "segments": segs,
    "pattern": {"tz": "Europe/London", "days": days},
}
if lockdown:
    cfg["modes"]["school"].update({"launcher": "nokia", "touch": False, "shade": False, "kiosk": True})
    cfg["unprotect"] = ["com.android.deskclock"]
    cfg["modes"]["night"]["allow"].append("com.android.deskclock")
print(json.dumps(cfg))
