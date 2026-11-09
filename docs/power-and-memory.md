# Power and memory management (design)

Goal: a phone that lasts a school day and a night on a charge, and stays quick, without the child or the parent having to do anything,
and without ever breaking what must work in the background (calls, messages, alarms, sync, music that is playing).
Everything is a per-mode setting in HA's "Power and memory" section, so School, Day and Night can differ. Defaults are gentle.

## What matters most (largest to smallest, on a keypad phone like this one)
1. The screen: on time and brightness.
2. Radios: Wi-Fi, mobile data, Bluetooth, location (location already has a per-mode setting).
3. Apps running when nobody is using them (Spotify idling online, browsers, stores).
4. Background sync and wake-ups.
5. Memory pressure (slows everything, makes Android kill things at bad moments).

## The pieces

| # | Piece | What it does | Status |
|---|---|---|---|
| 1 | Close unused apps | Stops an app unused for N minutes (Never / 10 / 30 / 60), by hiding and showing it again so even apps with a media service end. Never touches protected apps, the Keep running list, anything playing or buffering music, anything with an ongoing non-media notification (download, navigation, recording, call). | built, live |
| 2 | Keep running list | Per app tick: WhatsApp, Clock and DAVx5 by default. | built, live |
| 3 | Screen-off clean-up | When the screen has been off for N minutes (Never, 2, 5, 10 or 30; default 5), stop everything not protected, kept, playing or busy. An alarm set when the screen goes off fires the clean-up if the screen is still off. | built |
| 4 | Memory-pressure rule | When free memory falls below a threshold, stop the least recently used apps first (still never the protected, kept or playing ones). | to build |
| 5 | Battery saver at N % | Switch Android's own Battery Saver on at a chosen level per mode (and off when charged), through the system setting we are allowed to write. | to build, to test |
| 6 | Screen timeout and brightness cap | Per mode: how soon the screen goes off, and a ceiling on brightness. Device owner may set both. | to build |
| 7 | Per-app idle time | Override for one app (Spotify 10 min, the browser 5 min, a game 3 min). | later |
| 8 | Background sync windows | Auto-sync off in a mode, DAVx5 on its own schedule. Risky: needs care so nothing is missed. | later, think first |
| 9 | Radios per mode | Wi-Fi, Bluetooth and data off in a mode, brought up for check-in windows. Full design in [radios-per-mode.md](radios-per-mode.md). | designed, not built |
| 10 | Reporting in HA | The last 24 hours: how many apps were closed, why (idle, screen off, by hand), which most, and the latest few; a "report only" switch shows what would be closed without closing it. Free-memory points and screen-on hours later. | built |

## Safety rules (apply to every piece)
- Calls, messages, alarms, emergency, our own app and Home Assistant are never stopped.
- Music that is playing (or buffering) is never stopped; a paused player is treated like any idle app.
- An app with an ongoing foreground-service notification that is not a media player is left alone (it is doing something).
- A parent's Keep running list always wins.
- Stopping an app never loses its data or login; Android starts it again when something needs it.
- Every action is logged on the phone and summarised in HA; a "dry run" setting shows what would be stopped without stopping it.
- Defaults are gentle (10 minutes idle, 5 minutes screen off). Nothing in School is more aggressive than in Day unless chosen.

## Measuring
The power diary already records level, charging, screen, mode and Wi-Fi through the day, and HA summarises drain per hour. Real-world use over a few weeks will show whether each piece earns its place; each setting can be switched off per mode.

## Order suggested
1. Reporting (10) with a dry-run switch, so what the saver does is visible.
2. Screen-off clean-up (3).
3. Memory-pressure rule (4).
4. Battery saver at N % (5) and screen timeout/brightness (6).
5. The rest only if the diary says they matter.
