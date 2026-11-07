# Prototype test plan (J36 with DumberOS)

Run top to bottom. Safest first. Record each result in `../SPEC.md` section 7 or tell Claude.
Tick the box and note the log line.

## 0. Before you start
- [ ] Stock ROM backup exists (the install guide's `mtk rl` backup).
- [ ] Phone is **not** the one your daughter will use yet.
- [ ] `adb devices` shows the J36 and you have authorised the computer on the phone.
- [ ] Install the HA Companion app (minimal flavour) if you want the nudge test (step 9).
- [ ] The APK is already built: `app/build/outputs/apk/debug/app-debug.apk`. Install it with
  `adb install -t -r app/build/outputs/apk/debug/app-debug.apk` (run from `~/HA/phoneguard/prototype`).
  Only rebuild (`./gradlew assembleDebug`, see README) if the source changes.

## 1. Device owner
- [ ] `adb shell dumpsys account | head -40`: note any accounts. Remove them if present.
- [ ] `adb shell dpm set-device-owner io.github.durkimat.phoneguard.lab/.AdminReceiver`
- [ ] Open the app. Status shows `deviceOwner=true`.
- [ ] Press **Refresh status + dump restrictions**. Note: `restrictions ours` vs `effective`.
  The effective list may already contain items set by DumberOS's App Blocker (collision check).

## 2. Hide and unhide (answers: launcher behaviour, App Blocker coexistence)
- [ ] Select **Gallery (glimpse)**, press **Hide**. Log shows `ok=true` and a time in ms.
- [ ] Look at the DumberOS home/launcher: does the icon vanish? Does D-pad navigation still work?
- [ ] Open DumberOS Settings > App Blocker: does it show the app as hidden? Can it unhide it?
- [ ] **Unhide**. Icon returns.
- [ ] Try **Suspend** on the same app. How does the launcher show it? Compare with hide.
- [ ] Try to hide **Settings** or **Dialer**: the app must say `REFUSED` (protected).
- [ ] Tick **Quarantine newly installed apps**, install any APK (`adb install -r some.apk`) or an app from the app list, then read the log: `QUARANTINE hide ... in N ms` and the time between `PKG added` and `QUARANTINE`. Leave the app closed (swipe away from recents) and install again: does the quarantine still fire (process stays alive)?

## 3. Restrictions (answers: which hold on this build)
Tick one at a time, try to defeat it, untick.
- [ ] Factory reset: Settings > System > Reset: blocked? Power menu: is there a reset button?
- [ ] Safe boot: long-press power > Reboot to safe mode: blocked? (Do this one carefully, it reboots.)
- [ ] Date/time: Settings > Date & time: greyed out?
- [ ] Add user / managed profile / switch user: greyed out?
- [ ] Unknown sources: try installing an APK from the browser. Note whether App Blocker clears it later.
- [ ] VPN, private DNS, USB file transfer, apps control (force stop / clear data on another app), modify accounts.
- [ ] **Debugging features** last: it asks to confirm, auto-reverts in 3 minutes. Does `adb devices` still work during it?
- [ ] **Clear ALL our restrictions** when finished.

## 4. Grayscale (answers: permission route, re-assert)
- [ ] Press **Gray ON (direct write)** before granting: expect a FAILED log with SecurityException.
- [ ] Press **Try device-owner self-grant of WRITE_SECURE_SETTINGS**. Log shows `call=true/false` and `now=true/false`.
- [ ] If `now=false`: `adb shell pm grant io.github.durkimat.phoneguard.lab android.permission.WRITE_SECURE_SETTINGS`
- [ ] **Gray ON**: screen goes grey. **Gray OFF**.
- [ ] **Start re-assert watcher**, then switch Colour correction off in Settings. Log should show `RE-ASSERTED` and how fast. Does the screen flicker?

## 5. Background survival and power
- [ ] **Protect from force-stop**: then try Settings > Apps > Phone Guard Lab > Force stop (greyed?). Try Clear data.
- [ ] **Try device-owner self-grant of battery exemption**: note `exempt=true/false`. If false, try **Ask for battery exemption** (system prompt).
- [ ] Status shows `exactAlarm=true`? If not, press **Open exact-alarm permission screen**.
- [ ] Set heartbeat to 5, press **START heartbeat alarm chain**. Leave the phone on the charger, screen off, overnight.
  In the morning read the log: `HEARTBEAT late=Ns deviceIdle=...`. Late by minutes during Doze = a problem. Note the standby bucket in status.
- [ ] Reboot the phone: log shows `BOOT`, and the chain restarts.
- [ ] **Auto time ON** and **Auto time zone ON** via DPM: log lines, no FAILED.

## 6. Home app pin
- [ ] **Pin the current Home app**. Then try to change the default Home app in Settings: blocked or ignored? **Unpin** afterwards.

## 7. Camera and QR
- [ ] **Try device-owner self-grant of CAMERA**: expect `call=false` (Android 12+ blocks silent sensor grants). Note it.
- [ ] **Scan a QR code**, grant the prompt, scan a QR shown on your monitor. Does it read? How far / how bright?

## 8. Keystore
- [ ] **ECDSA P-256 sign/verify**: log shows `sign/verify=true level=TEE` (or SOFTWARE: note it).

## 9. Companion app nudge (needs the Companion app on the phone)
- [ ] In HA: Developer tools > Actions, `notify.mobile_app_<phone>` with the data shown in the app.
- [ ] Log shows `NUDGE action=... extras=[a=1, b=hello]`. How fast (seconds)? Also try with the screen off.
- [ ] Note `from=`. Expected to be empty: Android 14 only shares sender identity if the sender opts in.

## 10. Network (only when you have a URL to test; a few requests at most)
- [ ] POST 1 KB to any HTTPS endpoint you control: status, ms, whether the `X-PG-Lab` header arrives.
- [ ] Not yet: the Nabu Casa cloudhook reply and limits need a small HA test integration (ask first).

## Clean up
- [ ] **RELEASE device owner**, then `adb uninstall io.github.durkimat.phoneguard.lab`.
- [ ] Pull `lab.log` and send it over.
