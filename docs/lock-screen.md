# Lock screen and PIN (2026-11-14)

Owner's wishes: no stock Android look at all, no swipe, the PIN typed on the keypad, in the active Phone Guard theme, with a real Android credential so data stays encrypted.

## What the image does
- **No PIN set:** the keyguard is disabled (`cmd lock_settings set-disabled true`, run once by pg-provision.sh at first boot): the phone boots straight into Phone Guard Home. (LockPatternUtils "lockscreen.disabled" only matters while there is no credential.)
- **PIN set (by the DPC, from Home Assistant: not built yet; see below):** Android's real lock screen runs and checks the PIN. SystemUI patch 0012 (`persist.sys.pg.bouncer_on_show=1`) raises the PIN pad at once on show/wake and 1.5 s/4 s after boot, and makes Back clear the entry instead of leaving the pad. **Phone Guard 0.3.5+ draws its own themed PIN panel over it** (`keys/PinCover.kt`, via the accessibility service's overlay window above the keyguard, same mechanism as the Bluetooth pairing panel): the launcher's colours and font for the current theme, 'Enter PIN', a star per digit, left key Emergency (press twice: dials 112, the EU number that also reaches UK services), right key Clear (Back). All keys still reach the real lock screen; the panel only counts digits, shows 'Wrong PIN' 1.3 s after Enter if the keyguard is still up, and disappears when it unlocks. If the service is off, Android's own pad shows.
- Why not draw the whole pad in Phone Guard: only platform-signed code may check the credential (ACCESS_KEYGUARD_SECURE_STORAGE is signature|setup), and the encrypted storage can only be unlocked by the real credential after a reboot.
- 'Discover Trust' (Lineage's onboarding notification): patch 0015 removes it. System look: dark theme + green seed (3DDC84) set at first boot.
- Phone Guard is direct-boot aware (0.3.3+) so it is Home before the PIN after a reboot (no FallbackHome/white screen).
- Hand-over from the boot animation (0.3.6+): the launcher's starting window is the animation's own dark colour, full screen.

## Tested (owner, real keypad, 2026-11-14)
PIN panel with correct and incorrect PINs: works well. Not yet tested: other themes (Classic Mac / dark), Back-clears-entry (needs the image with patch 0012's Back change), the Emergency double press (nobody pressed it).

## To do
- **Parent PIN workflow (app, Home Assistant):** set/clear the real PIN from the DPC with `DevicePolicyManager.resetPasswordWithToken` (a reset token must be set up while the user is unlocked; keep it in device-protected storage), triggered by an HA command; the phone never shows the PIN. Until then leave the phone with NO PIN for daily use.
- Panel under the Classic Mac skins, longer lockout display (the real lockout timer is hidden by the panel), a PIN entry when the screen is off and a key wakes it.
