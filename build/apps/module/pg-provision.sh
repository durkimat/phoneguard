#!/system/bin/sh
# First-boot provisioning: make Phone Guard the device owner, once, without the user doing anything (docs/integration-plan.md).
# Runs from init as root (uid 0 counts as "adb" for DevicePolicyManagerService.setDeviceOwner, which is why it works without the platform key).
# Idempotent: does nothing if Phone Guard already is the device owner. Gives up after ~5 minutes.
PKG=io.github.durkimat.phoneguard
ADMIN=$PKG/.AdminReceiver
say() { /system/bin/log -t pgprov "$*"; }
say "started"
# After the owner is set: skip the Lineage setup wizard (the phone is provisioned by Phone Guard, not by a person) and start Phone Guard.
# device_provisioned/user_setup_complete matter beyond the wizard: telephony refuses user mobile data until both are 1.
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
  timeout 20 cmd settings put global hide_error_dialogs 1
  timeout 20 cmd settings put secure theme_customization_overlay_packages '{"android.theme.customization.color_source":"preset","android.theme.customization.system_palette":"3DDC84","android.theme.customization.accent_color":"3DDC84","_applied_timestamp":1}'
  timeout 20 cmd activity start-activity --user 0 -n $PKG/.MainActivity
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
i=0
while [ $i -lt 20 ]; do
  if timeout 20 cmd device_policy list-owners 2>/dev/null | grep -q "$PKG"; then say "already device owner"; exit 0; fi
  p=$(timeout 20 cmd package path "$PKG" 2>&1); rc=$?
  case "$p" in package:*) ;; *) say "attempt $i: package path rc=$rc: $p"; sleep 15; i=$((i+1)); continue;; esac
  out=$(timeout 30 cmd device_policy set-device-owner --user 0 "$ADMIN" 2>&1) && { say "device owner set: $out"; finish; exit 0; }
  say "attempt $i: $out"
  sleep 15; i=$((i+1))
done
say "gave up"
exit 1
