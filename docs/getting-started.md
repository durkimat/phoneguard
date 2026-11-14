# Start here: from a stock Qin J36 to a managed phone

This is the first page to read. It takes you from a phone straight out of the box to the point where the setup wizard takes over. The wizard itself is in the [family setup guide](family-setup.md).

> **Status, in plain words.** The image is not publicly downloadable yet (it contains vendor files whose redistribution is being checked, see [licensing](licensing.md)), so step 3 below is "ask the project owner for the image, or build it yourself". The flashing steps are the ones we use on our own phone and have run many times, but they are written for people comfortable with a command line. The bootloader-unlock steps are the standard Android ones and have been confirmed by the project owner on the J36; we have not yet written them up from a fresh, locked phone with screenshots. A packaged "flash it for me" tool is planned, not built.

## What you are about to do

1. **Unlock the bootloader** (a normal Android developer setting). This erases the phone.
2. **Flash the Phone Guard image** with a USB cable and `fastboot`. This erases the phone again.
3. **Start it up.** On first start the phone sets itself up with Phone Guard in charge.
4. **Choose how it is managed:** a Family phone (managed from your own phone through a free Cloudflare account) or, later, an Open phone with nothing locked.

Everything on the phone is erased in steps 1 and 2: contacts, photos, apps, accounts. Back up anything you want first. **Flashing can also lose the phone's IMEI and radio calibration if the wrong partitions are written**, so only ever use the scripts and commands below, which write the system image and nothing else, and read [the recovery guide](recovery-guide.md) (sections 1 and 2) before you start.

## What you need

* **The phone:** a Qin J36 with at least half a battery, and a USB-C cable that carries data (some charge-only cables do not).
* **A computer** (Windows, Linux or macOS) with **adb and fastboot** from Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools). Unzip them somewhere and open a terminal there. On Windows you may also need a USB driver for fastboot mode (Google's USB driver or the one your phone's maker supplies); if `fastboot devices` shows nothing while the phone is in fastboot mode, that is the cause.
* **The Phone Guard image and flash script** (see step 3).
* About an hour, most of it waiting.

## Step 1: unlock the bootloader

The bootloader is the part of the phone that decides what software may start. A locked one refuses a different system.

1. On the phone open **Settings, About phone** and tap **Build number** seven times until it says you are a developer.
2. Open **Settings, System, Developer options** (the wording can differ a little). Switch on **OEM unlocking** and **USB debugging**. If OEM unlocking is greyed out, connect to Wi-Fi, wait a minute and try again, and make sure the phone is not locked to a carrier.
3. Plug the phone into the computer. Allow USB debugging when the phone asks.
4. In the terminal on the computer:

```bash
adb reboot bootloader
fastboot devices                # the phone should be listed
fastboot flashing unlock
```

5. Confirm on the phone's screen when it asks (usually volume keys to choose, power to confirm). **The phone erases itself** and restarts.
6. Check it worked:

```bash
adb reboot bootloader
fastboot getvar unlocked        # should say: unlocked: yes
```

An unlocked phone shows a warning screen at start-up. That is normal.

## Step 2 (recommended): keep a copy of the phone's identity

The phone's IMEI and radio calibration live in partitions that a flash does not touch if you follow this guide, but a mistake elsewhere can lose them, and there is no other copy. If you are comfortable with it, read-only backups with `mtkclient` are described in [the recovery guide](recovery-guide.md) (section 4) and take about half an hour. Skip this only if you accept that risk.

## Step 3: get the image

The image is a single file, `system.img`, plus a small flash script and a checksum list (`SHA256SUMS`).

* **Today:** ask the project owner for the image kit, or build the image yourself from this repository: the recipe is in `build/` and [own-build-viability](own-build-viability.md). Building needs a Linux machine with about 300 GB of free disk and several hours.
* **Later:** when the licensing question is settled, a download link will be here.

Whichever way you get it, check it before you flash. In the folder with the files:

```bash
sha256sum -c SHA256SUMS         # every line should say OK (on macOS: shasum -a 256 -c SHA256SUMS)
```

## Step 4: flash it

Put the phone in fastboot mode (`adb reboot bootloader`) and run these commands from the folder with `system.img`. Check that every command says `OKAY`; if one refuses, stop and read the recovery guide.

```bash
fastboot getvar unlocked                          # must say: unlocked: yes
fastboot reboot fastboot                          # into "fastbootd", where system can be written
fastboot getvar is-userspace                      # must say: is-userspace: yes
fastboot resize-logical-partition system_a <size of system.img in bytes>
fastboot flash system_a system.img                # takes several minutes; do not unplug
fastboot erase userdata
fastboot erase md_udc                             # this phone's /metadata partition (on this phone `fastboot -w` does nothing, and /metadata is `md_udc`)
fastboot reboot
```

The size of `system.img` in bytes is `stat -c %s system.img` on Linux, `stat -f %z system.img` on macOS, or the file's size in Properties on Windows. The stock layout leaves room for a system image of up to about 2350 MiB (2.3 GiB). The current image is below that, so the stock product partition can stay as it is; if a future image is larger, the baseline plan in [flash-plan-baseline](flash-plan-baseline.md) shows how to shrink `product_a` first. `vbmeta` and the other boot partitions are not written: with the bootloader unlocked the phone starts a modified system without them.

(`build/flash/flash-system-only.sh` in the repository wraps the first six commands with checksum and "unlocked" checks, but it keeps userdata, so it is for updating a phone that already runs the image. `build/flash/flash-baseline.sh` is the older first-flash script for an earlier image size and does not fit the current image; use the commands above.)

Do **not** write any other partition (in particular not `preloader`, `lk`, `tee`, `md1img`, `nvram`, `nvdata`, `nvcfg`, `protect1`, `protect2`, `proinfo`, `sec1`, `seccfg` or `otp`). Do not interrupt the flash. If something goes wrong, stop and read [the recovery guide](recovery-guide.md) before trying again.

## Step 5: first start

The first start can take several minutes. The phone then sets itself up: Phone Guard becomes the device owner (the app that is in charge of the phone): a small start-up service does this once, when the phone has finished booting, with nothing for you to do. You will see the Phone Guard status page and the Nokia-style home screen.

If it does not boot, or boots to a menu, see the recovery guide. The flash is repeatable: you can run step 4 again.

## Step 6: choose how it is managed

* **Family phone** (available now): the phone is managed from your own phone through a small private companion in your own free Cloudflare account. Go to the [family setup guide](family-setup.md). The setup wizard takes over from here: it creates your companion, connects the phone with a QR code, and (optionally) sets up Spotify and WhatsApp.
* **Open phone** (planned): the phone works on its own with nothing locked and no companion, set up on the phone itself. See [plan-two-editions](plan-two-editions.md) for where this stands.
* **Home Assistant** (works today, older route): if you already run Home Assistant, the integration in `ha/` manages the phone instead of the companion. See [ha/README.md](../ha/README.md).

## Troubleshooting the early steps

| Symptom | What to try |
|---|---|
| OEM unlocking is greyed out | Connect to Wi-Fi and wait a few minutes, then check again; check the phone is not carrier-locked. |
| `fastboot devices` shows nothing | Try another USB cable or port (the rear or front USB-C port can differ); on Windows install a fastboot USB driver; make sure the phone really is in fastboot mode. |
| `fastboot flashing unlock` says it is not allowed | OEM unlocking was not switched on in Developer options, or the phone has not finished checking in; repeat step 1.2. |
| The script stops with "bootloader not unlocked" | Run `fastboot getvar unlocked`. If it says no, repeat step 1. |
| The script stops with "not in fastbootd" | Run `fastboot reboot fastboot`, wait ten seconds and run the script again. |
| The phone shows the warning screen for a long time on first start | The first start is slow. Give it five minutes before worrying. |
| Anything stranger | [Recovery guide](recovery-guide.md). |
