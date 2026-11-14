# Image trim (2026-11-14)

The image is built from Lineage 21 + TrebleDroid. There is no Google software in it. These Lineage apps are removed because Phone Guard has its own versions or a keypad phone does not use them (about 150 MB):
Eleven (music), Gallery2, Aperture + ApertureLensLauncher (camera), Glimpse, Jelly (browser), Etar (calendar), Recorder, PhotoTable, Backgrounds, ThemePicker, LineageSetupWizard (first-boot provisioning skips the wizard), Seedvault (backup), ExactCalculator, AudioFX, and the stock launcher (Trebuchet/Launcher3).

Mechanism: the `overrides:` list of the `PhoneGuard` module in `build/apps/module/Android.bp`. To bring one back, delete its name from the list.

Kept for now: WebView (249 MB, needed), stock Dialer and Messages (until Phone Guard's own call/SMS screens are chosen), the AOSP keyboard LatinIME (until TT9 is proven), DeskClock (alarms), EmergencyInfo, StorageManager.

## Does trimming free space?
Not for the user. The system image is already flashed at its own size (flash-system-only.sh resizes the logical partition `system_a`). The space saved stays free inside the `super` partition, shared with the other logical partitions, and is only useful for the slot-B copy or OTA snapshots. Userdata is a separate physical partition; growing it would mean rewriting the phone's partition table (mtkclient), which is risky and not planned. Real benefits: shorter first-boot app compile, a bit less RAM/process clutter, smaller attack surface, fewer things to update.
