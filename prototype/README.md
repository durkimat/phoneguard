# Phone Guard Lab (throwaway prototype)

A single-screen Android app with one button per question the spec still has open
(see `../SPEC.md`). It is **not** the real controller. It exists to answer questions on the
Qin J36 running DumberOS, then be deleted.

- Package: `io.github.durkimat.phoneguard.lab` (the real app will be `io.github.durkimat.phoneguard`)
- Min API 30, target 34, Kotlin, plain views, ZXing for QR scanning.
- **`testOnly`**: so `adb shell dpm remove-active-admin` always works. The real app must not be `testOnly`.
- Logs to the screen and to a file you can pull: see below.

## Build

Toolchain (already installed in the home directory, no sudo): JDK 21 in `~/.local/jdks/jdk-21`,
Android SDK in `~/Android/Sdk` (platform 34, build-tools 34.0.0), Gradle wrapper 8.7.

```bash
export JAVA_HOME="$HOME/.local/jdks/jdk-21" ANDROID_HOME="$HOME/Android/Sdk"
cd ~/HA/phoneguard/prototype
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install and become device owner

```bash
adb install -t -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys account | head -40
adb shell dpm set-device-owner io.github.durkimat.phoneguard.lab/.AdminReceiver
```

Device owner can only be set when the phone has **no accounts**. If the second command lists a
microG or Google account, remove it in Settings first. Add accounts back afterwards.

## Get out again (any of these)

1. In the app: **RELEASE device owner (safe exit)**.
2. `adb shell dpm remove-active-admin io.github.durkimat.phoneguard.lab/.AdminReceiver` (works because it is `testOnly`).
3. The app clears the debugging restriction on every boot and after 3 minutes.
4. Last resort: factory reset.

Then `adb uninstall io.github.durkimat.phoneguard.lab`.

## Pull the log

```bash
adb pull /sdcard/Android/data/io.github.durkimat.phoneguard.lab/files/lab.log
```

See `TEST-PLAN.md` for the order to run things in and what to record.
