# Phone Guard installer

One script that checks your computer and phone, backs up what it can, verifies the image and flashes it, stopping at the first problem. It works on Windows, Linux and macOS with Node 20 or newer, and uses Google's `adb` and `fastboot`.

```bash
node pg-install.mjs              # everything, asking before it writes
node pg-install.mjs check        # only check the computer and phone (changes nothing)
node pg-install.mjs backup       # only the backup (reads from the phone)
node pg-install.mjs download --kit <folder>     # fetch the newest published image, unpack and check it (no phone needed)
node pg-install.mjs all --download --kit <folder>   # download if needed, then everything below
node pg-install.mjs verify --kit <folder>
node pg-install.mjs flash --kit <folder> [--keep-data]
node pg-install.mjs all --dry-run --kit <folder>      # show what it would do, change nothing
```

Run it from, or point `--kit` at, the folder that holds `system.img` and `SHA256SUMS`. If a `platform-tools` folder is next to the kit it uses that; otherwise `adb` and `fastboot` must be on your PATH.

## What it does

1. **Checks:** adb and fastboot present; exactly one phone connected; it is a Qin J36; battery at least 50%; the bootloader unlocked (in fastboot mode).
2. **Backup** (reads only; the phone is restarted into fastboot and back): `getprop`, the package list, battery state and `fastboot getvar all` into a dated folder with a checksum list. If the kit has a `proprietary-manifest.json`, it also copies those stock files off the phone. It reminds you about the deeper IMEI and radio backup with mtkclient (docs/recovery-guide.md section 4), which stays a guided, optional step because it needs a special phone mode.
3. **Gets and verifies the image:** with `--download` it finds the newest GitHub release whose tag starts with `image-`, downloads `system.img.gz` and `SHA256SUMS`, unpacks it (checking free disk space first) and verifies `system.img` against `SHA256SUMS`. A wrong checksum stops everything.
4. **Flashes**: restarts into fastbootd, makes room, writes `system_a`, erases `userdata` and `md_udc`, restarts. `--keep-data` skips the two erases (an update). It asks `yes` before writing, checks each step, and stops before any erase if the image write fails.
5. Leaves `install.log` and the backup in `~/phoneguard-install/<date>/` (or `--backup-dir`).

## Safety rules it keeps

- It writes only `system_a`, `userdata` and `md_udc`. Never the bootloader, radio, identity or security partitions. A test checks this on every run.
- Real `adb`/`fastboot` calls have time limits, so it cannot hang waiting for a phone that is in the wrong mode.
- It never continues when a check fails. `--yes` skips the questions but not the checks.

## Status

Tested: 29 checks against fake `adb` and `fastboot` programs (every stage, every stop condition, the exact commands and the allowed partitions), and the read-only parts (checks, backup, dry run) on a real J36. **Not yet run:** the real flash path on a stock-unlocked phone; it issues the same commands as our flash kits, but treat the first real use with care and keep the manual commands in docs/install/flash.md to hand.
