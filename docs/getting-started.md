# Install Phone Guard on a Qin J36

Follow these steps in order. Each one says what to do, how you know it worked, and where to find more detail if you need it. Allow about an hour, most of it waiting.

> **Where this stands.** The setup wizard (step 6) works today. The phone image (step 3) is not yet a public download, so for now you ask the project owner for it. Steps 2 to 4 use a computer and a USB cable.

## Before you start: what you need

- [ ] **A Qin J36** with at least half a battery, and a USB-C cable that carries data.
- [ ] **A computer** (Windows, Linux or macOS) with Google's platform-tools (`adb` and `fastboot`). [How to set that up](install/computer-setup.md).
- [ ] **The Phone Guard image kit** (`system.img` and `SHA256SUMS`). [How to get it](install/get-the-image.md).
- [ ] **Nothing on the phone you want to keep.** Steps 2 and 4 erase it.
- [ ] **A free Cloudflare account** for the final wizard (it tells you how to make one).

## The steps

**1. Check the computer sees the phone.**
Plug the phone in, turn on USB debugging, then run `adb devices`. *Worked when:* your phone is listed. [More detail](install/computer-setup.md)

**2. Unlock the bootloader.**
On the phone: Settings, About phone, tap Build number 7 times; then Developer options, switch on OEM unlocking. On the computer: `adb reboot bootloader`, then `fastboot flashing unlock`, and confirm on the phone. The phone erases itself. *Worked when:* `fastboot getvar unlocked` says `yes`. [More detail](install/unlock-bootloader.md)

**3. Get the image and check it.**
Put the kit in a folder and run `sha256sum -c SHA256SUMS`. *Worked when:* every line says OK. [More detail](install/get-the-image.md)

**4. Flash it.**
Put the phone in fastboot mode and run four short groups of `fastboot` commands (copy and paste them from the detail page). *Worked when:* the last command finishes without an error and the phone restarts. Do not unplug during the flash. [More detail](install/flash.md)

**5. Let it start.**
The first start takes a few minutes. *Worked when:* you see the Phone Guard status page and the Nokia-style home screen. [More detail](install/flash.md#first-start)

**6. Run the setup wizard.**
On the computer, open the setup tool and follow its seven steps (Cloudflare, install, connect the phone, your phone, Spotify, WhatsApp, finish). Spotify and WhatsApp can be skipped. *Worked when:* the wizard says "All set". [The wizard guide](family-setup.md)

**7. Set the timetable.**
Open your private parent page on your own phone and add the school and night times. *Worked when:* the phone shows the right mode. [Everyday controls](family-setup.md#everyday-controls)

## Stuck?

[Troubleshooting for steps 1 to 5](install/troubleshooting.md). If a flash goes wrong, stop and read the [recovery guide](recovery-guide.md) before trying again.

## Other ways to manage the phone

Home Assistant users can manage the phone with the integration in `ha/` instead of steps 6 and 7 ([ha/README.md](../ha/README.md)). A phone with nothing locked and no companion ("Open phone") is planned; see [plan-two-editions](plan-two-editions.md).
