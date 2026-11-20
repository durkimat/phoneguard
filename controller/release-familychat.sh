#!/usr/bin/env bash
# Builds, signs and checks the Family Chat release APK. Its signing key is its OWN, kept OUTSIDE the repository
# (~/.familychat-release/); back it up, because updates must be signed with the same key. Never uses Phone Guard's key.
# Usage: FC_VERSION=1.0.0 ./release-familychat.sh     Output: familychat/build/outputs/apk/release/familychat-release.apk
# Release assets (made by hand after the owner's go): the APK, its .sha256, NOTICE.txt, and the changelog text as the release body.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
KDIR="$HOME/.familychat-release"; KS="$KDIR/release.jks"; PASSF="$KDIR/pass"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
[ -f ../wabridge/out/wabridge.aar ] || { echo "build the bridge first: ../wabridge/build.sh"; exit 1; }

if [ ! -f "$KS" ]; then
  echo "== creating a new release key in $KDIR (back it up!)"
  mkdir -p "$KDIR"; chmod 700 "$KDIR"
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSF"; chmod 600 "$PASSF"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$(cat "$PASSF")" -keypass "$(cat "$PASSF")" \
    -alias familychat -keyalg RSA -keysize 3072 -validity 9000 -dname "CN=Family Chat release" >/dev/null
  chmod 600 "$KS"
fi

rm -f familychat/build/outputs/apk/release/*.apk # a failed build must never leave an old APK to be picked up
./gradlew --offline -q ${FC_VERSION:+-PfcVersion=$FC_VERSION} :wa-core:testDebugUnitTest :familychat:testDebugUnitTest :familychat:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease 2>&1 | grep -vE "^WARNING" || true
IN=familychat/build/outputs/apk/release/familychat-release-unsigned.apk
OUT=familychat/build/outputs/apk/release/familychat-release.apk
[ -f "$IN" ] || { echo "release build failed"; exit 1; }
"$BT/zipalign" -f -p 4 "$IN" "$OUT.aligned"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$(cat "$PASSF")" --ks-key-alias familychat --out "$OUT" "$OUT.aligned"
rm -f "$OUT.aligned" "$OUT.idsig"
"$BT/apksigner" verify --print-certs "$OUT" | head -3

echo "== checks"
M=$("$BT/aapt" dump xmltree "$OUT" AndroidManifest.xml)
echo "$M" | grep -qi "testOnly" && { echo "FAIL: release is testOnly"; exit 1; } || echo "ok: not testOnly"
"$BT/aapt" dump badging "$OUT" | grep -q "package: name='io.github.durkimat.familychat'" || { echo "FAIL: wrong package"; exit 1; }
echo "$M" | grep -i "android:label" | grep -qi whatsapp && { echo "FAIL: WhatsApp in the app name"; exit 1; } || echo "ok: no WhatsApp in the name"
unzip -l "$OUT" | grep -q "libgojni.so" || { echo "FAIL: bridge missing"; exit 1; } && echo "ok: bridge packaged"
# 16 KB pages (Android 15+): the .so must be stored uncompressed at a 16 KB offset in the zip and its ELF segments aligned to 16 KB
python3 - "$OUT" <<'PY' || { echo "FAIL: not 16 KB aligned"; exit 1; }
import sys,zipfile,struct,io
apk=sys.argv[1]; z=zipfile.ZipFile(apk); raw=open(apk,'rb'); bad=0
for i in z.infolist():
    if not i.filename.endswith('.so'): continue
    raw.seek(i.header_offset); h=raw.read(30); n,e=struct.unpack('<HH',h[26:30]); off=i.header_offset+30+n+e
    d=z.read(i); phoff,=struct.unpack_from('<Q',d,0x20); es,pn=struct.unpack_from('<HH',d,0x36)
    al=[struct.unpack_from('<IIQQQQQQ',d,phoff+k*es)[7] for k in range(pn) if struct.unpack_from('<I',d,phoff+k*es)[0]==1]
    ok=i.compress_type==0 and off%16384==0 and all(a%16384==0 for a in al); bad+=0 if ok else 1
    print(('ok' if ok else 'FAIL')+': 16 KB alignment of '+i.filename)
sys.exit(1 if bad else 0)
PY
ls -la "$OUT"
