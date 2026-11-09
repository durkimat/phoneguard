# Radios per mode (design, nothing built yet)

Piece 9 of [power-and-memory.md](power-and-memory.md), worked out. Goal: switch Wi-Fi, Bluetooth and mobile data off when nobody needs them, and bring them
up for short check-in windows so the phone still syncs with Home Assistant (HA), checks WhatsApp and reports its location. It must never get in the way of
calls, texts, alarms or music that is playing.

## What each radio is needed for
| Radio | Needed for | Not needed for |
|---|---|---|
| Wi-Fi / mobile data | The HA sync (the phone polls every 15 min), the HA "sync now" push, WhatsApp, Spotify, weather, location reports, clock sync, MMS | Calls, SMS, alarms, the mode timetable (the phone holds 14 days of it), emergency calls |
| Bluetooth | Headphones and speakers, calls over a headset | Everything else |

"Mobile" here means mobile **data** only. The voice and SMS radio is never touched (no airplane mode, ever).

## Settings (per mode, in HA's Power and memory section)
Each radio has three choices:
- **On**: as now. Nothing changes.
- **Saver**: the radio goes off when the screen has been off for N minutes (default 5) and nothing is using it. It comes back on when the screen turns on, at a check-in window, or when the child opens something that needs it.
- **Off**: off for the whole mode, except check-in windows (Wi-Fi and data only). Bluetooth "Off" stays off and the Bluetooth screen says "Off in School".

Plus one shared setting, **Check in every**: 15, 30 or 60 minutes, or Never (Never is only allowed when at least one radio is On or Saver).

Suggested defaults, chosen so nothing changes until a parent opts in: Day On/On/On; School Wi-Fi Saver, data Saver, Bluetooth Saver, check in every 30; Night all Saver, check in every 60.

## The check-in window
1. Alarm fires (exact, allow-while-idle; Doze limits this to about one per 9 minutes, so 15 minutes is the shortest we offer).
2. Wi-Fi comes on first if a saved network exists; wait up to 20 s. If it does not connect, mobile data comes on instead.
3. Run the normal work: HA sync, then WhatsApp's brief connect if its setting says it is due, then a location report if due.
4. Radios return to their mode state when the work ends, or after 90 s at the latest.
5. If a window fails three times running, leave the radio on until the next screen-off and log it, so a flaky network never leaves the phone unreachable.

## Never switch off (the "keep on" list)
- Charging.
- A call in progress, or Bluetooth audio connected or playing.
- Music playing through Spotify or anything else with a live media session.
- The Wi-Fi, Bluetooth or Music screens open; WhatsApp opened by the child (turn the radio on at once and show "Connecting...").
- An app update or download in progress.
- The first minute after boot, and while the phone is being set up or released.

## What gets slower (state this in the HA UI)
- A parent's "sync now" cannot reach a phone whose data is off: it is picked up at the next window. HA shows "next check-in 14:30".
- WhatsApp messages arrive at the window, exactly like the existing 30 and 60 minute WhatsApp settings. The radio window and the WhatsApp check should be one thing, not two.
- MMS and anything needing data waits for the window. Normal texts and calls are unaffected.
- Location reports are sent in windows, so "report every" below the check-in time makes no sense; HA should warn.

## How the phone would do it (to prove first)
These are the open technical questions. The phone is not on adb at the moment, so none are tested yet.
| Radio | Likely route | Risk |
|---|---|---|
| Wi-Fi | `WifiManager.setWifiEnabled`, which Android lets a device owner use | Low. The code already has it. |
| Bluetooth | `BluetoothAdapter.enable/disable`, which we already call | Medium. Newer targets are restricted and it may refuse. Fallback for "Off" only: the DISALLOW_BLUETOOTH restriction. |
| Mobile data | No public device-owner API. Options: a secure-settings write, a ROM-specific route, or leave data alone in the first version | High. Plan to ship Wi-Fi and Bluetooth first. |

## How it fits the code
- `Config.Mode` gains `wifi`, `data`, `bt` ("on", "saver", "off") and `checkInMin`; `Engine.Effective` carries them like it does `idleMin`.
- A pure function decides the wanted state from (mode, screen on, minutes since screen off, keep-on reasons, now). It is unit-tested the same way as `Engine`.
- A small `RadioManager` applies the decision, owns the check-in alarm, and logs every change to the power diary.
- HA: three selectors and the cadence in each mode, keys added to `LOCKDOWN_KEYS`, plus a dry-run (log only) switch like the idle saver's.
- The existing `WifiControl` and `BluetoothControl` do the switching. Both must tolerate being turned off underneath their screens.

## Measuring
- The power diary already records level, charging, screen, mode and whether Wi-Fi is the active network. Add the radio states and window counts.
- Before building: one night in Night mode with everything on, one with Wi-Fi and Bluetooth switched off by hand, same starting charge. If the difference is small, the feature is not worth its complexity and we stop there.
- HA report: radio on-time per day, windows run, windows failed, and the estimated saving.

## Order
1. Spike (an hour): confirm each switch works from our app on this phone; take the two-night baseline.
2. The pure policy function and its tests.
3. Wi-Fi and Bluetooth Saver, with the keep-on list, in dry-run first.
4. Check-in windows, merged with the WhatsApp check.
5. HA settings and the report.
6. Mobile data, only if step 1 found a safe route.

## Decisions for the owner
- Should "Off" for Bluetooth be final, or may the child switch it on from our menu?
- Are the suggested defaults right, in particular School with all radios on Saver?
- Is a 15 to 60 minute delay on a parent's "sync now" acceptable in Saver and Off modes?
