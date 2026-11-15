# Flash the image and first start (steps 4 and 5)

> **This erases the phone.** Keep it charged (above 50%) and do not unplug it or let the computer sleep during the flash.

## The easy way: the installer script

The `installer/` folder has a script that checks your computer and phone, makes a backup, verifies the image and runs the commands below for you, stopping at the first problem:

```bash
node pg-install.mjs all      # in the folder from step 1, where the image was downloaded; add --dry-run first to see the plan
```

Status: tested against simulated phones, and its read-only parts on a real J36. Its flash stage uses exactly the commands below but has not yet been run on a stock-unlocked phone, so keep these manual steps to hand. See [installer/README.md](../../installer/README.md).

## The manual way


Do these in a terminal in the folder that holds `system.img`. The same commands work on Windows, Linux and macOS. If `fastboot` is not found, the installer saved it in the `platform-tools` folder next to it: type `./platform-tools/fastboot` (Windows: `platform-tools\fastboot`).

1. Check the image ([step 3](get-the-image.md)) and that the phone is unlocked:

```bash
fastboot getvar unlocked                       # unlocked: yes
```

2. Put the phone in fastboot mode and then into "fastbootd", where the system partition can be written:

```bash
adb reboot bootloader
fastboot reboot fastboot
fastboot getvar is-userspace                   # is-userspace: yes
```

3. Make room for the image and write it. This takes several minutes; do not unplug:

```bash
SIZE=$(stat -c %s system.img)                  # macOS: stat -f %z system.img; Windows: use the file size in bytes
fastboot resize-logical-partition system_a $SIZE
fastboot flash system_a system.img
```

4. Erase the user data so the new system starts clean, then restart:

```bash
fastboot erase userdata
fastboot erase md_udc                          # this is the /metadata partition on this phone
fastboot reboot
```

`system_a` has room for about 2350 MiB with the phone's own `product` partition kept, which is more than today's image needs. If a future image is bigger, see the "shrink product" step in [flash-plan-baseline](../flash-plan-baseline.md).

**Updating later without losing the phone's data:** skip step 4. Run steps 2 and 3, then `fastboot reboot`. The kit's `flash-system-only.sh` does exactly that (it keeps the user data). The script `build/flash/flash-baseline.sh` in the repository is outdated: do not use it.

**Never write any other partition**, in particular not `preloader`, `lk`, `tee`, `md1img`, `nvram`, `nvdata`, `nvcfg`, `protect1`, `protect2`, `proinfo`, `sec1`, `seccfg` or `otp`. Writing the wrong one can lose the phone's IMEI and radio calibration for good. You do not need to write `vbmeta` either: an unlocked phone starts a modified system without it. For an extra safety net, read-only identity backups are described in the [recovery guide](../recovery-guide.md) (section 4).

## First start

The first start takes several minutes; be patient and do not interrupt it. The first start runs a one-off setup script that makes Phone Guard the device owner (the app in charge of the phone). You know it worked when you see the **Phone Guard status page** (it says "Device owner: true") and the Nokia-style home screen.

If it does not start, or stops at a menu, see [troubleshooting](troubleshooting.md). Flashing again is safe: repeat from step 2 above.

Next: [run the setup wizard](../family-setup.md).
