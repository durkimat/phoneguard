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
  ;;
build)
  ccache -M 100G
  source build/envsetup.sh; source vendor/lineage/vars/aosp_target_release
  (cd device/phh/treble && git clean -fdx && bash generate.sh lineage)
  lunch lineage_${TARGET}-${aosp_target_release}-userdebug
  make installclean
  WITH_ADB_INSECURE=true make -j12 systemimage        # -j12: 48 GB cap, ~4 GB per job at peak
  ;;
publish)
  d=/artifacts/lineage-21-$(date -u +%Y%m%d)-${TARGET}; mkdir -p "$d"
  cp "$OUT_DIR"/target/product/*/system.img "$d/system.img"
  (cd "$d" && sha256sum system.img > system.img.sha256 && ls -l)
  ;;
*) echo "usage: $0 sync|build|publish"; exit 1;;
esac
