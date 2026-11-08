import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import purepkg  # noqa: E402

purepkg.load()

from phoneguard import power as PW  # noqa: E402

H = 3600 * 1000
NOW = 1_794_000_000_000


def s(t, level, mah, plug=0, screen=0, mode="night", loc=0, wifi=1):
    return [t, level, int(mah * 1000), -20000, plug, screen, mode, 4, wifi, loc, 250, 0, "x"]


def test_merge_keeps_order_drops_repeats_junk_and_old():
    store = PW.merge([], [s(NOW - 1000, 90, 2600), s(NOW - 1000, 90, 2600), "junk", [1, 2], s(NOW - 500, 90, 2590)], NOW)
    assert [x[0] for x in store] == [NOW - 1000, NOW - 500]
    store = PW.merge(store, [s(NOW - 20 * 24 * H, 50, 1000)], NOW)  # older than the last one: ignored
    assert len(store) == 2
    far = PW.merge([s(NOW - 20 * 24 * H, 50, 1000)], [s(NOW, 49, 990)], NOW)
    assert [x[0] for x in far] == [NOW]  # the 20 day old line is dropped


def test_summary_splits_use_by_screen_mode_and_location():
    samples = [
        s(NOW - 4 * H, 100, 2950),
        s(NOW - 3 * H, 99, 2920),                    # 30 mAh in an hour, screen off, night
        s(NOW - 2 * H, 97, 2890, screen=1, mode="day"),   # 30 mAh, then the screen goes on
        s(NOW - 1 * H, 93, 2790, screen=0, mode="day", loc=1),  # 100 mAh in the hour with the screen on
        s(NOW, 91, 2740, mode="day", loc=1),              # 50 mAh with the screen off and location on
    ]
    r = PW.summarize(samples, NOW)
    assert r["on_battery"]["hours"] == 4.0 and r["on_battery"]["mah_per_h"] == 52.5
    assert r["by_screen"]["on"]["mah_per_h"] == 100.0 and r["by_screen"]["off"]["hours"] == 3.0
    assert r["by_mode"]["night"]["mah_per_h"] == 30.0 and r["by_mode"]["night"]["hours"] == 2.0
    assert r["by_mode"]["day"]["mah_per_h"] == 75.0 and r["by_mode"]["day"]["hours"] == 2.0
    assert r["by_location"]["on"]["mah_per_h"] == 50.0
    assert r["level"] == 91 and r["plugged"] is False
    assert 2900 < r["full_mah"] < 3100 and 50 < r["hours_left"] < 60
    assert len(r["chart"]) == 5


def test_charging_and_long_gaps_are_ignored():
    samples = [s(NOW - 10 * H, 50, 1500), s(NOW - 9 * H, 80, 2400, plug=1), s(NOW - 8 * H, 80, 2390, plug=1), s(NOW - 1 * H, 78, 2300), s(NOW, 77, 2290)]
    r = PW.summarize(samples, NOW)
    assert r["on_battery"]["hours"] == 1.0 and r["on_battery"]["mah_per_h"] == 10.0  # only the last hour counts
    assert "hours_left" in r
    charging = PW.summarize([s(NOW - H, 60, 1800, plug=1), s(NOW, 70, 2100, plug=1)], NOW)
    assert charging["plugged"] is True and charging["on_battery"]["mah_per_h"] is None and "hours_left" not in charging
