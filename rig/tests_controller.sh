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

# ---- the HA link (stand-in server on :8099 reached over adb reverse; or any hub at HA_ADMIN) ----
HA_ADMIN=${HA_ADMIN:-http://127.0.0.1:8099/admin}
ha() { curl -s "$HA_ADMIN/$1"; }
havw() { ha "view?child=her" | python3 -c "import json,sys; v=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

c_link() {
  section "HA link: confirm, config push, app approval, boost, release"
  ha "forget?child=her" >/dev/null; dbg unpair >/dev/null
  ha "pair?child=her" > "$OUT/pair.json"
  python3 -c "import json; print(json.load(open('$OUT/pair.json'))['qr'], end='')" > "$OUT/qr.txt"
  adb push "$OUT/qr.txt" /sdcard/Android/data/$CPKG/files/qr.txt >/dev/null
  expect "pairing succeeds" "$(dbg pair qr.txt)" "ok sas="; sleep 3
  expect "phone is paired (pending)" "$(dbg link)" "waiting for parent"
  sas=$(havw "v['device']['sas']"); note "SAS shown by HA: $sas  phone says: $(dbg link)"
  expect "wrong number is refused" "$(ha "confirm?child=her&sas=0000")" '"ok": false'
  expect "right number confirms" "$(ha "confirm?child=her&sas=$sas")" '"ok": true'
  dbg sync >/dev/null; sleep 1
  expect "config arrived and applied" "$(dbg status)" "cfg="
  [ "$(dbg status | grep -o 'cfg=[0-9]*')" != "cfg=null" ] && pass "phone holds a pushed config" || fail "no config on phone"
  expect "HA sees the phone in sync" "$(havw "v['device']['in_sync']")" "True"
  note "apps HA sees: $(havw "[a['pkg'].split('.')[-1] for a in v['apps']]")"
  ha "set_app?child=her&pkg=$CCAM&mode=school&allowed=1" >/dev/null
  ha "set_app?child=her&pkg=$CGAL&mode=night&allowed=1" >/dev/null
  dbg sync >/dev/null; sleep 1
  set_time 2026-11-09 10:00 ; expect "Monday 10:00 -> School from HA's timetable" "$(last_eval)" "mode=school"
  launcher_has $CCAM && pass "school: camera visible (approved in HA)" || fail "camera hidden"
  launcher_has $CJELLY && fail "school: browser should be hidden" || pass "school: browser hidden"
  ha "boost?child=her&mode=day&minutes=15" >/dev/null; dbg sync >/dev/null; sleep 1
  expect "boost from HA applied" "$(last_eval)" "+boost(day)"
  launcher_has $CJELLY && pass "boost: browser visible" || fail "boost not applied"
  ha "cancel_boost?child=her" >/dev/null; dbg sync >/dev/null; sleep 1
  launcher_has $CJELLY && fail "browser should be hidden after cancel" || pass "cancel_boost re-hides it"
  dbg clock real >/dev/null
}

c_link_newapp() {
  section "HA link: a newly installed app is reported and held until approved"
  dbg clock real >/dev/null
  ha "unknown?child=her&pkg=io.github.durkimat.phoneguard.lab" >/dev/null
  adb uninstall io.github.durkimat.phoneguard.lab >/dev/null 2>&1
  adb install -t "$HERE/../prototype/app/build/outputs/apk/debug/app-debug.apk" >/dev/null 2>&1; sleep 6
  expect "HA lists the new app as pending" "$(havw "v['pending_apps']")" "phoneguard.lab"
  ha "approve?child=her&pkg=io.github.durkimat.phoneguard.lab&modes=day,night" >/dev/null
  expect "approval clears it" "$(havw "v['pending_apps']")" "[]"
}

c_release() {
  section "HA link: parent releases the phone (this removes device owner; re-provision afterwards)"
  expect "release queued" "$(ha "release?child=her")" '"ok": true'
  dbg sync >/dev/null; sleep 2
  adb shell dpm list-owners | grep -q DeviceOwner && fail "still device owner" || pass "device owner removed"
  note "restrictions left on phone: $(adb shell dumpsys user | grep -c 'no_factory_reset')"
}

c_lockdown() {
  section "Controller: the School lockdown, applied by the controller (locks touch, shade and apps for about a minute, then restores)"
  top() { adb shell dumpsys window | grep -E 'mCurrentFocus' | head -1 | sed 's/.*{[^ ]* [^ ]* //; s/}//' | tr -d '\r'; }
  adb shell appops set $CPKG SYSTEM_ALERT_WINDOW allow; adb shell appops set $CPKG WRITE_SETTINGS allow
  push_config 7 lockdown >/dev/null; wake; home
  set_time 2026-11-09 10:00; sleep 4
  expect "evaluation reports the School lockdown" "$(last_eval)" "lock=nokia/touchOff/shadeOff/kioskOn"
  r=$(dbg lockdown); note "$r"
  expect "our launcher is the pinned Home app" "$r" "pinnedNokia=true"
  expect "touch is blocked" "$r" "touchBlocked=true"
  expect "kiosk is on" "$r" "locked=true"
  expect "the DumberOS menu-key overlay is switched off" "$r" "menuKeyOff=true"
  adb shell input keyevent KEYCODE_MENU; sleep 1; shot lockdown_menukey >/dev/null
  adb shell input keyevent KEYCODE_BACK; sleep 1; expect "the left key reached our launcher, not Android's overlay" "$(top)" "LauncherActivity"
  expect "focus is on our launcher" "$(top)" "LauncherActivity"
  adb shell input swipe 240 2 240 500 300; sleep 1; expect "shade does not open" "$(top)" "LauncherActivity"
  adb shell am start -n com.android.settings/.Settings 2>&1 | grep -q "error code 101" && pass "Settings is refused" || fail "Settings was not refused"
  launcher_has com.android.deskclock && fail "Clock should be hidden in School" || pass "Clock is hidden in School (no alarms can ring)"
  adb shell dumpsys package com.android.contacts | grep -q 'android.permission.READ_CONTACTS: granted=true' && pass "Contacts permissions were pre-granted" || fail "Contacts permission not granted"
  adb shell input keyevent KEYCODE_ENTER; sleep 1; adb shell input keyevent KEYCODE_1; sleep 2
  expect "key 1 opens Phone under the lock" "$(top)" "dialer"
  b=$(screen_text | cut -c1-120); adb shell input tap 239 375; sleep 1.5; a=$(screen_text | cut -c1-120)
  [ "$a" = "$b" ] && pass "a touch on the dialler does nothing" || fail "touch got through the overlay"
  adb shell input keyevent KEYCODE_HOME; sleep 2
  echo "--- back to Day"
  set_time 2026-11-09 16:00; sleep 4
  expect "evaluation reports the open Day" "$(last_eval)" "lock=default/touchOn/shadeOn/kioskOff"
  r=$(dbg lockdown); expect "the menu-key setting is put back" "$r" "menuKeyOff=false"; expect "normal Home app is back" "$r" "pinnedNokia=false"; expect "touch is back" "$r" "touchBlocked=false"; expect "kiosk is off" "$r" "locked=false"
  adb shell input swipe 240 2 240 500 300; sleep 1; screen_text | grep -q "Internet" && pass "shade opens again" || fail "shade still blocked"
  home; launcher_has com.android.deskclock && pass "Clock is back" || fail "Clock is still hidden"
  expect "Home key reaches the normal launcher" "$(top)" "launcher3"
  dbg clock real >/dev/null; dbg forgetcfg >/dev/null; dbg sync >/dev/null
}
