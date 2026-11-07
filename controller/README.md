# Phone Guard controller (Android)

The device-owner app that enforces the schedule on the child's phone. It hides apps that the
current mode does not allow, holds user restrictions, keeps grayscale on when a mode asks for it,
and warns 10 minutes before a change that removes apps. HA will push the configuration (next
step); until then it takes a config file through the debug build.

Status: core engine only. **Not yet implemented**: pairing, the signed transport to HA, the
single-use override code, the PIN-protected release, usage reporting, the HA integration.

## Layout
- `core/` pure Kotlin (no Android): config model, mode and schedule engine. Unit tests in `src/test`.
- `Controller.kt` evaluates (config, boost, time) and applies the result. One entry point,
  `Controller.evaluate`, called from every trigger (alarm, boot, clock change, package change).
- `Enforcer.kt` hides and unhides apps, applies restrictions, grayscale.
- `Protected.kt` apps that are never hidden. `Store.kt` persistence (device-protected storage, so it
  works after a reboot before the PIN is entered). `GuardService.kt` foreground service.
- `src/debug` the rig's command channel. Compiled into debug builds only, together with
  `testOnly`, so `dpm remove-active-admin` always works. The release build has neither.

## Build and test
```bash
export JAVA_HOME="$HOME/.local/jdks/jdk-21" ANDROID_HOME="$HOME/Android/Sdk"
cd ~/HA/phoneguard/controller
./gradlew testDebugUnitTest assembleDebug
```

## Install on the J36 (debug build)
```bash
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner io.github.durkimat.phoneguard/.AdminReceiver   # needs no accounts
adb shell pm grant io.github.durkimat.phoneguard android.permission.WRITE_SECURE_SETTINGS  # grayscale
```
Then run the rig: `cd ../rig && ./run_controller.sh` (see `rig/`). Get out with the rig's
`dbg release` or `adb shell dpm remove-active-admin io.github.durkimat.phoneguard/.AdminReceiver`.

## Config format (what HA will push)
See `rig/fixtures/gen_config.py`: `modes` (allowAll / allow list / gray), `base`, `segments` with
absolute epoch-millisecond spans for the next 14 days, `windowEnd`, a same-weekday `pattern` used
after the window runs out, and optional `protectExtra`, `unprotect` and `restrictions`.
