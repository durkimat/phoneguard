#!/bin/bash
# Re-flash ONLY system_a (same size as the baseline, so no resize). Keeps userdata, metadata, product and everything else.
# Run on the PC the phone is plugged into. Stops at the first failure. Asks before the write.
set -euo pipefail
cd "$(dirname "$0")"
SYS=system.img; SYS_BYTES=2511581184
say(){ printf '\n== %s\n' "$*"; }
ask(){ read -r -p "$1 [type yes to continue] " a; [ "$a" = yes ] || { echo "stopped, nothing written"; exit 1; }; }
fb(){ echo "+ fastboot $*"; fastboot "$@"; }

say "Checking files"
sha256sum -c SHA256SUMS
[ "$(stat -c %s $SYS)" = "$SYS_BYTES" ] || { echo "system.img size is wrong"; exit 1; }

say "Phone state (read-only)"
fastboot devices
[ "$(fastboot getvar unlocked 2>&1 | grep -o 'unlocked: *[a-z]*' | awk '{print $2}')" = yes ] || { echo "bootloader not unlocked, stopping"; exit 1; }
ask "This REPLACES system_a only (userdata is kept). Continue?"

say "Entering fastbootd"
fb reboot fastboot
sleep 8
[ "$(fastboot getvar is-userspace 2>&1 | grep -o 'is-userspace: *[a-z]*' | awk '{print $2}')" = yes ] || { echo "not in fastbootd, stopping"; exit 1; }
[ "$(fastboot getvar partition-size:system_a 2>&1 | grep -o '0x[0-9a-fA-F]*' | head -1 | xargs printf '%d')" -ge "$SYS_BYTES" ] || { echo "system_a is smaller than the image, stopping"; exit 1; }
fb flash system_a "$SYS"
fb reboot
echo "Done. Do not wrap this script in 'timeout': the write takes several minutes."
