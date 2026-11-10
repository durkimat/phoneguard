# Own-build viability and recovery plan (draft, 2026-11-10)

Direction (owner): build our own system image, accept an unlocked bootloader, offer it as DIY for tech-savvy parents or
as a supplied installed phone, optional small service charge for the management parts, no dependency on Dumber OS.
Hard constraint: one test device. No deliberate bricking. Recovery must be proven by research, backups and
read-only observation, not by breaking the phone.

## 1. What the Dumber repos show (read-only, 2026-11-10)

- No licence file or licence statement in any repo checked (dumberos_manifests, dumberos_build, device_owner,
  DumberOSUpdater, DumberOSPanel). With no licence the default is all rights reserved: we must not copy their code or
  patches without written permission. Using the same public upstreams (LineageOS, TrebleDroid/phh, AOSP) is separate.
- Their tree is large: default.xml pins LineageOS 21 on AOSP android-14.0.0_r67..r73 and mostly uses forks under a
  "DumberOS" GitHub org (android_* repos), plus their own device_phh_treble and hardware overlay forks. The patch
  repos named by their build script (lineage_patches_unified / lineage_build_unified) return 404, so their actual
  patches are not visible. Reproducing their image is therefore not possible from public material anyway.
- CORRECTION to earlier notes: DumbdroidAdmin is NOT a device owner. Its README says it does not register as device
  owner, profile owner or delegate. It is a platform-signed privileged system app (android.uid.system, Android.bp
  `privileged: true`) that hides apps and blocks app installs via DevicePolicyManager, and needs Android 14 policy APIs.
  Our controller is a real device owner; in our own image it could stay that way or become a privileged app.
- Updater: eu.dumbdroid.updater polls https://ota.dumbermini.com/<branch>/<variant>.json (fields build_timestamp,
  version, patchnotes, size, url), every 3 days, then downloads and reboots; a signature-warning dialog exists but the
  verification code was not read. So their OTA is a full system.img download from their server.
- Flashing is system-partition only (fastboot flash system); no vbmeta, boot or bootloader handling anywhere. That is
  the safest possible shape for us to copy in principle: vendor, boot, preloader and firmware are never touched.
- The phh wiki (archived Jan 2025) says the F21 Pro works with GSIs, notes fingerprint and NFC as unsupported there,
  keypad only partly working without copying the stock keylayout, and gives a super.img repack + SP Flash Tool method.
  Wiki pages "Bootloader unlock policies" and "Common Issues and fixes" were not read.

## 2. What we would depend on instead

- Upstream LineageOS 21 and TrebleDroid/phh trees (open source; check each licence when we start).
- Our own: controller app, hub, signing keys, OTA server, curated app source.
- Not usable from Dumber without asking: patches, updater, panel, admin app (no licence).
- Unknown and needing our own work: the device-specific tweaks they made (keylayout, hardware overlay, audio and
  Bluetooth fixes, NFC), and kernel/vendor quirks. Their closed patch set may hide real work.

## 3. Viability checklist

| # | Question | How to answer | Status |
|---|---|---|---|
| 1 | Exact SoC, board, vendor Android version, A/B + dynamic partitions, Treble flags | read-only `getprop` + `fastboot getvar` on our phone (section 5) | not done (phone not connected) |
| 2 | Bootloader state of our unit (locked/unlocked, OEM unlock) | `ro.boot.verifiedbootstate`, `ro.boot.flash.locked`, `fastboot getvar unlocked` | not done |
| 3 | Does a plain GSI boot and keep radio, keypad, audio, camera, BT, VoLTE working | needs a built image; defer until recovery proven | open |
| 4 | Can we obtain a known-good stock firmware package and verify its checksum | find Qin stock firmware, checksum, store offline | open |
| 5 | Can we back up the unit (all partitions incl. nvram/nvdata/protect) read-only | choose and rehearse method (section 4) | open |
| 6 | Licence review of upstream trees we use | read each licence | open |
| 7 | Build host works (Ubuntu container, 400 GB, 64 GB RAM) | set up new host; trial build of unmodified GSI | open |
| 8 | Security support story for stale vendor/kernel | compare vendor patch level with Android 14 baseline | open |
| 9 | Legal for supplied installed phones and a paid service | specialist advice (see bundled-hardware-research.md) | open |
| 10 | Own OTA server and signing key custody | design later | open |

## 4. Recovery strategy (research and rehearsal, no deliberate bricking)

Risk reduction by design:
- Only ever flash the system partition (as Dumber does). Never the preloader, bootloader, lk, tee, vbmeta without a
  proven need, nvram, nvdata or protect partitions.
- Keep the unit's own identity data (IMEI etc. on MediaTek lives in nvram/nvdata/protect) backed up per unit before
  the first flash. Not shareable between units.
- Prefer A/B slot behaviour: flash the inactive slot where the device supports it, so a bad image falls back.

Known-good states we must hold offline before any flash, each with checksum and stored off the build host:
1. Dumber's currently installed system image (what the phone runs now) if obtainable, or a read-back of it.
2. Qin stock firmware package (full, scatter-file based, flashable with SP Flash Tool) for the exact model.
3. Per-unit partition backup (at minimum nvram, nvdata, protect1/2, proinfo, persist, vbmeta*, boot*, super/system).

Recovery ladder to document and understand (to be rehearsed read-only where possible):
- Tier 1: boot to the other slot / fastbootd, reflash system.
- Tier 2: fastboot (bootloader or userspace) reflash of the known-good system image.
- Tier 3: MediaTek BROM/preloader mode with SP Flash Tool or mtkclient to restore partitions from the backup.
- Tier 4: stock firmware full flash, then restore nvram/protect from the per-unit backup.

Rehearsals that do not risk the unit (what "visible" means):
- Enter bootloader/fastboot with `adb reboot bootloader`, run `fastboot getvar all`, reboot. No writes.
- Check the BROM/preloader USB IDs by connecting the powered-off phone with the documented key combo and reading
  `lsusb` (MediaTek BROM typically 0e8d:0003, preloader 0e8d:2000). No writes.
- Read-only partition read-back with mtkclient, verified by checksums of a second read, only after the owner agrees
  and after reading that tool's docs for this SoC (it uses BROM exploits on some chips; confirm it can't write).
- Boot-test a candidate image with `fastboot boot` where supported (boot image only), never by flashing.
- Do not rehearse a wipe or a flash of the unit's only data until a second unit exists.

Open recovery questions: whether this SoC enforces anti-rollback; whether fastbootd `fetch` can read back
partitions; whether the preloader requires an authenticated download agent; whether stock firmware for this exact
model is obtainable and which version matches the current vendor firmware.

## 5. Read-only evidence to collect when the phone is connected

```
adb devices -l
adb shell getprop | sort            # keep full output
adb shell getprop ro.boot.verifiedbootstate; adb shell getprop ro.boot.flash.locked
adb shell getprop ro.boot.slot_suffix; adb shell getprop ro.boot.dynamic_partitions
adb shell getprop ro.treble.enabled; adb shell getprop ro.vndk.version; adb shell getprop ro.board.platform
adb shell getprop ro.vendor.build.fingerprint; adb shell getprop ro.vendor.build.security_patch
adb reboot bootloader; fastboot getvar all; fastboot reboot
```
Nothing above writes to the device.

## 6. Test-device policy

One device only. Until a second unit is available we do not flash experimental images to it. Work that can proceed
without risk: host setup, trial builds, reading, and the read-only checks above.

## 7. Evidence from our phone (read-only, 2026-11-10)

Device: Qin J35-class (product AGN_5337BQ_J35_E1_MV3224), running Dumber OS build `eng.michal.20261102.113851`.
Nothing written to the phone during these checks (one reboot to fastboot and back; the Phone Guard app update
was a separate, authorised step).

| Fact | Value | Why it matters |
|---|---|---|
| SoC | MediaTek MT6761 (Helio A22), eMMC storage | BROM-mode tools (mtkclient, SP Flash Tool) apply; known low-end chip |
| Vendor firmware | Qin stock vendor, **Android 12** (fingerprint ...:12/SP1A.210812.016), vendor patch 2024-01-05, VNDK 31 | Android 14 system image runs over an Android 12 vendor. Vendor/kernel are two years behind: weak security-support story. "31" in Dumber's build target matches VNDK 31 (inference) |
| Partitioning | A/B, dynamic partitions (super), Treble enabled | Matches what GSIs need |
| Bootloader | LK, `fastboot getvar unlocked` = **yes**, `secure` = no, build 2026-08-04 | Unlocked, as expected. Android properties are misleading (`ro.boot.flash.locked=1`, verifiedbootstate green, vbmeta device_state unlocked), so trust fastboot, not getprop |
| Unlock ability | `get_unlock_ability` = 0 | OEM-unlock toggle off (already unlocked; relock possibly changes this) |
| Slots | current a; a successful, b never booted (retry 7) | Slot B holds no tried image: no proven fallback slot yet |
| fastboot mode | bootloader fastboot, `is-userspace: no`; USB ID 0e8d:201c | fastbootd (userspace) can flash logical partitions; max-download 128 MB |
| Serial | 0123456789ABCDEF (generic placeholder) | Not a usable per-unit identity |
| Partitions seen | preloader_a/b on mmcblk0boot0/1, lk, tee, scp, sspm, spmfw, gz, md1img, boot/init_boot/vendor_boot, dtbo, vbmeta x3, super (5 GiB), plus nvram, nvdata, nvcfg, protect1/2, proinfo, seccfg, sec1, otp, expdb, frp, para, boot_para, flashinfo, logo, metadata, md_udc, userdata | The per-unit identity data to back up: nvram, nvdata, nvcfg, protect1, protect2, proinfo, sec1, seccfg (holds MediaTek unlock state), otp, md1img |

Things now known about our recovery position:
- Unlocked fastboot works and shows full variables, so a bad system image is recoverable by `fastboot flash`
  of a known-good system while the bootloader and preloader stay intact.
- Slot B has never booted, so we cannot count on it as a fallback until it is populated and proven.
- We still lack: the exact stock firmware package for J35 (Android 12 vendor), a verified read-back of every
  identity partition, and confirmation of how this chip's preloader accepts BROM connections.

Checklist update: items 1 and 2 done; item 8 shows a real weakness (vendor and kernel patch level 2024-01).

Next read-only steps (need owner OK for the tool use): confirm mtkclient's read-only mode and BROM USB ID with the
phone off (key combo documented for this model), then take a full read-back of the identity partitions and verify
by reading twice.
