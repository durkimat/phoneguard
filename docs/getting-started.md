# Install Phone Guard on a Qin J36

Follow these steps in order. Each one says what to do, how you know it worked, and where to find more detail if you need it. Allow about an hour, most of it waiting.

> **Where this stands.** Everything here works today. The image (step 3) is a download from this repository's Releases page, provided for owners of the phone while its licensing is clarified (see [licensing](licensing.md)). One small program, the installer, does the computer-side work for steps 1, 3 and 4: it fetches Google's `adb` and `fastboot` if you do not have them, downloads and checks the image, and flashes it. Its checks, tool download and image download have been tried against a real J36 on Linux. The flash stage uses exactly the commands in the [manual guide](install/flash.md) but has not yet been run on a phone straight from stock, and the Windows and macOS paths are tested with simulated phones only, so tell us if you hit a problem.

## Before you start: what you need

- [ ] **A Qin J36** with at least half a battery, and a USB-C cable that carries data.
- [ ] **A computer** (Windows, macOS or Linux) with about 5 GB of free disk space and **Node.js 20 or newer**. To check, open a terminal and type `node --version`. If it is missing or older, install the "LTS" version from <https://nodejs.org> (a normal installer; accept the defaults).
- [ ] **Nothing on the phone you want to keep.** Steps 2 and 4 erase it.
- [ ] **A free Cloudflare account** for the final wizard (it tells you how to make one).

You do **not** need to install `adb`, `fastboot` or the Phone Guard image yourself. The installer fetches them (the tools from Google, the image from this repository's Releases page) and checks them.

## The steps

**1. Get the installer and check the computer sees the phone.**
a. Make a new folder called `phoneguard`.
b. Download the installer into it: open [pg-install.mjs](https://raw.githubusercontent.com/durkimat/phoneguard/main/installer/pg-install.mjs), press Ctrl+S (Cmd+S on a Mac) and save it in that folder as `pg-install.mjs`. (Or in a terminal in the folder: `curl -LO https://raw.githubusercontent.com/durkimat/phoneguard/main/installer/pg-install.mjs`; on Windows type `curl.exe`.)
c. Open a terminal in that folder: on Windows, open the folder in File Explorer, click the address bar, type `cmd` and press Enter; on macOS, right-click the folder and choose New Terminal at Folder; on Linux, right-click and choose Open in Terminal.
d. On the phone: Settings, About phone, tap **Build number** seven times; then Settings, System, Developer options, switch on **USB debugging**. Plug the phone in and tap **Allow** when it asks.
e. In the terminal run `node pg-install.mjs check`. If you do not have `adb` and `fastboot` it offers to download them from Google (type `yes`). *Worked when:* every line says `OK`. On Windows, if it cannot see the phone it says why and can fetch a USB driver ([Windows help](install/windows-usb.md)). [More detail](install/computer-setup.md)

**2. Unlock the bootloader.**
On the phone: Settings, System, Developer options, switch on **OEM unlocking**. In the terminal (the installer saved the tools in the `platform-tools` folder next to it; on Windows use `platform-tools\adb` and `platform-tools\fastboot`):
`./platform-tools/adb reboot bootloader`, then `./platform-tools/fastboot flashing unlock`, and confirm on the phone. The phone erases itself. *Worked when:* `./platform-tools/fastboot getvar unlocked` says `yes`. [More detail](install/unlock-bootloader.md)

**3. Download the image and check it.**
Turn USB debugging on again (the erase switched it off), then run `node pg-install.mjs download`. It fetches the newest image (about 1 GB), unpacks it (about 2.4 GB) and checks it. *Worked when:* every line says `OK`. [More detail](install/get-the-image.md)

**4. Flash it.**
Run `node pg-install.mjs all`. It checks the phone, takes a backup of the phone's details, checks the image again, asks you to type `yes`, then flashes it. Do not unplug during the flash. *Worked when:* it says "Done" and the phone restarts. To see the plan first, add `--dry-run`. [More detail](install/flash.md)

**5. Let it start.**
The first start takes a few minutes. *Worked when:* you see the Phone Guard status page and the Nokia-style home screen. [More detail](install/flash.md#first-start)

**6. Run the setup wizard.**
On the computer, open the setup tool and follow its nine steps (Cloudflare, install, connect the phone, your phone, Spotify, email, calendars, WhatsApp, finish). Spotify, email, calendars and WhatsApp can be skipped. *Worked when:* the wizard says "All set". [The wizard guide](family-setup.md)

**7. Set the timetable.**
Open your private parent page on your own phone and add the school and night times. *Worked when:* the phone shows the right mode. [Everyday controls](family-setup.md#everyday-controls)

## Stuck?

[Troubleshooting for steps 1 to 5](install/troubleshooting.md). If a flash goes wrong, stop and read the [recovery guide](recovery-guide.md) before trying again.

## Other ways to manage the phone

Home Assistant users can manage the phone with the integration in `ha/` instead of steps 6 and 7 ([ha/README.md](../ha/README.md)). A phone with nothing locked and no companion ("Open phone") is planned; see [plan-two-editions](plan-two-editions.md).
