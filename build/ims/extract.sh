#!/bin/bash
# Copy the round-1 files out of the phone's own original system image (read-only) into a private folder.
# Usage: extract.sh IMAGE OUTDIR [FILELIST]. The output is proprietary: never commit it, never put it in /artifacts.
set -euo pipefail
IMG=${1:?image}; OUT=${2:?outdir}; LIST=${3:-$(dirname "$0")/round1-files.txt}
[ ! -e "$OUT" ] || { echo "$OUT exists; refusing to overwrite"; exit 1; }
mkdir -p "$OUT"
sha256sum "$IMG" | tee "$OUT/SOURCE.sha256"
grep -v '^#' "$LIST" | while read -r p; do
  [ -n "$p" ] || continue
  mkdir -p "$OUT$(dirname "$p")"
  debugfs -R "dump $p $OUT$p" "$IMG" 2>&1 | grep -v '^debugfs ' || true
  [ -s "$OUT$p" ] && echo "ok   $p ($(stat -c %s "$OUT$p") B)" || { echo "MISSING $p"; exit 1; }
done
(cd "$OUT" && find . -type f ! -name SOURCE.sha256 ! -name MANIFEST.sha256 -print0 | sort -z | xargs -0 sha256sum > MANIFEST.sha256)
chmod -R a-w "$OUT"
echo "done: $(grep -vc '^#' "$LIST") files in $OUT"
