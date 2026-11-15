# Windows USB help

> **Honest status:** this page was written without a Windows computer to test on. The installer's Windows checks are covered by automated tests against simulated answers, and Google's driver download was tested on Linux, but nobody has yet run this on real Windows hardware with a J36. If something here does not match what you see, please tell us.

## What you probably need to do: nothing

Windows 10 and 11 usually recognise an Android phone in normal mode by themselves. Fastboot mode is where a driver is most often missing. You do **not** need a MediaTek driver for any step in this guide: the J36 shows up as an ordinary Android device (USB ID `0E8D:201C`) in both Android and fastboot mode.

## What the installer does for you

If `node pg-install.mjs check` cannot find the phone on Windows, it asks Windows what it can see and tells you which of these you are in:

- **Windows does not see the phone at all.** Try another data cable, another USB port on the computer itself (not a hub), and make sure the phone is switched on.
- **Windows sees the phone and its driver is working.** Look at the phone for a USB debugging prompt and tap **Allow**; if there is none, switch USB debugging off and on again.
- **Windows sees the phone but has no working driver.** The installer offers to download **Google's USB driver** (from `dl.google.com`, checked against Google's checksum) into a `usb_driver` folder next to the installer.

## Installing the driver by hand

Google's driver only lists Google's own phones, so Windows would refuse it for the J36 as it comes. The installer therefore also writes `phoneguard_j36.inf` in the `usb_driver` folder: a copy of Google's list with the J36's USB ID added. To use it:

1. Press the Windows key, type **device manager**, press Enter.
2. Find the phone: usually under **Other devices** or **Portable devices**, often called "Android" or with a yellow warning mark. (Put the phone in the mode you need first: Android with USB debugging on, or fastboot.)
3. Right-click it, **Update driver**, **Browse my computer for drivers**, **Let me pick from a list of available drivers on my computer**, **Have Disk...**, **Browse**, and choose `phoneguard_j36.inf` in the `usb_driver` folder. Click **Next**.
4. Windows may warn that it cannot verify the publisher (the copy we made is not signed). Choose to install anyway.
5. Unplug and replug the phone, then run `node pg-install.mjs check` again.

If you would rather not use an unsigned copy, the alternative is the USB driver from the phone's maker (we have found no official download for the Qin J36).

## MediaTek drivers (only for the optional deep backup)

The deeper read-only backup of the phone's IMEI and radio data with `mtkclient` ([recovery guide](../recovery-guide.md), section 4) talks to the phone in a different low-level mode and does need extra Windows drivers (a MediaTek VCOM driver and `libusb`/UsbDk). Follow the `mtkclient` project's own Windows instructions for that. The installer does not fetch those, because we do not know of an official source for them.
