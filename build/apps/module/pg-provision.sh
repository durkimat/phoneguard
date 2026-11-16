#!/system/bin/sh
# First-boot provisioning (docs/plan-two-editions.md). Two jobs, both run from init as root:
#  1. PLUMBING (always, idempotent): skip the Lineage setup wizard, system look, no-pop-up baseline, usage-access app-op, data enable retry,
#     hand a pushed enrolment bundle to Phone Guard. Phone Guard is NOT made device owner here any more.
#  2. OWNER ON REQUEST (only while the phone has no edition yet): the person picks "Family phone" on the first-run screen, the app pairs with the
#     parent's companion and then writes Settings.Global pg_owner_req=1. This script (uid 0 counts as "adb" for DevicePolicyManagerService.setDeviceOwner,
#     so no platform key is needed) sets Phone Guard as device owner and clears the request. The app mirrors its edition into Settings.Global pg_edition
#     (open or family); once that is set the loop ends. A phone that already is device owner (an upgraded Family phone) just gets the plumbing.
# Gives up on the plumbing after ~5 minutes of the package not being there.
PKG=io.github.durkimat.phoneguard
ADMIN=$PKG/.AdminReceiver
say() { /system/bin/log -t pgprov "$*"; }
say "started"
# After the owner is set: skip the Lineage setup wizard (the phone is provisioned by Phone Guard, not by a person) and start Phone Guard.
# device_provisioned/user_setup_complete matter beyond the wizard: telephony refuses user mobile data until both are 1.
# Companion enrolment bundle (docs/plan-two-editions.md): the setup helper pushes {"v":1,"u":url,"t":token,"k":pin} (under 200 bytes) to
# /data/local/tmp/pg-enrol.json. Hand it to Phone Guard by explicit broadcast (EnrolReceiver checks the sender is root/shell via the DUMP
# permission and validates the JSON), then delete the file. No file crosses into the app's storage, so no extra SELinux access to app data.
ENROL=/data/local/tmp/pg-enrol.json
enrol() {
  [ -f "$ENROL" ] || return 0
  b=$(head -c 400 "$ENROL" 2>/dev/null)
  [ -n "$b" ] || { say "enrol: file empty"; rm -f "$ENROL"; return 0; }
  out=$(timeout 20 cmd activity broadcast --user 0 -n $PKG/.EnrolReceiver --es bundle "$b" 2>&1)
  say "enrol: $out"
  rm -f "$ENROL"
}
finish() {
  timeout 20 cmd settings put global device_provisioned 1
  timeout 20 cmd settings put secure user_setup_complete 1
  timeout 20 cmd package disable-user --user 0 org.lineageos.setupwizard
  # No stock lock screen while there is no PIN (a PIN set later brings the PIN pad back at once: SystemUI patch 0012).
  timeout 20 cmd lock_settings set-disabled true
  # Phone Guard look for the system UI (PIN pad, shade, Settings): dark theme and a green colour seed (3DDC84, the boot-animation green).
  timeout 20 cmd uimode night yes
  # No pop-ups baseline (Phone Guard re-applies both at every evaluation): no heads-up banners, no crash/ANR boxes.
  timeout 20 cmd settings put global heads_up_notifications_enabled 0
  # No ambient display: its dream woke the screen five seconds after every sleep timeout (also set by an overlay; this holds after a settings reset).
  timeout 20 cmd settings put secure doze_enabled 0
  timeout 20 cmd settings put global hide_error_dialogs 1
  timeout 20 cmd settings put secure theme_customization_overlay_packages '{"android.theme.customization.color_source":"preset","android.theme.customization.system_palette":"3DDC84","android.theme.customization.accent_color":"3DDC84","_applied_timestamp":1}'
  timeout 20 cmd activity start-activity --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME
  enrol
  # A wiped phone has mobile data off; Phone Guard manages it per mode afterwards. `cmd phone data enable` only works once the SIM
  # subscription exists (23 s or more after boot), so retry until a mobile_data<subId>=1 setting appears (max ~5 min).
  j=0
  while [ $j -lt 20 ]; do
    timeout 20 cmd phone data enable >/dev/null 2>&1
    if timeout 20 cmd settings list global 2>/dev/null | grep -qE '^mobile_data[0-9]+=1'; then break; fi
    sleep 15; j=$((j+1))
  done
  say "provisioned, wizard disabled"
}
appops() {
  # Usage access (screen time, idle apps) and the draw-over permission need an app-op that only root may set without a device owner.
  timeout 20 cmd appops set $PKG GET_USAGE_STATS allow
  timeout 20 cmd appops set $PKG SYSTEM_ALERT_WINDOW allow
  aurora_ops boot
}
# Aurora Store (the one app a parent may have Phone Guard install): installing other apps needs an app-op that only root can set, and it is
# LOST when Phone Guard hides and shows the app again, so it is applied at boot, on a request from the app (Settings.Global pg_appops_req, written
# after an install and after the app is shown) and every 5 minutes as a safety net while the store is installed. Idempotent; logs only when it had to change something.
# (-u: it is hidden from the child, and `path` does not show a hidden package.) All-files access is NOT given: Aurora installs and downloads without it.
aurora_ops() {
  case "$(timeout 20 cmd package list packages -u com.aurora.store 2>/dev/null)" in *package:com.aurora.store*)
    cur=$(timeout 20 cmd appops get com.aurora.store REQUEST_INSTALL_PACKAGES 2>&1 | head -1)
    case "$cur" in *allow*) ;; *)
      timeout 20 cmd appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow
      timeout 20 pm grant com.aurora.store android.permission.POST_NOTIFICATIONS
      say "aurora appops ($1): was [$cur], now [$(timeout 20 cmd appops get com.aurora.store REQUEST_INSTALL_PACKAGES 2>&1 | head -1)]";;
    esac;;
  esac
}
# The watcher for app-op requests, cheap when idle. An app cannot set a system property (no way to trigger an init.rc rule from it), so the request is a
# Settings.Global write. Settings.Global lives in one file: a native `stat` of its modification time (a few ms, no binder, no Java) tells us whether ANY
# global setting changed since the last look, and only then is the request key read (`cmd settings`). So an idle phone costs one tiny stat every 10 s,
# not a settings query. If the file cannot be stat'ed from this domain, the key is read once a minute instead. A slow safety-net check of the app-op itself
# runs every 5 minutes (the app also asks after every hide/show, install, boot and update).
SETTINGS_FILE=/data/system/users/0/settings_global.xml
last_m=""; ticks=0
check_req() {
  req=$(timeout 20 cmd settings get global pg_appops_req 2>/dev/null)
  case "$req" in ""|null|0) ;; *) aurora_ops request; timeout 20 cmd settings put global pg_appops_req 0;; esac
}
watch_tick() {   # called every 10 s
  ticks=$((ticks+1))
  m=$(stat -c %Y "$SETTINGS_FILE" 2>/dev/null)
  if [ -n "$m" ]; then
    if [ "$m" != "$last_m" ]; then last_m=$m; check_req; fi
  elif [ $((ticks % 6)) -eq 0 ]; then check_req; fi
  if [ $ticks -ge 30 ]; then ticks=0; aurora_ops poll; fi
}
i=0
while [ $i -lt 20 ]; do
  p=$(timeout 20 cmd package path "$PKG" 2>&1); rc=$?
  case "$p" in package:*) break;; esac
  say "attempt $i: package path rc=$rc: $p"; sleep 15; i=$((i+1))
done
if [ $i -ge 20 ]; then say "gave up: package not found"; exit 1; fi

if timeout 20 cmd device_policy list-owners 2>/dev/null | grep -q "$PKG"; then
  say "already device owner (Family phone)"; appops; enrol
  while :; do sleep 10; watch_tick; done
fi
finish; appops
say "plumbing done, no device owner (edition chosen on the phone)"

# Wait for the phone's edition and for an owner request. The loop ends as soon as an edition is recorded.
while :; do
  ed=$(timeout 20 cmd settings get global pg_edition 2>/dev/null)
  case "$ed" in
    open) say "edition is open: done"; exit 0;; # (an Open phone has no store to give grants to)
    family) say "edition is family: watching for app-op requests"; while :; do sleep 10; watch_tick; done;;
  esac
  req=$(timeout 20 cmd settings get global pg_owner_req 2>/dev/null)
  if [ "$req" = "1" ]; then
    out=$(timeout 30 cmd device_policy set-device-owner --user 0 "$ADMIN" 2>&1)
    if [ $? -eq 0 ]; then say "device owner set: $out"; timeout 20 cmd settings put global pg_owner_req 0
    else say "set-device-owner refused: $out"; timeout 20 cmd settings put global pg_owner_req -1; fi
  fi
  sleep 3
done
