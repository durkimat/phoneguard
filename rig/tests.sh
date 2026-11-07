#!/usr/bin/env bash
# Scenario tests for the J36 lab. Each function is independent and cleans up after itself.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

GALLERY=org.lineageos.glimpse

t_hide() {
  section "Hide / suspend / protected apps"
  expect "lab app is device owner" "$(pg status)" "owner=true"
  r=$(pg hide $GALLERY); expect "hide gallery" "$r" "ok=true"
  expect "dpm reports hidden" "$(pg state $GALLERY)" "hidden=true"
  if adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -q "$GALLERY"; then fail "gallery still has a launcher entry"; else pass "gallery launcher entry gone"; fi
  r=$(pg unhide $GALLERY); expect "unhide gallery" "$r" "ok=true"
  adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -q "$GALLERY" && pass "gallery launcher entry back" || fail "gallery launcher entry did not return"
  expect "suspend gallery" "$(pg suspend $GALLERY)" "failed=[]"
  adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -q "$GALLERY" && note "suspended app STILL listed by the package manager (launcher shows it greyed)" || note "suspended app not listed"
  pg unsuspend $GALLERY >/dev/null
  expect "dialler refused" "$(pg hide com.android.dialer)" "REFUSED"
  expect "settings refused" "$(pg hide com.android.settings)" "REFUSED"
}

t_restrictions() {
  section "User restrictions (state check; each is set then cleared)"
  for k in no_factory_reset no_safe_boot no_config_date_time no_add_user no_add_managed_profile \
           no_install_unknown_sources no_config_vpn disallow_config_private_dns no_usb_file_transfer \
           no_control_apps no_modify_accounts no_config_credentials no_user_switch no_config_mobile_networks; do
    pg restrict $k >/dev/null; r=$(pg has_restriction $k); pg unrestrict $k >/dev/null
    expect "$k can be set" "$r" "=true"
  done
  note "effective list now: $(pg restrictions)"
}

t_restrictions_ui() {
  section "Restrictions seen in the UI (Settings screens)"
  pg restrict no_config_date_time >/dev/null; wake
  adb shell am start -a android.settings.DATE_SETTINGS >/dev/null; sleep 2
  note "Date settings screen text: $(screen_text | cut -c1-300)"
  shot date_restricted >/dev/null
  pg unrestrict no_config_date_time >/dev/null; home
  pg restrict no_control_apps >/dev/null
  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$GALLERY >/dev/null; sleep 2
  ui "$OUT/ui.xml"
  if grep -q 'text="Force stop"[^>]*enabled="false"' "$OUT/ui.xml" 2>/dev/null; then pass "Force stop greyed with no_control_apps"; else note "force stop state unclear (see results/ui.xml)"; fi
  pg unrestrict no_control_apps >/dev/null; home
}

t_gray() {
  section "Grayscale"
  wake; home
  r=$(pg selfgrant secure); note "self-grant WRITE_SECURE_SETTINGS: $r"
  if ! adb shell dumpsys package $PKG | grep -q 'WRITE_SECURE_SETTINGS: granted=true'; then
    adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS && note "granted via adb (needed)"
  fi
  pg gray off >/dev/null; sleep 1; shot gray_off >/dev/null
  pg gray on >/dev/null; sleep 1; shot gray_on >/dev/null
  a=$(gray_fraction "$OUT/gray_off.png"); b=$(gray_fraction "$OUT/gray_on.png")
  note "greyish pixel fraction: off=$a on=$b"
  note "screenshots cannot show the colour filter (it is applied after capture) and the home screen is nearly grey anyway; judge by the setting"
  expect "setting is on" "$(pg gray on)" "now=1"
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 2   # the watcher needs a live process
  expect "re-assert watcher started" "$(pg gray_watch on)" "running"
  adb shell settings put secure accessibility_display_daltonizer_enabled 0
  sleep 2
  expect "watcher put it back" "$(pg gray_value)" "enabled=1"
  adb shell "grep GRAYWATCH $LOG" | tail -2 | cut -c1-140
  pg gray_watch off >/dev/null; pg gray off >/dev/null; home
}

t_power() {
  section "Battery and force-stop"
  note "self-grant battery exemption: $(pg selfgrant battery)"
  note "battery state: $(pg battery)"
  note "(the lab app is device owner, so its own Force stop is always greyed: protection is tested on Gallery)"
  for mode in off on; do
    pg protect $mode $GALLERY >/dev/null; sleep 1
    wake; adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$GALLERY >/dev/null; sleep 2
    shot fs_$mode >/dev/null
    tap_text "Force stop"; sleep 2
    t=$(screen_text | cut -c1-200)
    case "$t" in *"Force stop?"*|*"Force stop this"*|*"OK"*) r=dialog;; *) r=no-dialog;; esac
    note "protect=$mode: tapping Force stop gives: $r  [$t]"
    eval "res_$mode=$r"
    adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; home
  done
  [ "$res_off" = dialog ] && [ "$res_on" = no-dialog ] && pass "setUserControlDisabledPackages blocks force stop in Settings" || fail "force-stop protection not demonstrated (see results/fs_*.png)"
  pg protect off >/dev/null
}

t_homepin() {
  section "Home app pin"
  note "home apps: $(pg homes)"
  expect "pin Trebuchet" "$(pg pinhome com.android.launcher3)" "pinned"
  adb shell cmd package set-home-activity $PKG/.LabHome 2>&1 | head -2 | sed 's/^/        set-home-activity (shell): /'
  r=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | tr -d '\r')
  note "after trying to switch Home from adb, Home resolves to: $r"
  case "$r" in *launcher3*) pass "pin held against the switch";; *) fail "pin did not hold (shell may be exempt, so also try via Settings)";; esac
  wake; adb shell am start -a android.settings.HOME_SETTINGS >/dev/null; sleep 2
  note "Default home screen text: $(screen_text | cut -c1-300)"
  shot homepin_settings >/dev/null
  tap_text "Phone Guard Lab" && { sleep 2; note "tapped Lab Home in Settings; home now: $(pg homes)"; shot homepin_after_tap >/dev/null; } || note "Lab Home option (label: Phone Guard Lab) not tappable/visible"
  home
  pg pinhome off >/dev/null
  adb shell cmd package set-home-activity com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher >/dev/null 2>&1
}

t_doze() {
  section "Doze: does the exact-alarm heartbeat survive forced idle? (about 6 minutes, restores state afterwards)"
  note "exact alarms: $(pg battery)"
  adb shell dumpsys battery unplug >/dev/null
  pg quarantine off >/dev/null
  pg chain 1 >/dev/null
  adb shell input keyevent KEYCODE_SLEEP; sleep 2
  adb shell dumpsys deviceidle force-idle | head -1
  for m in 1 2 3 4 5 6; do sleep 60; done
  adb shell dumpsys deviceidle unforce >/dev/null; adb shell dumpsys battery reset >/dev/null
  pg chain stop >/dev/null; wake
  adb shell grep HEARTBEAT $LOG | tail -n 6 | cut -c1-120
  late=$(adb shell grep HEARTBEAT $LOG | tail -n 6 | sed -n 's/.*late=\([0-9-]*\)s.*/\1/p' | sort -n | tail -1)
  note "worst lateness over the run: ${late:-?}s"
  [ -n "$late" ] && [ "$late" -lt 120 ] && pass "heartbeat stayed within 2 minutes under forced Doze" || fail "heartbeat late or missing under forced Doze"
}

t_net() {
  section "Plain HTTPS reachability from the phone"
  adb shell am broadcast -n $PKG/.NetReceiver -a $PKG.NET --es url https://httpbin.org/get --es method GET >/dev/null; sleep 4
  adb shell grep 'NET#1 GET' $LOG | tail -1 | cut -c1-100
}

t_cleanup() {
  section "Cleanup"
  for k in $(pg restrictions | sed -n 's/^ours=\[\([^]]*\)\].*/\1/p' | tr -d ','); do pg unrestrict "$k" >/dev/null; done
  pg unhide $GALLERY >/dev/null; pg unsuspend $GALLERY >/dev/null; pg gray off >/dev/null; pg protect off >/dev/null; pg pinhome off >/dev/null
  note "restrictions now: $(pg restrictions)"
}
