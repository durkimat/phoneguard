#!/usr/bin/env bash
# A 480x640 keypad-style Android emulator for testing Phone Guard without the real phone.
#   ./emulator.sh create     one-off: make the virtual phone (needs the emulator and the android-34 google_apis x86_64 image from sdkmanager)
#   ./emulator.sh start      boot it in the background (add "window" to see it) and wait until it is ready
#   ./emulator.sh install    build nothing: install ../controller/app/build/outputs/apk/debug/app-debug.apk and make it device owner
#   ./emulator.sh stop       shut it down
# It uses adb serial emulator-5554, so with the real phone also connected pass -s to adb (the rig scripts use the only device they find).
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21}"
EMU="$ANDROID_HOME/emulator/emulator"; ADB="$ANDROID_HOME/platform-tools/adb"; # x86_64 only: the Android emulator cannot run arm64 images on an x86 PC, and its arm64 translation cannot run the WhatsApp bridge (see docs/emulator.md)
AVD=pg480; IMG="system-images;android-34;google_apis;x86_64"
APK="$(dirname "$0")/../controller/app/build/outputs/apk/debug/app-debug.apk"
PKG=io.github.durkimat.phoneguard

case "${1:-}" in
create)
  echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n $AVD -k "$IMG" -d pixel --force
  cfg="$HOME/.android/avd/$AVD.avd/config.ini"
  # the keypad phone's screen and keys: 480x640, a d-pad and a keyboard (the host keyboard's arrows, Enter and digits drive it), 2 GB of memory
  sed -i '/^hw.lcd.width\|^hw.lcd.height\|^hw.lcd.density\|^hw.keyboard\|^hw.dPad\|^hw.ramSize\|^hw.gpu.mode\|^showDeviceFrame\|^skin.name\|^skin.path/d' "$cfg"
  printf '%s\n' hw.lcd.width=480 hw.lcd.height=640 hw.lcd.density=240 hw.keyboard=yes hw.dPad=yes hw.ramSize=2048 hw.gpu.mode=swiftshader_indirect showDeviceFrame=no >> "$cfg"
  echo "created $AVD" ;;
start)
  opts=(-avd $AVD -no-boot-anim -no-audio -no-snapshot-save -netdelay none -netspeed full)
  [ "${2:-}" = "window" ] || opts+=(-no-window)
  nohup "$EMU" "${opts[@]}" > /tmp/pg-emulator.log 2>&1 &
  "$ADB" wait-for-device
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
  echo "booted: $("$ADB" shell getprop ro.build.version.release | tr -d '\r') $("$ADB" devices | grep emulator | head -1)" ;;
install)
  "$ADB" install -r -t -g "$APK"
  "$ADB" shell dpm set-device-owner "$PKG/.AdminReceiver" || echo "(already device owner, or the emulator has an account)"
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null ;;
stop) "$ADB" emu kill || true ;;
*) sed -n 2,8p "$0" ;;
esac
