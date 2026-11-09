import base64
import hashlib
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import purepkg  # noqa: E402

purepkg.load()

import pytest

from phoneguard import schedule as S
from phoneguard import spotify as SP
from phoneguard.hub import Hub

ORIGIN = "https://example.ui.nabu.casa"


def test_pkce_challenge_matches_verifier():
    v, c = SP.new_pkce()
    assert c == base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).decode().rstrip("=")
    assert len(v) >= 43 and "=" not in v


def test_redirect_must_be_https():
    assert SP.redirect_uri(ORIGIN + "/") == ORIGIN + "/api/phoneguard/spotify_callback"
    with pytest.raises(ValueError):
        SP.redirect_uri("http://192.168.1.159:8123")


def test_start_builds_the_spotify_address_and_state_is_single_use():
    p = SP.Pending()
    url = p.start("ellie", "a" * 32, ORIGIN, now=1000)
    q = parse_qs(urlparse(url).query)
    assert q["client_id"] == ["a" * 32] and q["code_challenge_method"] == ["S256"]
    assert q["redirect_uri"] == [ORIGIN + SP.CALLBACK_PATH]
    assert "user-modify-playback-state" in q["scope"][0]
    got = p.take(q["state"][0], now=1001)
    assert got["child"] == "ellie" and got["redirect"] == ORIGIN + SP.CALLBACK_PATH
    assert p.take(q["state"][0], now=1002) is None  # one use only
    form = SP.token_form(got, "CODE")
    assert form["grant_type"] == "authorization_code" and form["code"] == "CODE" and form["code_verifier"] == got["verifier"]
    assert "client_secret" not in form


def test_state_expires_and_unknown_state_is_refused():
    p = SP.Pending()
    q = parse_qs(urlparse(p.start("ellie", "a" * 32, ORIGIN, now=1000)).query)
    assert p.take("nope", now=1001) is None
    assert p.take(q["state"][0], now=1000 + SP.PENDING_SECONDS + 1) is None


def test_refresh_token_reaches_the_phone_but_not_the_card_or_copies():
    h = Hub({}, clock=lambda: 1_794_000_000_000)
    h.ensure_child("ellie", "Ellie")
    assert "spotifyRefresh" not in S.build_config(h.data["children"]["ellie"], 1_794_000_000_000)
    h.set_spotify("ellie", "REFRESH", "Ellie D")
    assert S.build_config(h.data["children"]["ellie"], 1_794_000_000_000, h.data.get("options"))["spotifyRefresh"] == "REFRESH"
    v = h.view("ellie")
    assert v["spotify"] == {"signed_in": True, "name": "Ellie D"} and "REFRESH" not in str(v)
    h.set_spotify("ellie", None)
    assert "spotifyRefresh" not in S.build_config(h.data["children"]["ellie"], 1_794_000_000_000)
    assert h.view("ellie")["spotify"]["signed_in"] is False


# ---- app visibility follows the matrix (no separate approvals)

def _child_hub():
    h = Hub({}, clock=lambda: 1_794_000_000_000)
    h.ensure_child("ellie", "Ellie")
    return h, h.data["children"]["ellie"]


def test_allow_all_mode_becomes_ticked_apps_once_and_new_apps_need_approval_by_default():
    h, ch = _child_hub()
    assert ch["modes"]["day"]["allowAll"] is True
    h.apply_app_policy(ch, {"a", "b"})
    day = ch["modes"]["day"]
    assert day["allowAll"] is False and day["allow"] == ["a", "b"] and not day.get("auto_new")
    h.apply_app_policy(ch, {"a", "b", "c"})  # a new app: nothing is ticked for it
    assert "c" not in ch["modes"]["day"]["allow"] and "c" in ch["seen"]
    assert S.build_config(ch, 1_794_000_000_000)["modes"]["day"]["allowAll"] is False


def test_a_mode_can_allow_new_apps_automatically_and_an_unticked_app_stays_unticked():
    h, ch = _child_hub()
    h.apply_app_policy(ch, {"a"})
    ch["modes"]["day"]["auto_new"] = True
    h.apply_app_policy(ch, {"a", "b"})
    assert ch["modes"]["day"]["allow"] == ["a", "b"]
    h.set_app_mode("ellie", "b", "day", False)  # the parent unticks it
    h.apply_app_policy(ch, {"a", "b"})
    assert "b" not in ch["modes"]["day"]["allow"]  # not added again: it is no longer "new"
    assert "b" in ch["known"]  # and the parent has now seen it


def test_select_all_ticks_and_unticks_every_reported_app_in_one_mode():
    h, ch = _child_hub()
    h.device_of = lambda cid: ("d", {"apps": [{"pkg": "a"}, {"pkg": "b"}]})
    h.set_mode_all("ellie", "school", True)
    assert ch["modes"]["school"]["allow"] == ["a", "b"]
    h.set_mode_all("ellie", "school", False)
    assert ch["modes"]["school"]["allow"] == []
    assert ch["modes"]["night"]["allow"] == ["com.android.deskclock"]  # other modes untouched


def test_start_up_migration_uses_the_apps_the_phone_last_reported():
    h, ch = _child_hub()
    h.device_of = lambda cid: ("d", {"apps": [{"pkg": "a"}, {"pkg": "b"}]})
    h.migrate_apps()
    assert ch["modes"]["day"]["allow"] == ["a", "b"] and ch["modes"]["day"]["allowAll"] is False


def test_idle_minutes_reach_the_phone_and_are_validated():
    h, ch = _child_hub()
    cfg = S.build_config(ch, 1_794_000_000_000)
    assert cfg["modes"]["day"]["idleMin"] == 10 and cfg["modes"]["school"]["idleMin"] == 10
    h.update_child("ellie", {"modes": {**ch["modes"], "day": {**ch["modes"]["day"], "idle_min": 30}}})
    assert S.build_config(ch, 1_794_000_000_000)["modes"]["day"]["idleMin"] == 30
    with pytest.raises(ValueError):
        h.update_child("ellie", {"modes": {**ch["modes"], "day": {**ch["modes"]["day"], "idle_min": -5}}})


def test_keep_running_list_defaults_and_reaches_the_phone():
    h, ch = _child_hub()
    cfg = S.build_config(ch, 1_794_000_000_000)
    assert "com.whatsapp" in cfg["keepRunning"] and "com.android.deskclock" in cfg["keepRunning"]
    h.update_child("ellie", {"keep_running": ["com.whatsapp"]})
    assert S.build_config(ch, 1_794_000_000_000)["keepRunning"] == ["com.whatsapp"]
    assert h.view("ellie")["keep_running"] == ["com.whatsapp"]


# ---- power saver report, screen-off clean-up and report-only switch

def test_reaped_entries_merge_without_repeats_and_summarise_the_last_day():
    from phoneguard import power as PW
    now = 1_794_000_000_000
    mins = 60_000
    new = [[now - 5 * mins, "a", "Spotify", "idle", 0], [now - 3 * mins, "b", "Browser", "screen off", 0], [now - 30 * 3_600_000, "c", "Old", "idle", 0],
           ["bad"], [now - 2 * mins, "a", "Spotify", "idle", 1]]
    store = PW.merge_reaped([], new)
    assert [e[1] for e in store] == ["c", "a", "b", "a"]
    assert PW.merge_reaped(store, new) == store  # nothing is added twice
    s = PW.summarize_reaped(store, now)
    assert s["closed"] == 2 and s["would"] == 1 and s["by_reason"] == {"idle": 1, "screen off": 1}
    assert s["top"][0][1] == 1 and s["latest"][0][1] == "Spotify" and s["latest"][0][3] == 1


def test_screen_off_minutes_and_report_only_reach_the_phone_and_are_validated():
    h, ch = _child_hub()
    cfg = S.build_config(ch, 1_794_000_000_000)
    assert cfg["modes"]["day"]["screenOffMin"] == 5 and "idleDryRun" not in cfg
    h.update_child("ellie", {"idle_dry_run": True, "modes": {**ch["modes"], "day": {**ch["modes"]["day"], "screen_off_min": 2}}})
    cfg = S.build_config(ch, 1_794_000_000_000)
    assert cfg["modes"]["day"]["screenOffMin"] == 2 and cfg["idleDryRun"] is True
    assert h.view("ellie")["idle_dry_run"] is True
    with pytest.raises(ValueError):
        h.update_child("ellie", {"idle_dry_run": "yes"})
    with pytest.raises(ValueError):
        h.update_child("ellie", {"modes": {**ch["modes"], "day": {**ch["modes"]["day"], "screen_off_min": 5000}}})


# ---- store, app jobs and updates

def test_store_parsing_and_versions():
    from phoneguard import store as ST
    found = ST.parse_search({"apps": [{"name": "Calc", "summary": "Adds up", "url": "https://f-droid.org/en/packages/com.example.calc"}, {"name": "Bad", "url": "https://f-droid.org/en/packages/no spaces"}]})
    assert found == [{"pkg": "com.example.calc", "name": "Calc", "summary": "Adds up"}]
    assert ST.parse_info({"suggestedVersionCode": 33, "packages": [{"versionName": "3.1.2", "versionCode": 33}, {"versionName": "3.1.1", "versionCode": 32}]}) == ("3.1.2", 33)
    assert ST.apk_url("com.example.calc", 33) == "https://f-droid.org/repo/com.example.calc_33.apk"
    assert ST.is_newer("0.10.0", "0.9.5") and ST.is_newer("v1.0", "0.9.9") and not ST.is_newer("0.1.0", "0.1.0")
    rel = ST.parse_release({"tag_name": "v0.2.0", "body": "notes", "assets": [{"name": "phoneguard-0.2.0.apk", "browser_download_url": "https://x/a.apk", "size": 5}, {"name": "phoneguard-0.2.0.apk.sha256", "browser_download_url": "https://x/a.sha256"}]})
    assert rel["version"] == "0.2.0" and rel["url"] == "https://x/a.apk" and rel["sha256_url"] == "https://x/a.sha256"
    assert ST.parse_sha256_text("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  a.apk") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"


def test_app_jobs_queue_reach_the_phone_and_clear_on_results():
    h, ch = _child_hub()
    sha = "a" * 64
    cmd = h.queue_app("ellie", "install", "com.example.calc", "Calc", "https://f-droid.org/repo/com.example.calc_33.apk", sha, 1234)
    assert S.build_config(ch, 1_794_000_000_000)["appCommands"][0]["pkg"] == "com.example.calc"
    for bad in (("install", "bad pkg", "x", "https://x", sha), ("install", "com.a.b", "x", "http://x", sha), ("install", "com.a.b", "x", "https://x", "nope"), ("nuke", "com.a.b", "x", "https://x", sha)):
        with pytest.raises(ValueError):
            h.queue_app("ellie", bad[0], bad[1], bad[2], bad[3], bad[4])
    with pytest.raises(ValueError):
        h.queue_app("ellie", "install", "com.example.calc", "Calc", "https://x", sha)  # already waiting
    h.queue_app("ellie", "remove", "com.example.old", "Old")
    h._merge_app_results(ch, [[cmd["id"], "done", "installed Calc", 1_794_000_000_100], ["unknown", "done", "", 1]])
    assert [c["pkg"] for c in ch["commands"]] == ["com.example.old"]
    assert ch["app_log"][-1][1:4] == ["Calc", "install", "done"]
    v = h.view("ellie")
    assert v["app_commands"][0]["kind"] == "remove" and v["app_log"][0][3] == "done"


def test_update_is_offered_only_when_newer_and_ready():
    h, ch = _child_hub()
    h.device_of = lambda cid: ("d", {"state": {"version": "0.1.0"}, "apps": []})
    assert h.update_for("ellie") is None
    h.set_latest({"version": "0.2.0", "notes": "n", "url": "https://x/a.apk", "size": 9, "sha256": "b" * 64})
    assert h.update_for("ellie")["available"] is True
    cmd = h.queue_update("ellie")
    assert cmd["kind"] == "update" and cmd["pkg"] == "io.github.durkimat.phoneguard"
    h.set_latest({"version": "0.1.0", "url": "https://x/a.apk", "sha256": "b" * 64})
    assert h.update_for("ellie")["available"] is False
    with pytest.raises(ValueError):
        h.queue_update("ellie")


def test_index_parsing_and_local_search_and_official_matches():
    from phoneguard import store as ST
    idx = {"apps": [{"packageName": "org.a.notes", "name": "Easy Notes", "summary": "write things", "suggestedVersionCode": "3"},
                    {"packageName": "org.b.clock", "localized": {"en-US": {"name": "Big Clock", "summary": "shows the time and notes"}}, "suggestedVersionCode": "1"},
                    {"packageName": "org.c.nohash", "name": "Broken", "suggestedVersionCode": "1"}],
           "packages": {"org.a.notes": [{"versionCode": 3, "versionName": "1.2", "apkName": "org.a.notes_3.apk", "hash": "c" * 64, "size": 10}],
                        "org.b.clock": [{"versionCode": 1, "versionName": "1.0", "apkName": "org.b.clock_1.apk", "hash": "d" * 64, "size": 20}],
                        "org.c.nohash": [{"versionCode": 1, "apkName": "x.apk", "hash": "bad"}]}}
    entries = ST.parse_index(idx)
    assert [e["pkg"] for e in entries] == ["org.a.notes", "org.b.clock"]
    assert [e["pkg"] for e in ST.search_entries(entries, "notes")] == ["org.a.notes", "org.b.clock"]  # the name first, then the summary
    assert ST.search_entries(entries, "zzz") == []
    assert [o["name"] for o in ST.official_matches("whats")] == ["WhatsApp"] and ST.official_matches("calculator") == []
