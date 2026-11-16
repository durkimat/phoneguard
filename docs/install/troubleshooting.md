# Troubleshooting steps 1 to 5

| Symptom | What to try |
|---|---|
| `adb devices` shows nothing | Another USB cable (some only charge) or port; USB debugging on; unplug and replug; allow the computer on the phone's screen. |
| `adb devices` says `unauthorized` | Look at the phone and tap **Allow** (tick "always allow"). |
| `fastboot devices` shows nothing | Check the phone really is in fastboot mode (`adb reboot bootloader`); try another cable or port. On Windows see [Windows USB help](windows-usb.md); on Linux see the udev note in [computer setup](computer-setup.md#linux-if-the-phone-is-not-seen). |
| `adb` or `fastboot` "not found" | Run `node pg-install.mjs check`: it offers to download them from Google. Or use `./platform-tools/adb` from the folder next to the installer. |
| `node` is not recognised, or is too old | Install Node.js 20 or newer (the LTS version) from <https://nodejs.org>, then reopen the terminal. |
| OEM unlocking greyed out | Wi-Fi on, wait a few minutes, check again; the phone must not be carrier locked. |
| `fastboot flashing unlock` refused | OEM unlocking is not on; repeat [step 2](unlock-bootloader.md). |
| `fastboot getvar unlocked` says `no` | The bootloader is still locked; repeat [step 2](unlock-bootloader.md). |
| `is-userspace` says `no` | You are not in fastbootd: run `fastboot reboot fastboot`, wait ten seconds, check again, then continue. |
| `resize-logical-partition` or `flash` fails | Check you are in fastbootd (above) and that the image passed its checksum. Do not unplug; run the step again. |
| Checksum line does not say OK | Download the kit again. Do not flash a file that fails its check. |
| Warning screen for a long time on first start | The first start is slow. Give it five minutes. |
| The phone will not start, or starts to a menu | Run the flash again from [step 4](flash.md). If that fails, read the [recovery guide](../recovery-guide.md). |
| Anything stranger | Stop and read the [recovery guide](../recovery-guide.md) before trying more. |

## "You're out of offline devices" (Spotify downloads)

A Spotify account has only a few offline-device slots (about 5) and Spotify has no central page to free them. Phone Guard says "Spotify has no free offline slot (free one in your Spotify account)". Free a slot by turning offline listening off on a device you no longer use (remove its downloads in Spotify, or press `#` on its downloaded playlists in Phone Guard). Before reflashing or erasing a phone that has downloads, do that first.
