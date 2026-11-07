#!/usr/bin/env bash
# Usage: ./run_controller.sh [test ...]   Tests: basic modes boost warning window quarantine gray foreign reset
cd "$(dirname "$0")"; source ./tests_controller.sh
adb get-state >/dev/null 2>&1 || { echo "no adb device"; exit 1; }
wake
tests=("$@"); [ ${#tests[@]} -eq 0 ] && tests=(basic modes boost warning window gray quarantine reset)
ts=$(date +%Y%m%d-%H%M%S)
{
  say "Phone Guard controller run $ts"
  for t in "${tests[@]}"; do "c_$t"; done
  summary
} 2>&1 | tee "results/controller-$ts.log"
