#!/system/bin/sh
# First-boot provisioning: make Phone Guard the device owner, once, without the user doing anything (docs/integration-plan.md).
# Runs from init as root (uid 0 counts as "adb" for DevicePolicyManagerService.setDeviceOwner, which is why it works without the platform key).
# Idempotent: does nothing if Phone Guard already is the device owner. Gives up after ~5 minutes.
PKG=io.github.durkimat.phoneguard
ADMIN=$PKG/.AdminReceiver
say() { /system/bin/log -t pgprov "$*"; }
i=0
while [ $i -lt 20 ]; do
  if cmd device_policy list-owners 2>/dev/null | grep -q "$PKG"; then say "already device owner"; exit 0; fi
  p=$(cmd package path "$PKG" 2>&1); rc=$?
  case "$p" in package:*) ;; *) say "attempt $i: package path rc=$rc: $p"; sleep 15; i=$((i+1)); continue;; esac
  out=$(cmd device_policy set-device-owner --user 0 "$ADMIN" 2>&1) && { say "device owner set: $out"; exit 0; }
  say "attempt $i: $out"
  sleep 15; i=$((i+1))
done
say "gave up"
exit 1
