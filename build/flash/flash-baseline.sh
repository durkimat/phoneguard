#!/bin/bash
# Flash the baseline GSI (docs/flash-plan-baseline.md section 4). Run on the PC the phone is plugged into.
# Stops at the first failure. Asks before every write step. Needs: fastboot, the files below in this directory.
set -euo pipefail
cd "$(dirname "$0")"
SYS=system.img; PROD=product_empty.img
SYS_BYTES=2511581184; PROD_BYTES=67108864

say(){ printf '\n== %s\n' "$*"; }
ask(){ read -r -p "$1 [type yes to continue] " a; [ "$a" = yes ] || { echo "stopped, nothing more written"; exit 1; }; }
fb(){ echo "+ fastboot $*"; fastboot "$@"; }

say "Checking files"
sha256sum -c SHA256SUMS
[ "$(stat -c %s $SYS)" = "$SYS_BYTES" ] || { echo "system.img size is wrong"; exit 1; }
[ "$(stat -c %s $PROD)" = "$PROD_BYTES" ] || { echo "product_empty.img size is wrong"; exit 1; }

say "Phone state (read-only)"
fastboot devices
[ "$(fastboot getvar unlocked 2>&1 | grep -o 'unlocked: *[a-z]*' | awk '{print $2}')" = yes ] || { echo "bootloader not unlocked, stopping"; exit 1; }
fastboot getvar all 2>&1 | tee fastboot-getvar-before.txt >/dev/null
echo "saved fastboot-getvar-before.txt"
echo "Reminder: you must have taken the userdata and metadata dumps (and checked them) BEFORE this point if you want the data back."
ask "This will REPLACE system and product and WIPE userdata and metadata. Continue?"

say "Entering fastbootd"
fb reboot fastboot
sleep 8
[ "$(fastboot getvar is-userspace 2>&1 | grep -o 'is-userspace: *[a-z]*' | awk '{print $2}')" = yes ] || { echo "not in fastbootd, stopping"; exit 1; }

say "Shrink product_a and put an empty ext4 in it"
fb resize-logical-partition product_a "$PROD_BYTES"
fb flash product_a "$PROD"
say "Grow system_a and flash our image"
fb resize-logical-partition system_a "$SYS_BYTES"
fb flash system_a "$SYS"
ask "Images written. Now wipe userdata and metadata and reboot?"
fb erase userdata
fb erase metadata
fb reboot
echo "Done. First boot can take several minutes. Then follow section 5 of the plan (adb logcat -b all -d > boot.log)."
