# Third-party apps in the image

| App | Package | Version | Source | SHA-256 of the APK | Licence |
|---|---|---|---|---|---|
| Traditional T9 (TT9) | io.github.sspanak.tt9 | 64.0 (versionCode 1645, minSdk 24) | https://github.com/sspanak/tt9/releases/download/v64.0/tt9-v64.0.apk (the 2.8 MB build without the extra dictionaries) | `4fe191d25a6c8fadab44abc018d44e40c0b21f6b4477e80f5a030dacff6786dd` | GPL-3.0 (see the project's page; verify before redistributing the image) |

How it gets in: `build/apps/module/{Android.bp,apps.mk}` are copied to `vendor/phoneguard/apps` by `build.sh`; the APK is staged at `/src/phoneguard-build/apps/tt9.apk` on the build container (not in git). It is installed as a normal system app, presigned (the author's signature is kept).

To use it on the phone: Settings, System, Languages and input, On-screen keyboard, Manage keyboards, switch on "Traditional T9", then pick it as the input method. Later, the integrated build (Phone Guard as device owner) can make it the default.

## Considered and left alone
- **NFC package (NfcNci, Tag):** the earlier worry was a crash loop every 15 s on Dumber. It was not seen on our image (baseline flash, 2026-11-12). Removing it means editing the base build's release flags and risks breaking the build for little gain, so it stays.
- **Treble app crash (`QtiAudio.startup`, ClassNotFoundException):** the app is a prebuilt, not in the source tree, and the crash happened twice at first boot and not again. Harmless; left alone.
