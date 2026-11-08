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


def test_usage_is_merged_trimmed_and_shown(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    def send(usage):
        env = ph.envelope({"kind": "sync", "cfgh": "", "state": {}, "apps": [], "usage": usage})
        ph.check_reply(hub.handle(env), env)
    send({"2026-11-07": {"a.one": 600, "a.two": 60}, "2026-11-06": {"a.one": 1200}})
    send({"2026-11-07": {"a.one": 900}})  # later today replaces earlier numbers for that day
    u = hub.view("her")["usage"]
    assert u["2026-11-07"] == {"a.one": 900} and u["2026-11-06"] == {"a.one": 1200}
    for d in range(1, 41):
        send({f"2026-10-{d:02d}"[:10] if d < 32 else "2026-09-01": {"x": 1}})
    assert len(hub.device_of("her")[1]["usage"]) == 30
    send("garbage"); send({"bad": 1})
    assert len(hub.device_of("her")[1]["usage"]) == 30


def test_remove_child_and_restrictions_patch(hub):
    ph = Phone(); pair(hub, ph)
    hub.update_child("her", {"restrictions": ["no_factory_reset", "no_config_vpn"]})
    assert hub.view("her")["restrictions"] == ["no_factory_reset", "no_config_vpn"]
    hub.update_child("her", {"modes": {**hub.data["children"]["her"]["modes"], "day": {"name": "Day", "allowAll": False, "allow": ["a.one"], "gray": False}}})
    cfg = S.build_config(hub.data["children"]["her"], T0)
    assert cfg["modes"]["day"]["allowAll"] is False and cfg["modes"]["day"]["allow"] == ["a.one"]
    hub.remove_child("her")
    assert "her" not in hub.data["children"] and not hub.data["devices"]


def test_copy_settings_between_devices_and_validation(hub):
    hub.ensure_child("tablet", "Tablet", "Her")
    hub.update_child("her", {"restrictions": ["no_config_vpn"], "week": {**hub.data["children"]["her"]["week"], "6": [[0, 600, "night"], [600, 1440, "day"]]}})
    hub.set_app_mode("her", "a.only.on.phone", "school", True)
    hub.data["children"]["her"]["modes"]["school"]["gray"] = False
    done = hub.copy_settings("her", "tablet", ["timetable", "restrictions", "mode_options"])
    t = hub.data["children"]["tablet"]
    assert done == ["timetable", "restrictions", "mode_options"]
    assert t["week"]["6"] == [[0, 600, "night"], [600, 1440, "day"]]
    assert t["restrictions"] == ["no_config_vpn"] and t["modes"]["school"]["gray"] is False
    assert "a.only.on.phone" not in t["modes"]["school"]["allow"]  # app lists stay per device
    assert hub.view("tablet")["person"] == "Her"
    with pytest.raises(ValueError):
        hub.copy_settings("her", "her", ["timetable"])
    with pytest.raises(ValueError):
        hub.copy_settings("her", "tablet", ["nonsense"])
    # a timetable naming a mode the target lacks is refused and leaves the target unchanged
    hub.data["children"]["tablet"]["modes"].pop("night")
    hub.data["children"]["tablet"]["week"] = {str(d): [[0, 1440, "day"]] for d in range(1, 8)}
    before = json.dumps(hub.data["children"]["tablet"], sort_keys=True)
    with pytest.raises(ValueError):
        hub.copy_settings("her", "tablet", ["timetable"])
    assert json.dumps(hub.data["children"]["tablet"], sort_keys=True) == before


def test_rename_child_keeps_everything(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    hub.boost("her", "day", 15)
    hub.set_app_mode("her", "a.one", "school", True)
    hub.ensure_child("other", "Other")
    with pytest.raises(ValueError):
        hub.rename_child("her", "other")
    hub.rename_child("her", "dumbo_phone")
    assert "her" not in hub.data["children"] and hub.data["children"]["dumbo_phone"]["modes"]["school"]["allow"] == ["a.one"]
    assert hub.device_of("dumbo_phone") is not None and hub.active_boost("dumbo_phone")
    r = sync(hub, ph)  # the paired phone carries on syncing, and gets this child's config
    assert "a.one" in r["cfg"]["modes"]["school"]["allow"]


def test_lockdown_defaults_override_and_copy(hub):
    ph = Phone(); pair(hub, ph); hub.confirm_pairing("her", hub.device_of("her")[1]["sas"])
    cfg = S.build_config(hub.data["children"]["her"], T0)
    sc = cfg["modes"]["school"]
    assert (sc["launcher"], sc["touch"], sc["shade"], sc["kiosk"]) == ("nokia", False, False, True)
    d = cfg["modes"]["day"]
    assert (d["launcher"], d["touch"], d["shade"], d["kiosk"]) == ("nokia", True, True, False)
    assert "com.android.deskclock" in cfg["modes"]["night"]["allow"] and "com.android.deskclock" not in sc["allow"]
    assert cfg["unprotect"] == ["com.android.deskclock"]
    # older data without the settings still gets the defaults
    for m in hub.data["children"]["her"]["modes"].values():
        for k in S.LOCKDOWN_KEYS: m.pop(k, None)
    assert S.build_config(hub.data["children"]["her"], T0)["modes"]["school"]["kiosk"] is True
    # forcing a mode
    h0 = S.cfg_hash(cfg)
    hub.set_override("her", "night")
    forced = S.build_config(hub.data["children"]["her"], T0)
    assert forced["override"] == "night" and S.cfg_hash(forced) != h0
    assert S.resolve_mode(forced, None, T0) == "night"
    assert hub.view("her")["override"] == "night"
    with pytest.raises(ValueError):
        hub.set_override("her", "nonsense")
    assert hub.view("her")["override"] == "night"  # a refused change leaves it as it was
    hub.set_override("her", "auto")
    assert "override" not in S.build_config(hub.data["children"]["her"], T0)
    # copy keeps each device's own app lists but takes the lockdown options
    hub.ensure_child("tab", "Tab", "Her")
    hub.data["children"]["her"]["modes"]["day"].update({"touch": False, "launcher": "default"})
    hub.copy_settings("her", "tab", ["mode_options"])
    assert hub.data["children"]["tab"]["modes"]["day"]["touch"] is False and hub.data["children"]["tab"]["modes"]["day"]["launcher"] == "default"


def test_sound_defaults_validation_and_copy(hub):
    ch = hub.data["children"]["her"]
    cfg = S.build_config(ch, hub.now())
    assert cfg["modes"]["school"]["sound"] == "silent" and cfg["modes"]["day"]["sound"] == "normal"
    assert cfg["modes"]["school"]["inbound"] is False and cfg["modes"]["day"]["inbound"] is True
    modes = json.loads(json.dumps(ch["modes"])); modes["night"]["sound"] = "vibrate"
    hub.update_child("her", {"modes": modes})
    assert S.build_config(hub.data["children"]["her"], hub.now())["modes"]["night"]["sound"] == "vibrate"
    bad = json.loads(json.dumps(modes)); bad["night"]["sound"] = "loud"
    with pytest.raises(ValueError):
        hub.update_child("her", {"modes": bad})
    assert hub.data["children"]["her"]["modes"]["night"]["sound"] == "vibrate"  # the refused change was not kept
    hub.ensure_child("sib", "Sib")
    hub.copy_settings("her", "sib", ["mode_options"])
    assert hub.data["children"]["sib"]["modes"]["night"]["sound"] == "vibrate"


def test_whatsapp_defaults_validation_and_copy(hub):
    ch = hub.data["children"]["her"]
    cfg = S.build_config(ch, hub.now())
    assert cfg["modes"]["school"]["wa"] == "open" and cfg["modes"]["day"]["wa"] == "30"
    assert cfg["whatsapp"] == {"all": False, "allowed": [], "perMinute": 5, "perDay": 200}  # nobody until a parent chooses
    hub.update_child("her", {"whatsapp": {"all": False, "allowed": ["+44 7700 900111", "g:Family", "  "], "perMinute": 3, "perDay": 40}})
    w = S.build_config(hub.data["children"]["her"], hub.now())["whatsapp"]
    assert w == {"all": False, "allowed": ["+44 7700 900111", "g:Family"], "perMinute": 3, "perDay": 40}
    modes = json.loads(json.dumps(ch["modes"])); modes["school"]["wa"] = "off"
    hub.update_child("her", {"modes": modes})
    assert S.build_config(hub.data["children"]["her"], hub.now())["modes"]["school"]["wa"] == "off"
    for bad in ({"allowed": "07700"}, {"allowed": [], "perMinute": 0}, {"allowed": [], "perDay": 5000}):
        with pytest.raises(ValueError):
            hub.update_child("her", {"whatsapp": bad})
    badmodes = json.loads(json.dumps(modes)); badmodes["day"]["wa"] = "always"
    with pytest.raises(ValueError):
        hub.update_child("her", {"modes": badmodes})
    assert hub.data["children"]["her"]["whatsapp"]["perMinute"] == 3  # refused changes were not kept
    hub.ensure_child("sib", "Sib")
    hub.copy_settings("her", "sib", ["whatsapp", "mode_options"])
    assert hub.data["children"]["sib"]["whatsapp"]["allowed"] == ["+44 7700 900111", "g:Family"]
    assert hub.data["children"]["sib"]["modes"]["school"]["wa"] == "off"
    assert hub.view("her")["whatsapp"]["perDay"] == 40


def test_whatsapp_app_choice_defaults_validation_and_config(hub):
    ch = hub.data["children"]["her"]
    cfg = S.build_config(ch, hub.now())
    assert cfg["modes"]["school"]["waApp"] == "simple" and cfg["modes"]["day"]["waApp"] == "auto"  # School keeps our screens
    modes = json.loads(json.dumps(ch["modes"])); modes["day"]["wa_app"] = "full"
    hub.update_child("her", {"modes": modes})
    assert S.build_config(hub.data["children"]["her"], hub.now())["modes"]["day"]["waApp"] == "full"
    bad = json.loads(json.dumps(modes)); bad["night"]["wa_app"] = "both"
    with pytest.raises(ValueError):
        hub.update_child("her", {"modes": bad})
    assert hub.data["children"]["her"]["modes"]["day"]["wa_app"] == "full"



def test_pinned_apps_are_tidied_and_reach_the_phone_config(hub):
    assert S.build_config(hub.data["children"]["her"], hub.now())["pinned"] == []
    hub.update_child("her", {"pinned": ["a.music", "", "a.maps", "a.music"]})
    assert S.build_config(hub.data["children"]["her"], hub.now())["pinned"] == ["a.music", "a.maps"]


def test_weather_location_is_rounded_validated_and_sent_to_the_phone(hub):
    assert "location" not in S.build_config(hub.data["children"]["her"], hub.now())
    hub.update_child("her", {"location": {"lat": 51.75321, "lon": -0.33987}})
    assert S.build_config(hub.data["children"]["her"], hub.now())["location"] == {"lat": 51.753, "lon": -0.34}
    with pytest.raises(ValueError):
        hub.update_child("her", {"location": {"lat": 120, "lon": 0}})
    with pytest.raises(ValueError):
        hub.update_child("her", {"location": {"lat": "x"}})
    assert hub.data["children"]["her"]["location"]["lat"] == 51.753
    hub.update_child("her", {"location": None})
    assert "location" not in S.build_config(hub.data["children"]["her"], hub.now())


def test_location_per_mode_defaults_validation_and_config(hub):
    ch = hub.data["children"]["her"]
    cfg = S.build_config(ch, hub.now())
    assert cfg["modes"]["school"]["location"] == "off" and cfg["modes"]["day"]["locEvery"] == 30  # off until a parent chooses
    modes = json.loads(json.dumps(ch["modes"])); modes["school"].update({"location": "network", "loc_every": 30}); modes["day"].update({"location": "gps", "loc_every": 0})
    hub.update_child("her", {"modes": modes})
    cfg = S.build_config(hub.data["children"]["her"], hub.now())
    assert cfg["modes"]["school"]["location"] == "network" and cfg["modes"]["day"]["locEvery"] == 0
    for bad in ({"location": "wifi"}, {"loc_every": -1}, {"loc_every": "5"}, {"loc_every": 5000}):
        worse = json.loads(json.dumps(modes)); worse["night"].update(bad)
        with pytest.raises(ValueError):
            hub.update_child("her", {"modes": worse})
    assert hub.data["children"]["her"]["modes"]["day"]["location"] == "gps"
