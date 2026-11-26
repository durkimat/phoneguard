#!/usr/bin/env bash
# The Android emulator needs a few shared libraries (libpulse, libXi and what they pull in) that a minimal Ubuntu / container image
# may not have, and installing them needs root. This unpacks them from the Ubuntu archive into ~/.cache/pg-emulator-libs, no root needed,
# and prints the LD_LIBRARY_PATH to use:   eval "$(rig/emulator-libs.sh)"
# Safe to run again: it only fetches what is missing.
set -euo pipefail
D="${PG_EMU_LIBS:-$HOME/.cache/pg-emulator-libs}"; mkdir -p "$D/debs"; cd "$D/debs"
[ -f P-jammy ] || for s in jammy-updates jammy; do curl -fsS -m 90 "http://archive.ubuntu.com/ubuntu/dists/$s/main/binary-amd64/Packages.gz" | gunzip > "P-$s"; done
for p in libpulse0 libasyncns0 libsndfile1 libflac8 libvorbis0a libvorbisenc2 libopus0 libogg0 libmpg123-0 libmp3lame0 libwrap0 libapparmor1 libxi6; do
  [ -e "$D/.got-$p" ] && continue
  for s in jammy-updates jammy; do
    f=$(awk -v p="$p" '$1=="Package:"{c=($2==p)} c&&$1=="Filename:"{print $2; exit}' "P-$s" 2>/dev/null || true)
    if [ -n "$f" ]; then curl -fsS -m 90 -O "http://archive.ubuntu.com/ubuntu/$f" && dpkg -x "$(basename "$f")" "$D/root" && touch "$D/.got-$p"; break; fi
  done
done
E="${ANDROID_HOME:-$HOME/Android/Sdk}/emulator"; L="$D/root/usr/lib/x86_64-linux-gnu"
echo "export LD_LIBRARY_PATH=$L:$L/pulseaudio:$E/lib64:$E/lib64/qt/lib:$E/lib64/gles_swiftshader:$E/qemu/linux-x86_64/lib64"
