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
