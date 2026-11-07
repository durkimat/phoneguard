# Phone Guard backlog

Things agreed or parked, newest first within each group. Not a promise of order. See `SPEC.md` for the design.

## Agreed, not started

### Sound profile per mode (added 2026-11-07)
Each mode gets a sound setting. **School defaults to silent.** Day and Night default to normal
(Night may want vibrate; the owner to say).

Design notes to settle when it is picked up:
- Setting per mode: normal, vibrate, silent. Possibly "silent except parents" (below).
- Mechanism: `AudioManager.setRingerMode`. Vibrate needs nothing special. **Silent needs Do Not Disturb
  access** on Android 9 and later; a device owner cannot self-grant it, so it is probably one more adb
  setup grant (`adb shell cmd notification allow_dnd io.github.durkimat.phoneguard`), like grayscale and
  usage access. Check on the J36 first.
- Keep her from undoing it: `DISALLOW_ADJUST_VOLUME` (and `DISALLOW_UNMUTE_DEVICE`) while the mode
  wants a fixed profile; clear it again when the mode allows normal sound.
- Safety question to decide: silent means a parent's call does not ring. Options: allow starred
  contacts through (Do Not Disturb with starred callers allowed, repeat callers allowed), or leave
  calls audible and silence only messages and app notifications. Emergency calls are never affected.
- Alarms: the alarm stream is separate from the ringer, so a morning alarm still sounds in School
  unless we decide otherwise. Decide whether the clock alarm should be allowed in School.
- Restore on leaving the mode: put the ringer back to what it was, not to a fixed value.
- HA: `sound` field on each mode (`normal | vibrate | silent`), a selector per mode in the card's
  "Modes and apps" table, `copy_settings` "mode options" to include it, new devices get
  `school.sound = "silent"`. Existing devices have no value and keep behaving as normal until set.
- Add to the rig: set a mode, read `AudioManager.ringerMode`, try to change the volume, check it holds.

### Lockdown for School and a custom launcher (see SPEC section 11)
Quiet School mode: no pull-down shade (measured: works with the stock launcher), very few apps
(dialler, messages, maybe WhatsApp), Nokia-style look and keypad navigation. Next step is a lock-task
test with the current launcher, then a minimal launcher in the same app. Open: touch in School,
WhatsApp in School, incoming calls under lock task (the J36 has no SIM here to test).

## To verify or finish

- Reboot test of the controller with the real PIN entered (direct-boot code is in, untested by an actual reboot).
- Run the real release flow from HA (phone restores everything and gives up device owner) on a
  paired phone, then re-provision.
- Phone-side recovery key and offline release; single-use override code with its lockout.
- Rate limiting on the webhook.
- Publish the HA state as real entities (today it is a plain state written by the integration).
- Real release build: not `testOnly`, no debug receiver, signing, and a written install order
  (adb steps today: device owner, WRITE_SECURE_SETTINGS for grayscale, usage access).
- Notification access for counts on a custom launcher (another adb grant).
- Usage: multi-activity apps are slightly under-counted; no per-mode or per-hour breakdown yet.
- Anchor the phone's clock to server time and report drift to HA.
- "Find her phone" boost button in HA: one-off location request plus stepped high-accuracy tracking
  (SPEC section 4g).
- Second launcher fallback and a controller watchdog if the launcher ever crashes.
- Pairing QR printed from HA works in tests via a debug command; scan it on the phone's camera at least once for real.
- Push the latest commits to GitHub (the owner approves each push).
