# Phone Guard

> **New here? Start with [docs/getting-started.md](docs/getting-started.md).** It takes you from a stock Qin J36 (unlock the bootloader, flash the image, first start) to the setup wizard, described in [docs/family-setup.md](docs/family-setup.md).

Manage a child's keypad phone (Qin J36) from your own phone, or from Home Assistant: time-based modes, per-mode app allowlists, a PIN-protected local override, without Google Family Link.

**Status: working prototype.** The phone runs an own-build Android 14 image with Phone Guard built in as a privileged system app (`build/`, `controller/`). Two ways to manage it:

- **Family edition:** a small private companion in your own free Cloudflare account (`companion/`), set up with a guided tool. No Home Assistant, no service run by this project.
- **Home Assistant edition:** a custom integration (`ha/`) that owns the timetable and shows each phone as a device.

The phone image is published as a [GitHub release](https://github.com/durkimat/phoneguard/releases/tag/image-20261114) for owners of the J36 (personal use, licences still being clarified, may be withdrawn: see [docs/licensing.md](docs/licensing.md)); design notes are in [SPEC.md](SPEC.md) and [BACKLOG.md](BACKLOG.md).

## Quick start (family phone)

Phone Guard turns a Qin J36 into a phone you manage from your own phone, with no Home Assistant and no service run by this project. You create a small private companion in **your own free Cloudflare account** with a guided setup tool, pair the child's phone with a QR code, and set a timetable from your phone.

1. Read the [family setup guide](docs/family-setup.md) (30 to 45 minutes, free).
2. Run the setup tool in `companion/setup-tool`.
3. Connect the child's phone and approve it with the 4 digits it shows.

Status: working prototype. Not yet: flashing from the tool, Cloudflare "Sign in" button, recovery after losing your private link. Details and known limits are in the guide. Developers: [companion developer notes](docs/companion-developers.md).

Licence: Apache-2.0.
