# Other ways to install, and status

The [Start page](../getting-started.md) has one guide per operating system. This page holds everything else.

## Status: what has been tested

- The installer's checks, its download of `adb` and `fastboot`, and its image download have been run against a real J36 on Linux.
- The flash stage uses exactly the commands in [flash](flash.md) but has not yet been run on a phone straight from stock.
- Windows and macOS are tested with simulated phones only. The Windows USB driver help has not been tried on real Windows ([Windows USB help](windows-usb.md)).
- Calendar, Spotify and the other wizard steps are described in [family-setup](../family-setup.md), including [known limits](../family-setup.md#known-limits).

## Doing the steps by hand instead of with the installer

- [Set up the computer and phone](computer-setup.md): the tools and USB debugging in more detail.
- [Unlock the bootloader](unlock-bootloader.md)
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
