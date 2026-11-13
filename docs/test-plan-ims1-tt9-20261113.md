# Flash and test plan: IMS round 1 + boot animation + boot-delay fix + TT9 (2026-11-13)

For the PC session (the one with adb/fastboot/mtkclient on the phone). The owner swaps over to test. Nothing is written to the phone until the owner types "yes" in the PC session's own chat.

## What the image contains (new since the last flash)
- IMS round 1 (MediaTek IMS apps, jars, permissions) - first time on a phone.
- Custom boot animation (`/product/media/bootanimation.zip`, type `f`, fades out when boot completes).
- Boot-delay fix: TrebleDroid's `rw-system.sh` no longer remounts and resizes the system filesystem at every boot (that step blocked init for about 44 s). The script also logs timing markers with tag `pgtime`.
- TT9 (Traditional T9 keyboard, io.github.sspanak.tt9 v64.0) as a system app.
- Same as before: Bluetooth sysbta fix (patch 0005), userdebug, test-keys.

## Kit
NAS 192.168.1.138, `/artifacts/flash-system-ims1-tt9-20261113/`: `system.img` (2,546,946,048 bytes), `flash-system-only.sh`, `SHA256SUMS`.
system.img sha256: `25a61559ba0739e175d94ca5bc701b85ff2dc62a6611688bd5ce090fbb0c700d`
Copy the directory to the PC the same way as the earlier kits. The image contains Qin's proprietary files: keep it out of git and GitHub.

## Phase A: flash (owner present, SIM OUT, phone on and in the normal state)
1. Prechecks (read-only): `adb devices`, battery above 50% (`adb shell dumpsys battery`), slot `_a` (`adb shell getprop ro.boot.slot_suffix`), SIM absent. Record `adb shell getprop ro.build.fingerprint` (the old image).
2. `sha256sum -c SHA256SUMS` in the kit directory.
3. Ask the owner for an explicit "yes" for: replace `system_a` only, resized to 2,546,946,048 bytes, userdata kept.
4. Run `./flash-system-only.sh` in a terminal. It asks "yes" itself. Never wrap it in `timeout`; the write takes several minutes. It reboots at the end.
5. If anything fails: stop and report. Do not improvise. Fallbacks, in order: re-run the script; flash the known-good image `/artifacts/flash-system-20261112/` (its own script); restore kit `/archive/phone-backups/restore-kit-dumber-20261110/`.

## Phase B: first boot (do NOT time it)
After a system change the first boot recompiles apps and is slow (several minutes). Wait for `adb devices` and `sys.boot_completed=1`. Save to the NAS folder `/archive/phone-backups/flash-run-20261113/`:
- `adb shell getprop ro.build.fingerprint` (should differ from the old one; build date 2026-11-13).
- `adb logcat -b all -d > boot1.txt` and `adb logcat -b crash -d > crash1.txt`.
- The new image's own checks, read-only:
  - `adb shell pm list packages | grep -E 'mediatek|ims|tt9|sspanak'` (expect ImsService, MtkGbaService, MtkTelephonyAssist, MtkCapCtrl, mediatek-res, io.github.sspanak.tt9).
  - `adb shell 'echo $BOOTCLASSPATH' | tr ':' '\n' | grep mediatek` (the eight mediatek jars should be listed).
  - `adb shell ps -A | grep -iE 'ims|mtk'`.
  - `grep -E 'FATAL|AndroidRuntime|NoSuchMethod|ClassNotFound|NoClassDefFound|avc:.*denied.*(ims|mtk|mediatek)' boot1.txt` and `crash1.txt`: list what appears (counts and first lines).
  - `adb shell ime list -a` (TT9 should be listed).
  - `adb shell wm size` and `adb shell wm density` (the real screen size; the boot animation was drawn at 480x640).
Report whether it booted at all, and any crash lines. If it does not boot within about 10 minutes, hold the power key 10 s, retry once, then go to the fallbacks above.

## Phase C: timed reboot (owner with a stopwatch)
Reboot with `adb reboot` (second boot, no longer recompiling). Owner starts the stopwatch at power-on and calls out times for: Qin logo, unlocked warning, screen goes black, boot animation starts, boot animation fades out, login screen. Also ask the owner to look at the animation: is it centred, the right size, does it fade smoothly when the phone is ready?
Then collect: `logcat -b all -d > boot2.txt`, and these lines: `grep pgtime boot2.txt` (timestamps of the rw-system.sh checkpoints), `grep -E 'boot_progress|BootAnimation' boot2.txt`, `adb shell getprop | grep ro.boottime`.
Expected: before the fix `boot_progress_start` was about 61 s and the animation started at 73 s; now it should be near 10-20 s. If there is still a long gap, the pgtime lines show which stretch of rw-system.sh takes the time: report the biggest gap between consecutive pgtime lines (label and seconds). Keep the existing dmesg limits: do not enable Rooted debugging or change settings without the owner's say.

## Phase D: SIM stage (only if Phase B/C look sane; owner present)
1. Power the phone completely OFF first (it reboots on SIM insert). Owner inserts the Smarty SIM (no PIN) and powers on.
2. Wait for boot. Collect `adb logcat -b radio -d > radio.txt` and `adb shell dumpsys telephony.registry`.
3. Registration: `adb shell getprop gsm.operator.alpha gsm.sim.state gsm.network.type`; mobile data state; `adb shell dumpsys telephony_ims` (or `dumpsys ims`) if available: is ImsService bound? Is there an IMS registration? Look in radio.txt and boot logs for "ImsService", "ims registered", "VoLTE", "timeout waiting for connection" (this was the old failure, 1473 times).
4. Voice (owner): place a call to another phone and listen on the earpiece; receive a call; report if the call connects and whether audio is heard both ways. Note any call-failure message.
5. SMS (owner): send one to the PC-side or another phone and receive one back.
6. Mobile data (Wi-Fi OFF): `adb shell svc wifi disable` only if the owner agrees, then check `ping`/`curl` or the browser. The earlier failure was `AT+EAPNACT` cause 16F1 for APN `mob.asm.net`. Report any change, and the APN list shown in Settings.
7. Wi-Fi calling: owner checks whether a Wi-Fi calling entry now appears in Settings, Network, SIM.
Keep each step short; the SIM is needed back in the owner's phone. Save everything to `/archive/phone-backups/flash-run-20261113/` and send a summary.

## What to send back
A short summary: booted or not, any crash lines, the pgtime biggest gap, the stopwatch times, the real screen size, how the animation looked, and the SIM results step by step with logs saved. Do not change phone settings, do not wipe, do not flash anything else.
