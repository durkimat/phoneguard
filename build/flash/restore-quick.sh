#!/bin/bash
# R2 in docs/flash-plan-baseline.md: put Dumber's system and the stock product back through fastbootd (data is wiped).
# Needs, in this directory: system_a.img (Dumber, from the 10 Nov extraction) and product_a.img (stock), both with SHA256SUMS-restore.
set -euo pipefail
cd "$(dirname "$0")"
ask(){ read -r -p "$1 [type yes to continue] " a; [ "$a" = yes ] || { echo stopped; exit 1; }; }
fb(){ echo "+ fastboot $*"; fastboot "$@"; }
sha256sum -c SHA256SUMS-restore
ask "Restore Dumber's system and the stock product (userdata and metadata get wiped)?"
fastboot devices
fb reboot fastboot; sleep 8
fb resize-logical-partition product_a 2486730752
fb flash product_a product_a.img
fb resize-logical-partition system_a 2173976576
fb flash system_a system_a.img
fb erase userdata; fb erase metadata
fb reboot
