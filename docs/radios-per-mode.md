# Radios per mode (design v2, nothing built yet)

Piece 9 of [power-and-memory.md](power-and-memory.md). This replaces the first draft after the owner's simplification: one switch per mode, no
separate "check in" setting, the child's own choices respected, and the child gets mobile data and flight mode controls.

## The idea in one paragraph
In a mode with **Radios sleep when locked** on, Wi-Fi, mobile data and Bluetooth go off once the phone has been locked for a while and nothing is
obviously using them. They come up briefly when a location report is due, and come straight back when the phone is unlocked, **to whatever the child
had them set to**. The point is School (phone hardly touched) and then idle hours in the other modes. A phone that is being used pays no penalty.

## Parent setting (HA, per mode, Power and memory)
One new setting: **Radios sleep when locked**: off or on. Nothing else.
- The delay reuses the existing `screen_off_min` (the minutes after the screen goes off before the idle saver acts; default 5), so both savers act together.
- The check-in rhythm is not a setting: it is the mode's **location report interval** (`loc_every`), because that is the reason to be online.
  - Live location (0) means the radios never sleep in that mode.
  - Location off in a mode: fall back to the WhatsApp check (30 or 60) if on, else a fixed 60 minutes so HA can still reach the phone. Never longer.
  - Shortest interval 15 minutes (Doze lets allow-while-idle alarms through about once per 9 minutes).
- Default: on in every mode, including Day (parents can switch any mode off). A locked, silent phone gains nothing from live radios. The cost is that WhatsApp and "sync now" wait for the next window, as with the existing 30 and 60 minute settings.

## The child's own settings are the "intent"
The phone remembers what the child wants for each radio: **intent** (on or off). Sleeping never changes intent; it only suspends the radio.
- Intent changes when the child toggles a radio in our screens, or Android's own settings change a radio while we are not sleeping it. We keep a flag for "we switched it", so our own switching is never mistaken for the child's choice.
- Unlock (`ACTION_USER_PRESENT`): every radio whose intent is on comes back at once. A radio the child switched off stays off. Screen on but still locked does not count; unlocking does.
- Wi-Fi reconnects to the saved network by itself. Bluetooth reconnects to the last device by itself in most cases.

## When it must not sleep (locked but obviously in use)
Android always has background noise, so network traffic is not a good test. Use explicit signals only:
- Charging. (The saving is pointless and this keeps updates and fast syncs working overnight.)
- A call in progress, or ringing.
- Media playing: an active media session in the PLAYING state, or `AudioManager.isMusicActive()` (Spotify here, or anything else).
- Audio actually playing to a Bluetooth device, or a call routed to one. A device that is merely connected (an Alexa, a car, a watch) does not hold anything: Bluetooth is switched off like the rest and the device reconnects after unlock. Test: `AudioManager.isMusicActive()` or an active call while A2DP/HEADSET is connected, so playing music is already covered by the media rule and this adds the call-over-headset case.
- Hotspot or tethering on.
- Our own downloads or app updates running; the first minute after boot; the phone being set up or released.
- A grace period of `screen_off_min` after the screen goes off, so the last sync or a message being sent can finish.

Later, if real use shows holes, a traffic-rate test can be added (for example more than a set number of KB a minute for two minutes) using measured numbers from the power diary, not guesses. Not in version one.

## Check-in windows (location reports)
1. An exact allow-while-idle alarm at the next report time.
2. Wi-Fi first if a saved network exists (cheaper); if not connected in 20 s, mobile data.
3. Work: HA sync, WhatsApp check if due, location report, any queued command results.
4. Back to sleep when the work ends, or at 90 s at the latest. Three failed windows in a row: stay on until the next unlock and log it, so a bad network can never strand the phone.
5. A radio the child switched off is **still** used for a window in a mode that reports location. The parent's location setting is the point of the mode, and switching a radio off should not defeat it. The window lights the icon briefly; the child's intent is unchanged.

## What the child sees: icons, where to find it
### Status icons: three states plus "off"
Today an icon is solid (connected) or faint (on, not connected), and absent where a mode hides it. Add a fourth: **off**, drawn faint with a **diagonal slash** (lower left to upper right, full ink, the same stroke weight). So:
| Icon | Connected | On, not connected | Off |
|---|---|---|---|
| Wi-Fi | solid | faint | faint + slash |
| Bluetooth | solid | faint | faint + slash |
| Mobile data (new, x 66 to 100, between signal bars and Wi-Fi) | solid two small arrows, up and down | faint arrows | faint arrows + slash |
| Signal bars | as now | as now (bars are voice/SMS, not data) | not applicable |
| Flight mode | the signal bars are replaced by a small aeroplane glyph and Wi-Fi, Bluetooth and data all show slashed | | |

While a radio is merely **sleeping** the icon shows "off" too, but nobody sees that: sleep only happens locked and the icons come back at unlock. If the phone is unlocked while a radio is still waking up the icon shows faint (on, not connected) for a few seconds, which is honest.

The icons are tappable already (Wi-Fi opens its screen); extend the same to Bluetooth and the new data icon, and make the signal bars open Connections.

### One place for it: Settings > Connections
Settings already has 10 rows, so do not add four more. Replace the **Wi-Fi** and **Bluetooth** rows with one **Connections** row, which opens a short list:
```
Connections
1 Wi-Fi: On        (opens the Wi-Fi screen, as now; its first row toggles)
2 Bluetooth: On    (opens the Bluetooth screen)
3 Mobile data: On  (OK toggles)
4 Flight mode: Off (OK toggles)
```
Why this and not the alternatives:
- Four more Settings rows would push the settings list long; this is net one row fewer.
- A separate top-level menu entry clutters the main menu the parent has carefully trimmed.
- It is not hidden: Connections is the same place people look on any phone, and the status icons are one-tap shortcuts into it.
- Wi-Fi and Bluetooth keep their own screens because they have lists (networks, devices); data and flight mode are plain switches, so they toggle in place. The row text shows the state, so the difference needs no explanation.
- When the mode has radios sleep on, a one-line footer on Connections says **"Radios rest while locked"** so the child understands why they went off, and that unlocking brings them back.
- A digit key per row, like the other menus. Existing digit habits for rows after Wi-Fi move up by one; acceptable.

### Flight mode
- Chosen by the child; turns off Wi-Fi, Bluetooth, mobile data and the mobile radio (no calls or texts in or out). Emergency calls remain possible in Android's own flight mode on most devices; verify on this phone before shipping.
- It **ends automatically at the next mode change**, so a phone left in flight mode never misses the start of school or a parent's mode. A boost or override also ends it.
- Before it starts, the phone sends a last report saying "Flight mode, since 21:04" so HA shows it instead of just "offline". HA can warn if it lasts more than a set time (later).
- Check-in windows still run during flight mode (see decisions): they try and, finding no signal, go back to sleep. Flight mode never hides the child from HA's last-seen time.
- The child's intent for each radio is kept, so leaving flight mode restores them.

## How it is done (to prove first with the phone on adb)
| Radio | Route | Risk |
|---|---|---|
| Wi-Fi | `WifiManager.setWifiEnabled` (the code already uses it) | Low |
| Bluetooth | `BluetoothAdapter.enable/disable` (already used) | Medium: may be refused on newer Android. Fallback for sleeping only: none; if it refuses, skip Bluetooth and log it. |
| Mobile data | We hold WRITE_SECURE_SETTINGS (Enforcer already checks it). Try `Settings.Global "mobile_data"` and, if that is ignored, `TelephonyManager.setDataEnabled` through the device-owner route, then a ROM-specific route | High: the spike decides. If nothing is safe, data is simply not switched and the data icon and row are hidden. |
| Flight mode | `Settings.Global AIRPLANE_MODE_ON` plus the change broadcast, or the connectivity service call | High: the broadcast is protected on newer Android. If it cannot be done properly, do not fake it with a half-measure; hide the row and say so. |

Spike (an hour, phone connected): each of the four, switch off and on from our app, time how long each takes to come back, and confirm the phone is still
reachable for calls when only data is off. Also confirm what Android does to Wi-Fi when Wi-Fi sleep and Doze interact.

## Fit with the code
- `Config.Mode` gains `radiosSleep: Boolean`; `Effective` carries it (like `idleMin`). HA: one new key `radios_sleep` in `LOCKDOWN_KEYS`, a toggle in the mode editor, dry-run switch like the idle saver.
- `RadioPolicy` (pure, unit-tested like `Engine`): inputs are mode flag, locked or not, minutes since screen off, keep-awake reasons, intent per radio, flight mode, time to next report; output is the wanted state per radio and the next wake time.
- `RadioManager` applies it, holds the intent store, owns the alarm and the window, and writes every change and its reason to the power diary.
- `HomeInfo` gains `data` (-1 hidden, 0 off, 1 on not connected, 2 connected) and `flight`; `NokiaSkin.statusLine` draws the slash and the new icon; `LauncherModel` gets the Connections screen.
- `WifiControl` and `BluetoothControl` must cope with being switched off underneath their screens (lists empty, "Off").

## Measuring
- Power diary additions: radio states, sleep time per radio, windows run, windows failed, reason a radio was held on.
- First: one School-like night with everything on and one with Wi-Fi, Bluetooth and data off by hand, same charge. If the gap is small, stop.
- HA report: radio on-time per day, windows, failures, estimated saving.

## Order
1. Spike and baseline.
2. Status icons (slash, data icon) and Connections with data and flight rows, so the child controls exist even before sleeping does (useful on its own).
3. `RadioPolicy` and tests; `RadioManager` in dry-run (logs what it would do).
4. Go live for Wi-Fi and Bluetooth, then data if the spike allowed it.
5. HA toggle and report.

## Decisions (owner, 2026-11-09)
- A radio the child switched off **does** still come up for a location window in modes that report location.
- Flight mode does **not** suppress check-ins, since that would hand the child a way to disable tracking. The window still tries (Wi-Fi first, then data); in real flight mode it simply gets no signal and goes back to sleep. Note Android lets Wi-Fi be switched on inside flight mode, so a saved network may still connect; that is acceptable. The auto-end at the next mode change and the "flight mode since" report stay.
- **Day mode may use radio sleep too**: the saving applies whenever the phone is locked and nothing is playing, in any mode. Default is on in every mode (parents can switch a mode off).
- **Live tracking (loc_every 0) switches sleep off for that mode.** There is no separate "track now" request today: HA's only wake-up is the "sync now" nudge (a push through the Companion app), which cannot reach a phone whose data and Wi-Fi are off, so it is delivered at the next window. If a parent-triggered live tracking request is added later it needs the same rule: it waits for the next window (so it must be allowed to wait, with HA showing "waiting for check-in 14:30"), and once the phone is reached it holds the radios on for the length of the live session.
