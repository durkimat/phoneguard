# Other ways to install, and status

The [Start page](../getting-started.md) has one guide per operating system. This page holds everything else.

## Status: what has been tested

- The installer's checks, its download of `adb` and `fastboot`, and its image download have been run against a real J36 on Linux.
- The image itself has been flashed to a real J36 over a previous test image (with and without a wipe), and both editions have been tested on it: pairing a Family phone, locking and unlocking it from the parent side, boosting it, releasing it to Open, switching an Open phone to Family (erase), and choosing Open from fresh.
- Not yet run: the flash on a phone straight from stock firmware, Windows and macOS (tested with simulated phones only), and the Windows USB driver help ([Windows USB help](windows-usb.md)).
- Not yet tested end to end: WhatsApp linking, email and calendars on a real account, and a PIN on an Open phone.
- Calendar, Spotify and the other wizard steps are described in [family-setup](../family-setup.md), including [known limits](../family-setup.md#known-limits).

## Doing the steps by hand instead of with the installer

- [Set up the computer and phone](computer-setup.md): the tools and USB debugging in more detail.
- [Unlock the bootloader](unlock-bootloader.md) (the installer has an `unlock` command)
- [Get the image and check it by hand](get-the-image.md)
- [Flash it by hand](flash.md)

## Build the image yourself

For people comfortable with Linux and a very large build (about 300 GB of disk and several hours): [build-it-yourself](../build-it-yourself.md).

## Home Assistant

Home Assistant users can manage the phone with the integration in `ha/` instead of the wizard ([ha/README.md](../../ha/README.md)).

## If something goes wrong

[Troubleshooting](troubleshooting.md), and the [recovery guide](../recovery-guide.md) if a flash fails. Stop and read it before trying again.

## Licensing

See [licensing](../licensing.md).
