# Recovery guide: Qin J35-class phone (MediaTek MT6761), Dumber OS / own GSI (draft, 2026-11-10)

Status of each step is marked TESTED (done on our unit, read-only) or UNTESTED (written from tool docs, never run).
Nothing in section 5 (writing) has been run. Do not rehearse writes on the only device.

## 1. Our unit and what we hold

- SoC MT6761 (Helio A22), eMMC 30 GB, A/B + dynamic partitions (`super` 5 GiB), kernel 4.19.191, stock vendor Android 12
  (Qin AGN_5337BQ_J35_E1_MV3224), bootloader (LK) UNLOCKED, secure boot / SLA / DAA all disabled.
- Backup (read-only reads, TESTED): /home/matthew/phone-backups/qin-j35-20261110/ (ask the owner for the cloud copy)
  - partitions/ every partition except userdata (+ gpt.bin, gpt_backup.bin, hwparam.json), partitions.sha256
  - identity/ nvram nvdata nvcfg protect1 protect2 proinfo sec1 seccfg otp (read twice, identical), identity.sha256
  - preloader/preloader_boot1.bin (eMMC boot1 area, 4 MiB), preloader.sha256
  - Verify before use: `sha256sum -c` in each folder.
- The backup holds this unit's IMEI and calibration data. Encrypt it before it leaves the machine, e.g.
  `7z a -mhe=on -p backup.7z qin-j35-20261110` (7-Zip), or `tar c qin-j35-20261110 | gpg -c > backup.tar.gpg`.
- Not in the backup: userdata (private data, 23 GB), RPMB, preloader copy in boot2.
- The original factory firmware package from the owner's earlier backup is not located yet; keep it with this set once found.

## 2. Golden rules

1. Only ever write `super` (system/vendor/product) or `boot`-family images, one slot at a time, and only after a full backup.
2. Never write: preloader (boot1/boot2), lk_a/b, tee_a/b, md1img (modem), nvram, nvdata, nvcfg, protect1/2, proinfo, sec1,
   seccfg, otp. Losing nvram/protect/proinfo can lose the IMEI and radio calibration; the identity backup is the only copy.
3. Prefer the inactive slot (B). Slot B has never booted, so it is NOT a proven fallback yet.
4. Keep the phone charged (battery above 50%) and the cable steady for any write.
5. One change at a time; note the date, file name and checksum of what was written.

## 3. What each state looks like from the PC (all TESTED observations)

| State | USB ID on PC | Seen how |
|---|---|---|
| Android running | 0e8d:201c (adb) | normal |
| Fastboot (LK) | 0e8d:201c, `fastboot devices` lists it | `adb reboot bootloader` |
| Preloader window | 0e8d:2000 "MT65xx Preloader" | phone off, plug USB, no key; lasts about 2-3 s |
| Off-charge screen | 0e8d:2046 | after the preloader window passes |
| Recovery | 18d1:d001, adb "unauthorized" | LK boot menu (hold a volume key at power on): recovery works only as a menu |

The LK boot menu (recovery / fastboot / normal) appears when a volume key is held at power-on.

## 4. Safe things to run (no writes)

```
adb shell getprop ro.boot.verifiedbootstate     # misleading; trust fastboot instead
adb reboot bootloader; fastboot getvar all; fastboot getvar unlocked; fastboot reboot
```
Read partitions over preloader mode (TESTED, read-only), using mtkclient (pinned commit 118a000, isolated venv):
```
git clone https://github.com/bkerler/mtkclient.git && cd mtkclient && git checkout 118a000
python3 -m venv ../venv && ../venv/bin/pip install pyusb pycryptodome pycryptodomex colorama pyserial capstone mfusepy keystone-engine unicorn
../venv/bin/python mtk.py printgpt            # start it first, THEN power off, unplug, replug the phone (no key)
../venv/bin/python mtk.py r nvram nvram.bin   # read one partition
../venv/bin/python mtk.py rl outdir --skip userdata   # read everything but userdata
```
Tips from our runs: start the tool first, then plug the phone in while it is off; retry until it catches the 2-3 second
window (about one try in two); wrap long reads in a retry loop that keeps a result only if complete; the front
USB-C port worked.

## 5. Recovery ladder (writing; UNTESTED, last resort first checked)

Decide by symptom:

1. Phone boots but misbehaves (own image): boot to the LK menu, `fastboot set_active a`, reboot (switch back to the old slot).
2. Boots to fastboot but not Android: flash a known-good system with fastboot. Logical partitions need fastbootd:
   `fastboot reboot fastboot` then `fastboot flash system <known-good system.img>`; `fastboot erase userdata` only if told to.
   This is the method Dumber's own guide uses (UNTESTED here).
3. Fastboot does not work but the preloader window does (bootloop, blank screen): write partitions back with mtkclient in
   preloader mode, from the backup: `mtk w super super.bin` (about 5 GB, slow) and if boot images were changed
   `mtk w boot_a boot_a.bin` (and `init_boot_a`, `vendor_boot_a`, `dtbo_a`, `vbmeta_a`) (UNTESTED). Write one slot only.
4. IMEI or radio broken after a bad flash: restore nvram, nvdata, nvcfg, protect1, protect2, proinfo, sec1 from identity/ with
   `mtk w <name> <file>` (UNTESTED). Never restore these from another unit.
5. No USB at all, not even the 2-3 s preloader window: the preloader may be damaged. Do not experiment; restore preloader
   from preloader_boot1.bin using the boot1 area (`mtk w preloader preloader_boot1.bin --parttype boot1`) only if the tool
   documents it for this chip, and ideally on a second unit first. Test points / BROM key combo for this model are unknown.

Before any step: re-verify checksums of the files you will write.

## 6. Open points

- Locate the owner's original factory firmware (Android 12) and its scatter file; test that SP Flash Tool or mtkclient can write it.
- Rehearse a write on a second, sacrificial unit before ever writing to the daily phone.
- Confirm whether fastboot `fetch` can read partitions without the preloader window (convenience, not required).
- Find the model's low-level key combo / test point and add it here.
- Slot B: populate and boot-test it to make it a real fallback.
