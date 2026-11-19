# Changelog 0.4.79 / 0.4.80 (working notes for the release notes)

## Stage 1: menus and Home keys (0.4.79)
- Main menu is nine entries at most: Phone, Messages, Contacts, Calendar, Clock, Extras, Settings, Camera, Music. Entries a mode can hide (Camera, Music) come last, so hiding them leaves no gap.
- New Extras screen: Weather, Classic Games, Calculator, parent-pinned apps, Navigate, Android Apps (last).
- Email, Navigate, Android Apps, Weather, Classic Games, Calculator, Call log, Running Apps and Notifications left the main menu.
- Call log is reached from the Phone screen's options (left soft key on the dial pad: Call log, Contacts) and the Home missed-calls count. The Call key in the main menu still opens it.
- Messages: Texts, Write message, WhatsApp, Notices (the old Notifications screen), Email (last). WhatsApp set-up/link/unlink/names/re-sync are a "WhatsApp options" row inside WhatsApp.
- Settings: Sound, Look, Connections, This phone, Running Apps (last).
- Sound: Ringer mode, Calls (Ringtone, Volume, Vibrate, Speakerphone button, Tune style), Messages (Text tone, WhatsApp tone, Volume, Vibrate), Media volume, Call volume, Sound test. Vibrate is a per-kind on/off under the Ringer mode, which stays the master.
- This phone: Battery, Screen lock, Time zone, Language, Typing (predictive text), Phone options / Phone type, Android settings (last). Connections no longer has Battery.
- Clock: "Set time: from network" is hidden (not greyed) when the time cannot be set.
- Home right soft key is always "Contacts". A long press (about 500 ms) opens Music where music is allowed, nothing otherwise; a short press acts when the key comes up. D-pad Right with nothing selected is Contacts too.

## Stage 2 (0.4.79): shared scroll clock, Now playing screen, all-day events (not started)
## Stage 3 (0.4.80): music behaviour, Spotify downloads discoverability (not started)

## Earlier in this cycle (already released)
- 0.4.77: Home zones, Now strip (pages, slide, dots, icons, two lines), calendar change observer, ring screen stays in front, radio song/artist from Planet Radio and BBC.
- 0.4.78 (image ims52): power menu, SET_TIME/REBOOT, no transient status bar on a swipe, own-dialer volume keys, boot chime.
