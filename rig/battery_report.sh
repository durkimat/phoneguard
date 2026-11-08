#!/usr/bin/env bash
# Summarise a battery test over wireless adb (or USB). Usage: rig/battery_report.sh [adb serial]
# Run `adb shell dumpsys batterystats --reset` when the phone is unplugged to start a test.
S=${1:+-s $1}
PKG=io.github.durkimat.phoneguard
a() { adb $S shell "$@"; }
echo "== now"; a date; a dumpsys battery | grep -E "level|status|USB powered"
echo "== time on battery"; a dumpsys batterystats | grep -E "Time on battery|Discharge|Estimated battery|Screen on|Screen off"
echo "== our app's use since the last reset"; a dumpsys batterystats $PKG | grep -E "Estimated power use|Uid u0|Wake lock|Foreground|CPU|Mobile network|Wi-Fi|wakeups|Alarm|Job" | head -30
echo "== alarms (wakeups) for our app"; a dumpsys alarm | grep -A3 "$PKG" | grep -E "tag=|count=|wakeups" | sort | uniq -c | sort -rn | head -10
echo "== app log: WhatsApp checks and connections"; a "cat /sdcard/Android/data/$PKG/files/phoneguard.log 2>/dev/null | grep -i -E 'wa:|whatsapp|poll|connect|mode ' | tail -60"
