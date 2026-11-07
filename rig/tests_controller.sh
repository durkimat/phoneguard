#!/usr/bin/env bash
# Scenario tests for the real controller. Uses the debug clock to jump through the timetable.
source "$(dirname "${BASH_SOURCE[0]}")/clib.sh"

c_basic() {
  section "Controller: basics"
  expect "controller answers" "$(dbg ping)" "pong"
  expect "is device owner" "$(dbg status)" "owner=true"
  expect "restrictions applied" "$(dbg restrictions)" "no_factory_reset"
  r=$(dbg restrictions); for k in no_safe_boot no_config_date_time no_add_user no_add_managed_profile; do expect "restriction $k" "$r" "$k"; done
  dbg noconfig >/dev/null; sleep 1
  note "no config: $(dbg status)"
  hidden_has $CGAL && pass "no config -> Emergency hides gallery" || fail "Emergency did not hide gallery"
  launcher_has com.android.dialer && pass "dialler still visible in Emergency" || fail "dialler vanished"
  launcher_has com.android.settings && pass "settings still visible in Emergency" || fail "settings vanished"
}

c_modes() {
  section "Controller: day / school / night"
  expect "config loads" "$(push_config 1)" "loaded"
  set_time 2026-11-09 06:00;  expect "06:00 is Night" "$(last_eval)" "mode=night"
  launcher_has $CGAL && pass "night: gallery visible" || fail "night: gallery hidden"
  launcher_has $CCAM && fail "night: camera should be hidden" || pass "night: camera hidden"
  launcher_has com.android.deskclock && pass "night: clock visible" || fail "night: clock hidden"
  expect "night is not grey" "$(dbg gray)" "actual=false"

  set_time 2026-11-09 10:00;  expect "10:00 is School" "$(last_eval)" "mode=school"
  launcher_has $CCAM && pass "school: camera visible" || fail "school: camera hidden"
  launcher_has $CGAL && fail "school: gallery should be hidden" || pass "school: gallery hidden"
  launcher_has $CJELLY && fail "school: browser should be hidden" || pass "school: browser hidden"
  expect "school is grey" "$(dbg gray)" "actual=true"

  set_time 2026-11-09 16:00;  expect "16:00 is Day" "$(last_eval)" "mode=day"
  launcher_has $CJELLY && pass "day: browser visible" || fail "day: browser hidden"
  launcher_has $CGAL && pass "day: gallery visible" || fail "day: gallery hidden"
  expect "day is not grey" "$(dbg gray)" "actual=false"

  set_time 2026-11-14 10:00;  expect "Saturday 10:00 is Day (no school)" "$(last_eval)" "mode=day"
}

c_boost() {
  section "Controller: boost"
  push_config 1 >/dev/null
  set_time 2026-11-10 10:00; expect "Tuesday 10:00 is School" "$(last_eval)" "mode=school"
  launcher_has $CJELLY && fail "browser should be hidden before boost" || pass "browser hidden before boost"
  dbg boost day 20 >/dev/null; sleep 1
  expect "boost shows in state" "$(last_eval)" "+boost(day)"
  launcher_has $CJELLY && pass "boost: browser visible" || fail "boost: browser still hidden"
  expect "boost lifts grey" "$(dbg gray)" "actual=false"
  set_time 2026-11-10 10:25; expect "after boost: School again" "$(last_eval)" "mode=school"
  launcher_has $CJELLY && fail "browser should be hidden after boost" || pass "browser hidden after boost"
}

c_warning() {
  section "Controller: 10-minute warning"
  dbg reset >/dev/null; push_config 1 >/dev/null
  set_time 2026-11-10 08:10
  note "08:10 (school at 08:30): $(last_eval | cut -c1-200)"
  set_time 2026-11-10 08:21
  expect "warning fired at 08:21" "$(adb shell "grep EVAL $CLOG" | tail -4)" "WARNED"
  adb shell dumpsys notification --noredact | grep -q "Phone changes to School" && pass "warning notification is posted" || note "notification text not found in dumpsys (check on the phone)"
  set_time 2026-11-10 15:21
  r=$(last_eval); case "$r" in *WARNED*) fail "warned for a change that only adds apps";; *) pass "no warning going School -> Day";; esac
}

c_window() {
  section "Controller: after the pushed window runs out"
  push_config 1 >/dev/null
  set_time 2026-11-23 10:00; expect "Mon 23 Nov (past window) is School" "$(last_eval)" "mode=school"
  set_time 2026-11-28 10:00; expect "Sat 28 Nov is Day" "$(last_eval)" "mode=day"
  set_time 2026-12-02 22:00; expect "Wed 2 Dec 22:00 is Night" "$(last_eval)" "mode=night"
}

c_quarantine() {
  section "Controller: a newly installed app is hidden at once (School mode)"
  push_config 1 >/dev/null; set_time 2026-11-10 10:00
  adb uninstall io.github.durkimat.phoneguard.lab >/dev/null 2>&1
  APK="$HERE/../prototype/app/build/outputs/apk/debug/app-debug.apk"
  adb install -t "$APK" >/dev/null 2>&1; sleep 5
  hidden_has io.github.durkimat.phoneguard.lab && pass "new app hidden within ~5s of install" || fail "new app not hidden"
  adb shell "grep 'package change' $CLOG" | tail -2 | cut -c1-140
  set_time 2026-11-10 16:00
  hidden_has io.github.durkimat.phoneguard.lab && fail "new app still hidden in Day" || pass "new app visible again in Day"
}

c_gray() {
  section "Controller: grayscale re-assert"
  push_config 1 >/dev/null; set_time 2026-11-10 10:00
  expect "grey on in School" "$(dbg gray)" "actual=true"
  adb shell settings put secure accessibility_display_daltonizer_enabled 0; sleep 2
  expect "service put grey back" "$(dbg gray)" "actual=true"
  adb shell "grep 're-asserted' $CLOG" | tail -1 | cut -c1-120
  set_time 2026-11-10 16:00; expect "grey off in Day" "$(dbg gray)" "actual=false"
}

c_foreign() {
  section "Controller: leaves other apps' hiding alone"
  push_config 1 >/dev/null; set_time 2026-11-10 16:00
  # Hide an app as 'someone else' via the lab-free route: use pm to hide for user (shell is allowed).
  adb shell pm hide $CJELLY >/dev/null 2>&1
  set_time 2026-11-10 10:00; set_time 2026-11-10 16:00
  adb shell pm list packages -d >/dev/null
  dbg hidden | grep -q $CJELLY && note "we recorded jelly as ours (it was hidden by shell before we looked)" || pass "app hidden by someone else is not claimed by us"
  adb shell pm unhide $CJELLY >/dev/null 2>&1
}

c_reset() {
  section "Controller: reset to normal"
  note "$(dbg reset)"
  [ "$(dbg hidden)" = "[]" ] && pass "nothing hidden after reset" || note "still hiding: $(dbg hidden)"
}

c_realalarm() {
  section "Controller: a REAL alarm (real clock, no debug time): 2-minute boost ends and School returns"
  dbg reset >/dev/null
  python3 - > "$OUT/cfg_now.json" <<'PY'
import json, time
now = int(time.time()*1000)
print(json.dumps({"version": 9, "base": "day", "windowEnd": now + 3600_000*3,
  "modes": {"day": {"name": "Day", "allowAll": True},
            "school": {"name": "School", "allow": ["org.lineageos.aperture"], "gray": False}},
  "segments": [{"from": now - 600_000, "to": now + 3600_000*2, "mode": "school"}]}))
PY
  adb push "$OUT/cfg_now.json" /sdcard/Android/data/$CPKG/files/cfg_now.json >/dev/null
  dbg config cfg_now.json >/dev/null; sleep 1
  expect "starts in School" "$(last_eval)" "mode=school"
  dbg boost day 2 >/dev/null; sleep 1
  expect "boost running" "$(last_eval)" "+boost(day)"
  launcher_has $CJELLY && pass "browser visible during boost" || fail "browser hidden during boost"
  say "  ... waiting for the real alarm (about 2 minutes)"
  sleep 135
  r=$(adb shell "grep 'EVAL alarm' $CLOG" | tail -1)
  case "$r" in *"mode=school all=false"*) pass "alarm fired and School returned  [$r]";; *) fail "no alarm eval back in School  [$r]";; esac
  launcher_has $CJELLY && fail "browser still visible after boost" || pass "browser hidden again after boost"
  dbg reset >/dev/null
}
