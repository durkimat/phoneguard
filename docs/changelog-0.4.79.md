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

## Stage 2 (0.4.79): shared scroll clock, Now playing screen, all-day events
- One scroll clock for every scrolling text in the launcher (`ScrollClock`, in `PaintedSkin.marquee` and the Home strip): a line scrolls only if it needs to; when more than one scrolls they move together (same offset, timing and direction, travel = the longest overflow). Applies to Now playing, the Mac player, title bar plus highlighted row, the menu grid label, the Navigate caption and the strip.
- Now playing: header = station (radio, with the preset number) / "Playing here" / device; main line = song; second line = artist; third line unchanged. One MusicInfo mapping for every screen (radio title = song or the stream's one text line, artist when known, new `station`).
- Home strip calendar page: all-day events (rule B): timed events first; one timed plus an all-day event today = timed then all-day; one timed plus a later all-day = timed only; no timed = all-day events, today's first; multi-day shown once. Prefix "All day " or "DD/MM ".
- Sound > Calls / Messages labels shortened to fit the row (Tune, Speaker button, Style, Text, Chat).
## Stage 3 (0.4.80): music behaviour, Spotify downloads discoverability
- Calls and music: any call (ringing, dialled or on) pauses the music; the radio fades out (about 0.5 s) and lets go, an app's music on this phone is paused at once, Spotify on another device is left alone. When the last call ends the music comes back (the radio fades in) only if it is still the way we left it and nothing else is playing; something the person paused is never started. (`MusicHold`)
- Alarm or timer: the radio ducks to about 20% (0.4 s) and returns (0.8 s) when it stops or snoozes; an app's music pauses and resumes. The alarm's own volume is never touched. A call that comes while an alarm rings pauses the radio fully; nothing resumes until both are over.
- Real Stop: a long press of OK on Now playing ("Hold OK: stop"), and a last row "Stop music" in the Radio and Spotify menus (shown only while that provider has something). The radio is released; an app's music on this phone gets a transport stop and the app is closed (the next play is a cold start); Spotify on another device is paused; the Sleep timer is cancelled; Home notice "Stopped".
- A paused radio or app is stopped and dropped after 2 hours (checked on the idle saver's tick, so accurate to a few minutes).
- A mode that no longer allows music stops what is playing; where music stays allowed nothing stops.
- A Stopped Spotify stays off Home until it is seen playing again.

## Earlier in this cycle (already released)
- 0.4.77: Home zones, Now strip (pages, slide, dots, icons, two lines), calendar change observer, ring screen stays in front, radio song/artist from Planet Radio and BBC.
- 0.4.78 (image ims52): power menu, SET_TIME/REBOOT, no transient status bar on a swipe, own-dialer volume keys, boot chime.
- Spotify downloads are discoverable: the Playlists screen always shows a hint for the highlighted playlist ("# download   * pin", "# remove download", "Downloading..."); the Spotify menu opened from the Playlists screen ends with "Download playlist" / "Remove download" for that playlist; a "What do the marks mean?" help screen (↓ downloaded, … downloading, * pinned); the "Phone Guard keys are off" refusal now says "Turn on Phone Guard keys in Settings, then try again". No "Remove all downloads" row.
- Now playing hints (Nokia look) spaced out: "Hold OK: stop" joins the hint block.
