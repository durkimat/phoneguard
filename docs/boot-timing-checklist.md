# Boot timing: what to collect (2026-11-13)

Symptom (owner, from memory): Qin logo, then the orange "unlocked" warning appears at once and stays about a minute, then the screen goes black, then a few seconds later the boot animation starts. Not yet measured.

Known from the original-firmware backup (`/archive/phone-backups/qin-j35-20261110/partitions/expdb.bin`, LK log with `[PROFILE]` lines): LK `boot_time` was 2.9 s on one boot and 7.6 s on another (includes the 5 s warning wait; 1st logo 0.8 s, kernel decompress 0.4 s). Those boots predate our images, so they do not explain the current delay.

## Owner (stopwatch, next boot)
Time from pressing power: (a) Qin logo appears, (b) warning appears, (c) screen goes black, (d) boot animation starts, (e) lock screen or launcher.
Also note whether the very first boot after a flash is slower than a later reboot.

## Phone, read-only (PC session, after boot)
1. `adb shell dmesg` saved in full, with the kernel timestamps. Note the time of: display/fb init, first-stage init mounting `super`, `init: starting service 'bootanim'`, `sys.boot_completed`. Kernel time 0 is when LK hands over.
2. `adb shell getprop | grep ro.boottime` (init stage times) and `adb logcat -b events -d | grep boot_progress`.
3. `adb shell ls /sys/fs/pstore /proc/last_kmsg` and save any content (previous-boot kernel log).
4. Look for long gaps in the dmesg timestamps (fsck or mount of userdata, dm-verity, FBE key setup, waiting on a device).
5. If it can be done with mtkclient: re-read `expdb` after the boot and take the latest LK `[PROFILE] boot_time`. This gives the bootloader time for that exact boot. (The preloader time is not in this log.)

## Reading the result
- Android's animation starts about 10 to 20 s after kernel time 0, so the extra time is before the kernel: look at LK and the preloader.
- The animation starts about a minute after kernel time 0, so the kernel or early init is slow: look at the gaps from step 4.
- Note: the warning staying on screen does not prove LK is still running. LK's last frame stays up until the kernel's display driver takes over.

## Result so far (2026-11-13)
Owner's stopwatch on the current (baseline + BT fix) image: Qin logo 1 s, unlocked warning 1.5 s, screen black 69 s, boot animation 80 s, login 101 s. The owner remembers no such wait on the Dumber firmware.

From the baseline warm-reboot log (`/archive/phone-backups/flash-run-20261112/boot2-after-reboot.log`): logd starts at about 9 s of kernel time, but `boot_progress_start` (zygote) is at 62 s. The log has a 47 s gap straight after TrebleDroid's `rw-system.sh` runs `resize2fs /dev/tmp-phh` (rw-system.sh lines 353-366: remount `/` rw, create `/dev/tmp-phh`, `resize2fs`, remount ro). That script is run by init synchronously (`exec - root -- /system/bin/rw-system.sh`, vndk.rc), so a slow step in it delays the whole boot, including SurfaceFlinger, so the bootloader frame stays on screen. The earlier bootloader (LK) logs show 3 to 8 s, so LK is not the problem.

Not yet confirmed: which command in rw-system.sh is slow. The PC session was asked (read-only) for `dmesg` `waiting took` lines, `ro.boottime.*` and boot_progress. If rw-system.sh is confirmed, apply `build/patches-pending/0008-treble-skip-system-resize-at-boot.patch` (disables that block), rebuild, and re-time.
