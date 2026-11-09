#!/usr/bin/env bash
# Builds, signs and publishes a Phone Guard release on GitHub, so the Phone Guard integration in Home Assistant can offer it
# (it checks the repository's newest release every few hours and shows an "Update this phone" button).
# Usage: ./publish-release.sh 0.2.0 "What changed"      Needs: the GitHub CLI (gh) logged in, and the release key in ~/.phoneguard-release/
# The signing key stays on this computer: Android only accepts an update signed by the key that signed the installed app.
set -euo pipefail
cd "$(dirname "$0")"
V="${1:?give the version, for example 0.2.0}"; NOTES="${2:-Release $V}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 0.2.0"; exit 1; }
PG_VERSION="$V" ./release.sh
APK="app/build/outputs/apk/release/phoneguard-release.apk"
OUT="app/build/outputs/apk/release/phoneguard-$V.apk"
cp "$APK" "$OUT"
( cd "$(dirname "$OUT")" && sha256sum "phoneguard-$V.apk" > "phoneguard-$V.apk.sha256" )
echo "== sha256: $(cut -d' ' -f1 "$OUT.sha256")"
gh release create "v$V" "$OUT" "$OUT.sha256" --repo durkimat/phoneguard --title "Phone Guard $V" --notes "$NOTES"
echo "== published v$V; Home Assistant will offer it within a few hours (or run the phoneguard.check_updates service)"
