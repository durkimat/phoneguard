#!/bin/bash
# Phone Guard GSI build. Run inside tmux on the build container (ssh -p 2222 builder@192.168.1.138):
#   tmux new -s lineage 'bash /src/build.sh sync 2>&1 | tee -a /out/logs/sync.log'
#   tmux new -s lineage 'bash /src/build.sh build 2>&1 | tee -a /out/logs/build.log'
# Stages: sync (download + patch, ~100+ GB), build (system.img), publish (copy to /artifacts).
set -euo pipefail
SRC=/src/lineage-21; PLAN=/src/phoneguard-build   # PLAN = a copy of phoneguard/build/ (see PLAN.md)
TARGET=${TARGET:-arm64_bvN}                          # arm64, A/B, vanilla (no GApps), no su
mkdir -p /out/logs "$SRC"; cd "$SRC"
export USE_CCACHE=1 CCACHE_EXEC=/usr/bin/ccache CCACHE_DIR=/ccache OUT_DIR=/out LC_ALL=C

case "${1:-}" in
sync)
  [ -d .repo ] || repo init -u https://github.com/LineageOS/android.git -b lineage-21.0 --git-lfs --no-clone-bundle
  mkdir -p .repo/local_manifests; cp "$PLAN"/local_manifests/*.xml .repo/local_manifests/
  repo sync -c --force-sync --no-clone-bundle --no-tags -j8      # gentle on GitHub/Google: 8 jobs
  # Patches (PLAN.md path B): TrebleDroid's official patches-for-developers.zip, applied by their own apply-patches.sh.
  # It skips patches already applied and prints "Failed applying" for rejects; we keep that list for hand fixing.
  TDREL=${TDREL:-android_14.0.0_r17}
  if [ ! -d /src/td-patches/patches ]; then
    mkdir -p /src/td-patches && cd /src/td-patches
    curl -fsSL -o p.zip https://github.com/TrebleDroid/treble_experimentations/releases/download/$TDREL/patches-for-developers.zip
    sha256sum p.zip | tee /out/logs/td-patches.sha256; unzip -q p.zip
    curl -fsSL -o apply-patches.sh https://raw.githubusercontent.com/TrebleDroid/treble_experimentations/master/apply-patches.sh
    cd "$SRC"
  fi
  bash /src/td-patches/apply-patches.sh /src/td-patches 2>&1 | tee /out/logs/patches.log
  grep "Failed applying" /out/logs/patches.log | tee /out/logs/patch-rejects.txt || true
  # patch(1) leaves *.orig backups when a hunk applies with fuzz; aapt2 rejects them inside res/ dirs. Drop untracked ones.
  repo forall -c 'git ls-files --others --exclude-standard | grep -E "\.(orig|rej)$" | xargs -r rm -v' | tee -a /out/logs/patches.log
  ;;
build)
  ccache -M 100G
  repo forall -c 'git ls-files --others --exclude-standard | grep -E "\.(orig|rej)$" | xargs -r rm -v' || true   # see sync step
  # Our own fixups (build/patches/*.patch, path = target repo with / replaced by _ in the file name's prefix is not
  # used; each patch header names its repo in PATCHDIR below). Idempotent: skipped if already applied.
  for pf in /src/phoneguard-build/patches/*.patch; do
    dir=$(sed -n 's/^# repo: //p' "$pf" | head -1)
    (cd "$SRC/$dir" && { git apply -R --check "$pf" 2>/dev/null && echo "already applied: $pf" || git apply "$pf"; })
  done
  set +u   # Android's envsetup.sh and lunch use unset variables
  source build/envsetup.sh; source vendor/lineage/vars/aosp_target_release
  (cd device/phh/treble && git clean -fdx && bash generate.sh lineage)
  lunch treble_${TARGET}-${aosp_target_release}-userdebug
  export LINEAGE_BUILD=treble_${TARGET}   # what Lineage's breakfast sets; AOSP product makefiles key off it
  make installclean
  # TrebleDroid adds public API (FAKE_PACKAGE_SIGNATURE) that API lint/checkapi reject; skip those checks, not the build.
  DISABLE_STUB_VALIDATION=true WITH_LINEAGE_CHARGER=false WITH_ADB_INSECURE=true make -j$(nproc) systemimage   # all 16 cores; Soong/ninja also size their own parallelism. Drop to -j12/-j8 if the 48 GB cap OOMs
  ;;
publish)
  d=/artifacts/lineage-21-$(date -u +%Y%m%d)-${TARGET}; mkdir -p "$d"
  cp "$OUT_DIR"/target/product/*/system.img "$d/system.img"
  (cd "$d" && sha256sum system.img > system.img.sha256 && ls -l)
  ;;
*) echo "usage: $0 sync|build|publish"; exit 1;;
esac
