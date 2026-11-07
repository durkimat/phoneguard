#!/usr/bin/env bash
# Builds, signs and checks the release APK. The signing key lives OUTSIDE the repository
# (~/.phoneguard-release/); keep a backup of it, because updates must be signed with the same key.
# Usage: ./release.sh        Output: app/build/outputs/apk/release/phoneguard-release.apk
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
KDIR="$HOME/.phoneguard-release"; KS="$KDIR/release.jks"; PASSF="$KDIR/pass"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

if [ ! -f "$KS" ]; then
  echo "== creating a new release key in $KDIR (back it up!)"
  mkdir -p "$KDIR"; chmod 700 "$KDIR"
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSF"; chmod 600 "$PASSF"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$(cat "$PASSF")" -keypass "$(cat "$PASSF")" \
    -alias phoneguard -keyalg RSA -keysize 3072 -validity 9000 -dname "CN=Phone Guard release" >/dev/null
  chmod 600 "$KS"
fi

./gradlew --offline -q testDebugUnitTest assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease 2>&1 | grep -vE "^WARNING" || true
IN=app/build/outputs/apk/release/app-release-unsigned.apk
OUT=app/build/outputs/apk/release/phoneguard-release.apk
[ -f "$IN" ] || { echo "release build failed"; exit 1; }
"$BT/zipalign" -f -p 4 "$IN" "$OUT.aligned"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$(cat "$PASSF")" --ks-key-alias phoneguard --out "$OUT" "$OUT.aligned"
rm -f "$OUT.aligned" "$OUT.idsig"
"$BT/apksigner" verify --print-certs "$OUT" | head -3

echo "== checks"
M=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$OUT" 2>/dev/null || "$BT/aapt" dump xmltree "$OUT" AndroidManifest.xml)
echo "$M" | grep -qi "testOnly" && { echo "FAIL: release is testOnly"; exit 1; } || echo "ok: not testOnly"
echo "$M" | grep -qi "DebugReceiver" && { echo "FAIL: debug receiver is in the release"; exit 1; } || echo "ok: no debug receiver"
echo "$M" | grep -qi "usesCleartextTraffic" && echo "NOTE: cleartext setting present" || echo "ok: no cleartext setting"
unzip -l "$OUT" | grep -q "LabLockdown" && { echo "FAIL: lab code present"; exit 1; } || echo "ok: no lab code"
ls -la "$OUT"
