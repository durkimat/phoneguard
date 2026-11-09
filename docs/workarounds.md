# Workarounds ("hacks") register

Things Phone Guard does that Android does not offer a clean, supported way to do. Each one is a risk on a new Android version or another ROM, so each
entry says what it is, why there is no clean route, what breaks, and what to do if it does. Add to this list whenever a new one is introduced; remove
an entry when a supported route appears.

| # | Workaround | Why there is no clean route | If it breaks | Where |
|---|---|---|---|---|
| 1 | **Pressing Android's own buttons with the accessibility service** (the Bluetooth pairing "Pair" / "Cancel" buttons, a panel stood over the dialog) | Android shows pairing in its own dialog; apps cannot pair silently | Pairing shows Android's touch dialog or fails; the cover panel times out after 60 s | `keys/F2Service.kt` (`onPairingDialog`) |
| 2 | **Hidden Bluetooth calls by reflection**: `removeBond` (forget), profile `connect` / `disconnect` | No public API to connect or forget a device | "Android refused"; the menu says so. Connection is verified through the profile proxies, so a refusal is never reported as success | `net/BluetoothControl.kt` |
| 3 | **Bluetooth `enable()` / `disable()`** (deprecated, refused to ordinary apps) | Supported route removed for apps; works for a device owner (proved on Android 14, 2026-11-09) | Message "Android refused: use Android settings" | `net/BluetoothControl.kt` |
| 4 | **`WRITE_SECURE_SETTINGS` granted to ourselves** to switch our accessibility service on and to set greyscale | A device owner may grant itself the permission; otherwise a human has to use Android settings | Service stays off; the Doctor/status screen should say so | `Enforcer.kt` |
| 5 | **Capturing the global output mix with `Visualizer(0)`** for the spectrum | Android has no public "what is playing" audio tap for another app's audio (Spotify) | Spectrum is blank; nothing else is affected. Needs RECORD_AUDIO | `music/Spectrum.kt` |
| 6 | **Starting Spotify behind a cover** so the device list contains this phone | The Web API only lists a device while the Spotify app is running on it | "Play on" lacks this phone; waits 9 s then shows what it has | `launcher/LauncherActivity.kt` (`wakeSpotify`) |
| 7 | **Force-stopping idle apps** (`killBackgroundProcesses`, usage stats) to save power | No supported "close this app for good" for another app | Apps stay running; power saving is smaller | `IdleReaper.kt` |
| 8 | **WhatsApp through an unofficial bridge** (whatsmeow, linked as a device) | WhatsApp has no consumer API | Messages stop until the bridge is updated; account risk is the owner's to accept | `whatsapp/`, `wabridge/` |
| 9 | **Screen cover / touch-blocker overlays** (accessibility overlay and an overlay window) to hide Android's UI during work we drive, and to ignore touches on touch phones | A device owner cannot hide system dialogs or filter touch directly | A dialog or a flicker is visible; Back and the home key still work | `keys/ScreenCover.kt`, `launcher/` |
| 10 | **Radios from the app**: `WifiManager.setWifiEnabled` (deprecated; works for a device owner on Android 14) | Supported route removed for apps | Radio does not switch; sleep is skipped and logged | `net/WifiControl.kt` |
| 11 | **Mobile data by `Settings.Global mobile_data` / `setDataEnabled`** (planned) | No supported route; needs a phone with a SIM to prove it | Data icon and row hidden; no sleep for data | spike only |

## Ideas that would remove several of these
- A **ROM that grants the platform privileges we need** (DumberOS or LineageOS built with Phone Guard as a privileged system app): removes 1, 2, 3, 9, 11 and would make an FM radio possible. The cost is that it is device-specific, so it is a per-device decision, not a default.
- **Android 13+ supported APIs** to watch: they keep adding device-owner policies; check each release for ones that replace a row above.

## Considered and dropped
- **Flight mode by driving Android's Settings screen** (2026-11-09): no supported route (the broadcast that makes radios obey is refused). The owner chose not to add another hack; the child has the individual radio switches instead.
