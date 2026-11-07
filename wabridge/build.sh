#!/usr/bin/env bash
# Builds out/wabridge.aar (arm64 only) for the controller app. Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest)
# and the Android NDK (sdkmanager "ndk;26.3.11579264"). The AAR is not committed (about 7 MB): run this before building the app.
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$HOME/.local/gotool/go/bin:$HOME/.local/gotool/gopath/bin:$PATH" GOPATH="${GOPATH:-$HOME/.local/gotool/gopath}" GOFLAGS=-mod=mod
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}" JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdks/jdk-21}"
go test ./...
mkdir -p out
gomobile bind -target=android/arm64 -androidapi 30 -ldflags="-s -w" -trimpath -o out/wabridge.aar .
ls -la out/wabridge.aar
