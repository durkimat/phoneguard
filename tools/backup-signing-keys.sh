#!/usr/bin/env bash
# Makes a NEW dated, AES-256 gpg-encrypted archive of all signing keys: the platform key set, Phone Guard's release key and
# Family Chat's release key. Run it yourself in a terminal: gpg asks for the passphrase (twice) and nothing is written anywhere.
# Never overwrites an existing archive. Output: ~/signing-keys-all-YYYYMMDD.tar.gpg, then checked by decrypting into a temp dir.
set -euo pipefail
D=$(date +%Y%m%d); OUT="$HOME/signing-keys-all-$D.tar.gpg"
[ -e "$OUT" ] && { echo "$OUT exists; not overwriting"; exit 1; }
PLATFORM=/archive/phone-backups/signing-keys-20261114
for p in "$PLATFORM" "$HOME/.phoneguard-release/release.jks" "$HOME/.familychat-release/release.jks"; do [ -e "$p" ] || { echo "missing $p"; exit 1; }; done
tar -C / -cf - "${PLATFORM#/}" "${HOME#/}/.phoneguard-release" "${HOME#/}/.familychat-release" | gpg --symmetric --cipher-algo AES256 --output "$OUT"
chmod 600 "$OUT"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
echo "== checking (gpg asks for the passphrase once more)"
gpg --decrypt "$OUT" | tar -C "$T" -xf -
for f in "$T$PLATFORM" "$T$HOME/.phoneguard-release/release.jks" "$T$HOME/.phoneguard-release/pass" "$T$HOME/.familychat-release/release.jks" "$T$HOME/.familychat-release/pass"; do [ -e "$f" ] && echo "ok: ${f#$T}" || { echo "MISSING: ${f#$T}"; exit 1; }; done
K="$T$HOME/.familychat-release"
"${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/keytool" -list -v -keystore "$K/release.jks" -storepass "$(cat "$K/pass")" 2>/dev/null | grep "SHA256:" | head -1 | tr -d ' ' | grep -iq '28:77:0C:2A.*0114\|2877 0c2a' && echo "ok: familychat cert starts 28770c2a" || echo "check the familychat cert SHA-256 by eye: expected 28770c2a...0114"
sha256sum "$OUT"; ls -l "$OUT"; echo "temporary copy removed on exit"
