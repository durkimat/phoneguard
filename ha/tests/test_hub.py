import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import purepkg  # noqa: E402

purepkg.load()

import pytest

from phoneguard import protocol as P
from phoneguard import schedule as S
from phoneguard.hub import Hub

T0 = 1_794_000_000_000  # a Saturday in Nov 2026


class Phone:
    """A stand-in for the controller: same signing and envelope rules."""

    def __init__(self):
        self.pem, self.pk = P.generate_key()
        self.priv = P.load_private(self.pem)
        self.ctr = 0
        self.did = None
        self.ha_pk = None

    def envelope(self, payload, did=None, ctr=None):
        self.ctr = self.ctr + 1 if ctr is None else ctr
        n = P.new_nonce()
        p = P.compact(payload)
        did = did or self.did
        return {"v": 1, "did": did, "ctr": self.ctr, "n": n, "p": p,
                "s": P.sign(self.priv, P.request_bytes(did, self.ctr, n, p))}

    def check_reply(self, reply, env):
        assert "error" not in reply, reply
        assert reply["n"] == env["n"]
        assert P.verify(P.load_public(self.ha_pk), P.reply_bytes(reply["did"], reply["n"], reply["t"], reply["p"]), reply["s"])
        return json.loads(reply["p"])


@pytest.fixture
def hub():
    now = [T0]
    h = Hub({}, clock=lambda: now[0])
    h.clock = now
    h.ensure_child("her", "Her")
    return h


def pair(hub, phone):
    info = hub.create_pairing("her", "https://example/hook")
    qr = json.loads(info["qr"])
    phone.did, phone.ha_pk = qr["d"], qr["k"]
    env = phone.envelope({"kind": "pair", "code": qr["c"], "pk": phone.pk, "model": "J36"})
    reply = hub.handle(env)
    body = phone.check_reply(reply, env)
    assert body["sas"] == P.sas(phone.pk, phone.ha_pk, qr["c"])
    return body


def sync(hub, phone, cfgh="", apps=()):
    env = phone.envelope({"kind": "sync", "cfgh": cfgh, "state": {"mode": "day"}, "apps": list(apps)})
    return phone.check_reply(hub.handle(env), env)


def test_pairing_then_confirm(hub):
    ph = Phone()
    body = pair(hub, ph)
    r = sync(hub, ph)
    assert r["pending"] and r["sas"] == body["sas"] and "cfg" not in r
    assert not hub.confirm_pairing("her", "0000x")
    assert hub.confirm_pairing("her", body["sas"])
    r = sync(hub, ph)
    assert not r["pending"] and r["cfg"]["modes"]["school"]["gray"] is True


def test_pairing_code_is_single_use_and_expires(hub):
    ph = Phone()
    info = hub.create_pairing("her", "u")
    qr = json.loads(info["qr"])
    ph.did, ph.ha_pk = qr["d"], qr["k"]
    hub.clock[0] += 11 * 60_000
    env = ph.envelope({"kind": "pair", "code": qr["c"], "pk": ph.pk})
    assert hub.handle(env)["error"] == "pairing refused"
    ph2 = Phone(); hub.clock[0] = T0
    qr = json.loads(hub.create_pairing("her", "u")["qr"])
    ph2.did, ph2.ha_pk = qr["d"], qr["k"]
    bad = ph2.envelope({"kind": "pair", "code": "000000", "pk": ph2.pk})
    assert hub.handle(bad)["error"] == "pairing refused"
    good = ph2.envelope({"kind": "pair", "code": qr["c"], "pk": ph2.pk}, ctr=1)
    assert "error" not in hub.handle(good)
    again = hub.handle(good)
    assert "error" in again or again.get("n")  # a retry after success must not create a second device
    assert len(hub.data["devices"]) == 1


def test_rejects_bad_signature_replay_and_unknown(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    env = ph.envelope({"kind": "sync", "cfgh": "", "state": {}, "apps": []})
    assert "error" not in hub.handle(env)
    assert hub.handle(env)["error"] == "replay"
    forged = ph.envelope({"kind": "sync", "cfgh": "", "state": {}, "apps": []})
    forged["p"] = forged["p"].replace("sync", "synd")
    assert hub.handle(forged)["error"] in ("bad signature", "unknown kind")
    other = Phone(); other.did = ph.did
    assert hub.handle(other.envelope({"kind": "sync"}))["error"] == "bad signature"
    assert hub.handle({"v": 1, "did": "nope", "ctr": 1, "n": "x", "p": "{}", "s": "x"})["error"] == "unknown device"
    assert hub.handle("garbage")["error"] == "bad request"


def test_config_pushed_only_when_changed_and_boost_and_release(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    r = sync(hub, ph)
    assert r["cfg"] and r["boost"] is None
    r2 = sync(hub, ph, cfgh=r["cfgh"])
    assert r2["cfg"] is None
    hub.set_app_mode("her", "org.x", "school", True)
    r3 = sync(hub, ph, cfgh=r["cfgh"])
    assert "org.x" in r3["cfg"]["modes"]["school"]["allow"]
    b = hub.boost("her", "day", 15)
    assert sync(hub, ph, cfgh=r3["cfgh"])["boost"] == b
    b2 = hub.boost("her", "day", 15)
    assert b2["until"] == b["until"] + 15 * 60_000  # presses add time, like the heating boost
    hub.clock[0] += 40 * 60_000
    assert sync(hub, ph, cfgh=r3["cfgh"])["boost"] is None
    hub.release("her")
    assert sync(hub, ph, cfgh=r3["cfgh"])["cmds"] == [{"type": "release"}]
    assert hub.handle(ph.envelope({"kind": "sync"}))["error"] == "unknown device"


def test_new_apps_are_pending_after_the_first_sync(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    a = [{"pkg": "a.one", "label": "One"}, {"pkg": "a.two", "label": "Two"}]
    sync(hub, ph, apps=a)
    assert hub.view("her")["pending_apps"] == []
    sync(hub, ph, apps=a + [{"pkg": "a.new", "label": "New"}])
    assert hub.view("her")["pending_apps"] == ["a.new"]
    hub.approve_app("her", "a.new", ["day", "night"])
    v = hub.view("her")
    assert v["pending_apps"] == [] and "a.new" in v["modes"]["night"]["allow"]


def test_schedule_window_holiday_and_dst():
    ch = S.default_child("x")
    now = 1_793_000_000_000  # Sat 24 Oct 2026, before the clocks go back
    cfg = S.build_config(ch, now)
    assert len(cfg["segments"]) > 30 and cfg["windowEnd"] > now
    ch["holiday"] = {"from": "2026-10-26", "until": "2026-10-30"}
    cfg2 = S.build_config(ch, now)
    mon_school = [s for s in cfg["segments"] if s["mode"] == "school"]
    assert len([s for s in cfg2["segments"] if s["mode"] == "school"]) == len(mon_school) - 5
    assert S.cfg_hash(cfg) != S.cfg_hash(cfg2)
    # Mon 26 Oct 08:30 local is 08:30 GMT (after the change), Fri 23 Oct was BST
    first_mon = next(s for s in cfg["segments"] if s["mode"] == "school" and s["from"] > now)
    from datetime import datetime
    from zoneinfo import ZoneInfo
    assert datetime.fromtimestamp(first_mon["from"] / 1000, ZoneInfo("Europe/London")).strftime("%H:%M") == "08:30"
