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
adb shell cmd notification allow_dnd io.github.durkimat.phoneguard   # silent sound profile (Do Not Disturb)
```
To use our own call screens, make the app the phone's Dialer (one grant, replaces the stock one):
`adb shell cmd role add-role-holder --user 0 android.app.role.DIALER io.github.durkimat.phoneguard`.
**To go back to the stock dialler at any time:**
`adb shell cmd role add-role-holder --user 0 android.app.role.DIALER com.android.dialer`.
Other one-time grants used by the launcher: `appops set <pkg> SYSTEM_ALERT_WINDOW allow` (touch off) and
`appops set <pkg> WRITE_SETTINGS allow` (left-key behaviour). Usage access is self-granted.
Then run the rig: `cd ../rig && ./run_controller.sh` (see `rig/`). Get out with the rig's
`dbg release` or `adb shell dpm remove-active-admin io.github.durkimat.phoneguard/.AdminReceiver`.

## Release build and install order
`./release.sh` builds the unit tests, then the release APK, signs it with the key in `~/.phoneguard-release/`
(created on first run; **back up that folder**: updates must be signed with the same key) and checks the
result: not testOnly, no debug receiver, no cleartext setting, no lab code. Lint is skipped (its plugin is not
cached for offline builds). The APK is `app/build/outputs/apk/release/phoneguard-release.apk`.

A release build is **not testOnly**, so `dpm remove-active-admin` will not remove it. The way out is the
parent's Release in HA (the phone drops device owner and removes its changes), or a factory reset. Test the
release flow on a spare phone first.

Order for a new phone (no accounts on it, or device owner is refused):
1. `adb install phoneguard-release.apk`
2. `adb shell dpm set-device-owner io.github.durkimat.phoneguard/.AdminReceiver`
3. The one-time grants above (`WRITE_SECURE_SETTINGS`, `SYSTEM_ALERT_WINDOW` and `WRITE_SETTINGS` app-ops, `allow_dnd`).
4. Open the app and pair it with the Phone Guard card in HA (scan the QR code, check the matching words on both).
5. Set up WhatsApp and any other app that needs a tap in Day mode, before School's touch lock. In School the
   phone is totally silent and blocks incoming calls (parents ring the school office).

Moving from a debug build to release: remove the debug build first (`dpm remove-active-admin`, then
uninstall), because the signing key differs. That also loses the pairing, so pair again.

## Config format (what HA will push)
See `rig/fixtures/gen_config.py`: `modes` (allowAll / allow list / gray), `base`, `segments` with
absolute epoch-millisecond spans for the next 14 days, `windowEnd`, a same-weekday `pattern` used
after the window runs out, and optional `protectExtra`, `unprotect` and `restrictions`.
