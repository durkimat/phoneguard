#!/usr/bin/env bash
# Helpers for driving the Phone Guard lab app and the J36 over adb. Source this file.
PKG=io.github.durkimat.phoneguard.lab
LOG=/sdcard/Android/data/$PKG/files/lab.log
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/results}"
mkdir -p "$OUT"
PASS=0; FAIL=0; INFO=0

say()  { printf '%s\n' "$*"; }
pass() { PASS=$((PASS+1)); say "  PASS  $*"; }
fail() { FAIL=$((FAIL+1)); say "  FAIL  $*"; }
note() { INFO=$((INFO+1)); say "  NOTE  $*"; }
section() { say; say "== $* =="; }

lablines() { adb shell "wc -l < $LOG" | tr -d '\r '; }

# pg <cmd> [a] [b]  -> prints the text after "=> " from the lab app's log
pg() {
  local n; n=$(lablines)
  local extra=""
  [ -n "${2:-}" ] && extra="$extra --es a '$2'"
  [ -n "${3:-}" ] && extra="$extra --es b '$3'"
  adb shell "am broadcast -n $PKG/.CmdReceiver -a $PKG.CMD --es c '$1' $extra" >/dev/null
  local i line
  for i in $(seq 1 40); do
    line=$(adb shell "tail -n +$((n+1)) $LOG" | tr -d '\r' | grep -F "CMD $1 ${2:-} =>" | tail -1)
    [ -n "$line" ] && { printf '%s\n' "${line#*=> }"; return 0; }
    sleep 0.25
  done
  echo "TIMEOUT"; return 1
}

# expect <description> <actual> <substring>
expect() { case "$2" in *"$3"*) pass "$1  [$2]";; *) fail "$1  [got: $2, wanted: $3]";; esac; }

shot() { adb exec-out screencap -p > "$OUT/${1:-shot}.png"; echo "$OUT/${1:-shot}.png"; }

# ui <outfile.xml>: dump the on-screen element tree
ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > "${1:-$OUT/ui.xml}"; }

# tap_text <text>: tap the first element whose text/desc contains the string; returns 1 if none
tap_text() {
  ui "$OUT/ui.xml"
  local xy
  xy=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re,sys
t=sys.argv[1].lower(); x=open(sys.argv[2],encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    tx=re.search(r' text="([^"]*)"',n); d=re.search(r' content-desc="([^"]*)"',n)
    if (tx and t in tx.group(1).lower()) or (d and t in d.group(1).lower()):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        if b:
            a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  [ -n "$xy" ] || return 1
  adb shell input tap $xy
}

screen_text() { ui "$OUT/ui.xml"; python3 - "$OUT/ui.xml" <<'PY'
import re,sys
x=open(sys.argv[1],encoding='utf-8').read()
print(' | '.join(t for t in re.findall(r' text="([^"]+)"',x)))
PY
}

wake()  { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1; adb shell svc power stayon usb; }
home()  { adb shell input keyevent KEYCODE_HOME; sleep 1; }
back()  { adb shell input keyevent KEYCODE_BACK; }

# gray_fraction <png>: fraction of pixels where R~G~B (1.0 = fully greyscale)
gray_fraction() { python3 - "$1" <<'PY'
import sys
from PIL import Image
im=Image.open(sys.argv[1]).convert('RGB'); px=list(im.getdata())
g=sum(1 for r,gg,b in px if max(r,gg,b)-min(r,gg,b)<12)
print(round(g/len(px),3))
PY
}

summary() { say; say "== $PASS passed, $FAIL failed, $INFO notes =="; }
