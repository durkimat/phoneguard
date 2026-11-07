#!/usr/bin/env bash
# Helpers for the real controller (io.github.durkimat.phoneguard debug build). Source after lib.sh.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CPKG=io.github.durkimat.phoneguard
CLOG=/sdcard/Android/data/$CPKG/files/controller.log
CGAL=org.lineageos.glimpse; CCAM=org.lineageos.aperture; CJELLY=org.lineageos.jelly

clines() { adb shell "wc -l < $CLOG" | tr -d '\r '; }

# dbg <cmd> [a] [b] -> text after "=> " of the controller's DBG log line
dbg() {
  local n; n=$(clines); local extra=""
  [ -n "${2:-}" ] && extra="$extra --es a '$2'"
  [ -n "${3:-}" ] && extra="$extra --es b '$3'"
  adb shell "am broadcast -n $CPKG/.DebugReceiver -a $CPKG.DEBUG --es c '$1' $extra" >/dev/null
  local i line
  for i in $(seq 1 40); do
    line=$(adb shell "tail -n +$((n+1)) $CLOG" | tr -d '\r' | grep -F "DBG $1 ${2:-} =>" | tail -1)
    [ -n "$line" ] && { printf '%s\n' "${line#*=> }"; return 0; }
    sleep 0.25
  done
  echo TIMEOUT; return 1
}

# epoch ms for a London local time: ldn 2026-11-09 10:00
ldn() { python3 -c "
from datetime import datetime; from zoneinfo import ZoneInfo
print(int(datetime.strptime('$1 $2','%Y-%m-%d %H:%M').replace(tzinfo=ZoneInfo('Europe/London')).timestamp()*1000))"; }

# set_time 2026-11-09 10:00  -> moves the controller's clock and re-evaluates
set_time() { dbg clock "$(ldn "$1" "$2")" >/dev/null; sleep 1; }

push_config() {
  python3 "$HERE/fixtures/gen_config.py" "${1:-1}" ${2:-} > "$OUT/cfg.json"
  adb push "$OUT/cfg.json" /sdcard/Android/data/$CPKG/files/cfg.json >/dev/null
  dbg config cfg.json
}

launcher_has() { adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -q "$1"; }
hidden_has() { dbg hidden | grep -q "$1"; }
last_eval() { adb shell "grep '^[0-9-]* [0-9:]*  EVAL' $CLOG" | tail -1 | tr -d '\r'; }
