# Testing in the Android emulator

`rig/emulator.sh` makes and runs a 480x640 virtual phone with a d-pad and keyboard (Google APIs, Android 14, x86_64) so most of Phone Guard can be tested without the real phone and without anyone unlocking it.

```bash
rig/emulator.sh create    # once
rig/emulator.sh start     # about 25 s to boot, headless; add "window" to watch it
rig/emulator.sh install   # installs the debug APK and makes it device owner
adb exec-out screencap -p > shot.png ; adb shell input keyevent KEYCODE_DPAD_DOWN   # same as on the phone
rig/run_controller.sh     # the controller tests (use -s via ANDROID_SERIAL if the real phone is also connected)
rig/emulator.sh stop
```

## What works (tried 2026-11-09)
- Boot in 25 s, device owner provisioning, the whole app, restrictions, mode switching, grey screen, the ten-minute warning, newly installed apps hidden. `rig/run_controller.sh`: 37 passed, 8 failed.
- No lock screen to get past, and no owner needed for screen-off tests.
- A virtual SIM, so mobile data and the data symbol can be tried there (not yet done).

## What does not
- **The WhatsApp bridge.** The Android emulator cannot run arm64 images on an x86 PC, and its arm64 translation layer cannot run the bridge (illegal instruction in the Go runtime). A native x86_64 build also fails: the bridge's SQLite library (`modernc.org/sqlite`) uses old system calls such as `lstat` that Android's x86_64 sandbox blocks (SIGSYS). The app therefore skips the bridge on x86 (log: "whatsapp: not available on x86_64"). Options if it is needed: build the bridge for x86_64 with a C-based SQLite behind build tags (arm64 and the phone unchanged), or test WhatsApp on the real phone only.
- The 8 failing rig tests expect LineageOS/DumberOS package names (gallery, camera, clock, browser, dialler); the stock image has others. They are test-data differences, not app faults; a per-image package map would fix them.
- Real battery and Doze behaviour, MediaTek/DumberOS quirks, the side button's key code, real keypad feel and the FM chip.

## Releasing without the phone
`controller/publish-release.sh X.Y.Z "notes"` already builds, signs and publishes a GitHub release; HA offers it and the phone updates with "Update this phone". The test phone currently has a debug-signed build, which cannot take a release-signed update: moving it is a one-time uninstall and re-provision.
