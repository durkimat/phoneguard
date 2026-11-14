# Set up your computer (step 1)

You need two small programs, `adb` and `fastboot`, from Google's **platform-tools**. They are free and need no installation.

1. Download platform-tools for your system from <https://developer.android.com/tools/releases/platform-tools> and unzip it to a folder you will remember.
2. Open a terminal in that folder (on Windows, Command Prompt or PowerShell; on macOS or Linux, Terminal).
3. Check they run:

```bash
adb version
fastboot --version
```

On macOS and Linux put `./` in front (`./adb version`) unless you added the folder to your path.

## Turn on USB debugging on the phone

1. Settings, **About phone**, tap **Build number** seven times.
2. Settings, **System**, **Developer options** (the names can differ slightly), switch on **USB debugging**.
3. Plug the phone into the computer with a data-capable USB-C cable. Tap **Allow** when the phone asks whether to trust the computer.
4. Run `adb devices`. Your phone should be listed as `device`. If it says `unauthorized`, look at the phone and tap Allow.

## Windows only

Fastboot mode (step 2) needs a USB driver. If `fastboot devices` shows nothing while the phone is in fastboot mode, install Google's USB driver or the driver from your phone's maker, then try again.

## If the phone is not listed

Try another cable (some only charge), another USB port, and unplug and replug. See [troubleshooting](troubleshooting.md).

Next: [unlock the bootloader](unlock-bootloader.md).
