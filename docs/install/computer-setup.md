# Set up your computer and phone (step 1)

You need **Node.js 20 or newer** (to run the installer) and Google's **platform-tools** (`adb` and `fastboot`). The installer fetches the second for you.

## 1. Node.js

Open a terminal (below) and type `node --version`. If it says `v20` or higher, you are done. If not, install the "LTS" version from <https://nodejs.org> (a normal installer; accept the defaults), then close and reopen the terminal.

## 2. The installer

1. Make a folder called `phoneguard`.
2. Download [pg-install.mjs](https://raw.githubusercontent.com/durkimat/phoneguard/main/installer/pg-install.mjs) into it (open the link, press Ctrl+S or Cmd+S, save it as `pg-install.mjs`). Or, in a terminal in that folder: `curl -LO https://raw.githubusercontent.com/durkimat/phoneguard/main/installer/pg-install.mjs` (on Windows, `curl.exe`).
3. Open a terminal **in that folder**:
   - *Windows:* open the folder in File Explorer, click the address bar, type `cmd`, press Enter.
   - *macOS:* right-click the folder, **New Terminal at Folder**.
   - *Linux:* right-click in the folder, **Open in Terminal**.

## 3. Turn on USB debugging on the phone

1. Settings, **About phone**, tap **Build number** seven times.
2. Settings, **System**, **Developer options** (the names can differ slightly), switch on **USB debugging**.
3. Plug the phone into the computer with a data-capable USB-C cable. Tap **Allow** when the phone asks whether to trust the computer.

## 4. Check it all

```bash
node pg-install.mjs check
```

If `adb` and `fastboot` are not installed, the installer says so and offers to download Google's platform-tools (about 10 to 17 MB, from `dl.google.com`, checked against Google's published checksum). Type `yes`. It saves them in a `platform-tools` folder next to the installer and uses them from then on; nothing is installed on your computer and nothing is changed outside that folder. Then it lists what it found. You want every line to say `OK`: both tools present, exactly one phone, the phone is a J36, battery 50% or more.

If you already have platform-tools, it uses yours, so nothing is downloaded.

To use the tools yourself in later steps, they are in that folder: `./platform-tools/adb devices` (Windows: `platform-tools\adb devices`).

## Windows: if the phone is not seen

Windows sometimes needs a USB driver, mostly for fastboot mode. The installer notices when Windows can see the phone but has no working driver, and can download one for you. See [Windows USB help](windows-usb.md).

## Linux: if the phone is not seen

Some Linux setups only let an administrator talk to USB phones. Try again with another cable and port first; if it still shows nothing, install your distribution's Android udev rules (on many systems the package is called `android-udev-rules` or `android-sdk-platform-tools-common`), unplug and replug, and run `check` again.

## If the phone is not listed

Try another cable (some only charge), another USB port directly on the computer (not a hub), and unplug and replug. See [troubleshooting](troubleshooting.md).

Next: [unlock the bootloader](unlock-bootloader.md).
