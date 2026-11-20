#!/usr/bin/env bash
# Builds out/wabridge.aar (arm64 only) for the controller app. Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest)
# and the Android NDK (sdkmanager "ndk;26.3.11579264"). The AAR is not committed (about 7 MB): run this before building the app.
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$HOME/.local/gotool/go/bin:$HOME/.local/gotool/gopath/bin:$PATH" GOPATH="${GOPATH:-$HOME/.local/gotool/gopath}" GOFLAGS=-mod=mod
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}" JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21}"
go test ./...
mkdir -p out
gomobile bind -target=android/arm64 -androidapi 30 -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" -trimpath -o out/wabridge.aar .
# 16 KB page size (Android 15+ devices): every LOAD segment of the .so must be aligned to 0x4000
unzip -p out/wabridge.aar jni/arm64-v8a/libgojni.so > out/libgojni.check.so && python3 - <<PY
import struct,sys
f=open("out/libgojni.check.so","rb").read()
phoff,=struct.unpack_from("<Q",f,0x20); es,n=struct.unpack_from("<HH",f,0x36)
bad=[hex(struct.unpack_from("<IIQQQQQQ",f,phoff+i*es)[7]) for i in range(n) if struct.unpack_from("<IIQQQQQQ",f,phoff+i*es)[0]==1 and struct.unpack_from("<IIQQQQQQ",f,phoff+i*es)[7]%16384]
print("16 KB aligned" if not bad else "NOT 16 KB aligned: "+str(bad)); sys.exit(1 if bad else 0)
PY
rm -f out/libgojni.check.so
ls -la out/wabridge.aar
