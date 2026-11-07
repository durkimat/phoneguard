#!/usr/bin/env bash
# Usage: ./run.sh [test ...]   (default: all fast tests).  Tests: hide restrictions restrictions_ui gray power homepin doze net cleanup
cd "$(dirname "$0")"; source ./tests.sh
adb get-state >/dev/null 2>&1 || { echo "no adb device"; exit 1; }
wake
tests=("$@"); [ ${#tests[@]} -eq 0 ] && tests=(hide restrictions restrictions_ui gray power homepin net cleanup)
ts=$(date +%Y%m%d-%H%M%S)
{
  say "Phone Guard rig run $ts on $(adb shell getprop ro.product.model | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
  for t in "${tests[@]}"; do "t_$t"; done
  summary
} 2>&1 | tee "results/run-$ts.log"
