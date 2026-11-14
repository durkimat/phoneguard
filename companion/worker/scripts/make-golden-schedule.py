"""Makes test/golden-schedule.json: children + times + the config the Home Assistant code (schedule.py) builds from them.
The JS port (src/hub/schedule.js) must reproduce every `cfg` exactly, apart from `version` (a different hash).
Run from the phoneguard repo checkout:  python3 make-golden-schedule.py /path/to/phoneguard/ha  > test/golden-schedule.json"""
import copy, json, sys
from pathlib import Path
ha = Path(sys.argv[1])
sys.path.insert(0, str(ha / "tools"))
import purepkg  # noqa
purepkg.load()
from phoneguard import schedule as S  # noqa

def child(**kw):
    c = S.default_child("Test")
    for k, v in kw.items():
        c[k] = v
    return c

cases = []
def add(name, c, now, options=None):
    cfg = S.build_config(c, now, options)
    cfg.pop("version")
    cases.append({"name": name, "child": copy.deepcopy(c), "now": now, "options": options, "cfg": cfg})

T0 = 1_794_000_000_000          # Sat 7 Nov 2026
add("default child", child(), T0)
add("holiday turns school into day", child(holiday={"from": "2026-11-09", "until": "2026-11-13"}), T0)
add("bad holiday ignored", child(holiday={"from": "x", "until": "y"}), T0)
add("override to a real mode", child(override="night"), T0)
add("override to a missing mode is dropped", child(override="nope"), T0)
add("clocks go back 25 Oct 2026", child(), 1_793_000_000_000)
add("clocks go forward 28 Mar 2027", child(), 1_806_000_000_000 - 3 * 86400_000)
add("New York", child(tz="America/New_York"), T0)
add("Auckland", child(tz="Pacific/Auckland"), T0)
add("restrictions, protect, unprotect", child(restrictions=["no_install_apps", "no_config_wifi"], protect_extra=["b.app", "a.app"], unprotect=[]), T0)
add("whatsapp, location, pinned, notes", child(whatsapp={"all": True, "allowed": [" +441234 ", "g:Family", ""], "perMinute": 9, "perDay": 300}, location={"lat": 51.7512, "lon": -0.3399}, pinned=["z.app", "a.app"], notes_hide=["x.y", "a.b"]), T0)
c = child(radio_stations=[{"name": "BBC", "url": "https://x/y"}, {"name": "bad", "url": "http://nope"}], keep_running=["z", "a"], idle_dry_run=True)
add("radio, keep running, dry run", c, T0)
add("look", child(look={"theme": "amber", "font": "mono", "accent": "#D42A1E", "menu_style": "grid", "shadow": "off"}), T0)
c = child(commands=[{"id": "abc", "kind": "remove", "pkg": "p.q", "name": "Pq", "url": "", "sha256": "", "size": 0}])
add("app commands", c, T0)
c = child(); c["modes"]["day"]["location"] = "gps"; c["modes"]["day"]["loc_every"] = 5; c["modes"]["night"]["sound"] = "silent"; c["modes"]["night"]["wa"] = "off"; c["modes"]["school"]["idle_min"] = 99999
add("per-mode options and clamping", c, T0)
add("spotify option and refresh", child(spotify_refresh="REFRESH"), T0, {"spotify_client_id": "a" * 32})
print(json.dumps(cases, indent=1, sort_keys=True))
