"""Pure state machine for pairing, sync and parent actions. No Home Assistant imports, so it is
unit-testable. The integration wraps it: it persists `data`, registers the webhook and services."""
from __future__ import annotations

import json
import re
import secrets
import time
from typing import Any, Callable

from . import protocol as P
from . import power as PW
from . import schedule as S

PAIRING_TTL_MS = 10 * 60_000
BOOST_CAP_MIN = 180
USAGE_DAYS = 30


class Hub:
    def __init__(self, data: dict, clock: Callable[[], int] | None = None):
        self.data = data
        self.now = clock or (lambda: int(time.time() * 1000))
        for k, v in (("children", {}), ("devices", {}), ("pairing", {}), ("boosts", {})):
            data.setdefault(k, v)
        if "ha_key_pem" not in data:
            data["ha_key_pem"], data["ha_pk"] = P.generate_key()
        self.priv = P.load_private(data["ha_key_pem"])

    # ---- children ------------------------------------------------------------------------------

    def ensure_child(self, cid: str, name: str | None = None, person: str | None = None) -> dict:
        ch = self.data["children"].get(cid)
        if ch is None:
            ch = S.default_child(name or cid)
            ch["person"] = person or ch["name"]
            self.data["children"][cid] = ch
        return ch

    OPTION_KEYS = ("spotify_client_id",)

    def set_options(self, patch: dict) -> None:
        """Settings shared by every phone (today: the Spotify app's Client ID, which is public, never a secret)."""
        opts = self.data.setdefault("options", {})
        for k, v in patch.items():
            if k not in self.OPTION_KEYS:
                raise ValueError(f"unknown option {k!r}")
            if k == "spotify_client_id":
                v = (v or "").strip().lower()
                if v and not (len(v) == 32 and all(ch in "0123456789abcdef" for ch in v)):
                    raise ValueError("a Spotify Client ID is 32 letters and digits (0 to 9 and a to f)")
            if v:
                opts[k] = v
            else:
                opts.pop(k, None)

    def set_spotify(self, cid: str, refresh: str | None, name: str = "") -> None:
        """Keep (or clear) the Spotify refresh token for one phone. Never part of what the card can edit or copy."""
        ch = self.data["children"][cid]
        if refresh:
            ch["spotify_refresh"] = refresh
            ch["spotify_name"] = name
        else:
            ch.pop("spotify_refresh", None)
            ch.pop("spotify_name", None)

    def update_child(self, cid: str, patch: dict) -> None:
        ch = self.data["children"][cid]
        allowed = {"name", "person", "override", "tz", "base", "notify", "modes", "week", "holiday", "protect_extra", "unprotect", "restrictions", "whatsapp", "pinned", "location", "notes_hide", "keep_running", "idle_dry_run"}
        before = {k: ch[k] for k in allowed if k in ch}
        for k, v in patch.items():
            if k == "whatsapp" and isinstance(v, dict):
                v = dict(v)
                if isinstance(v.get("allowed"), list):  # tidy what the parent typed: no blanks, no repeats
                    seen: list[str] = []
                    for x in v["allowed"]:
                        x = x.strip() if isinstance(x, str) else x
                        if x and x not in seen:
                            seen.append(x)
                    v["allowed"] = seen
            if k == "location" and v is not None:  # two numbers on the map, rounded so the phone never holds an exact address
                try:
                    v = {"lat": round(float(v["lat"]), 3), "lon": round(float(v["lon"]), 3)}
                except (KeyError, TypeError, ValueError):
                    raise ValueError("location needs a lat and a lon") from None
                if not (-90 <= v["lat"] <= 90 and -180 <= v["lon"] <= 180):
                    raise ValueError("location is out of range")
            if k == "notes_hide" and isinstance(v, list):
                v = list(dict.fromkeys(x for x in v if isinstance(x, str) and x))
            if k == "pinned" and isinstance(v, list):  # keep the parent's order, no blanks or repeats
                v = list(dict.fromkeys(x for x in v if isinstance(x, str) and x))
            if k in allowed:
                ch[k] = v
        try:
            self._validate(ch)
        except Exception:
            ch.update(before)  # a bad change must never be kept, let alone reach the phone
            for k in allowed - before.keys():
                ch.pop(k, None)
            raise

    def _validate(self, ch: dict) -> None:
        S.build_config(ch, self.now())  # bad timezone or timetable shape
        for dow, segs in ch["week"].items():
            for _start, _end, mode in segs:
                if mode not in ch["modes"]:
                    raise ValueError(f"the timetable uses mode {mode!r}, which this device does not have")
        for mid, m in ch["modes"].items():
            if m.get("wa", "open") not in S.WA_CHECKS:
                raise ValueError(f"mode {mid!r} has an unknown WhatsApp check setting {m['wa']!r}")
            if m.get("wa_app", "auto") not in S.WA_APPS:
                raise ValueError(f"mode {mid!r} has an unknown WhatsApp app setting {m['wa_app']!r}")
            if m.get("notes", "show") not in S.NOTE_MODES:
                raise ValueError(f"mode {mid!r} has an unknown notifications setting {m['notes']!r}")
            if m.get("location", "off") not in S.LOC_MODES:
                raise ValueError(f"mode {mid!r} has an unknown location setting {m['location']!r}")
            if not isinstance(m.get("loc_every", 30), int) or isinstance(m.get("loc_every", 30), bool) or not 0 <= m.get("loc_every", 30) <= 1440:
                raise ValueError(f"mode {mid!r} needs location minutes between 0 (live) and 1440")
            if not isinstance(m.get("screen_off_min", 5), int) or isinstance(m.get("screen_off_min", 5), bool) or not 0 <= m.get("screen_off_min", 5) <= 1440:
                raise ValueError(f"mode {mid!r} needs screen-off minutes between 0 (never) and 1440")
            if not isinstance(m.get("idle_min", 10), int) or isinstance(m.get("idle_min", 10), bool) or not 0 <= m.get("idle_min", 10) <= 1440:
                raise ValueError(f"mode {mid!r} needs idle minutes between 0 (never) and 1440")
            if m.get("sound", "normal") not in S.SOUNDS:
                raise ValueError(f"mode {mid!r} has an unknown sound setting {m['sound']!r}")
        w = S.whatsapp_with_defaults(ch)
        if not isinstance(w["allowed"], list) or not all(isinstance(x, str) for x in w["allowed"]):
            raise ValueError("the WhatsApp allowed list must be a list of numbers and group names")
        if not (1 <= int(w["perMinute"]) <= 60) or not (1 <= int(w["perDay"]) <= 2000):
            raise ValueError("WhatsApp send limits must be 1 to 60 a minute and 1 to 2000 a day")
        if "idle_dry_run" in ch and not isinstance(ch["idle_dry_run"], bool):
            raise ValueError("idle_dry_run must be true or false")
        if not isinstance(ch.get("idle_dry_run", False), bool):
            raise ValueError("idle_dry_run must be true or false")
        if "keep_running" in ch and not (isinstance(ch["keep_running"], list) and all(isinstance(x, str) for x in ch["keep_running"])):
            raise ValueError("the keep-running list must be a list of app names")
        if ch["base"] not in ch["modes"]:
            raise ValueError(f"base mode {ch['base']!r} does not exist")
        if ch.get("override") is not None and ch["override"] not in ch["modes"]:
            raise ValueError(f"mode {ch['override']!r} does not exist")

    def set_override(self, cid: str, mode: str | None) -> None:
        """Force one mode regardless of the timetable (None or "auto" goes back to the timetable)."""
        self.update_child(cid, {"override": None if mode in (None, "", "auto") else mode})

    COPY_PARTS = ("timetable", "holiday", "restrictions", "mode_options", "whatsapp")

    def copy_settings(self, src: str, dst: str, parts: list[str]) -> list[str]:
        """Copy settings between two guarded devices. App lists are never copied: they depend on what is installed."""
        if src == dst:
            raise ValueError("choose a different device to copy to")
        a, b = self.data["children"][src], self.data["children"][dst]
        patch: dict = {}
        done = []
        for part in parts:
            if part == "timetable":
                patch["week"] = json.loads(json.dumps(a["week"])); patch["base"] = a["base"]; patch["tz"] = a["tz"]
            elif part == "holiday":
                patch["holiday"] = json.loads(json.dumps(a["holiday"]))
            elif part == "restrictions":
                patch["restrictions"] = json.loads(json.dumps(a.get("restrictions")))
            elif part == "whatsapp":
                patch["whatsapp"] = json.loads(json.dumps(S.whatsapp_with_defaults(a)))
            elif part == "mode_options":
                modes = json.loads(json.dumps(b["modes"]))
                for mid, m in a["modes"].items():
                    if mid in modes:  # same mode on both: take the options, keep this device's own app list
                        m = S.mode_with_defaults(mid, m)
                        modes[mid]["gray"] = m.get("gray", False)
                        modes[mid]["auto_new"] = bool(m.get("auto_new", False))
                        for k in S.LOCKDOWN_KEYS:
                            modes[mid][k] = m[k]
                patch["modes"] = modes
            else:
                raise ValueError(f"unknown part {part!r}")
            done.append(part)
        self.update_child(dst, patch)
        return done

    def set_holiday(self, cid: str, frm: str | None, until: str | None) -> None:
        self.data["children"][cid]["holiday"] = {"from": frm, "until": until} if frm and until else None

    def set_app_mode(self, cid: str, pkg: str, mode: str, allowed: bool) -> None:
        m = self.data["children"][cid]["modes"][mode]
        s = set(m.get("allow", []))
        (s.add if allowed else s.discard)(pkg)
        m["allow"] = sorted(s)
        self.dismiss_app(cid, pkg)  # touching an app's ticks is the parent's decision about it: no longer new

    def migrate_apps(self) -> None:
        """At start-up: bring every phone's modes to the explicit-list form from the apps it last reported."""
        for cid, ch in self.data["children"].items():
            found = self.device_of(cid)
            if found and found[1].get("apps"):
                self.apply_app_policy(ch, {a["pkg"] for a in found[1]["apps"]})

    # ---- installing, removing and updating apps (jobs the phone does and reports back)

    APP_KINDS = ("install", "remove", "update")

    def queue_app(self, cid: str, kind: str, pkg: str, name: str, url: str = "", sha256: str = "", size: int = 0) -> dict:
        from . import store as ST
        if kind not in self.APP_KINDS:
            raise ValueError("unknown job")
        if not ST.valid_pkg(pkg):
            raise ValueError("that is not an app name")
        if kind != "remove":
            if not url.startswith("https://"):
                raise ValueError("the download address must be https")
            if not re.fullmatch(r"[0-9a-f]{64}", sha256 or ""):
                raise ValueError("the checksum is missing")
        ch = self.data["children"][cid]
        cmds = ch.setdefault("commands", [])
        if any(c["kind"] == kind and c["pkg"] == pkg for c in cmds):
            raise ValueError("that is already waiting for the phone")
        cmd = {"id": secrets.token_hex(6), "kind": kind, "pkg": pkg, "name": name[:60] or pkg, "url": url, "sha256": sha256, "size": int(size), "at": self.now()}
        cmds.append(cmd)
        return cmd

    def cancel_app(self, cid: str, cmd_id: str) -> None:
        ch = self.data["children"][cid]
        ch["commands"] = [c for c in ch.get("commands", []) if c["id"] != cmd_id]

    def _merge_app_results(self, ch: dict, results: Any) -> None:
        """The phone reports each finished job as [id, "done" or "failed", detail, time]."""
        if not isinstance(results, list):
            return
        log = ch.setdefault("app_log", [])
        for r in results:
            if not (isinstance(r, list) and len(r) >= 4 and isinstance(r[0], str) and r[1] in ("done", "failed")):
                continue
            cmd = next((c for c in ch.get("commands", []) if c["id"] == r[0]), None)
            if cmd is None:
                continue
            log.append([int(r[3]), cmd["name"], cmd["kind"], r[1], str(r[2])[:120]])
            ch["commands"] = [c for c in ch["commands"] if c["id"] != r[0]]
        del log[:-40]

    def set_latest(self, latest: dict | None) -> None:
        """The newest Phone Guard release GitHub has (None when there is none)."""
        if latest:
            self.data["latest"] = latest
        else:
            self.data.pop("latest", None)

    def update_for(self, cid: str) -> dict | None:
        from . import store as ST
        latest = self.data.get("latest")
        found = self.device_of(cid)
        if not latest or not found:
            return None
        have = str((found[1].get("state") or {}).get("version", ""))
        return {"version": latest["version"], "notes": latest.get("notes", ""), "installed": have, "available": ST.is_newer(latest["version"], have), "ready": bool(latest.get("sha256"))}

    def queue_update(self, cid: str) -> dict:
        u = self.update_for(cid)
        latest = self.data.get("latest")
        if not u or not u["available"] or not latest or not latest.get("sha256"):
            raise ValueError("there is no update ready for that phone")
        return self.queue_app(cid, "update", "io.github.durkimat.phoneguard", "Phone Guard " + latest["version"], latest["url"], latest["sha256"], latest.get("size", 0))

    def set_mode_all(self, cid: str, mode: str, allowed: bool) -> None:
        """The select-all tick at the top of a mode's column: every app the phone has reported, or none."""
        found = self.device_of(cid)
        pkgs = sorted({a["pkg"] for a in found[1]["apps"]}) if found else []
        m = self.data["children"][cid]["modes"][mode]
        keep = set(m.get("allow", []))
        m["allow"] = sorted(keep | set(pkgs)) if allowed else sorted(keep - set(pkgs))

    def apply_app_policy(self, ch: dict, pkgs: set[str]) -> None:
        """Run on every report of the phone's apps. The matrix is the only authority on what is visible:
        an old "allow everything" mode becomes that mode with every current app ticked (once), and a mode that
        allows new apps automatically ticks each app the first time it is seen."""
        for m in ch["modes"].values():
            if m.get("allowAll"):
                m["allow"] = sorted(set(m.get("allow", [])) | pkgs)
                m["allowAll"] = False
                m.setdefault("auto_new", False)
        seen = ch.get("seen")
        if seen is None:
            ch["seen"] = sorted(pkgs)  # what is on the phone at the start is the baseline, not "new"
            return
        fresh = pkgs - set(seen)
        if fresh:
            for m in ch["modes"].values():
                if m.get("auto_new"):
                    m["allow"] = sorted(set(m.get("allow", [])) | fresh)
            ch["seen"] = sorted(set(seen) | pkgs)

    def approve_app(self, cid: str, pkg: str, modes: list[str]) -> None:
        for mode in modes:
            self.set_app_mode(cid, pkg, mode, True)
        self.dismiss_app(cid, pkg)

    def dismiss_app(self, cid: str, pkg: str) -> None:
        ch = self.data["children"][cid]
        ch["known"] = sorted(set(ch.get("known") or []) | {pkg})

    # ---- boost ---------------------------------------------------------------------------------

    def boost(self, cid: str, mode: str, minutes: int, extend: bool = True) -> dict:
        now = self.now()
        cur = self.active_boost(cid)
        minutes = max(1, min(int(minutes), BOOST_CAP_MIN))
        if cur and extend and cur["mode"] == mode:
            until = min(cur["until"] + minutes * 60_000, now + BOOST_CAP_MIN * 60_000)
        else:
            until = now + minutes * 60_000
        self.data["boosts"][cid] = {"mode": mode, "until": until}
        return self.data["boosts"][cid]

    def cancel_boost(self, cid: str) -> None:
        self.data["boosts"].pop(cid, None)

    def active_boost(self, cid: str) -> dict | None:
        b = self.data["boosts"].get(cid)
        if b and b["until"] > self.now():
            return b
        self.data["boosts"].pop(cid, None)
        return None

    # ---- pairing -------------------------------------------------------------------------------

    def create_pairing(self, cid: str, url: str) -> dict:
        self.ensure_child(cid)
        for old in [d for d, p in self.data["pairing"].items() if p["child"] == cid]:
            del self.data["pairing"][old]
        did = secrets.token_hex(6)
        code = f"{secrets.randbelow(10**6):06d}"
        self.data["pairing"][did] = {"child": cid, "code": code, "expires": self.now() + PAIRING_TTL_MS}
        qr = P.compact({"v": P.V, "u": url, "d": did, "c": code, "k": self.data["ha_pk"]})
        return {"did": did, "code": code, "qr": qr, "expires": self.data["pairing"][did]["expires"]}

    def device_of(self, cid: str) -> tuple[str, dict] | None:
        for did, dev in self.data["devices"].items():
            if dev["child"] == cid and not dev.get("released"):
                return did, dev
        return None

    def confirm_pairing(self, cid: str, sas: str) -> bool:
        found = self.device_of(cid)
        if not found or not found[1]["pending"] or found[1]["sas"] != sas.strip():
            return False
        found[1]["pending"] = False
        return True

    def release(self, cid: str) -> bool:
        found = self.device_of(cid)
        if not found:
            return False
        found[1]["release"] = True
        return True

    def forget(self, cid: str) -> None:
        for did in [d for d, v in self.data["devices"].items() if v["child"] == cid]:
            del self.data["devices"][did]
        for did in [d for d, v in self.data["pairing"].items() if v["child"] == cid]:
            del self.data["pairing"][did]

    # ---- the one network entry point -----------------------------------------------------------

    def handle(self, env: Any) -> dict:
        try:
            return self._handle(env)
        except (KeyError, ValueError, TypeError, AttributeError, json.JSONDecodeError):
            return {"error": "bad request"}

    def _handle(self, env: dict) -> dict:
        if env.get("v") != P.V:
            return {"error": "version"}
        did, ctr, n, p, s = env["did"], env["ctr"], env["n"], env["p"], env["s"]
        if not isinstance(ctr, int) or not isinstance(n, str) or not isinstance(p, str):
            return {"error": "bad request"}
        payload = json.loads(p)
        if did in self.data["pairing"] and payload.get("kind") == "pair":
            return self._pair(did, ctr, n, p, s, payload)
        dev = self.data["devices"].get(did)
        if dev is None or dev.get("released"):
            return {"error": "unknown device"}
        if not P.verify(P.load_public(dev["pk"]), P.request_bytes(did, ctr, n, p), s):
            return {"error": "bad signature"}
        if ctr <= dev["ctr"]:
            return {"error": "replay"}
        dev["ctr"] = ctr
        if payload.get("kind") == "sync":
            return self._sync(did, dev, n, payload)
        return {"error": "unknown kind"}

    def _pair(self, did: str, ctr: int, n: str, p: str, s: str, payload: dict) -> dict:
        pr = self.data["pairing"][did]
        if self.now() > pr["expires"] or ctr != 1 or payload.get("code") != pr["code"]:
            return {"error": "pairing refused"}
        pk = payload["pk"]
        if not P.verify(P.load_public(pk), P.request_bytes(did, ctr, n, p), s):
            return {"error": "bad signature"}
        del self.data["pairing"][did]  # the code works once
        for old in [d for d, v in self.data["devices"].items() if v["child"] == pr["child"]]:
            del self.data["devices"][old]
        self.data["devices"][did] = {
            "child": pr["child"], "pk": pk, "ctr": 1, "pending": True,
            "sas": P.sas(pk, self.data["ha_pk"], pr["code"]), "model": payload.get("model", ""),
            "paired_at": self.now(), "last_seen": self.now(), "state": {}, "apps": [], "cfgh": "",
        }
        return P.make_reply(self.priv, did, n, {"kind": "pair_ok", "sas": self.data["devices"][did]["sas"]}, self.now())

    def _sync(self, did: str, dev: dict, n: str, payload: dict) -> dict:
        now = self.now()
        dev["last_seen"] = now
        dev["state"] = payload.get("state", {})
        dev["apps"] = payload.get("apps", [])
        dev["cfgh"] = payload.get("cfgh", "")
        self._merge_usage(dev, payload.get("usage"))
        dev["power"] = PW.merge(dev.get("power", []), payload.get("power"), now)
        dev["reaped"] = PW.merge_reaped(dev.get("reaped", []), payload.get("reaped"))
        self._merge_app_results(self.data["children"][dev["child"]], payload.get("appResults"))
        self._merge_fix(dev, payload.get("loc"))
        if isinstance(payload.get("labels"), dict):
            dev.setdefault("labels", {}).update({k: v for k, v in payload["labels"].items() if isinstance(k, str) and isinstance(v, str)})
        ch = self.data["children"][dev["child"]]
        if ch.get("known") is None and dev["apps"]:
            ch["known"] = sorted(a["pkg"] for a in dev["apps"])  # the apps already on the phone are not "new"
        if dev["apps"]:
            self.apply_app_policy(ch, {a["pkg"] for a in dev["apps"]})
        if dev["pending"]:
            return P.make_reply(self.priv, did, n, {"kind": "sync", "pending": True, "sas": dev["sas"]}, now)
        cfg = S.build_config(ch, now, self.data.get("options"))
        pushed = dev["cfgh"] != S.cfg_hash(cfg)
        if pushed:
            dev["cfgh"] = S.cfg_hash(cfg)  # assume it lands; the phone's next report corrects this
        cmds = []
        if dev.get("release"):
            cmds.append({"type": "release"})
            dev["released"] = True
        reply = {
            "kind": "sync", "pending": False, "cfgh": S.cfg_hash(cfg),
            "cfg": cfg if pushed else None,
            "boost": self.active_boost(dev["child"]), "cmds": cmds,
        }
        return P.make_reply(self.priv, did, n, reply, now)

    def _merge_fix(self, dev: dict, fixes: Any) -> None:
        """Keep the newest position the phone sent: [time ms, latitude, longitude, accuracy m, provider, mode]."""
        if not isinstance(fixes, list):
            return
        for f in fixes:
            if (isinstance(f, list) and len(f) >= 5 and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in f[:4])
                    and -90 <= f[1] <= 90 and -180 <= f[2] <= 180 and f[3] >= 0):
                if f[0] > (dev.get("fix") or [0])[0]:
                    dev["fix"] = f[:6]

    def _merge_usage(self, dev: dict, usage: Any) -> None:
        """Seconds in the foreground per app per day. The phone sends today and yesterday every sync."""
        if not isinstance(usage, dict):
            return
        store = dev.setdefault("usage", {})
        for day, apps in usage.items():
            if isinstance(day, str) and len(day) == 10 and isinstance(apps, dict):
                store[day] = {k: int(v) for k, v in apps.items() if isinstance(k, str) and isinstance(v, (int, float))}
        for old in sorted(store)[:-USAGE_DAYS]:
            del store[old]

    def rename_child(self, old: str, new: str) -> None:
        """Change the key (and so the sensor name) of a guarded device without losing anything."""
        if old not in self.data["children"]:
            raise KeyError(old)
        if new == old:
            return
        if new in self.data["children"]:
            raise ValueError(f"{new!r} already exists")
        self.data["children"][new] = self.data["children"].pop(old)
        if old in self.data["boosts"]:
            self.data["boosts"][new] = self.data["boosts"].pop(old)
        for table in ("devices", "pairing"):
            for rec in self.data[table].values():
                if rec.get("child") == old:
                    rec["child"] = new

    def remove_child(self, cid: str) -> None:
        self.forget(cid)
        self.data["children"].pop(cid, None)
        self.data["boosts"].pop(cid, None)

    # ---- what the card shows -------------------------------------------------------------------

    def view(self, cid: str) -> dict:
        now = self.now()
        ch = self.data["children"][cid]
        found = self.device_of(cid)
        cfg = S.build_config(ch, now, self.data.get("options"))
        boost = self.active_boost(cid)
        out: dict = {
            "child": cid, "name": ch["name"], "person": ch.get("person") or ch["name"], "tz": ch["tz"], "base": ch["base"], "notify": ch["notify"],
            "modes": {mid: S.mode_with_defaults(mid, m) for mid, m in ch["modes"].items()},
            "override": ch.get("override") if ch.get("override") in ch["modes"] else None, "week": ch["week"], "holiday": ch["holiday"],
            "mode_now": S.resolve_mode(cfg, boost, now), "boost": boost, "now": now,
            "device": None, "apps": [], "pending_apps": [], "usage": {}, "labels": {},
            "restrictions": ch.get("restrictions"),
            "whatsapp": S.whatsapp_with_defaults(ch),
            "options": dict(self.data.get("options") or {}),
            "pinned": list(ch.get("pinned") or []),
            "notes_hide": list(ch.get("notes_hide") or []),
            "keep_running": list(ch.get("keep_running", S.DEFAULT_KEEP_RUNNING)),
            "idle_dry_run": bool(ch.get("idle_dry_run", False)),
            "app_commands": [{"id": c["id"], "kind": c["kind"], "pkg": c["pkg"], "name": c["name"]} for c in ch.get("commands", [])],
            "app_log": [e for e in reversed(ch.get("app_log", []))][:8],
            "update": self.update_for(cid),
            "location": ch.get("location"),
            "spotify": {"signed_in": bool(ch.get("spotify_refresh")), "name": ch.get("spotify_name", "")},
        }
        pairing = next(((d, p) for d, p in self.data["pairing"].items() if p["child"] == cid), None)
        if pairing and pairing[1]["expires"] > now:
            out["pairing"] = {"expires": pairing[1]["expires"]}
        if found:
            did, dev = found
            known = set(ch.get("known") or [])
            out["device"] = {
                "did": did, "pending": dev["pending"], "sas": dev["sas"] if dev["pending"] else None,
                "last_seen": dev["last_seen"], "state": dev["state"], "model": dev.get("model", ""),
                "in_sync": dev["cfgh"] == S.cfg_hash(cfg), "releasing": bool(dev.get("release")),
            }
            out["usage"] = dev.get("usage", {})
            out["power"] = PW.summarize(dev.get("power", []), now)
            out["reaped"] = PW.summarize_reaped(dev.get("reaped", []), now)
            out["fix"] = dev.get("fix")
            out["labels"] = dev.get("labels", {})
            out["apps"] = sorted(dev["apps"], key=lambda a: a.get("label", a["pkg"]).lower())
            out["pending_apps"] = [a["pkg"] for a in dev["apps"] if a["pkg"] not in known]
        return out
