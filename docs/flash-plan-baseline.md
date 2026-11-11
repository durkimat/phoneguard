# Flash plan: baseline Lineage 21 GSI on the Qin J36/J35 (draft, 2026-11-11)

Status: PLAN ONLY. Nothing has been flashed. Every write below needs the owner's go at that step. Commands marked (UNTESTED) have never been run on this phone.
Phone: MT6761, bootloader unlocked, stock vendor, A/B + dynamic partitions (`super` 5 GiB). It is plugged into the OLD PC (adb, fastboot, mtkclient live there).

## 0. What gets written, and what never does
Written: `super` logical partitions `system_a` (our image) and `product_a` (shrunk, emptied), then userdata and metadata are wiped.
Never touched: preloader, lk, tee, md1img (modem), nvram, nvdata, nvcfg, protect1/2, proinfo, sec1, seccfg, otp, boot*/vendor_boot*/dtbo*/vbmeta* (Dumber also leaves these alone), vendor_a.
Assumption to verify on first boot: with the bootloader unlocked, a modified system boots without re-signing (Dumber's flash is system-only and works). Unverified for product_a changes.

## 1. Files (all verified; copy them to the old PC and re-check checksums there)
| File | Size / checksum | Where |
|---|---|---|
| Our baseline system.img | 2,511,581,184 B, sha256 e1a736d24693be7161d8ff50c6792c9361164548e72446dd9dfdba021a35aae3 | /artifacts/lineage-21-20261111-arm64_bvN/ |
| Dumber system_a (current phone state) | sha256 0615df75de0713b05b7ac1a600fdfc95ff50073231d9fe8d05db68ea70052083 | ~/phone-backups/qin-j35-20261110/extracted/ |
| Dumber-era product_a | sha256 fb934ead...1a56 (identical to Qin stock product_a) | same folder |
| Whole super as it is now | 5 GiB, sha256 f97fb7a0dbfc9fd1cffbc6a1a910f042a1d36e384d01c99f3ff8b88c00b17a86 | ~/phone-backups/qin-j35-20261110/partitions/super.bin |
| Original Qin system_a | sha256 aabb1148...5283 | /archive/phone-backups/qin-j35-original-firmware/extracted/ |
| Identity partitions | verified 2026-11-11 | ~/phone-backups/qin-j35-20261110/identity/ |
Also create now: an empty 64 MiB ext4 product image: `mke2fs -t ext4 -L product -d <empty dir> product_empty.img 64M` (mke2fs exists on the workstation).

## 2. The size problem and the fix
Group main_a = 5118 MiB; vendor_a 395 MiB; product_a 2371.5 MiB; our system needs 2395.2 MiB (613,179 blocks of 4096). With product_a kept, system_a can only reach 2352 MiB.
Fix: shrink product_a to 64 MiB and flash an empty ext4 into it (keeps a valid /product mount; the stock Google apps in it are not needed). That leaves ~2264 MiB of free room in the group.
Slot B is empty (vbmeta_b, boot_b, dtbo_b all zero; vendor_b/product_b have no extents), so there is NO slot-switch fallback.

## 3. Before the first write (owner decisions and checks)
1. **Your data (owner chose: take it, 2026-11-11).** Flashing wipes userdata (apps, Phone Guard state, WhatsApp bridge session, photos). It was NOT in the backups.
   - "23 GB" is the SIZE of the userdata partition (23.27 GiB, read from gpt.bin), not what is stored in it. BACKLOG.md noted about 21 GB free, so roughly 2 GB is used. A raw read still copies the whole partition (empty blocks are mostly zeros, so it compresses and can be stored sparse/compressed on btrfs).
   - **First find out what the used space is (read-only, on the old PC with the phone booted):**
     `adb shell df -h /data` and `adb shell dumpsys diskstats` (app, data and cache sizes per package). If `adb root` works (the Dumber image is userdebug): `adb root; adb shell du -h -d1 /data 2>/dev/null | sort -h | tail` and `adb shell du -h -d1 /data/media/0 /data/data /data/app`. Without root, `dumpsys diskstats` and `adb shell pm list packages -3` still show it. Under file-based encryption names inside user directories are encrypted, so a raw dump cannot tell us what fills the space; asking the running phone can.
   - **Then dump:** `mtk r userdata userdata.bin` and a fresh `mtk r metadata metadata_now.bin` with mtkclient in preloader mode, verified by reading twice (UNTESTED at this size; allow 1-2 hours). Dump userdata and metadata at the same moment, with the phone idle, so they match. The files hold private data: keep read-only on /archive, encrypt before any copy leaves the NAS.
2. Battery above 50%, good cable, direct USB port (the front port worked).
3. `adb shell getprop ro.boot.slot_suffix` should be `_a`. Save `fastboot getvar all` output to a file as the "before" state.
4. Re-verify sha256 of every file above on the old PC.
5. Know the recovery ladder (docs/recovery-guide.md): fastboot works while the bootloader is intact, mtkclient preloader mode otherwise.

## 4. Flash (fastbootd; UNTESTED)
```
adb reboot bootloader
fastboot getvar unlocked                      # expect yes
fastboot reboot fastboot                      # userspace fastbootd (needed for logical partitions)
fastboot getvar is-userspace                  # expect yes
fastboot getvar super-partition-name
fastboot resize-logical-partition product_a 67108864
fastboot flash product_a product_empty.img
fastboot resize-logical-partition system_a 2511581184
fastboot flash system_a system.img            # raw 2.4 GB, fastboot splits it (max-download 128 MB)
fastboot erase userdata
fastboot erase metadata
fastboot reboot
```
Check after each command that it said OKAY. If `resize-logical-partition` or the flash refuses, STOP and go to section 7; do not improvise with other partitions.
First boot can take several minutes. adb: the build is userdebug with insecure adb; if the screen/keypad is dead, `adb devices` and `adb logcat` still work if USB debugging came up.

## 5. What to check on first boot (log everything: `adb logcat -b all -d > boot.log`, `adb shell dumpsys telephony.registry`, `getprop`)
1. Boots to the launcher; keypad keys work (Back/Home/Call/End, digits, D-pad); `getevent -p` shows which device and keylayout the keypad uses.
2. SIM (Smarty): registered on the network? mobile data (APN from Lineage's merged list) works?
3. VoLTE call: expected to FAIL without the MediaTek IMS pieces. Look at telephony.registry for IMS state and logcat for ImsService/ims/mtkims errors.
4. Wi-Fi, Bluetooth pairing, audio (speaker, earpiece, headset), battery and charging indicator, display brightness, torch/camera.
5. Anything crashing in a loop (the stock NFC package is a known ANR source; there is no NFC chip).
Results go into docs and decide what the IMS port needs.

## 6. Going back to exactly where the phone is now
Exact state = Dumber image + stock product/vendor + the phone's own data. Pick one:
- **R1, byte-exact (recommended if you took the data dumps).** mtkclient preloader mode: write back `super` from the 10 Nov super.bin (sha256 f97fb7a0...), then `userdata` and `metadata` from the dumps taken in step 3.1: `mtk w super super.bin`, `mtk w userdata userdata.bin`, `mtk w metadata metadata_now.bin` (UNTESTED; super is 5 GiB, slow). This restores the partition table, Dumber's system, the stock product and the original data together. Write userdata and metadata as a pair or the encryption keys will not match.
- **R2, quick, functional.** fastbootd: `fastboot resize-logical-partition product_a 2486730752`, `fastboot flash product_a product_a.img` (the extracted stock one), `fastboot resize-logical-partition system_a 2173976576`, `fastboot flash system_a system_a.img` (Dumber's), then wipe userdata/metadata. Everything works as before except the old data is gone, and logical partition placement inside super differs slightly.
- **R3, original Qin stock.** Same as R2 with the original system_a (sha256 aabb1148..., 1,821,229,056 bytes).
Phone Guard reinstall after any wipe: provision again as device owner (Provisioning steps in the app docs).

## 7. If something goes wrong
- Boots to fastboot only / bootloop: `fastboot reboot fastboot`, redo R2 (needs no data) to get back a working phone.
- Fastboot unreachable but the preloader window works: R1 with mtkclient (retry until it catches the 2-3 s window, as in the recovery guide).
- Identity or radio problems (no IMEI): restore the identity partitions from ~/phone-backups/qin-j35-20261110/identity/ (recovery-guide section 5.4). Our flash never writes them, so this is only for the unexpected.
- No USB at all: stop and read recovery-guide section 5.5. Do not experiment.

## 8. Ready-made kits (prepared 2026-11-11)
- Flash kit: /artifacts/flash-baseline-20261111/ (system.img, product_empty.img, SHA256SUMS, flash-baseline.sh). It is a build output, so /artifacts is the right place. Script source: build/flash/flash-baseline.sh (checks checksums, requires unlocked bootloader, asks before each write, stops on the first error).
- Restore kit (quick return, R2): /archive/phone-backups/restore-kit-dumber-20261110/ (Dumber system_a.img, stock product_a.img, SHA256SUMS-restore, restore-quick.sh). Copy of backup data, so it lives with the backups, not in /artifacts.
- For R1 (byte-exact) the files are super.bin in the 10 Nov partitions folder plus the userdata/metadata dumps to be taken.

## 9. SIM timing (owner: the phone reboots when a SIM is inserted, so power off first, every time)
Current phone has NO SIM. Order, with the owner told at each SIM step:
1. (Optional, recommended) Reference test on the CURRENT Dumber image: power off, insert the owner's SIM, boot, wait for registration, then collect read-only VoLTE evidence via the PC session (`dumpsys telephony.registry`, `getprop | grep -i ims`, a short logcat of IMS, one test call). This records what "working" looks like on this vendor before anything changes. Then power off and take the SIM out.
2. Userdata and metadata dumps (phone off, mtkclient preloader mode): SIM not needed, leave it out.
3. Flash, then first boot WITHOUT a SIM: check boot, keypad, Wi-Fi, Bluetooth, audio, display, charging.
4. Power off, insert the SIM, boot: registration, mobile data, VoLTE call (expected to fail until the IMS pieces are in), SMS.
5. After any later SIM change, power off first.
Remove the SIM PIN before the tests if one is set (a PIN prompt blocks the keypad test).
