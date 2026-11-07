"""Pure state machine for pairing, sync and parent actions. No Home Assistant imports, so it is
unit-testable. The integration wraps it: it persists `data`, registers the webhook and services."""
from __future__ import annotations

import json
import secrets
import time
from typing import Any, Callable

from . import protocol as P
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

    def update_child(self, cid: str, patch: dict) -> None:
        ch = self.data["children"][cid]
        allowed = {"name", "person", "override", "tz", "base", "notify", "modes", "week", "holiday", "protect_extra", "unprotect", "restrictions"}
        before = {k: ch.get(k) for k in allowed}
        for k, v in patch.items():
            if k in allowed:
                ch[k] = v
        try:
            self._validate(ch)
        except Exception:
            ch.update(before)  # a bad change must never be kept, let alone reach the phone
            raise

    def _validate(self, ch: dict) -> None:
        S.build_config(ch, self.now())  # bad timezone or timetable shape
        for dow, segs in ch["week"].items():
            for _start, _end, mode in segs:
                if mode not in ch["modes"]:
                    raise ValueError(f"the timetable uses mode {mode!r}, which this device does not have")
        if ch["base"] not in ch["modes"]:
            raise ValueError(f"base mode {ch['base']!r} does not exist")
        if ch.get("override") is not None and ch["override"] not in ch["modes"]:
            raise ValueError(f"mode {ch['override']!r} does not exist")

    def set_override(self, cid: str, mode: str | None) -> None:
        """Force one mode regardless of the timetable (None or "auto" goes back to the timetable)."""
        self.update_child(cid, {"override": None if mode in (None, "", "auto") else mode})

    COPY_PARTS = ("timetable", "holiday", "restrictions", "mode_options")

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
            elif part == "mode_options":
                modes = json.loads(json.dumps(b["modes"]))
                for mid, m in a["modes"].items():
                    if mid in modes:  # same mode on both: take the options, keep this device's own app list
                        m = S.mode_with_defaults(mid, m)
                        modes[mid]["gray"] = m.get("gray", False)
                        modes[mid]["allowAll"] = m.get("allowAll", False)
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
        if isinstance(payload.get("labels"), dict):
            dev.setdefault("labels", {}).update({k: v for k, v in payload["labels"].items() if isinstance(k, str) and isinstance(v, str)})
        ch = self.data["children"][dev["child"]]
        if ch.get("known") is None and dev["apps"]:
            ch["known"] = sorted(a["pkg"] for a in dev["apps"])  # the apps already on the phone are not "new"
        if dev["pending"]:
            return P.make_reply(self.priv, did, n, {"kind": "sync", "pending": True, "sas": dev["sas"]}, now)
        cfg = S.build_config(ch, now)
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
        cfg = S.build_config(ch, now)
        boost = self.active_boost(cid)
        out: dict = {
            "child": cid, "name": ch["name"], "person": ch.get("person") or ch["name"], "tz": ch["tz"], "base": ch["base"], "notify": ch["notify"],
            "modes": {mid: S.mode_with_defaults(mid, m) for mid, m in ch["modes"].items()},
            "override": ch.get("override") if ch.get("override") in ch["modes"] else None, "week": ch["week"], "holiday": ch["holiday"],
            "mode_now": S.resolve_mode(cfg, boost, now), "boost": boost, "now": now,
            "device": None, "apps": [], "pending_apps": [], "usage": {}, "labels": {},
            "restrictions": ch.get("restrictions"),
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
            out["labels"] = dev.get("labels", {})
            out["apps"] = sorted(dev["apps"], key=lambda a: a.get("label", a["pkg"]).lower())
            out["pending_apps"] = [a["pkg"] for a in dev["apps"] if a["pkg"] not in known]
        return out
