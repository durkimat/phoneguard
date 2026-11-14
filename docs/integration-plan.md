# Phone Guard integration plan (2026-11-14)

Goal: Phone Guard (`io.github.durkimat.phoneguard`, `controller/`) ships in the image as a privileged system app, is the only HOME, holds the SMS/dialer roles, and is device owner from first boot.

## Decision to confirm: how to sign it
Checked in the Android 14 source (frameworks/base/core/res/AndroidManifest.xml): almost every permission the hacks need (WRITE_SECURE_SETTINGS, FORCE_STOP_PACKAGES, BLUETOOTH_PRIVILEGED, MODIFY_PHONE_STATE, READ_PRIVILEGED_PHONE_STATE, MODIFY_AUDIO_ROUTING, CONTROL_INCALL_EXPERIENCE, INSTALL/DELETE_PACKAGES, REBOOT, STATUS_BAR, MANAGE_USERS, CHANGE_COMPONENT_ENABLED_STATE, GET_APP_OPS_STATS) is `signature|privileged`: a **privileged app on the allowlist gets them without the platform key**. Signature-only ones (SET_ACTIVITY_WATCHER, NETWORK_SETTINGS, MANAGE_ACTIVITY_TASKS) are not in the app's manifest today.
- **Recommended: priv-app signed with Phone Guard's own release key** (the one `controller/release.sh` already uses). Updates from HA (silent install as device owner) keep working with the same release flow; the platform key never leaves the build; the app does not run as uid system. Needs only a privapp-permissions allowlist.
- Alternative: platform-signed. Strictly more powerful (uid system, all signature perms) but every HA update must then be signed with the platform key, and a bug in the app is a system-uid bug.
The image build supports either (`certificate: "platform"` or `presigned`).

## Work items (in order)
1. **Module**: `build/phoneguard-app/` = Android.bp (`android_app_import`, privileged, `presigned` or `certificate: platform`, `dex_preopt` on), `privapp-permissions-phoneguard.xml` (allowlist of the privileged permissions above that the manifest actually requests; a missing entry = system_server boot loop, as with CarrierConfig), `default-permissions` (pre-grant runtime permissions, WRITE_SECURE_SETTINGS), `role` defaults (HOME, SMS, DIALER via config_defaultSms/Dialer overlays), APK staged from the release build like tt9.apk (not in git).
2. **No stock launcher / Messages / Dialer**: `PRODUCT_PACKAGES` removal of Trebuchet/Launcher3QuickStep, keep a recovery path (adb + the old launcher kept disabled-but-installed first, removed once the app is trusted).
3. **Device owner at first boot**: `dpm set-device-owner` equivalent via a one-shot provisioning (device_owner.xml in /data/system is not shippable; use a first-boot init service running `dpm set-device-owner io.github.durkimat.phoneguard/.AdminReceiver` before the setup wizard completes, and skip the setup wizard).
4. **Lock screen theme / white screen after unlock**: window background of the launcher theme; persistent+priority start.
5. **SMS and dialer roles**: Messages (AOSP) holds SMS today; Phone Guard has its own in-call UI (`.incall`): decide replace vs coexist after the app test.
6. Test each row of `docs/own-build-outcomes.md`; record results there.

## Prerequisites done
Own keys (docs/signing-keys.md), VoLTE/IMS/SMS/data working (ims7), boot fix, animation, TT9.
