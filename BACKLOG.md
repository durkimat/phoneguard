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

### Full Nokia interface for School (added 2026-11-07)
Owner's brief: every screen in School mode replicates the original Nokia interface, with only slight
tweaks where a feature needs one (for example WhatsApp). The phone must read as a plain dumb phone
because the school bans smartphones. Today only the home screen and the main menu are ours; Messaging,
Contacts and the dialler are the stock Android apps.

To build, one built-in Nokia screen at a time, inside the launcher engine so every skin can draw them:
- **Messages**: the original SMS interface (inbox, write message, sent, drafts, templates, delivery
  reports, message settings). **WhatsApp lives in this same interface** (see the open question below),
  not as a separate smartphone app.
- **Contacts** (Phonebook): the original list, search, view, add, edit, delete, speed dials, my number.
- **Calculator**: add it to the menu with an original-style calculator screen.
- **Check what else Nokia had** and decide each (candidates below).

Original-Nokia features to consider (to be confirmed against a reference model's manual):
- Call register: missed, received and dialled calls, call duration.
- Profiles (General, Silent, Meeting, Outdoor, Pager): ties in with the sound-profile item above, so
  one "profile" setting per mode could be the same thing.
- Settings: time and date, tones, display, call settings (parents' choice what is exposed).
- Organiser: alarm clock (off in School by design), calendar, to-do, notes.
- Extras: stopwatch, countdown timer, unit converter; games (Snake) as an optional Day or Night item
  only, never in School unless a parent turns it on.
- Predictive text: T9 is already the keyboard (`io.github.sspanak.tt9`); keep it.

Open questions:
- **Which Nokia is the reference?** Owner (2026-11-07): not fixed. It can borrow from any of the monochrome
  Nokias; goal is consistency with no fall-back to native Android apps. Owner wants to choose from
  mock-ups before any build.
  (Original question:) A monochrome Series 30 (3310 or 3410 style), a 6310, or another.
  This decides the look, the menu numbering, and what exists.
- **WhatsApp inside the Nokia messages screen: how?** (researched 2026-11-07, third-party sources)
  - Official route: **companion mode**. WhatsApp lets a second Android phone be linked to an existing
    account (Linked devices, scan a QR code), up to four linked devices including web and desktop. Her
    smartphone stays primary and keeps her number; the J36 runs the official app as a linked phone,
    needs no SIM for WhatsApp, and keeps end-to-end encryption. The primary phone must come online at
    least every 14 days or the link lapses.
  - Beeper is a Matrix service that bridges WhatsApp with the open-source `mautrix-whatsapp` bridge,
    which behaves like a linked device (it uses the `whatsmeow` library). It is unofficial, and the
    bridge decrypts and re-encrypts messages, so it is not end to end.
  - Unofficial clients (whatsmeow, and Baileys, which is what the "WhatsApp Gateway" app already on
    the owner's HA is built on) are reported to break WhatsApp's terms and to risk account bans. The
    risk level is anecdotal but consistent. Not recommended on her real number.
  - The only official API is the WhatsApp Business Platform: businesses only, not personal accounts.
  - **Recommended spike: official companion mode plus notifications.** Install the real WhatsApp on the
    J36 as a companion to her account. WhatsApp stays allowed (not hidden, or it cannot post anything)
    but is left out of the menu. A notification listener in the launcher reads the sender and text from
    each WhatsApp notification into our Messages screen, and replies through the notification's own
    reply action. The listener can be enabled by the controller (it holds WRITE_SECURE_SETTINGS).
    Limits to expect: only messages received while the listener runs (no old history), text only (no
    voice notes, photos or calls), replies only to a conversation with a live notification (starting a
    brand-new chat is not possible this way), and it depends on WhatsApp keeping its notifications on.
  - To run the spike: link the J36 to a test WhatsApp account by scanning a QR from a phone (a user
    action, with the user's own account), grant notification access, and try read and reply.
- **Outside School** everything reverts: the launcher setting is per mode, so Day and Night use the normal
  launcher and the stock Messaging, Contacts and Phone apps. Messages and contacts live in the shared
  system databases, so both interfaces show the same data. The default SMS and dialler roles would be
  switched per mode by the controller.
- **WhatsApp bridge runs ON THE PHONE, not in HA: owner's later decision (2026-11-07). This supersedes the
  HA-hosted bridge design below.** Reason: HA should only control and monitor what the phone can do;
  messaging must not stop when HA is down.
  - Approach: embed the open-source Go library `whatsmeow` (the one `mautrix-whatsapp` and Beeper's
    bridge use; licence MPL-2.0, fine as a library in an Apache-2.0 app) in the controller app as an
    Android library built with `gomobile`, behind a thin Go wrapper: connect, pair, receive events,
    send text, list contacts. Arm64 only (the J36 is `arm64-v8a`, 2.9 GB RAM, 21 GB free). No
    precedent for this exact combination turned up in a quick search, so it is unproven until a spike.
  - Pairing: the phone asks for a pairing code (link by phone number) and she or a parent types it into
    Linked devices on her smartphone. No camera or QR needed. Done once, in a normal (touch) mode.
  - Where things live: the session keys, the allowlist copy, and the last ~20 messages per allowed chat
    sit in the app's private storage on the phone. HA holds only settings (allowlist, rate limits, on
    or off) and monitoring (connected or logged out, counts). Settings are cached, so it all works with
    HA down. Parents cannot read chats.
  - Connection and battery: a linked device needs a live connection; the foreground service keeps it,
    but the J36 battery is small. Policy to measure: connect in modes that need it (School), reconnect
    periodically elsewhere to collect queued messages. Needs a battery measurement before committing.
  - Unknowns the spike must answer: does whatsmeow build and run under gomobile on this phone; RAM and
    battery cost of the connection; how long WhatsApp queues messages for a linked device that is
    offline; how often the library needs updating when WhatsApp bumps its client version (it needs
    updating from time to time, so the app needs an update path); pairing by code works.
  - **Spike result 1 (2026-11-07, `spikes/wa-size/`, no account linked):** `whatsmeow` (latest, a commit dated
    the same day, so it is actively maintained) plus a pure-Go SQLite store builds to a **16 MB static
    arm64 program** with no NDK (about a third of that once compressed in an APK). Run on the J36 it
    connected to WhatsApp's servers and held the connection for 90 s using **19 MB of memory** (2 MB Go
    heap, 9 goroutines) and about **0.03 s of CPU in 80 s idle**. Android needs two small workarounds in
    plain Go: its own DNS resolver and embedded root certificates. So size and idle weight are not a
    problem. Still unmeasured: a *paired* session (initial sync, keeping contacts, receiving and
    sending) and real battery drain over hours. Both need an account linked by pairing code, which is
    a user action on a smartphone.
  - Toolchain: Go 1.27.3 is installed in `~/.local/gotool` (no sudo). `gomobile` and the Android NDK
    (about 1 GB) are only needed to turn it into an Android library later. Ask before downloading.
  - Same account-ban risk as any unofficial linked device: low volume and allowlist only help.
- (Superseded, HA-hosted variant) **WhatsApp bridge: owner's decision (2026-11-07).** Use a bridge so a chat can be started and a little history seen,
  accepting the unofficial-library risk because School use is low volume. Design brainstorm:
  - Existing "WhatsApp Gateway" app on the owner's HA (FaserF, Baileys) is a bot gateway: REST with
    `X-Auth-Token`, `POST /send_message {number, message}`, `GET /contacts`, a polling `GET /events` queue
    that empties when read, and an optional webhook. No history, one account, and polling it would steal
    events from the owner's own automations. So build our own small bridge app ("Phone Guard WhatsApp
    bridge", a Home Assistant app) as an extra linked device on her account (another slot, no clash).
  - Bridge: pairs by QR or pairing code scanned on her smartphone (Linked devices); allowlist of chats;
    drops everything else on arrival (nothing stored for other chats, groups, media or status); text
    only; rate limits (messages per hour and per day, minimum gap, typing presence like the existing
    app); alerts in HA when the session drops or is logged out; credentials stored in the app's private
    storage. Stored retention is a family decision, see below.
  - Plumbing: bridge talks only to our HA integration (internal). The integration pushes a nudge when a
    message arrives and answers new signed request kinds from the phone: `wa_threads`, `wa_thread`
    (a chat, newest page), `wa_send` (text, to an allowed contact, with a client id so a retry never
    double sends). No persistent socket on the phone; battery as today.
  - Phone: one Messages screen shows SMS and WhatsApp chats together (a chat is one entry with an
    [SMS] or [WA] tag and an unread count); open a chat to scroll the last ~20 messages with Up and
    Down; Reply and Write message use our own text field with the T9 keyboard; recipient picked from
    the allowed contacts; marks for sent, delivered, read; photos and voice notes show as [photo] and
    [voice message] placeholders; unread count on the home screen.
  - Card: a list of her WhatsApp contacts with a tick for "may message in School", plus the limits.
  - Open decisions for the owner: (1) where does history live: only on the phone (HA keeps an in-transit
    queue and delivery marks, parents cannot read chats) or also in HA (parents can read, needs an
    honest conversation with her first); (2) which modes the bridge serves (School only, or the Nokia
    screens whenever that launcher is on); (3) reuse the owner's own session for a first test or use a
    spare number.
  - Lower-risk alternative kept in reserve: official companion mode on the J36 plus a notification
    listener (read and reply, no history, no new chats), optionally plus an accessibility service to
    press Send after a `wa.me` deep link opens a new chat. No unofficial protocol at all.
  - Phases: mock-ups and choice of look; bridge app and integration plumbing with a one-contact
    end-to-end test; Messages screens on the phone (SMS first, then WhatsApp); the card controls;
    hardening (reconnect, session loss alerts, rate limits, backups of the session).
- **Our own SMS app or the stock one?** A built-in screen needs to be the default SMS app; a device
  owner can set the default SMS application. The stock app would then be hidden in School.
- **Our own call screens?** Replacing the incoming-call and in-call screens means becoming the default
  dialler (an InCallService). Emergency calls must always work. Needs a SIM to test.
- **Contacts**: read and write through the Android contacts provider (the permissions can be
  pre-granted by the device owner); decide whether a parent controls the list from Home Assistant.

Approach: build each screen as engine behaviour (lists, text viewer, text editor, number entry,
dialogs, status line) plus Nokia skin drawing, with unit tests for the behaviour. Start with
Contacts and the calculator (no SIM, no WhatsApp dependency), then Messages, then WhatsApp, then
call screens.

### Lockdown for School and a custom launcher (see SPEC section 11)
Owner's brief (2026-11-07): School should look and behave like a classic Nokia: no pull-down menus,
very few apps (dialler, messages, maybe WhatsApp), keypad and D-pad only. Mock-up shown and discussed
before building. Non-School modes use the normal launcher.

Per-mode "lockdown" settings to add to the mode model and the card:
- Launcher: Nokia-style (ours) or normal. School = ours, Day and Night = normal by default.
- Touchscreen: on or off. Off in School by default; configurable.
- Pull-down shade: on or off (measured: `setStatusBarDisabled` works with the stock launcher).
- Sound profile (the item above).
- During a boost the less restrictive of the scheduled mode and the boost mode wins for each setting.

How touch can be turned off: our launcher ignores touch itself. For other apps there is no device
owner switch for the touch panel; the plan is an invisible full-screen overlay that swallows touches
(needs "draw over other apps", one more adb setup grant). Dialogs that need a tap (WhatsApp first run,
permission prompts) cannot be answered in School, so set those up in Day first.

Test plan once built: run WhatsApp (read, reply with T9, voice note, answer a call, send a photo) with
touch off and only the D-pad and keypad, and decide what is usable. A later option is touch on only
while a named app (WhatsApp) is in front. Also test: incoming calls under lock-task mode (no SIM
here), alarm clock over lock task, power menu, launcher crash fallback.

Design requirement: several launcher skins (a later, not text-only Nokia style, and others) must be
addable without touching the engine: skin = how it is drawn; engine = behaviour. Skin is a per-mode setting.

Alarms: decided 2026-11-07, School excludes the Clock (hidden, so nothing rings) and is silent; no alarm
screen needed. To do in HA: put `com.android.deskclock` in `unprotect` and list it in the Modes and apps
table so a mode can hide it; default School leaves it unticked; say on the card that alarms set for
School hours are skipped. Calls under lock-task are still untested (no SIM here).

Order: (1) lock-task and touch-overlay experiments with the current launcher (done, results in SPEC); (2) minimal Nokia
launcher in the same app; (3) notification counts and the status line.

### Done 2026-11-07 (kept for the record)
- Per-mode lockdown wired end to end: launcher, touchscreen, shade, kiosk, Home pin, permission pre-grants.
- Mode override (Auto/Day/School/Night) next to Boost in the card.
- Card controls for the lockdown settings; School locked down by default; Clock left out of School, kept in Night.

### Launcher follow-ups (from the first build)
- The strip behind the launcher's status line is drawn darker than the rest of the screen while locked
  down (looks like the system status bar background). Cosmetic.
- Wire the per-mode settings into the controller: launcher (normal or Nokia), touch overlay, shade, lock
  task. Today they only exist as debug commands.
- Pin the Home app explicitly for every mode; otherwise a second Home app makes Android show a chooser.
- Pre-grant runtime permissions for every allowed app so no prompt ever appears (Contacts, Phone,
  Messaging, WhatsApp, Camera). Keypad cannot reliably answer them.
- Skins: add a second skin to prove the split; config field for the skin id.
- Unread counts for WhatsApp and others (needs notification access, an adb grant).
- Test with a SIM: signal bars, incoming call screen under lock-task, call log counts.
- Menu: which apps appear is "visible launcher apps minus plumbing". Make the exclusions and ordering
  configurable from HA.
- Home banner shows "<mode> until <time>"; decide wording for boosts and for Emergency.

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

## Nokia launcher themes (decided 2026-11-07)
- Layout (skin) and theme are separate. Theme = background colour + ink colour + selection style; font is monospace for all.
- Themes: Green LCD (default, #a9b98d / #1b2616), Grey (#cfd3cc / #1a1a1a), Amber on dark (#1a1200 / #ffb52e). Selection style: inverted bar (default) or outlined.
- Chosen on the phone only: Settings > Display > Theme, kept across mode changes. HA control (lock/choose) is optional later.
- Mock-ups for home, menu, Messages, WhatsApp conversation and T9 write screen still to be shown before building.

## Spike result 2: paired session (2026-11-07)
- Pairing by phone-number code works; linked device shows as "PhoneGuard" (set with store.SetOSInfo; the PairPhone display name must stay a standard browser form such as "Chrome (Linux)", "PhoneGuard" or "PhoneGuard (Android)" gave bad-request 400).
- Paired and connected: about 23-27 MB RSS; heavy CPU only during first minute (initial sync).
- Found: SQLITE_BUSY ("database is locked") during post-pairing app-state sync. Real build must open the sqlite store with busy_timeout and WAL (and ideally one connection).
- Still to measure: CPU once settled, incoming message delivery, offline queue, battery while unplugged.

## Nokia launcher key rules (decided 2026-11-07)
- Green LCD mock-ups approved (home, menu, Messages, WhatsApp conversation, T9 write).
- Everywhere: D-pad Left/Right = the left/right label, same as the two keys under the screen.
- Exception: the write screen, where D-pad Left/Right move the cursor; Send/Clear stay on the keys under the screen.

## Browser and apps suited to the tiny screen (added 2026-11-07)
- Find a browser that suits the 2.8" screen and keypad: possibly text-only or simple-graphics browsing (D-pad navigation, no touch).
- Wider idea: the Nokia-style School mode may suit adults too, with more flexibility in the non-school modes. That needs a shortlist of apps that fit this device (keypad-friendly, small-screen, low battery use) and a way to include them in the launcher menu.
- To research: keypad/D-pad friendly browsers on Android, text-mode browsers, F-Droid apps for small screens. Keep to open-source where possible. Ask before downloading anything.
- Design question: should the launcher be usable outside HA control, as a standalone "dumbphone" product? Parked, needs a decision later.

## Smartphone variant with a half-screen keyboard (added 2026-11-07)
- Broaden the product to a normal smartphone whose layout matches this dumb phone: a keyboard fixed to the bottom half of the screen, so the overall format is the same (screen above, keys below).
- Needs design work: some apps should use the whole screen and others perhaps not. Decide per app (or per category) which get the full screen and which get the split layout.
- Open questions to settle then: how the split is enforced on stock Android (window insets, a custom keyboard, split-screen or an overlay), touch versus D-pad navigation, and how it works with the existing modes and lockdown.
- Not started; pick up when we reach this item.

## Greyscale setting shelved (2026-11-07)
- The Android grey-screen option is redundant now the launcher has its own themes. School no longer uses it (set off in HA). Keep the code and card row for now; consider removing later.
- Lesson: grey screen filters adb screenshots? No: screencap shows colour even when the filter is on, so check the setting (accessibility_display_daltonizer_enabled), not the screenshot.
- Themes now: Green, Grey, Amber (dark), Orange (light). Selection style variants (outlined) still optional.

## Messages built (2026-11-07)
- Our own Messages screens: Inbox (unread star), conversation (page by page, newest first on open), Write with multi-tap typing (Abc/abc/ABC/123 with #, * for punctuation, 0 space), cursor editing (D-pad Left/Right), recipient typed or picked from Contacts. Sending uses SmsManager; with no SIM it says so and records nothing.
- Texts we send are kept in our own small store, because we are not the default messaging app so Android does not save them. Received texts come from the phone's SMS store (needs the stock messaging app as default).
- Debug builds only: made-up messages can be read from the "fake" key in the launcher prefs (the SMS store cannot be written from adb).
- To do: predictive T9 (the TT9 input method is installed; consider using it or a word list), delete message, Sent items view, MMS/long-message check, incoming-message refresh while reading, WhatsApp inside Messages, call directly from Contacts, notification of new messages while in School.
- Cosmetic: Android's location indicator pill shows over the launcher's status line (from the HA app's location use); hide or accept.

## Predictive text (T9) built (2026-11-07)
- Own engine in the launcher (WordList, Predictor): one key per letter shows the likeliest word, underlined; * steps through the other matches; 0 accepts and adds a space; 1 accepts and types punctuation; Clear removes one key press at a time; # cycles T9 Abc / T9 abc / T9 ABC / Abc / abc / ABC / 123 (multi-tap stays available for unusual words). Settings > Predictive text switches the T9 modes off.
- Why not TT9 itself: its word list is not in its APK (it unpacks into private storage), and using it as the keyboard would need a hidden text box and its own suggestion bar, with no control of the look or keys. Revisit if we ever want its 174,000-word dictionaries or other languages.
- Word list: 50,000 British-English words, bundled in assets/words with notices. Order from Peter Norvig's count_1w.txt (his page licenses the code under MIT; the data's licence is not stated there: CHECK BEFORE ANY PUBLIC RELEASE, or rebuild from another source such as wordfreq or SCOWL's own size ranks). Spelling filter and UK forms from SCOWL en_GB-ise 2026.02.25.
- To do: learn new words and names (user dictionary, add contact names), tune the ranking towards texting (home beats good now), next-word suggestions, other languages, a way to add a word that is not in the list.

## Calculator and call log built (2026-11-07)
- Calculator (Calculator.kt, unit tested): digits, # decimal point, * cycles + - x /, Up/Down give + and -, Equals on the left key, Clear removes a digit then the sum, then leaves. 12 digits, division to 12 significant figures, "Error" on divide by zero.
- Call log: the green key from Home or Menu (and the Call log menu entry) opens our own list, newest first, > dialled, < received, ! missed; Call rings the highlighted number via the Dialler (opens with the number filled in). Names come from Contacts. Debug builds can read made-up calls from the "fakecalls" prefs key.
- To do: call times and a detail view, delete entry, missed-call count clearing when viewed, call directly instead of filling in the Dialler, and our own in-call screens.

## Sound profile built (2026-11-07)
- Per-mode `sound`: normal | vibrate | silent. School defaults to silent, the others normal. Silent uses Do Not Disturb total silence: nothing sounds or buzzes, with NO exceptions (owner decision 2026-11-07: during school parents ring the school office, never the child). Vibrate keeps the ringer on vibrate. Normal puts back what the owner had before (ringer and Do Not Disturb), not a fixed value.
- Needs one adb setup grant: `adb shell cmd notification allow_dnd io.github.durkimat.phoneguard`. Without it silent falls back to vibrate and the card says so.
- DISALLOW_ADJUST_VOLUME is NOT used: it mutes the whole device including call audio. Instead GuardService watches ringer and Do Not Disturb changes and puts the profile back.
- A boost takes the louder of the two modes. Rig test `sound` (10 checks) passes on the J36; engine and HA tests added.
- Deployed to the live HA on 2026-11-07 (owner approved the restart): integration files hub.py and schedule.py, card v0.7.0 (resource ?v=0.7.0) with Sound and Incoming calls rows. Backup of the previous versions on HA at /root/phoneguard-bak-20261107. The test phone is now in total silence with incoming calls blocked while School is forced.
- Incoming calls: a separate per-mode setting `inbound`, School = blocked. While blocked, GuardService hangs up (TelecomManager.endCall, ANSWER_PHONE_CALLS) on every call that starts ringing; the rig checks the decision, a real incoming call still needs testing with a SIM (planned: owner moves their SIM over tomorrow).
- To do: decide whether Night should default to vibrate; messages/WhatsApp alert sounds in School (silent covers them).

## Dial screen and release build (2026-11-07)
- Own dial screen: digits on Home open it (number shown big, contact name underneath when the digits match one, * twice gives +). Call/green key places the call through Telecom (CALL_PHONE, self-granted); Clear deletes a digit and leaves when empty; the green key on an empty number opens the call log. Placing a real call is NOT tested (no SIM).
- The in-call and incoming-call screens are still the stock Android ones. Replacing them needs our app to hold the Dialer role with an InCallService (answer, reject, end, mute, speaker, audio routing). Needs a SIM to build and test properly: ask the owner for one. Mock the screens first.
- Release build: controller/release.sh (signed with a private key in ~/.phoneguard-release, back it up). Checked: not testOnly, no debug receiver. A release build cannot be removed with dpm remove-active-admin, so test the HA Release on a spare phone before installing it on any phone that matters.

## Reboot with PIN test (2026-11-07): passed
- Restarted the J36 while in School. Before unlock: RUNNING_LOCKED, our process running as device owner, hidden apps stay hidden. After the PIN: BOOT_COMPLETED re-evaluated within seconds and the full School lockdown was back (Nokia launcher pinned, touch blocked, kiosk on, menu-key overlay off, Clock hidden).
- Not yet covered: a reboot with a pending boost or a holiday, power-off during a mode change.

## SIM test plan, WhatsApp calls, and a "dumb" mode outside School (owner, 2026-11-07)
- Owner will move their SIM into the test phone for a short test (probably 2026-11-08). They will then have no other phone to text from, but can ring the phone.
  - Incoming text: send a text from the test phone to its own number (carrier permitting), or ask someone else; WhatsApp messages come from the paired test account.
  - Incoming call: ring it from any other line. Check it is hung up in School (inbound blocked), and rings normally in a mode that allows calls.
  - Then build the in-call and incoming-call screens (needs the Dialer role + InCallService). Mock them up first for approval.
- WhatsApp calls through the dumb interface: whatsmeow cannot carry call audio (it only sees call events). Plan: a "WhatsApp call" entry on the Contacts detail screen that hands over to the real WhatsApp app (via the contact's WhatsApp call data row), offered only in modes that allow calls, never in School. Unknown: whether WhatsApp's linked-device mode on a second Android phone supports calls; test before promising.
- "Dumb" mode for use outside School: a preset mode with the Nokia launcher but more freedom (touch on, calls and sound on, wider app list, optional shade). Modes already carry all of this (launcher, touch, shade, kiosk, sound, inbound, apps), so it is mostly a named preset in HA plus a few features to gate per mode (audio/media, incoming calls). Ties in with the standalone idea (adults using the interface without HA control).

## Startup "bootloader unlocked" warning (owner, 2026-11-07)
- The J36 shows an unlocked-bootloader warning at startup that waits about 5 seconds. Owner finds it very irritating and wants it gone.
- It comes from the bootloader (the phone's firmware, before Android starts), not from anything we install, so the controller app cannot remove it. Research needed, nothing to promise yet:
  - Relocking the bootloader removes the warning but is risky on a custom ROM (DumberOS is LineageOS-based): a locked bootloader will refuse an image it does not trust and can leave the phone unbootable. Do not try on the only phone.
  - Some MediaTek phones keep the warning picture in a logo partition, but the delay and the message are in the bootloader itself.
  - Ask the DumberOS maker (Dumbermini) whether a signed build or a patched bootloader exists for the J36.
- Not a blocker. Revisit with a spare phone.

## First SIM tests (2026-11-07)
- SIM (3 UK) works: signal bars, text sent from our Write screen to its own number arrived (sent and received copies in the SMS store), inbox shows the unread star, conversation shows both.
- Incoming call in School: phone saw the call, hung up once, no sound or screen; the caller heard the network's "cannot accept the call" message. Double log line fixed (hang up once per call).
- Fixed: conversation header cut a phone number's last digit; "Message sent" notice now clears itself after 1.5 s.
- T9 ranking: "ii" beats "hi" (web text); tune the word list towards texting. First suggestion is not always right, * steps through the rest.
- Note: voice calls on this SIM run over IMS/VoLTE (the circuit-switched domain reports "not registered"); calls reached the phone, so it works on 3 UK.
- Incoming call, both ways proven on the SIM (2026-11-07): blocked and silent in School (rejected, no UI), answered normally in Day (20:04:52, 3 s). Still stock Android call screens, in-call screens to build.

## In-call screens built (2026-11-07), real call path NOT yet proven
- Built: incoming (Answer/Reject), calling (Speaker/End), in call (timer, Options: speaker and mute, key tones, up/down volume, End), call ended (duration, closes itself after 2 s). CallService (InCallService), CallActivity (shows over the lock screen), DialActivity (tel: links open our dial screen). All drawn by the same Nokia skin and themes. Model and keys unit tested; every screen checked on the phone with made-up calls (debug command `fakecall`).
- Incoming calls the mode blocks are rejected inside CallService (and by the older receiver as a fallback). One call at a time: a second incoming call while one is going is rejected (busy). No call waiting, hold or conference UI yet.
- NOT done: the app is not yet the Dialer on the phone. With no SIM there is no call to hand it, and I did not want an untested call handler in place overnight (emergency calls go through it). Tomorrow with the SIM: set the role (README has the command and the way back), then test answer, reject, end, mute, speaker, key tones, an outgoing call from our dial screen, and a call under the School lock-task. Dial 112/999 is never to be tested.
- Contacts "Call" and the call log's Call now open our dial screen with the number typed (one more press rings).
- Cosmetic: Android's location indicator pill can cover the status line.

## Spike result 3: offline queue (2026-11-07)
- wa-spike stopped 18:17, restarted 20:18 on the J36 (two hours offline). On reconnect it received the queued messages at once: two texts the owner sent to themselves while it was off, plus two media messages from group chats. So a linked device is caught up after at least two hours offline. Longer gaps (overnight, days) still to measure.
- The linked device sees ALL the account's chats and groups, including group messages and channels. The real bridge must filter by the allowlist on the phone before storing or showing anything, and should drop everything else without keeping it.
- Idle after catching up: about 22 MB RSS, connected; battery use over hours not yet measured (needs the phone unplugged).

## First real calls through our call screens (2026-11-07 evening)
- Worked with a real incoming call in Day: our Incoming screen, answer via D-pad Left, timer, Options, Mute (shown and confirmed by Android's audio state), key tone, volume keys, End on the right key (Call ended 00:53, call log 52 s).
- Bug found by the owner: the left key under the screen did not answer or select. Cause: that key is DumberOS's menu key, remapped to a "search" intent only under School lockdown, and the intent always went to the launcher. Fix: KeyRouter activity (pinned for VOICE_ASSIST / WEB_SEARCH) hands the key to the call screen during a call, else on to the launcher; the call screen turns the remap on for the length of the call in any mode and the controller restores the mode's setting when it closes. Verified with a made-up call; the real-call retest is next.
- The "Incoming call" banner covered the top of the screen: now only posted when the phone is asleep or locked.
- Still to test: left key answer/select with a real call, speaker (loud: warn first), reject, outgoing call from our dial screen, School lock-task, blocked incoming call rejected inside CallService.

## Speaker, left key and number keys on real calls (2026-11-07 evening)
- Speaker: Android reports the speaker route active (and at maximum call volume) from our screen, and the stock dialler behaves the same: owner hears no difference either way. This phone has no usable loudspeaker for calls (or it is the same transducer). Not our bug. To do: hide the Speaker entry on phones that cannot use it (a per-device setting or a probe), and use a headset test later.
- Left key stopped working during a call after a periodic re-check (the controller put the left-key setting back to the mode's value). Fixed: while the call screen is up the remap is kept; and the controller never restores the left key to "do nothing" (value 0 was found stored from earlier experiments; reset to the default 1).
- Number keys now choose the numbered entry in every list (call options 1 speaker / 2 mute, settings, theme, font, inbox, contacts, call log).
- Lesson: never edit the lineagesettings key by hand; it bypasses the saved snapshot so the app's restore does nothing.

## Real-call test results with the SIM (2026-11-07 evening): all passed
- Incoming, Day: our Incoming screen; answer with the left key (after the KeyRouter fix) and the D-pad; timer; Options (speaker, mute) by key or by number; key tones; volume keys; End on the right key; call log entries correct.
- Incoming, Reject (right key): logged as rejected, never answered.
- Incoming, School: rejected inside CallService with no sound or screen ("incall: incoming call rejected by the mode"); call log shows it as rejected.
- Outgoing from our dial screen: from a tel: link in Day, and by typing on the Nokia home screen in School; the call screen shows over the School lock (touch still blocked, kiosk on); End works; the launcher returns.
- Left key kept through a periodic re-check during a call; the setting returns to the mode's value when the call screen closes (Day = 1, School = 4).
- Not working because of this phone, not our code: speaker (no audible difference in our screen or the stock one).
- Not tested: call waiting (a second call is rejected as busy), emergency calls (deliberately not), an outgoing call to a number that does not answer ("Calling..." phase never seen: the test line answers at once), VoIP/WhatsApp calls, Bluetooth, headset.
- State left on the phone: our app holds the Dialer role (stock dialler kept installed; README has the way back), School forced, SIM in.

## Speakerphone setting (2026-11-07)
- Settings > Speakerphone: Show or Hide, saved on the phone. Hidden removes Speaker from the call Options and from the Calling screen's left key. Set to Hide on the J36 (no usable loudspeaker). Default is Show.

## WhatsApp bridge library built into the app (2026-11-07)
- wabridge/ (Go, whatsmeow): Open, PairWithPhone, Connect/Disconnect, Send, SetAllowed, SetLimits, Logout, Listener (status, pair code, message). Rules enforced in Go before anything reaches Kotlin: text only, one-to-one chats only (groups, broadcast lists and channels dropped), allowlisted numbers only (LID addresses resolved to phone numbers or dropped), send limits (5 a minute, 200 a day by default), messages up to 1000 characters. SQLite store with one connection, WAL and a 15 s busy timeout (the spike's "database is locked" fix). Unit tests found and fixed an allowlist bug (numbers written with spaces were split).
- wabridge/build.sh builds out/wabridge.aar (arm64, about 7 MB, not committed) with gomobile and NDK 26.3 (installed under ~/Android/Sdk/ndk). App: whatsapp/WhatsApp.kt (wrapper) and WaStore.kt (last 20 messages per allowed chat, private SQLite, drops chats that stop being allowed); debug command `wa` (open, pair, connect, allow, send, status, logout).
- Verified on the J36: the library loads in the app and opens ("not linked"). Not yet linked to an account in the app, no screens yet.
- Next: link the owner's account in the app (needs the owner), Messages > WhatsApp screens, HA settings (on/off, allowlist, limits), connection policy and battery checks.
- Proven on the J36 (2026-11-07 21:16): the app's bridge reused the spike's linked-device session (copied into the app's files; no new link made), connected, and stored a self-message from the allowlisted number (LID resolved to the phone number). The controller protects itself from force-stop, so replacing session files needs `wa close` (Bridge.Close) or an app reinstall.
- Next: mock-ups, then Messages > WhatsApp screens, the app's own pairing screen (test by unlinking and relinking), HA settings, connection policy and battery.

## WhatsApp decisions (owner, 2026-11-07) and bridge update
- Groups are needed (the family uses a group). Allow-all must be possible (no restriction today), as well as an allowlist. Allowlist entries: phone numbers and "g:Group name". Done in the bridge: SetAllowed, SetAllowAll, group names looked up once and cached, group messages shown as "Sender: text", sending to a group by id ("g" + id).
- Checking: periodic only (no always-on), possibly not at all during school hours or hourly; configurable in HA. To build: connection policy in the app, HA setting per mode.
- Chat names matched from the phone's Contacts. To build in the chat list (Kotlin side).
- Non-text shows as markers: [photo] (with caption), [video], [voice note], [audio], [document], [sticker], [location], [contact]. Reactions and edits show nothing. Done in the bridge.
- WhatsApp calls: whatsmeow can see an incoming call (CallOffer) and reject it (RejectCall) but cannot carry call audio. Done: the bridge reports calls from allowed people (OnCall), stored in the chat as "[missed call]" or "[call declined]", and SetRejectCalls(true) declines every incoming WhatsApp call (to be wired to the mode's incoming-calls setting). Later experiment: install the real WhatsApp on the J36 as a companion phone and see whether it offers calls; if so, hand over from Contacts ("WhatsApp call") in modes that allow calls. The real app must stay hidden in School: its calls are not telecom calls and bypass our blocker.

## WhatsApp screens built (2026-11-07 evening)
- Messages > WhatsApp (shown once an account is linked, with the unread count): chat list (names from Contacts where the number matches, groups by name), conversation (same view as SMS), reply with the same T9 write screen ("WhatsApp" tag, "To: name"), sent through the bridge. Opening the list connects the bridge on demand and it disconnects 3 minutes after the last look, so nothing runs in the background. Reading a chat marks it read. Allowlist and call-declining are remembered in the app's prefs for now (debug command `wa allow <numbers|all>`); HA will set them.
- Verified on the J36: menu entry, chat list and conversation from the real bridge. Not yet tested: sending a reply (needs the owner's OK to message their own chat), groups (need a group chat), the periodic checks, the app's own pairing screen, HA settings.
- Cosmetic: the Android status bar peeks over the header for a moment when the location indicator appears.

## WhatsApp calls through the dialler by handing over to the real WhatsApp (owner OK'd the idea, 2026-11-07)
- Owner is happy for calls to hand over to the real WhatsApp app, with as much dialler integration as possible (even if it only invokes WhatsApp to place a call). Do research, then testing, after the current jobs (WhatsApp screens, HA settings, connection policy, pairing screen).
- To research: how WhatsApp exposes calls to other apps (a contact's WhatsApp call data row, vnd.android.cursor.item/vnd.com.whatsapp.voip.call, and the whatsapp:// and wa.me links); whether our dial screen and call log can offer "WhatsApp call" for a contact; whether WhatsApp can be installed on the J36 as a companion phone (linked device) and still place and receive calls; how it behaves under our call screens and under School (it must stay hidden or blocked in School: its calls are not telecom calls and bypass our blocker).
- To test (needs the owner): install the real WhatsApp on the J36 (needs a download: ask first), link as companion, try a voice call both ways, check the call UI is usable with the keypad and without touch, check audio.

## Signal messenger integration (owner, 2026-11-07)
- Add Signal to the Nokia Messages interface, alongside SMS and WhatsApp (chat list, conversation, reply, same T9 write screen and key rules).
- To research first: how to talk to Signal from the phone itself without HA in the middle (as with the WhatsApp bridge). Options to look at: a linked-device client (Signal supports linking secondary devices; libraries include signal-cli and libsignal-based clients, Java/Kotlin and Rust), versus using the real Signal app with notification access. Check licence (Signal's client libraries are AGPL-3.0, which matters for how this is distributed), size and battery cost, whether a linked device can reach groups, how rate limits and terms apply, and whether Signal allows unofficial clients.
- Same design rules as WhatsApp: allowlist or allow-all set from HA, text only with markers for other content, periodic checks only, nothing readable by HA, last 20 messages per chat kept on the phone.
- WhatsApp reply sending verified on the J36 (2026-11-07 21:37): "Hi" and "Yo" sent from the Nokia write screen to the owner's own chat via the bridge (owner OK'd test messages to themselves). WhatsApp does not echo our own sends back to this device, so the app keeps its own copy of what it sends (fixed, shows as "Me"). Owner to confirm the messages arrived on their phone.

## WhatsApp connection policy built (2026-11-07 evening)
- Per-mode setting `wa` in the config: "off" (no WhatsApp at all), "open" (connects only when WhatsApp is opened in the Nokia Messages, drops after 3 minutes idle), "30" or "60" (a brief background check every 30 or 60 minutes: connect, let queued messages arrive for ten seconds, disconnect, arm the next). A boost takes the more frequent of the two. The phone's default when HA does not say is "open". Planned HA defaults: School "open", others "30".
- Per-device `whatsapp` section in the config: all (allow everyone and every group), allowed (numbers and "g:Group name"), perMinute, perDay. WhatsApp calls are declined when the mode blocks incoming calls (wired to the mode's incoming-calls setting; the rig does not yet prove a real declined call).
- Home screen "new messages" now includes unread WhatsApp.
- Rig test `wa` (9 checks) passes on the J36: the alarm is armed in Day, a check connects briefly and disconnects, Night hourly, School no checks and the alarm cancelled.
- To do: HA side (mode option, per-device settings in the card, defaults), the app's pairing screen, group test, battery measurement of the periodic checks.

## HA side of the WhatsApp settings built and deployed (2026-11-07/08)
- Integration: per-mode `wa` (off, open, 30, 60; defaults School "open", others "30"), per-device `whatsapp` (all, allowed list of numbers and "g:Group name", perMinute 1-60, perDay 1-2000; default nobody allowed), validation, tidy-up of the typed list (blanks and repeats dropped), copy between devices, in the view; pushed to the phone in the config. 13 HA tests pass. Card v0.8.0: "WhatsApp checks" row per mode and a WhatsApp panel (allow everyone, one entry per line, limits, save); resource ?v=0.8.0.
- Deployed to the live HA with a restart (owner authorised it while asleep). Backups of the previous files on HA: /root/phoneguard-bak-20261107 and /root/phoneguard-bak-20261107b. The test device's allowed list was set to the owner's own number (447855260768) so the test chat keeps working.
- Seen: when HA's default (nobody allowed) reached the phone, it forgot the stored WhatsApp chats of everyone not on the list (as designed). A rig run that ends with forgetcfg/sync can leave the phone briefly in Emergency mode until the next sync: nudge it.

## Research findings (2026-11-08)
### Handing WhatsApp calls to the real app
- Community answers say a contact's WhatsApp voice call can be started with ACTION_VIEW on the contact's data row, `content://com.android.contacts/data/<id>`, with MIME type `vnd.android.cursor.item/vnd.com.whatsapp.voip.call` and package `com.whatsapp` (a plain `callto:` or ACTION_CALL did not work). Not official documentation: verify on the J36. Sources: https://kaweah.freedombox.rocks/kiwix/content/stackoverflow.com_en_all_2023-11/a/38674897 , https://b4x.com/android/forum/posts/855945 . Needs WhatsApp installed and its contact rows present (WhatsApp writes them when it syncs contacts); numbers need a country code.
- Companion mode on a second Android phone: recent guides say it can place and receive voice and video calls (and receives them with the primary offline); older beta-era articles do not mention calls; quality of sources is mixed, nothing official found. Sources: https://gadgets.beebom.com/guides/how-to-link-whatsapp-to-another-phone , https://www.ycloud.com/blog/how-to-use-whatsapp-on-two-phones , https://wabetainfo.com/whatsapp-is-releasing-the-companion-mode-for-all-beta-testers-on-android/ . Verify on the J36.
- Plan: after the owner OKs a download of the real WhatsApp APK (from whatsapp.com), install it on the J36 as a companion, check calls both ways and the call UI without touch; then add "WhatsApp call" to the Contacts detail screen (and a dial-screen option) that launches the intent above, only in modes that allow incoming/outgoing calls. In School it must stay hidden.
### Signal
- signal-cli is GPLv3 and is built on libsignal-service-java; the newer libsignal is AGPLv3 and its README says use outside Signal is unsupported; mautrix-signal notes it needs a separate licence exception from Signal for libsignal. Sources: https://github.com/hoehermann/signal-cli , https://git.sudo.is/matrix/mautrix-signal/src/branch/main/LICENSE.exceptions . Nothing found on Signal's terms for unofficial or linked-device clients: unresolved, check Signal's terms before building anything.
- Consequence: embedding libsignal (AGPL) in this app would make the distributed app AGPL-3.0, while Phone Guard is Apache-2.0 today. Decide before building: relicense, or keep Signal as a separate optional module, or use the real Signal app (notification access) instead. Do not start building until this and the terms are settled.
- Fixed (2026-11-08): Settings > WhatsApp > Unlink account said "cannot unlink" because WhatsApp's remove-device request needs a live connection and the bridge is normally disconnected. Logout now connects first, then wipes the stored chats and resets the bridge so a new link starts clean; the failure message now includes the reason. Not yet exercised by the owner.
- Verified by the owner on the J36 (2026-11-08 07:43): Settings > WhatsApp > Unlink account, then Messages > WhatsApp > Link account, number, "Get code", code typed into WhatsApp: linked and reconnected. The allowlist and call-declining survive an unlink and relink. The app's own pairing screen is now proven (no longer relying on the spike's session).
- Card v0.8.1 (2026-11-08): "＋ Add a person" button next to the person's name; it opens the add form for a person (HA people without a device yet, or someone not in HA, plus their first phone). Deployed (resource ?v=0.8.1, no restart needed).
- WhatsApp history (2026-11-08): the bridge was dropping the history bundle WhatsApp sends when a device is linked. Now handled (events.HistorySync): allowed chats only, newest 40 per chat passed on, marked as history (not counted unread); the app keeps 20; asks WhatsApp for about 30 days. WhatsApp only sends it at linking, so the owner must unlink and link once more to get it. Older history after that would need an on-demand request anchored on a stored message (message ids are not stored yet): possible later.
- Card v0.8.2: "＋ Add a person" (and the other people) now sit level with the person, on the same row, not under their name (owner: under the name made it look like part of that person). Checked at desktop and phone width. "＋ Add a device" stays with the device tabs.
- Fixed (2026-11-08 07:55): linking failed twice for the owner. Log showed the bridge was disconnected by the idle timer 45 s after the pairing code appeared (idle disconnect did not know a link was in progress), and the previous attempt's code could be shown again on a new attempt. Now: no idle disconnect while unlinked/pairing, the stored code is cleared when pairing starts, on success and on unlink, and while the code shows the left key is "New code". WhatsApp.touch does nothing until an account is linked.
- Fixed (2026-11-08 08:05): after the owner relinked with "allow everyone" on, the history bundle for hundreds of chats produced one launcher redraw per message on the main thread, pinning a core and making the phone unresponsive for minutes (Android ANR dialogs). Now: WhatsApp-driven redraws are merged (one per 300 ms), history is limited to 20 messages for each of the 60 most recent chats, the store keeps only the 60 most recently active chats (pruneChats), and the "calls: N entries" log line is gone. Lesson: nothing event-driven may redraw the launcher per event.
- Seen: the NFC service (com.android.nfc) fails to start and is killed every ~15 s (ANR dialogs) on the J36, which has no NFC hardware. Looks like a DumberOS quirk; not caused by us. To check with the DumberOS maker, or disable the package if it can be done safely.
- Note for privacy: with "allow everyone" the phone keeps the last 20 messages of the 60 most recent chats of the linked account (stored privately on the phone, never sent to HA).
- Redraws for the one-off history batch are merged to one every 2 s (never held longer than 5 s); live messages keep 300 ms (owner's suggestion).
- Chat as a list (2026-11-08, owner: only one message was visible and nothing showed how to reach the others): opening a chat (SMS or WhatsApp) now shows a list: "Reply", then every kept message newest first, one-line previews with > mine / < theirs, ellipsis for long lines; Read opens a message in full (Up/Down moves between messages, Back returns to the same row). Verified on the J36 with the real family group. 98 unit tests.
- Group messages without a display name (common in history) now fall back to the sender's number in the bridge, and the app swaps in the contact name from the phone's Contacts when the number matches ("Mum: ..."). Applies to messages received from now on; history already stored keeps no names until the next link.
- Fixed (2026-11-08): opening a message from the chat list showed a different one. Every redraw called setItems, which trimmed the selected row to the app menu's length (about seven rows), so any list longer than the menu (chat lists, SMS inbox, call log, contacts) had its selection pulled back to row 7 on each redraw. Now only the menu itself trims. Regression test with a 20-message chat; verified on the phone (row 4 opens message 18 of 20).
- WhatsApp's own names (owner's idea, 2026-11-08): the bridge now looks up each sender in WhatsApp's contact store: the name from the owner's address book as synced from the main phone (FullName/FirstName), else the display name the person chose (push name), else a business name, else the name carried by the message, else the number (which the app swaps for a local contact name when it matches). Both addresses WhatsApp may use (phone and hidden LID) are tried. Unit tested with a fake contact store. Applies to messages received from now on.
- Idea: store the sender separately from the text and resolve names when drawing, so old messages pick up names learned later (today the "Sender: " prefix is baked into stored group messages).

## WhatsApp re-sync (owner's request, 2026-11-08): built
- Settings > WhatsApp > "Re-sync names and messages" (and debug `wa resync`): connects, asks WhatsApp again for the address-book names (app state "critical_unblock_low", full) and the group list, gives stored group messages their senders' names (sender stored apart from the text, resolved when read), then asks the owner's main phone for up to 20 OLDER messages before the oldest message we hold (with a WhatsApp id) in each of the 20 most recently active chats; they arrive as history (not unread). No unlink needed. Newer missed messages cannot be requested (the protocol only goes backwards from a known message); messages sent while the phone is offline are delivered on reconnect.
- Store v2: sender_id, sender_name, mid columns; duplicate checks by id, or by time and text for older rows. Message is now one struct (gomobile) with id and sender id.
- First real run: no errors, 0 names refreshed and 0 chats extended, because everything stored so far predates ids. It takes effect for messages received from now on; for the existing 60 chats it needs one more link, or simply new messages arriving.
- Idea: a "Fetch more" entry at the foot of a chat list for one chat on demand.
- Fixed (2026-11-08): group senders showed numbers after a relink. WhatsApp sends names in a separate history bundle that can arrive after the messages, so the name was not yet known when each message was stored. Now the bridge says "history" after each bundle and the app looks the stored senders' names up again a few seconds after each bundle and each connection (store.renameSenders via Bridge.NameFor), then redraws. Verified on the J36 without relinking: 8 of 8 messages from others in Durkin Chat and 280 of 280 across all groups now carry a name.

## Names in WhatsApp lists (owner's requests, 2026-11-08)
- Settings > WhatsApp > "Names in lists": how a group sender's name is shortened in the one-line chat previews: first+last initials (default: Karen Durkin -> KD; many names use the first and last), all initials, first name up to 5 letters, or full name. One-word names stay as they are, a bare number shows as its last four digits. The full name still shows in the header when a message is opened (messages now carry their sender separately from the text). Unit tested (NameStyle).
- The owner's own nicknames win: a sender whose number is in THIS phone's Contacts is shown by that contact name (as WhatsApp itself does), else WhatsApp's name (the owner's address book as synced, then the display name they chose), else the number. Verified on the J36 by adding 07803243270 as "Wife": the WhatsApp list row and the group previews now say Wife (27 messages relabelled). The test contact "Wife" was left on the test phone.
- Fixed: with our app as the phone's Dialer the stock Phone app says "You must set Dialler as the default"; the menu's Phone entry now opens our own dial screen ("Enter a number").

## Predictive text ranking rebuilt (owner's request, 2026-11-08)
- The word list is now ranked by film and TV dialogue (hermitdave/FrequencyWords en_50k.txt, OpenSubtitles 2018, CC-BY-SA-4.0: the list file is shared under that licence, credit in assets/words/NOTICE.txt), filtered to British spellings with SCOWL, with the old web-ranked list as fallback for words dialogue lacks. Contractions (don't, I'm, it's...) and texting words (ok, lol, omg, mum, nan, bye...) are placed by hand because dialogue lists split or lack them; strong swear words are left out of suggestions (still typeable letter by letter). Rebuild with tools/build_wordlist.py.
- Before/after (rank in the list): hi 1146 -> 276, mum 10930 -> 150, good first for 4663 (was home), yes 454 -> 79, ii 547 -> 3469. Real-list tests in RealWordListTest. Checked on the J36: 44 -> Hi, 4663 -> good, 3668 -> don't.
- Still to do: learn words the owner types (user dictionary), contact names as suggestions, next-word suggestion.
- Abbreviations (owner, 2026-11-08): about 70 texting abbreviations and slang added to the word list by hand, placed among everyday words: lmao, brb, btw, idk, tbh, omw, smh, ngl, lmk, wyd, rn, fwiw, bff, ily, nvm, cuz, bruh, jk, hbd, soz, cba and more (tools/build_wordlist.py). Ones standing for swear words (wtf, fml, stfu, lmfao, af, jfc, omfg, ffs) are left out. Checked on the J36: 5626 -> Lmao, 289 -> btw, 435 -> idk, 272 -> brb as first suggestions.
- Learning (owner's idea): words in messages the owner SENDS (SMS or WhatsApp) are counted on the phone (learned-words.tsv, top 3000 kept, never sent anywhere); words used twice or more lead the suggestions, ones used once follow the usual words, and words the list does not know (names, nicknames) are offered; a name keeps its capital (mid-sentence capital, or capital on a word the list does not know). First names from the phone's Contacts are suggested from the start. Swear words are never learned. Settings > Predictive text > "Forget learned words" clears it. Unit tested (LearnedWordsTest, 122 tests).

## "WhatsApp app" setting: real app or our simple screens (owner's design decision, 2026-11-08)
- Owner: as a second phone the messaging is fine; if the full WhatsApp is installed the interface should use it instead of running WhatsApp twice. Built as a per-mode setting `waApp`: "auto" (default: the real app when it is installed and visible in that mode, else our screens), "simple" (always ours; the real app is hidden from the menu: School's default), "full" (the real app). When the real app is in use, our "WhatsApp" entry in Messages disappears, the menu offers the real app, our bridge disconnects and cancels its checks (one client only), and our unread count stops counting. A boost only ever opens it up (simple < auto < full). If the real app is not installed, our screens are used whatever was asked.
- Phone side built and unit tested (Engine.useRealWhatsApp, 124 tests), installed on the J36; behaviour unchanged there (the real app is not installed). HA side built and tested (wa_app per mode, default simple for School and auto otherwise, validation, card v0.9.0 row "WhatsApp app") but NOT yet deployed to the live HA (needs a restart).
- To test with the real WhatsApp (needs the owner's OK to download the app): link it as a companion, set Day to Auto, check the menu offers the real app and that Messages hides ours; set School to "Our screens" and check the real app is gone from the menu.
- Deployed (2026-11-08 10:15, owner authorised the restart): HA integration and card v0.9.0 with the "WhatsApp app" row (Day and Night auto, School simple). Previous files backed up on HA at /root/phoneguard-bak-20261108.
- Real WhatsApp downloaded (official, https://www.whatsapp.com/android/current/WhatsApp.apk, 156 MB, com.whatsapp 2.26.44.71, signature valid, signer WhatsApp LLC, Menlo Park; the older-Android signer is WhatsApp Inc.) and installed on the J36 over adb with the owner's OK. NOT opened or registered. Phone Guard saw the new app at once, hid it and listed it under "New apps waiting" (pending_apps = com.whatsapp): the new-app approval flow works. The APK is kept in the session scratch folder only, not in the repository.
- Next, with the owner at the phone: approve WhatsApp for Day in the card, in Day mode open it and link it as a companion (second phone), then test: menu offers the real app, Messages hides ours, our bridge stands down; calls both ways; "WhatsApp call" hand-over from Contacts; School set to "Our screens" hides the real app.

## WhatsApp: paused, to test further (owner, 2026-11-08)
- Paused with the real WhatsApp installed but not set up. When resumed (owner at the phone): approve WhatsApp for Day in "New apps waiting", in Day open it and link as a companion, then check: the menu offers the real app and Messages hides ours, our bridge stands down (no checks, disconnected), School set to "Our screens" hides the real app; calls both ways (voice, speaker is not usable on this phone); the "WhatsApp call" hand-over from Contacts; and that WhatsApp's own prompts need touch, so set it up in Day first.
- Also still to test: a real group (names, previews), battery with the phone unplugged for a few hours (periodic checks on and off), and whether history arrives after an unlink and link with ids (resync).

## "Other apps" in the Nokia menu (owner's idea, 2026-11-08): design notes
- Idea: option 8 "Other Apps" in the main menu opening a plain numbered list of the other allowed Android apps, so the launcher can start any app the mode allows. Assessment: it will work (apps are already launched this way, e.g. the camera) and it keeps the main menu short. Refinements to settle before building: (1) keep core items in FIXED numbered slots so digit shortcuts never move (a missing item leaves a gap or is skipped consistently), with Settings last and Apps just before it; hide the Apps entry when the mode allows no other apps (School); (2) let the parent PIN a few apps to the main menu (for example the real WhatsApp, Maps) and rename them, with the rest in the Apps list; (3) many apps need touch: School has touch off, so offer a per-app "touch works inside this app" setting (the touch blocker lets touches through while that app is in front, detected with the usage-stats grant we already have) and show which apps are keypad-friendly; (4) apps with their own set-up screens or permission prompts must be prepared in Day first; (5) labels: use the app's name, with an override in HA; (6) check that Back and the left key behave inside other apps under kiosk lock (the left key is only remapped for our screens and calls).
- Alternatives considered: a flat list (today: grows long), pinning only, shortcuts on the home screen soft keys, number-key quick launch (hold a digit). Recommendation: the Apps folder plus a few pinned items.

## Other apps and pinning (built 2026-11-08, not yet on the phone or HA)
- Menu is now: Phone, Messages, (real WhatsApp), Contacts, pinned apps, "Other apps" (only if any allowed app is left), Call log, Calculator, Settings. "Other apps" is a numbered list that launches the app; Back returns to the menu. The kiosk lock still allows every listed app. Parents pin apps with a new "Main menu" column in the card (v0.10.0); the config key is `pinned`. Touch is not decided per app: the mode's "Touchscreen works" setting stays the rule, so School only allows keypad-friendly apps by what is ticked there.
- To do: install the APK, deploy HA files (hub.py, schedule.py, card v0.10.0, bump the resource ?v=), test on the J36.

## Games (owner idea, 2026-11-08): Snake first, then more
- Own-built, drawn in the current theme. Candidates from the 3310 era: Snake II, Space Impact, Pairs II, Bantumi (mancala), Bounce. Classic order of effort: Snake (easy), Pairs (easy), Space Impact (medium), Bantumi (medium, needs a simple computer opponent), Bounce (harder).
- Third-party clones found are mostly Flutter/touch based (MIT or GPL); keypad input and our theme make a rewrite simpler. Avoid Nokia names and artwork: use generic names (Snake, Pairs, Shooter, Seeds).

## Camera without touch (owner, 2026-11-08): after the games
- Does the stock Camera app work with the keypad alone (shutter on a key, switching camera, viewing photos)? Check it. If it does not, or looks out of place, build a themed camera screen in our launcher (preview, shutter on OK, gallery list). Decide after testing; touch-only Camera should stay out of School.
- HA deployed 2026-11-08 (pinned apps, card v0.10.0); backup at /root/phoneguard-bak-20261108c on HA.

## Games: Snake built (2026-11-08)
- Menu: Games (before Call log) > Snake. Pure model `Snake.kt` (22x20 grid, walls and own body end the game, speeds up with score, best score saved on the phone), drawn by NokiaSkin in the theme. Keys: D-pad or 2/4/6/8 steer, OK/5/left key start-pause-resume-again, right key Back. Installed on the J36, drew correctly; needs hands-on play (feel of the speed, key repeat).
- Next games: Pairs, then Space Impact-style shooter, then Seeds (mancala). Each is a pure model plus a skin screen plus a row in the Games list.

## APK updates for the phone (owner, 2026-11-08)
- Find a way to update the Phone Guard APK on the phone from HA (or another route) instead of adb: for example HA offers the new release APK, the phone checks it against the signing key and installs it as device owner (silent install through the device-owner package installer), with a version check, a rollback path and the update only in a mode that allows it. Needs a design: where the APK is hosted (HA /config/www or Nabu Casa), integrity (hash signed with the project key), and testing on a spare phone first.

## Games: Pairs, Shooter, Seeds built (2026-11-08)
- Games menu: Snake, Pairs (4x4 memory, fewest moves saved), Shooter (side-scrolling, 3 lives, best score saved; OK fires, left key pauses), Seeds (Kalah mancala against the phone; 1-6 or arrows then OK). Pure models Pairs.kt, Shooter.kt, Seeds.kt with tests (GamesTest.kt); drawn by NokiaSkin. Not yet seen on the phone: the phone's system restarted during the install test.
- Finding: with the screen off, an adb End-call key (KEYCODE_ENDCALL) crashed the phone's system process (WakeLock under-locked in ScreenOffMediaKeyHandler, a DumberOS/Android bug), which restarts the phone and asks for the PIN. Never send keys over adb after the screen has gone off; wake it first. Seeds, shooter and pairs screenshots wait for the unlock.

## Blaster, an R-Type-style shooter (owner request, 2026-11-08)
- Replaced the simple Shooter with "Blaster": sideways scrolling in a 44x40 cell field with a star background; the ship moves four ways while keys are held (key-up now reaches the launcher); a tap fires; holding OK/5 charges (gauge under the ship) and releasing lets a piercing beam go; weaving drones in lines, straight-flying tanks that shoot back; crates "P" bring a pod that sits at the ship's front, blocks shots, smashes drones and doubles the shots; 3 lives; best score saved. Installed, not yet played (the phone was locked after its restart).
- Ideas: a boss at the end of a stage, pod launch and detach, more weapons, upward-scrolling variant, stages with names, sound off (School is silent).

## Blaster v2: more of the original (2026-11-08)
- Added: pod launch/recall with # (flies ahead, fires on its own, comes home), weapon crates (S spread, B bouncing shots, + speed up to 2, P pod; weapon resets to the basic gun when hit), stages of 8 waves ending in a boss with aimed fire and a health bar, stage banner, harder each stage. HUD shows stage, weapon, pod state.
- Not yet: terrain (floor and ceiling), bits/satellites, missiles, multiple boss types, the upward-scrolling variant, high-score per stage. Needs hands-on tuning of speed, difficulty and key feel.

## Weather on the home screen (owner, 2026-11-08) - research done, not built
- Wanted: a small outline icon in the theme plus current temperature on the home screen, with a hint when rain is coming (next few hours). Where touch is on, tapping it opens a themed forecast; without touch the forecast is an entry in the menu / Other apps list. Owner likes AccuWeather's four-hour rain forecast but wants everything in our theme, so the data comes from an API and we draw it ourselves.
- Data sources checked (2026-11-08):
  - Open-Meteo: free for non-commercial use, no key, under 10,000 calls a day, CC BY 4.0 attribution; has 15-minute precipitation for Europe (good for "rain in the next hours") plus hourly and daily forecasts and a weather code for the icon. Best fit. Still to check on its docs page: the exact attribution wording and the `weather_code` list.
  - Met Office DataHub: UK, free plan about 360 calls a day, hourly data for 2 days, one location a call; needs an account and key. Good quality for the UK but no minute-level rain on the free plan.
  - AccuWeather API: MinuteCast (two hours, minute by minute) is billed separately and not in every region; the free plan is small or being withdrawn (conflicting reports, a Home Assistant thread says free Core Weather access is being discontinued). Not a good base.
  - MET Norway locationforecast: free, global, needs a proper User-Agent; its nowcast does not cover the UK.
- Plan: fetch from Open-Meteo on a timer (every 30 to 60 minutes, only when the screen has been used, school-friendly), with the location set from HA (a latitude and longitude per child, default home) so the phone never needs location permission. Cache the last answer and show nothing when it is stale. Icons are simple outlines (sun, cloud, rain, snow, storm, mist, night) drawn in code in the theme colours. "Rain in 2h" style hint only when the next 4 hours have more than a little rain. Forecast screen: now, next 12 hours, the next days.
- Open point: the phone needs internet (the WhatsApp bridge already does); a weather call is small and should respect School silence (no sound, no notification).

## New-message icon on the home screen (owner, 2026-11-08)
- The home screen shows "N new messages" and "N missed calls" as text lines today. Add a small outline envelope (and a phone-handset outline for missed calls) in the theme beside or in the status bar so a new message is visible at a glance, including WhatsApp when our screens are in use. Ideas: the envelope blinks gently or shows the count; the status bar icon stays when the home text is hidden; check it in all four themes and both fonts.

## Lock screen (owner, 2026-11-08): option 1 tried
- Owner chose to keep Android's own PIN unlock (a modern phone user expects typing a PIN) and theme what can be themed. Built `LockWallpaper`: paints the lock-screen wallpaper in the current theme (LCD colour, faint pixel lines, ink frame) at start and whenever the theme changes. Android adds its own dimming and white clock, so the green looks darker than the home screen. If it is not close enough: brighten the colours to counter the dimming, or look at a trust-agent/own-lock route (option 2) later. The PIN pad itself stays Android's.

- Weather decision (owner, 2026-11-08): Open-Meteo it is. Before building, read its docs for the attribution wording and the weather_code list.
- Lock screen PIN entry goes white (owner, 2026-11-08): the PIN pad is Android's own and cannot be screenshotted (secure window). Trying the system dark theme (`cmd uimode night yes`, stored as secure ui_night_mode=2) so the PIN screen is dark instead of white. If it works, the app should set it itself (needs a check that writing ui_night_mode with WRITE_SECURE_SETTINGS applies without adb) and the choice becomes a setting.

## Lock screen option 2 tried (2026-11-08): blocked on this phone
- Rotation is now locked upright whenever the Nokia screens are in use (RotationLock, set and restored by Lockdown); installed, verified accelerometer_rotation goes to 0.
- `setKeyguardDisabled(true)` as device owner returns false while a PIN is set, so the system lock screen cannot be hidden under our control.
- A trust agent (which would let us declare the phone "unlocked" by day and keep the PIN for boot) needs `android.permission.PROVIDE_TRUST_AGENT`, which is signature|privileged on this ROM, so an ordinary app cannot provide one. Only possible if the DumberOS maker installs Phone Guard as a privileged system app: ask them (same conversation as the bootloader warning).
- Still possible: (a) keep the PIN, dark system theme so the PIN pad is dark (owner: better, not ideal); (b) lengthen the time before the phone re-locks after the screen goes off so the PIN pad shows less (Settings.Secure lock_screen_lock_after_timeout, untested); (c) drop the system PIN and use only our own themed lock (a real security downgrade: not recommended).

## Contacting the DumberOS maker (owner may get in touch, 2026-11-08)
Questions to put to them together:
1. **Privileged app.** Can Phone Guard be shipped as a privileged system app (a priv-app folder plus a privapp-permissions entry in their build)? That would allow `PROVIDE_TRUST_AGENT` (a themed lock by day with the real PIN kept for boot), and possibly other device-owner limits we work around today. Say what we need: only a trust agent permission to start.
2. **Bootloader warning.** The unlocked-bootloader screen with its 5 second delay at start: can it be shortened or removed, or the bootloader re-locked on their builds?
3. **Source.** Is DumberOS source published (device tree, patches)? A fork looks too large for us; a small patch is fine.
4. **Quirks seen on the J36:** the NFC service (`com.android.nfc`) crashes about every 15 seconds though the phone has no NFC; an `adb` End-call key sent with the screen off crashed the system process (WakeLock under-locked in ScreenOffMediaKeyHandler); the left key under the screen is swallowed by DumberOS (we use the key_menu_action setting to get it).
5. **Updates.** How do they ship OS updates, and would an update remove our device-owner setup or the left-key setting?

## Option 3 (own lock, no system PIN): reasons it is not recommended
- Storage encryption is tied to the PIN; without it the phone decrypts itself at every boot and our lock is only an app on top, so a lost phone is easier to read (the bootloader is unlocked, which makes it worse), the lock fails if our app crashes, it cannot stop a reboot into recovery, and it would have to offer emergency calls itself. Keep the system PIN.

## Weather built (2026-11-08)
- Phone: `Weather.kt` (parse, icons, rain hint), `WeatherStore.kt` (fetch from Open-Meteo at most every 30 min, 15 min retry after a failure, stale after 3 h so wrong weather is never shown), home strip (outline symbol, temperature, "Rain in 4h" style hint), "Weather" menu entry and forecast screen (next hours, next 3 days, "Weather data by Open-Meteo.com"), tap on the strip opens it where touch works. HA: per-child `location` (rounded to 3 decimals), card v0.11.0 "Weather" section with "Use this house's location" / "Turn weather off". HA deployed (backup /root/phoneguard-bak-20261108d), resource ?v=0.11.0.
- Findings from the Open-Meteo docs: 15-minute rain is native only in Central Europe (ICON-D2), France and North America; the UK gets hourly data interpolated, so the rain hint works by the hour. Attribution is a link "Weather data by Open-Meteo.com" next to the data (the phone shows the text; the card text mentions it). Free use is for non-commercial apps with no subscriptions or ads; 10,000 calls a day.
- Not yet: the forecast screen on the phone was not seen (testing keys put the phone to sleep, locked); the home strip is checked (cloud, 10°). To do: icon check for all symbols, rain hint in a real rainy spell, other themes, phone-width of long descriptions, setting the location in HA (card button) so the phone keeps it after the next sync (the debug-set location is replaced by HA's config until then).
- Test-script lesson: KEYCODE_ENDCALL sleeps the phone even with the screen on; avoid it in scripts, use Back keys to get home.

## Game keys reviewed for a T9 keypad (owner, 2026-11-08)
- Rule: movement on the arrows and 2 4 6 8 (1 3 7 9 for diagonals where it matters), action on OK or 5, pause on the left key, Back on the right key, no letter keys. Crate letters (P, S, B, +) in Blaster are labels on the screen, not keys; the help page now says so in words.
- Blaster: the gun now fires by itself, so one thumb can fly; hold OK or 5 and let go for the beam (a quick tap adds a shot); pod out/in on # or 0; pause on the left key or *; 1 3 7 9 fly diagonally; any direction key starts the game.
- Snake, Pairs, Seeds unchanged (arrows or 2 4 6 8, OK or 5, Seeds 1 to 6 for the pits). Idea: crate symbols drawn as shapes instead of letters.

## Home screen tidy (owner, 2026-11-08)
- Weather strip centred (symbol and temperature as a group, rain note under it); missed calls and messages moved down to make room.
- Location icon in the status bar: caused by Android's own "twilight" service asking for the network location now and then (it was left on automatic dark mode earlier). While the Nokia screens are in use the device-owner switch now turns location off (LocationLock, restored on release; Settings.Secure location_mode now 0). Nothing needs it: weather uses the place chosen in HA. Trade-off: no phone finding by location while the Nokia launcher is on. If wanted back, make it a per-mode setting.
- Dark bar when swiping from the top: it is Android's status bar showing briefly where "Pull-down shade works" is on. Launcher now gives the system bar the theme's LCD colour (untested: swipe test needs the phone unlocked). The sure fix is to untick the shade for the mode in the card, which disables the bar completely (School already does).

## Dark bar on a swipe from the top (owner, 2026-11-08): cannot be removed in School yet
- In School the phone is in Android's lock-task (kiosk) mode, and in that mode the system, not our app, owns the status bar: `setStatusBarDisabled` returns true but adds no record (checked with `dumpsys statusbar`: only the lock-task record exists), and lock-task features (we pass only KEYGUARD) have no way to switch the transient bar off. A swipe from the top edge therefore still shows an empty dark strip for a moment (no icons, no shade). Hiding the bars through the window insets API made no difference. The strip is the system's translucent scrim, drawn above our window.
- Where the shade is off and the kiosk is off (Day with "Locked to allowed apps" unticked), `setStatusBarDisabled` does apply and the bar does not appear (untested: check on a Day swipe).
- Options if it matters: leave it; ask the DumberOS maker (privileged app can call StatusBarManager.disable); or replace lock-task with other confinement (large change).

## Nokia launcher in every mode, with touch (owner, 2026-11-08)
- The Nokia screens are now the home screen in all modes. HA default for new devices: launcher "nokia" for Day and Night too (School still adds no touch, no shade and the kiosk lock). The live child's Day and Night modes were switched to Nokia through the phoneguard.set_child service; the phone synced and became the Home app (checked: LauncherActivity on Home).
- Touch where a mode allows it: a tap chooses a row in any list, the two labels at the bottom act as the soft keys (left Select/Menu, right Back/Contacts), and a tap on the home weather opens the forecast. Forecast screen seen at last: looks right. Apps launched from the lists (Other apps) work with touch in Day.
- Weather location set from HA's home zone (51.7638, -0.3233, rounded) through the service.
- Mistake in testing: a tap on a menu row opened the real WhatsApp (it is allowed in Day and set to Auto); nothing was registered. To think about: tapping a game's screen does nothing yet except the soft keys; maybe touch controls for Pairs (tap a card) and Seeds (tap a pit).

## Home-screen symbols (owner, 2026-11-08)
- Replaced the "N new messages" / "N missed calls" text with small outline symbols and counts, centred in a row under the weather: envelope (new texts), speech bubble (new messages in our local WhatsApp, only when our screens are in use) and handset (missed calls). Where touch is allowed a tap opens the SMS inbox (at the first unread chat), the WhatsApp chat list or the call log; Back returns to the home screen. Tested on the J36 with fake counts (debug command `badges`).
- Ideas: a keypad shortcut to the first symbol (Up on the home screen?), blink on a new arrival, symbols for a low battery or silent mode, check all four themes.

## Palette trial (owner, 2026-11-08)
- Android's colours (Settings, pull-down, PIN pad) are already tinted green: they come from the system palette, which follows the lock-screen wallpaper (`color_source: lock_wallpaper`). Settings headings and switches show pale green on a dark grey-green background.
- Tried writing `theme_customization_overlay_packages` over adb to preset colours (green, then a diagnostic red/VIBRANT): the system rewrote it back to the lock-wallpaper value at once and nothing changed. Then repainted the lock wallpaper in each of the four themes: the palette did NOT follow (all four looked the same green), so the palette seems to be computed only at certain moments (wallpaper picked in Styles, boot) and our wallpaper changes do not trigger it.
- Ideas to try next: set the wallpaper with `WallpaperManager.setBitmap(..., allowBackup)` for FLAG_SYSTEM too (home wallpaper) in case the colours come from there; reboot to see whether the palette is recomputed from the current wallpaper (will need the PIN); check Settings > Wallpaper & style on DumberOS for a colour picker; ask the maker. Fonts need a privileged overlay (the ROM ships Rubik, Lato and Noto Serif only).

## Email (owner, 2026-11-08): future feature
- The envelope symbol is kept for email, which is a later feature (themed inbox, symbol and count on the home screen like the others, a tap opens the email inbox). Needs design: which account and protocol, how the phone gets mail safely (no passwords typed on the phone: set up from HA), what it may show in School.

## Home symbols revised (2026-11-08)
- Text messages: a square speech bubble with two lines. WhatsApp: a round bubble with a tail and a small handset inside. Missed calls: the handset. Symbols only show when the count is above zero, so with nothing new the row is empty by design (the test fake counts were switched off, which is why they went).

## Swipe scrolling and classic games (owner, 2026-11-08)
- Where touch is allowed, a swipe scrolls any list (the main menu is nine rows, seven fit): one row for every 62 design pixels of travel, a tap still chooses the row you touch, and the chosen row stays inside the visible rows. Checked on the J36 (menu swiped up and down). The games themselves ignore all touch, including their soft-key labels, so they stay keypad classics; the Classic Games list and How to play page still take touch.
- Menu entry and list title renamed "Classic Games".

## "Android Apps" (owner, 2026-11-08)
- Main menu entry renamed from "Other apps" to "Android Apps" and hidden in any mode where touch is off (School), since Android apps are built for touch. An app a parent pins to the main menu still shows in School (so the Camera, allowed there, appears only if pinned). Card v0.11.1 text updated and deployed.

## Themed camera: decision 2026-11-08 (build later, maybe tomorrow)
- Decision (owner): build our own themed camera, option 2. Aperture started recording VIDEO in School with no touch controls, so it is useless there. The Camera is pinned in HA for the child at the moment (set 2026-11-08); unpin it until our camera exists if that is a problem.
- Plan: CameraX (Apache-2.0) inside our app. Themed viewfinder (theme colours and fonts, status line, frame); OK takes the photo, and so does the unused button on the side of the phone; 2 toggles flash, 1/3 zoom out/in, 5 or # switches front/back camera, right key goes back; self-timer; a themed photo viewer (next/previous on the arrows). Photos only to start (video later if wanted). Per-mode camera allow/block in the card. Works in School with touch off.
- Side button: not yet identified. Run `adb shell getevent -l` while the owner presses it once and twice to see what it sends. Single press: make our camera the default for the still-image camera intent (as with Home) or catch the key code; double press needs a key the system delivers to us even when we are not in front, so it depends on what the button sends (a Lineage button setting like key_menu_action may help).
- Location in photos (owner wants it, even low accuracy): location is currently switched off while the Nokia screens are in use (it stopped Android's location icon popping up). Options: (a) turn location on at low accuracy (network, no GPS) only while the camera is open and write the fix into the photo's EXIF ourselves; (b) leave it on permanently at low accuracy and accept the icon; (c) use the last known fix. Think about privacy: photo location travels with a photo if it is ever sent (strip it when sharing).
- Open questions: video yes/no later, a dithered monochrome viewfinder option, whether photos can be sent (text or WhatsApp) or stay on the phone, storage limits and a way to copy photos off.

## Battery test, night 1 (prepared 2026-11-08)
- Scenario B first: Night mode (21:00 to 07:00) set explicitly to WhatsApp checks every 30 minutes with our screens ("wa": "30", "wa_app": "simple"); override set back to auto; HA backup of the child data at /root/phoneguard-bak-20261108e. Checked on the phone with the clock moved to 23:00: policy 30, an inexact repeating wake alarm with about a 22 minute window every 30 minutes; clock restored.
- Prepared: stay-awake while plugged in turned off, wireless adb on (192.168.1.228:5555, lasts until the phone restarts), `rig/battery_report.sh <serial>` collects the readings (battery level, discharge, our app's estimated use, alarms, log lines).
- Start: owner unplugs at bedtime and tells me; I reset the battery stats (`dumpsys batterystats --reset`) and note the level. Morning: run the report before plugging in.
- Night 2: set Night "wa" to "off" for the baseline, same hours. Optional night 3: hourly.

## Camera: answers (owner, 2026-11-08)
- Photos AND video, both in the first design. Video with CameraX VideoCapture: OK starts and stops, a recording indicator and elapsed time on the viewfinder, a length/size limit, the mic permission pre-granted by Phone Guard, no shutter sound in silent modes, a mode switch between photo and video (key `*`, or Left/Right on the viewfinder).
- Dithered / monochrome viewfinder as an option (old-phone look): it only changes what is shown on screen. Photos and video are still captured in full colour; the viewfinder frames are converted to the theme's two colours (threshold or ordered dither) for the display. Setting in the camera's own menu, off by default or on by default to be decided; test the cost in battery and frame rate on the J36.
- Photo viewer shows the stored colour photo in the same dithered style (option) so the whole thing looks like an LCD, with a full-colour view available.

## Photo handling (owner, 2026-11-08): backlog
- Where photos and video go. Owner's options: OneDrive (leaning towards it; the family has plenty of storage and it already feeds Immich, which runs at home on a NAS), Google Photos, or straight to Immich. Things to settle: upload only on Wi-Fi (and maybe only while charging), what to upload (new photos only, never deleted ones), whether the phone keeps a local copy and for how long, parent control per mode and a switch in the card, credentials set up from HA so nothing is typed on the phone, duplicates and resumable uploads, size limits for video, and what the child sees ("uploaded" tick in the viewer).
- Notes to check when we get there: OneDrive through Microsoft Graph (an app registration, device-code sign-in, upload sessions for big files); Immich has its own upload API with an API key and handles duplicates by device asset id; Google Photos' API has become restrictive for apps (check current rules before choosing it).

## Media in messages (owner, 2026-11-08): backlog
- Handle pictures, video and voice notes in messages inside the old-school interface. Today messages show markers like "[photo]". Idea: open a received picture full-screen in the dithered LCD style (with the colour version a key away), play voice notes with a simple progress bar, show a video as a still with a play key, download only on request over Wi-Fi, size limits, a parent switch per mode, and a way to attach a photo from our camera when writing. Needs: SMS/MMS (does the carrier support MMS for this SIM, and our own MMS sending), WhatsApp media through the bridge (download, decrypt, thumbnails), and storage rules. A safety question to answer first: who may send pictures to the child in each mode.

## Location per mode (owner, 2026-11-08): after the battery tests
- Context: the phone's location has been switched off while the Nokia screens are in use (to stop Android's location icon popping up); that stopped the HA Companion app reporting, so HA's `person.dumbo` and the map went stale (last report 13:56:48, checked at 16:20). Phone Guard's own "seen" time is a different thing: it is our app's last sync with HA.
- Wanted (owner): per mode, configurable in HA: location tracking on or off, accuracy (low/network only, balanced, high/GPS) and frequency (for example every 30 minutes at school, every 10 minutes at other times, and real time on demand). Probably some tracking even in School. The owner expects an occasional GPS fix every 30 minutes to cost little battery; measure it before committing.
- Design questions: (1) the OS location switch and accuracy can be set by Phone Guard as device owner (setLocationEnabled, the secure location mode); (2) frequency is up to whoever reports the position. The HA Companion app has its own settings (and HA can send it commands such as turning high accuracy mode on and off with an interval, or asking for one update, through the notify service), but Phone Guard could instead report the position itself on a timer in its own sync, which would remove the dependence on the Companion app and let one setting per mode drive everything; (3) a "locate now" / "real time for 30 minutes" button in the card (a boost-like option); (4) keep the camera's photo location tag in mind; (5) fix Android's icon popping up (it came from the automatic dark mode asking for location; automatic dark mode is now off).
- Tests to do: drain per hour with location off, network-only every 30 minutes, GPS every 30 minutes, and real time, same night conditions as the WhatsApp tests (rig/battery_report.sh).

## Quick family dashboard (owner, 2026-11-08): idea
- Non-admin family members need a good view in HA. The idea: a dashboard (and a card that can be embedded at the top of the existing family dashboard) generated by our integration for anyone with a tracked phone: who is where (people and their location, battery, last seen), and, where Phone Guard is installed, the child's mode now, what is next, boost, and an "unlock more time" style action. Should work for someone with nothing set up yet: it discovers the person and device trackers in HA, and shows more once Phone Guard is paired.
- Notes: HA supports dashboard strategies (a custom element that builds the views from live state) and ordinary custom cards: the existing phoneguard-card could gain a "family overview" mode. Care with permissions: non-admins must only get safe actions (see, boost); configuration and release must stay admin-only (check how the services behave for non-admin users today). Design a phone-width layout first and prototype it in the lab dashboard before touching the family one, as always.

## Do we still need the HA Companion app? (owner question, 2026-11-08)
- Yes, for now, even if Phone Guard reports the position itself. Today the Companion app also (a) delivers the sync nudge: the card's `notify` field is `notify.mobile_app_dumbphone2`, a push through the Companion, (b) provides the battery and state sensors, (c) is what the HA person entity and map are built on. If Phone Guard reports the position it would create its own `device_tracker` entity for the person, so (c) no longer needs the Companion.
- To drop the Companion later we would need our own push path (FCM or UnifiedPush, or a short polling interval) and our own sensors for battery and charging. Not now: a possible simplification later, and a separate question from location.

## Restricting what a non-admin can see in HA (owner, 2026-11-08): for consideration
- Wish: non-admin family members see only the family dashboard. Open question whether HA can enforce that.
- What I believe (to verify against the current HA docs and by experiment before designing anything): a dashboard can be set to "require admin", which hides it from non-admin users; each user can choose their default dashboard and hide sidebar items, but those are per-user display choices, not security. HA has no per-user permission system for entities or services that the interface enforces: a logged-in non-admin can read any state and call most services through the API or websocket if they know how, even when the screens hide them.
- What that means for us: (1) treat "hidden" as convenience, not protection; (2) make our own services and websocket commands check that the caller is an admin where it matters (set_child, release, pairing, approvals, remove): the call context carries the user id, so the integration can refuse non-admins; allow safe ones (boost) for non-admins if wanted; (3) keep sensitive data out of ordinary entity attributes: today `sensor.phoneguard_*` carries the whole child config including the WhatsApp allowed numbers; a non-admin can read that through the states API. Split it: a small public sensor (mode, next change, battery, last seen) and the full data only through an admin-checked websocket command; (4) check what a non-admin user can actually do today (a test user), then decide how far to go.
- Decide with the owner after looking at what family members need to see.

## A version that does not need Home Assistant (owner idea, 2026-11-08): for consideration, big piece of work
- For people without HA: a standalone server (a small container the family runs anywhere, or a hosted service) speaking the same protocol the phone already speaks with HA, with its own login, its own web page and its own push path. The core is already separate from HA (the hub logic loads without HA in the tests), so a standalone wrapper is plausible: the transport (an endpoint instead of the HA webhook), storage (a file or small database), users and admin checks, notifications, and the parent view (the card is plain JavaScript and could be hosted as a normal page).
- Costs and questions: who hosts it and who pays; accounts, passwords and security (this is a child-control system, so it must be done properly); push notifications without the Companion app; how photos, location and WhatsApp settings fit in; keeping two front ends in step. A good first step is to keep the hub and protocol free of HA-specific code, and to write down the protocol, so a standalone version stays possible.

## Standalone version on Cloudflare's free tier? (owner question, 2026-11-08)
- Looks viable for a family-sized deployment. Parts: a Worker (the endpoint the phone talks to, with Ed25519 verification through WebCrypto), D1 (SQLite) or Durable Objects with SQLite for storage, Pages for the parent web page (our card as a plain page), Cron Triggers for housekeeping, Cloudflare Access (free for a small number of users) for the parent login, R2 for photos if the OneDrive/Immich idea is not used.
- Free-tier numbers seen in a quick search (must be checked on Cloudflare's own pages before relying on them): Workers free plan about 100,000 requests a day and about 10 ms CPU per request; D1 about 5 GB, 5 million row reads and 100,000 row writes a day (and Cloudflare announced that from 1 September 2026 D1 queries fail when the free daily limits are exceeded); Durable Objects now available on the free plan with SQLite storage only (request quota unclear). A phone that syncs every few minutes uses a few hundred requests a day, so a family fits easily; a hosted service for many families would not.
- Big costs: the Python hub would be rewritten in TypeScript (Python Workers are beta; Cloudflare Containers are paid), push to the phone needs its own route (polling, FCM or UnifiedPush; the phone's microG may or may not carry FCM), and hosting other people's children's data brings legal duties (UK GDPR, the Children's Code), security work and support. Keep it as a possibility; the first step stays "keep the protocol and hub free of HA-specific code".

## GPS battery test series (owner, 2026-11-08): after the WhatsApp battery tests
- Aim: measure the battery cost of position reports so the per-mode location settings can offer sensible choices.
- Runs, same conditions as the WhatsApp tests (phone unplugged, screen off, Night mode, WhatsApp checks off, Wi-Fi on, no SIM, report with `rig/battery_report.sh` over wireless adb): (0) baseline, location off (phase A of the WhatsApp test serves, same hours); (1) network-only (low accuracy) every 30 minutes; (2) GPS every 30 minutes; (3) GPS every 10 minutes; (4) GPS every minute; (5) live (continuous updates, one a second). Optional: network-only every 10 minutes and every minute for comparison.
- Length: 3 hours for the slow ones; the every-minute and live runs may be 1 to 2 hours because they drain faster. Compare % per hour and the estimated mAh for our app and the location service from `dumpsys batterystats`; also note how many fixes succeeded.
- How to drive the test: the cleanest is to build the feature first: Phone Guard asks Android for a fix on its own timer (LocationManager, one-shot request with a timeout, or updates at the chosen interval) and reports it to HA; then the test is just setting the mode's frequency and accuracy in HA. Needs: location switched on while testing (setLocationEnabled), the location permission granted to Phone Guard by the device owner, and the HA Companion's own location reporting turned off during the test so only one thing asks for fixes.
- Watch-outs: GPS indoors may not get a fix, and searching costs more than a fix does, so put the phone by a window or take the same spot for every run and record fix success; the first fix after a long gap takes longer (cold start) than later ones; Android batches and delays background requests unless a foreground service or an exact alarm is used; the location icon will appear in the status bar during requests (hide it if we can).
- Then decide defaults for each mode and the allowed choices on the card.

## Spotify in the Nokia interface (owner idea, 2026-11-08): research, not started
- Aim: a themed music player on the keypad that plays her Spotify playlists.
- Options found (searches on 2026-11-08; verify before building):
  1. Control the official Spotify app from our own themed "Now playing" screen through Android's media session (play/pause, next, previous, title, artist, album art, volume). Official, works with any music app, needs notification access for Phone Guard (we can grant it with WRITE_SECURE_SETTINGS). Cannot browse her playlists (Spotify's browse service only answers its approved callers).
  2. Spotify's App Remote SDK (official, Android): our app controls the Spotify app in the background; can start a playlist or track by its Spotify link, reads the player state and album art; needs the Spotify app installed, a registered client id and one-time consent. Premium is reported to matter for on-demand playback (free accounts are limited); the playlist-browsing part (content API) needs checking in the SDK docs.
  3. Spotify Web API: list her playlists and start one on a device. Development mode rules tightened in 2026: the app owner needs Premium, limited users (reported as 5 per app, previously 25), a smaller set of endpoints (playback control and playlist management reported to remain as of July 2026); the postponement of the changes for existing apps is unclear. Fine for one family if the numbers hold; check the current Spotify page.
  4. Unofficial player (librespot-based): would give a fully themed player with browsing, but Spotify says such clients are probably forbidden, it needs Premium and risks the account being blocked; the account is hers. Not recommended without a firm decision (compare the WhatsApp bridge decision).
  5. Text clients (ncspot, spotify-tui, spotifyd): terminal programs built on librespot or the Web API; not suited to this phone.
- Suggested path: stage 1, a themed Now Playing screen over the media session plus a "Music" menu entry that opens Spotify; stage 2, a playlist picker (App Remote or Web API) so playlists can be chosen on the keypad; stage 3, album art in the dithered style, sleep timer, and per-mode rules (for example no music in School).
- Open points: free or Premium account (and Family plan); can the Spotify app run on this phone (no Google Play; microG present; the App Blocker app may interfere; offline downloads need storage); where does the sound come from (the J36 has no usable loudspeaker as we found, so headphones or Bluetooth); touch-off: Spotify's own UI is touch only, so playlists must be chosen from our screens; battery cost of streaming (test like the others); parent control from HA (HA's Spotify integration can also control playback).

## Spotify: decisions so far (owner, 2026-11-08)
- Option 2 chosen: Spotify App Remote SDK driving the official Spotify app in the background. Owner thinks the account is on a Family plan (Premium); she will use Bluetooth headphones; start with playlists and a simple search, then browsing. The owner has seen a simplified client built the same way: if the name is remembered, look at it for ideas (check its licence before reusing any code).
- To check before building: (1) can the App Remote content API list her playlists (it has a browse tree, but its limits are unclear); (2) the App Remote has no search, so search probably needs the Web API (search endpoint availability in development mode and the user cap/Premium rule for the app owner); (3) the SDK is Spotify's own library with its own licence: do not commit it to the open repository, document how to add it; (4) the Spotify app must be installed, allowed in the modes where music is wanted, logged in and Bluetooth must work with the DumberOS build; (5) register the app on Spotify's developer dashboard (client id, package name and signing fingerprint of the release key), and add her account as an allowed user if the Web API is used.
- Keypad plan for the screens: Music menu > Playlists, Search, Now playing; Up/Down moves, OK plays, 4/6 previous/next, 5 pause, Up/Down on Now playing for volume, `*` shuffle, `#` repeat; search typed with the T9 keyboard we already have.

## Spotify: the clients the owner named (research 2026-11-08)
- Spotify Lite: retired by Spotify in November 2025; a Wear OS port was made by modifying the old Lite APK (halving the drawables). Not a base to build on.
- Sidespot: described on the Mudita forum (June 2026, version 0.2.1, review by nilss) as a lightweight Spotify app for small-screened phones, including DumberOS phones. Has the user's playlists and search; no playlist management, no artist pages; an "e-ink mode" (white background); rated 3 to 4 out of 5. Found through a post by its developer on Reddit r/sidephone ("lightweight Spotify client for sidephone"), which could not be fetched. NOT known: how it talks to Spotify (official app in the background, the Web API, or an unofficial library), whether Premium is needed, touch or keypad, licence, where to download. To find out: read the Reddit post, ask the developer, or look at the app on the phone (decide on the source of the APK first).
- Outify (Codeberg, TomKo): third-party open-source Android client, Kotlin plus Rust, built on librespot, needs Premium, its own README says use can be against Spotify's terms, early development.
- Jetispot (GitHub, original by iTaysonLab, archived; forks such as BobbyESP): Compose and Material 3 client built on librespot-java, Apache-2.0, needs Premium, listed as discontinued.
- So of the four, only Sidespot might use the "official app in the background" approach; Outify and Jetispot use the unofficial librespot route (the one with the terms-of-service and account risk). The plan stays: App Remote SDK driving the official app, unless Sidespot turns out to be reusable.
- Decision (owner, 2026-11-08): write our own Spotify screens rather than adopt Sidespot or another client, so the whole player works from the keypad. One-time set-up (Spotify login and the App Remote consent) needs touch, so do it in a touch mode first.

## Battery and screen-time monitoring, built in (owner idea, 2026-11-08)
- Aim: see what is using the battery in everyday use and learn what "normal" looks like, instead of relying on one-off tests (location and mobile signal cost vary too much for lab numbers to hold).
- Phone side (new sampler, stored locally, uploaded in the normal sync): a sample about every 15 minutes and on each event (plug in or out, screen on or off, mode change, boost): battery level, charging, mode, screen-on minutes, foreground minutes per app (we already count usage per day; add per hour and per mode, and fix the under-count of multi-screen apps), signal bars and Wi-Fi state, location mode and last fix, WhatsApp check count and connected time, bytes by Phone Guard and by the WhatsApp bridge (TrafficStats), and the cycle count and charge counter if the phone reports them (battery health).
- Attribution: without special permission, work it out statistically (drain per hour in each state, for example screen on versus off, mode, location on or off, signal bars), which is exactly the "learn normal use" idea. With the `BATTERY_STATS` permission (can be granted once over adb, like the other grants) we could read Android's own per-app estimates from `BatteryUsageStats`; or ask the DumberOS maker. Worth trying the adb grant first.
- HA side: store a compact history (30 to 90 days), publish sensors (battery, drain per hour now and normal for this mode and hour, screen minutes, estimated time left), learn a rolling normal per mode and per hour of day, and raise a notice when the drain is clearly above normal ("draining 3 times faster than usual, mostly screen and WhatsApp"). The card gets a battery chart over days with the mode bands behind it and a "what used it" list.
- On the phone: a Nokia-style battery screen in Settings (level bar, charging state, rough time left, today's screen time and top apps).
- Privacy: this is app-usage data about a child; make it visible to her (the battery screen) and parents (the card), keep a limited history, and let the card show it only to admins at first.
- Ties in with: the GPS test series (the monitoring can replace much of it once location frequency is a setting), the quick family dashboard (battery per person), and the non-admin visibility question.
- Decision (owner, 2026-11-08): use Android's own power data as well as our own readings (the adb grant for `BATTERY_STATS`, and `DUMP` if needed, joins the other one-time grants in the install order), combine them for a detailed breakdown in HA, and use what we learn to choose defaults per mode.
- Notes: Android's per-app numbers are model-based estimates from the phone's power profile, not measured current, and the J36's profile may be generic, so first compare them with our lab numbers (the WhatsApp nights, the GPS series) before trusting them; read them as deltas since the last sample, since they accumulate since the last full charge. The breakdown should separate screen, each app, the radios (Wi-Fi and mobile), location and the bridge. Defaults: let the card show an estimated daily cost beside each setting (WhatsApp checks, location frequency and accuracy, screen time), and a goal such as "last until 22:00" from which it suggests settings; keep every suggestion a suggestion, parents decide.

## Battery test results (2026-11-08, afternoon)
- Phase A (Night, WhatsApp checks OFF, our screens, wireless adb on, Wi-Fi on, no SIM, screen off): 15:46:49 to 18:48:22, 3h 01m on battery, level 100% to 97%, discharge 88.4 mAh (about 29 mAh an hour, about 1% an hour), device awake (uptime) 50.5% of the time, Phone Guard 20 alarm wakeups, no WhatsApp alarm (the policy was off).
- Caveat: the phone was awake half the time even with the screen off. The wireless adb connection and Wi-Fi probably keep it from deep idle, so these numbers are higher than a normal day; the comparison between phases is still fair because the conditions are the same.
- Phase B (WhatsApp checks every 30 minutes) started 18:49:17 at 97%, running for 3 hours.

## Themed camera: code written, NOT yet installed or tried (2026-11-08)
- Built while the battery test runs (nothing installed on the phone): `camera/Dither.kt` (rotation, mirror, 2-percent level stretch, ordered dither or smooth ramp in the theme colours), `CameraModel.kt` (keys: OK/5 or the volume keys or a camera key shoots; Up/Down zoom 1x to 3x; Left/Right photo or video; 1 gallery; 2 flash; 3 timer off/3/10; 4 dots or tones; # front or back camera; Back exits), `CamView.kt` (title bar, framed viewfinder, settings line, soft keys, self-timer count, REC clock), `CameraActivity.kt` (CameraX 1.3.4: live frames to the dithered viewfinder, photo and video saved in colour to DCIM/Camera through MediaStore, gallery by MediaStore with thumbnails dithered or in colour), a "Camera" menu entry (built in, all modes for now), permissions pre-granted for our own package (camera, microphone, media read). Unit tests for the dither and the key model pass.
- To do on the phone: install; try photo, video, flash, zoom, timer, front camera, gallery; check the viewfinder speed and the battery cost; find what the side button sends (`adb shell getevent -l`) and map it; videos in the gallery show a still only (no playback yet); location tag in photos; per-mode camera allow in the card; the pinned Aperture entry can be removed in HA once ours is good.

## Built-in power measurement, stage 1 (2026-11-08): code written, not installed or deployed
- Phone: `PowerLog` keeps a diary (device-protected `power.log`, 3 days) of lines [time, level %, charge counter µAh, current µA, plugged, screen, mode, signal bars, Wi-Fi, location, temperature, our app's network bytes, reason], taken at each sync (about every sync interval), at each evaluation (at most once a minute), and on screen on/off and plug in/out (receivers in GuardService). Sent in the sync as `power` (at most 400 lines, committed only after HA answers).
- HA: `power.py` stores up to 14 days (8000 lines), and the child's state now has a `power` summary for the last 24 hours: hours on battery, mAh per hour overall and split by screen on/off, by mode, by location on/off and by Wi-Fi, current level, full capacity implied by the counter, an hours-left estimate, and a thin chart line. Tests added (test_power.py).
- Still to do: a "Battery" section in the card (chart with mode bands, the splits, hours left); an "unusual drain" notice learned from the history (rolling normal by mode and hour); a Nokia battery screen in Settings; the `BATTERY_STATS` adb grant and Android's own per-app estimates (compare with these lab numbers first); screen minutes per hour from the usage data; Companion-free battery sensors in HA.
- Deploy order: HA files (hub.py, power.py), then install the APK. Charge counter and current may be coarse or unsupported on this phone (the lab test showed a charge counter of 2946000 µAh at 100%, so it exists).

## More done while the test ran (2026-11-08, evening), nothing installed yet
- Card v0.12.0: a "Battery" section (level line for the last day with the modes shaded behind it, green dots when charging, ticks for screen on; hours left; mAh per hour split by screen, mode, location and Wi-Fi). Needs HA deploy and the phone diary before it shows anything.
- Per-mode "Camera offered" setting (HA `camera`, default on in every mode; phone hides the menu entry and the camera screen closes if the mode does not offer it; boost: least restrictive wins). In the card's mode table.
- Nokia battery page: Settings > Battery (last row): a big battery, level and charging, a rough time left from the diary, screen time today and the top three apps. The drain rate maths is tested (`PowerLog.rate`).

## Location per mode: plumbing built (2026-11-08), reporting NOT built
- HA: per mode `location` (off, network = low accuracy, gps) and `loc_every` (minutes, 0 = live), defaults off and 30, validated, copied with the other mode options, in the card (v0.13.0) as "Location" and "Location every". Phone: parsed into the mode, boost takes the more capable setting and the more frequent interval, and the OS location switch now follows the mode's setting (off means location off, replacing the earlier "off whenever the Nokia screens are on" rule). Default is still off, so behaviour does not change until a parent picks one.
- Still to build: the reporter on the phone (alarm-driven one-shot fix by provider, live updates while a live mode is on, permissions pre-granted including background location, a short timeout and the battery cost), sending positions in the sync, and HA turning them into a device tracker for the person (a `device_tracker` entity with zone matching) and a "locate now" boost. Then the GPS battery tests.
- Held back from deploying: pushing this config now would make the phone re-evaluate during the battery test.

## Location reporter built (2026-11-08), untried on the phone
- Phone `LocationReporter`: per mode "off / network / gps" with minutes between reports (0 = live): an inexact wake-up (kind "loc") asks for one fix from the chosen provider with a 20 s (network) or 60 s (GPS) timeout, writes it to a small file, sets the next wake-up and syncs; live mode listens every second, saves one fix per 5 s and syncs every 30 s. Fixes ride in the sync as `loc` (at most 20, committed after HA answers). The app grants itself fine, coarse and background location (a device owner can grant its own sensor permissions); the OS location switch follows the mode.
- HA: keeps the newest valid fix per phone (`fix` in the child state) and publishes `device_tracker.phoneguard_<child>` (latitude, longitude, accuracy, provider; the state is "home" in the Home zone, the zone's name in another zone, else "not_home"). To use it, add that entity to the person's device trackers; once it works the HA Companion's own location reporting can be switched off.
- Not yet: a "locate now" button in the card (a one-shot request over the next sync), the GPS battery test series, a battery-saving rule (skip a GPS fix when the phone has not moved), hiding Android's location icon.

## Battery test results, phase B (2026-11-08, ended early by the owner)
- Phase B (Night, WhatsApp checks every 30 minutes, our screens, wireless adb, no SIM, screen off): 18:49:17 to 19:57:38, 1h 08m on battery, level 97% to 96%, discharge 29.5 mAh = about 26 mAh per hour (phase A, checks off: about 29 mAh per hour), device awake 50.2% of the time. Phone Guard's own estimate from Android over the 68 minutes: 0.0063 mAh (CPU 1.9 s, wake locks 2.7 s), against the phone's total of 29.5 mAh.
- Reading: the cost of checking every 30 minutes is too small to see in the whole-phone drain (B was not higher than A), and Phone Guard's own estimate is a few thousandths of a mAh. The Wi-Fi radio time of a check is not broken out per app in this estimate, and 1% steps of battery hide anything under about 29 mAh, so the honest result is "no measurable cost; below the noise". The built-in monitoring (charge counter in µAh, over days) is the way to see more.
- The big finding is the baseline: about 26 to 29 mAh an hour with the screen off, and the phone awake half the time. That is a drain of roughly 1% an hour, so about 100 hours on paper if left idle, but wireless adb and Wi-Fi are probably keeping it from deep idle; compare with a night with Wi-Fi only and no adb.
- Raw outputs: scratchpad `phaseB_snapshot.txt` and `phaseB_app.txt` (session-only).

## First phone test of the camera and the battery diary (2026-11-08, 20:00 onwards)
- Deployed: HA (backup /root/phoneguard-bak-20261108f, child data in storage-phoneguard.json there), card v0.13.0, APK installed over USB; wireless adb is off again. The app granted itself the camera, microphone, media and location permissions on its first evaluation (a device owner can grant its own sensor permissions).
- Camera works: live dithered viewfinder (slow for about a second at start while the camera wakes), OK takes a colour JPEG into DCIM/Camera, tones mode (4), gallery (1) showing the dithered and the colour photo. Not yet tried: video, timer, zoom, flash, front camera.
- Side button found: it sends KEY_F2 from the `mtk-pmic-keys` input (next to power); one press about 0.27 s down; two quick presses seen as two events 0.14 s apart. Android ignores it. Now mapped: in the launcher it opens the camera (not during a call, and only where the mode offers the camera); inside the camera it takes the picture. It only reaches us while one of our screens is in front; from another app it still does nothing (needs the privileged route). A double press is available for something else later (for example switch photo/video).
- The touchscreen driver (`sitronix_ts_i2c`) also reports gesture keys (W, E, U, O, S, L, Z, C, V, M and others): drawing letters on the screen might be a gesture feature of this phone. Not used.
- Battery diary: first run failed with a SecurityException (the Wi-Fi check needs ACCESS_NETWORK_STATE, which was missing); fixed and checked: HA now shows level, plugged, temperature 22.6 C and the full capacity 2946 mAh, and the chart starts. The charge counter drain figures will fill in once the phone is on battery.
- After installing, the stock launcher was showing after the unlock; pressing Home brings ours back. Probably the lock screen restored the last task. Watch whether it repeats after a reboot.

## Camera: effects, double-press launch, old camera removed (owner, 2026-11-08)
- Shooting effects (key 4 cycles): Plain (no extra copy), Dots (theme-colour two-tone dither), Tones (theme-colour ramp), Mono (black to white), Warm (cream to brown). The viewfinder shows the effect live; each photo is saved in full colour and, unless Plain, a second copy with the effect (`PG_<time>_dots.png` and so on) made from the saved JPEG a moment later, on a background thread: up to 1440 on the long side, with the dots in 2-pixel cells (finer than the screen's 3-pixel cells); PNG. First try: a 2448x3264 JPEG of 1.9 MB gave a 1080x1440 dots PNG of 42 KB; it looks good. Effects do not apply to video. Ideas: more effects (newspaper halftone, posterise, edge/outline, scan lines, a frame with the date), a bigger or choosable dither grain, effect on the live viewfinder in colour once Preview is added.
- F2: now two quick presses open the camera from the launcher (one press does nothing, so a knock does not matter); inside the camera one press is the shutter. Tested with the key events.
- Old camera (Aperture) removed from every menu: it is in the launcher's exclusion list, unpinned in HA and out of School's allowed list. It stays installed because other apps that ask for a camera (a share or take-a-picture button) still use it; if we want those to use ours we would need to answer the still-image camera intent too and be chosen as the default.

## Camera: more effects, colour Normal, landscape, left key (owner requests, 2026-11-08)
- Effects now: Normal (the camera's own colour viewfinder, no extra copy), Dots, Tones, Mono, Warm, Newsprint (45-degree halftone dot screen, dots sized by darkness; redone after the first square-grid version looked blotchy), Poster (four flat shades), Outline (edge lines), Scan lines, Dated frame (tones inside a border with the date and time). Key 4 steps forward, key 6 back. Every effect except Normal saves a second file next to the colour original (`..._dots.png`, `_newsprint`, `_dated` and so on), made from the saved photo on a background thread (about 20 seconds for the heavier ones, longer than it should: optimise later).
- Landscape: the camera screen follows the sensor (fullSensor, ignoring the system's rotation lock) with a wide layout (140 by 105 picture cells on a 640 by 480 grid) and the photo, video and frames are turned to match how the phone is held, so scan lines and everything else stay level in the picture. Layout checked by forcing landscape (debug command `camera land`); NOT yet tried by actually turning the phone: please try holding it sideways.
- Left key (the one under the screen at the top left): the Nokia screens now take it in every mode (before, Day left it to DumberOS, whose selector popped up). In the camera it is Shoot (the key router hands it to the camera while the camera has focus). Checked: Menu key in the camera saved a photo and its effect copy.
- Other notes: logcat on this phone is flooded by an NFC messages loop, so the app's own lines scroll away fast; clear it (`adb logcat -c`) before a test.

## Camera: the screen stays portrait, the picture follows the phone (owner, 2026-11-08)
- The whole camera screen is locked to portrait again (out of keeping with the Nokia look to rotate it). The picture inside follows how the phone is held (an orientation listener, mapped as CameraX recommends): the viewfinder frame is turned a quarter at a time so it looks upright to the person holding the phone, and photos, video and the effect copies are saved upright. Scan lines (and every other effect) are made on the upright picture, so they stay level to the viewer: in a sideways hold they run vertically on the portrait screen. Checked with a debug command that pretends the phone is held sideways (`camrot 1`): portrait hold gave horizontal lines, the pretend sideways hold gave vertical ones on the screen. NOT tried with a real turn of the phone or a real scene: please try, and say if it is upside down the wrong way round (a one-line fix).
- Normal is now drawn from the camera's frames in colour (at twice the cell resolution, smooth) instead of using a separate preview view, so it turns the same way as the effects. The preview library is no longer used.

## Trebuchet (the stock launcher) keeps coming back (owner, 2026-11-08)
- Cause found: the stock launcher's task (Trebuchet, task 92) has been alive since Day used the stock home screen on 7 Nov and stays in the task stack/recents. Whenever whatever is above it closes (Back from a camera opened in its own task, an unlock that restores the last task, an app crash or reinstall) Android falls back to it, although the Home key itself correctly opens ours (Home resolves to Phone Guard Home).
- Fixes: (1) done now: after each unlock (USER_PRESENT, a receiver in GuardService) the phone goes to our home screen when the mode uses the Nokia screens and no call is on, like a Nokia waking up; (2) force-stopped Trebuchet once; (3) still open: take its task out of the stack for good (a reboot would do it) or hide the stock launcher in Nokia modes, with a safe fallback in case our launcher fails (see the launcher watchdog item).
- Not yet seen on a real unlock: please watch the next unlock.

## Stock launcher hidden while the Nokia screens are the home (owner, 2026-11-08)
- Trebuchet (`com.android.launcher3`) is now hidden by Phone Guard (device-owner app hiding, which also removes its window from the background list) whenever the mode's home screen is Nokia AND Android already sends the Home action to our launcher. It is put back when a mode uses another home screen, and the release flow unhides everything Phone Guard hid (it is in the hidden list). Checked on the phone: the package is gone from the list, evaluations are steady (hid=0 unhid=0 on repeat), our launcher is in front.
- Safety to remember: with the stock launcher hidden, a failing Nokia launcher leaves no home screen. Recovery: Release from HA (unhides it), or set a mode's home screen to Normal. Still worth building the launcher watchdog/fallback (backlog) before leaving this on a child's phone for real.
- The phone has no Home key (keys: left soft key, Back, Call, End/power, D-pad, number keys): Back from our home opens Contacts; End goes home.

## Camera help key; and the Android pull-down in Day (owner, 2026-11-08)
- Camera: the left key under the screen is now Help (it was Shoot, which OK, the side button and the volume keys already do). It shows a page listing every key (OK/side button, zoom, photo/video, gallery, flash, timer, effects forward and back, front/back camera, the gallery keys and the effect names); any key closes it. While a video records the left key is Stop. Viewed on the phone: it fits the screen in the green theme.
- The full Android notification and quick-settings pull-down works in Day (Day has "Pull-down shade works" ticked): out of keeping with the Nokia launcher (owner). To decide. What the shade gives today: Android notifications (the real WhatsApp's, system notices), quick settings (Wi-Fi, Bluetooth for the headphones, brightness, torch). Options: (a) keep it in Day only, (b) turn it off in every mode and give the Nokia Settings its own entries that open the matching Android pages (Bluetooth, Wi-Fi, display), plus our own notification symbols (already: texts, WhatsApp, missed calls), (c) a themed pull-down of our own. Suggested: (b) in steps: add Settings > Bluetooth / Wi-Fi / Display (open the Android settings pages; touch needed), then untick the shade in Day.

## Video, and Android settings from our Settings (owner, 2026-11-08)
- Video mode failed with "No camera": this camera (limited hardware level) cannot run our live-picture stream (YUV analysis) together with a video recorder ("No supported surface combination"). Fix: in video mode the viewfinder is the camera's own plain colour picture (a PreviewView, library camera-view added back) with the recorder; photos keep our drawn viewfinder with the effects. Checked on the phone: REC clock, stop, a 6.4 MB MP4 saved in DCIM/Camera. Known limits: no effects on the video viewfinder (video is saved plain anyway), and the video viewfinder does not turn with the phone (the saved video does follow how it is held).
- Settings > "Android settings" (owner idea): opens Android's own main Settings app, so everything is reachable (Wi-Fi, Bluetooth, apps, display) without the pull-down. Shown only where touch works (hidden in School, like Android Apps). Checked on the phone: Settings list shows it as entry 5, opens the Android settings.
- Next: decide to untick "Pull-down shade works" in Day now that nothing depends on it.

## Side button (F2): double press camera, hold for the torch (owner, 2026-11-08)
- Built `F2Service` (an accessibility service whose only job is to see the F2 key; no screen content is read; switched on by Phone Guard through the secure setting, no prompt) so the side button works in whatever app is in front while the screen is on: two quick presses open the camera, a hold of 0.6 s switches the LED torch on or off without touching the screen, and inside the camera one press is the shutter. The torch goes off by itself after 10 minutes. While the torch is on, a small torch symbol shows in the middle of the Nokia status line (our screens only, so only when the phone is unlocked and awake).
- Checked on the phone: the service is bound and the key interceptor is active; two presses opened the camera and one press inside took a photo; the torch switch and its symbol work (debug command). Not testable by injecting keys: the hold, and everything with the screen OFF.
- Known limit: Android only delivers a key to apps while the screen is on, so with the screen off the button may do nothing. The raw key does arrive at the hardware (seen with getevent). To test: with the screen off, hold the side button. If nothing happens it needs the privileged route (the DumberOS maker) or the key mapped as a wake key.
- Torch cannot be used while the camera screen is open (the camera holds the flash); the hold is ignored there.

## Notifications (owner, 2026-11-08): lock screen done, the rest to consider
- Side button with the screen OFF: confirmed by the owner that neither the hold nor the double press does anything (Android drops keys that are not wake keys while the screen is off). Needs the DumberOS maker (privileged app or F2 as a wake key). Added to the questions for them.
- Lock screen: notification cards are now switched off while the Nokia screens are in use (the secure setting behind Settings > Display > Lock screen, put back afterwards and set again at every evaluation; the device-admin keyguard switch was refused for this admin with a SecurityException even after declaring the policy, so it was not used). The card that was showing was Android's own note that the organisation (Phone Guard) granted location/camera access; it appears whenever Phone Guard pre-grants a sensor permission. Checked on the phone: the lock screen now shows only the date, the big clock and "Charged".
- Notifications in general, to decide: (1) where they appear now: the Android pull-down (Day), heads-up banners over our screens, the lock screen (now off), and the notification dot/icons in Android's status bar (hidden by our screen except on a swipe). (2) Options: (a) leave the pull-down in Day only, nothing else; (b) switch heads-up banners off in Nokia modes (Settings.Global heads_up_notifications_enabled) and show only our symbols (texts, WhatsApp, missed calls); (c) a Notification Listener of our own: it can count every app's notifications, show a "notes" symbol on the home screen and a themed Notifications list (title and text, one key to open the app or clear), and can cancel noise (system notes, apps the parent does not want); this replaces the pull-down for notifications entirely. (d) per-app rules from HA (allow, silence, hide) and a per-mode notification policy next to the other settings. Suggested: (b) now, (c) next, (d) after.
- Also: the permission-granted note on the pull-down and lock screen could be avoided if the sensor permissions were granted at provisioning rather than later (ask the maker, or grant before the organisation name is set; check).

## Music: stage 1 built (2026-11-08), waiting for Spotify on the phone
- "Music" in the main menu (not in School, which is locked to its apps): a Now playing screen that is a remote for whatever music app is playing (Spotify first), through Android's media session (needs the notification listener, which Phone Guard now switches on). Shows title, artist, album, a progress bar with times, play or pause, the volume; keys: OK, 5 or the left key play/pause; D-pad Left/Right or 4/6 previous/next; Up/Down or 2/8 volume; Back leaves. With nothing playing OK opens Spotify (when installed). Unit-tested; not yet tried against a real player: Spotify is not installed on the phone (no music app is).
- Installing Spotify: the phone has no Play Store icon in our menu and I will not fetch an APK from a third-party site (untrusted source). Install it on the phone from the Play Store or the DumberOS "More Apps" store, log in (the owner's job, a password), then I test Now playing, playlists through the App Remote, and search.
- Next stages: playlists and search (App Remote needs a Spotify developer client id registered by the owner: a free dashboard app with package io.github.durkimat.phoneguard and the SHA-1 of our signing key), album art in the dithered style, sleep timer, per-mode music setting in HA.

## Notifications tried on the phone (2026-11-08, 21:05)
- Works: a posted notification gives a bell with a count on the home screen, a Notifications list (with "Clear all"), a detail page (app, title, text; OK opens the app and clears it, 0 clears it). Lock-screen cards and heads-up banners are off in the Nokia modes.
- Finding: writing `enabled_notification_listeners` as a secure setting is NOT enough: Android's notification service ignored it until `adb shell cmd notification allow_listener <component>` was run (a one-time grant like allow_dnd; added to the README grants). Without it the bell never shows and the Music remote cannot see the media session. Could be done without adb by the DumberOS maker's privileged route, or by asking the user to switch it on in Settings > Notifications > Notification access.
- Not yet checked: hidden apps (HA list) being cleared as they arrive, the mode switch to "Cleared" (School), and opening a real app's notification.

## Aurora Store installed (owner asked, 2026-11-08, 21:05)
- Aurora Store 4.8.4 (9.4 MB) downloaded from f-droid.org (the official F-Droid repository: file com.aurora.store_76.apk, SHA-256 fd9c75d9...c4d11 matches the repository index, signed by F-Droid), installed over USB, and given the "install other apps" permission with `appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow` so it does not stop to ask. It shows up in Android Apps (Day) and as a new app waiting in the card (decide there where it may run). Use: open it, choose anonymous sign-in, search for Spotify, install, then open Spotify and log in (owner's job). Risks to note: Aurora is a third-party client for Google Play; installing Spotify this way is the owner's call; some apps refuse an uncertified phone.

## Music remote tried against Spotify (2026-11-08, 21:20)
- Spotify installed (through Aurora) and playing. The Music screen showed the real track (title, artist, album), the progress bar with times, and the controls worked: OK paused and resumed, 6 skipped to the next track, volume steps changed the phone's music volume. The screen refreshes once a second while open.
- Next: the playlist picker and search (needs the owner to register a Spotify developer app: Client ID plus our package name and signing fingerprint), album art in the dithered style, a sleep timer, shuffle and repeat keys, the track title scrolling when it is longer than the line, per-mode Music setting in HA, and keeping Spotify allowed in the modes where music is wanted.

## Spotify developer app (2026-11-08, 21:25)
- Steps, values and fingerprints are in `docs/spotify-setup.md` (debug SHA1 CB:4A:50:...:70, release SHA1 8B:B1:3A:...:4C, package io.github.durkimat.phoneguard, redirect io.github.durkimat.phoneguard://spotify-callback). The owner registers the app and gives me the Client ID. Then: download Spotify's App Remote SDK (github.com/spotify/android-sdk, an official release; not committed to the repo, licence is Spotify's), add the redirect intent filter, build the playlist picker and search (search needs the Web API: PKCE login, scopes playlist-read-private, user-library-read, and the Web API search endpoint).

## Spotify: a shared account on many devices (owner, 2026-11-08)
- The account is shared across several devices (speakers and so on), so what plays and where is not always this phone. The player must know and manage it, otherwise half the time Play starts elsewhere.
- Design: use Spotify's Web API for the player state and the controls, not only the phone's own media session. It reports the playback wherever it is (GET /me/player), lists the devices on the account (GET /me/player/devices), can move playback to a device (PUT /me/player with a device id), and can start a playlist on a chosen device (PUT /me/player/play with a context URI and device id), plus play/pause/next/previous/volume on any device. Needs a Premium account and the scopes user-read-playback-state, user-modify-playback-state, playlist-read-private and user-library-read; sign-in once with PKCE through the redirect URI (touch, in Day).
- Screens: Now playing shows "Playing on: <device>" (and "Not playing" with the last device); a key (for example 0 or *) opens "Play on..." listing the devices with this phone first; "Play here" is the common action: it launches Spotify on the phone in the background so the phone appears as a device, then transfers playback to it. Playlists start on the chosen device (default: this phone). Matching this phone in the list: device type Smartphone and the name equal to the phone's device name, with a manual choice remembered.
- Parents (HA): a per-mode setting for which devices this phone may play on or take over from (so it cannot cut off a speaker in the lounge unless allowed), and whether playback may be moved at all. The phone keeps working without HA (settings are cached).
- Consequence for the earlier plan: with the Web API doing playlists, search, devices and control, the App Remote library (Spotify's licence, a separate download) may not be needed at all. The Spotify developer app is still needed (Client ID, redirect URI, the user list in development mode). The media-session remote stays as the fast local path for play/pause/next on this phone.

## Spotify Client ID is a setting (owner, 2026-11-08, 21:40)
- Not in the code any more. HA integration option `spotify_client_id` (service phoneguard.set_options, admin only; validated as 32 lowercase hex letters and digits, trimmed), one value for every phone; the card (v0.15.0) has a Spotify section with a box, Save and Remove. HA puts it in each phone's config (`spotifyClientId`); with none, the phone's Music menu says "Spotify: set up in HA". The id used to sign in is remembered on the phone so token refreshes keep using it. The Client ID is public by design; the Client secret is never needed, asked for or stored.
- Set for this household: done through the service. Deployed with a backup at /root/phoneguard-bak-20261108h.

## Loudspeaker: "reduce telephony earpiece volume" (owner, 2026-11-08): to test
- The phone does have a usable loudspeaker, we are told: Settings > Sound and vibration, scroll to the very bottom, untick "Reduce telephony earpiece volume". That should fix the speaker (the earpiece may then be louder at its minimum setting). Our earlier finding that speakerphone made no difference on the J36 may have been this setting.
- To do: (1) test it on a real call and with the Speaker key in our call screens, and with Spotify (media volume through the speaker); (2) find what the setting really changes so Phone Guard can switch it per mode (it is not among the obvious keys: nothing named earpiece or reduce in the system, secure or global settings, nor in the LineageOS settings table; probably a DumberOS or vendor setting): toggle it by hand and compare `settings list system`, `settings list secure`, `settings list global`, the lineagesettings table and `getprop` before and after; (3) decide the per-mode rule (for example speaker-friendly in Day for music and speakerphone, earpiece-safe in School or at night so a call cannot blast) and add it to the card as a mode option; (4) if it cannot be set from our app, ask the DumberOS maker.
- If the speaker works, re-enable the Speakerphone entries (Settings > Speakerphone Show/Hide was added because it did not work) and the Speaker key in the call screen by default.

## Main menu rearranged; hiding Trebuchet undone (owner, 2026-11-08, evening)
- Main menu (10 entries, digits 1 to 9 and 0): Phone, Messages, Contacts, [pinned apps], Music, Camera, Android Apps, Weather, Classic Games, Calculator, Logs & Settings (0). Logs & Settings holds Call log, Notifications and Settings and has its own Back. WhatsApp is no longer a main entry: our own WhatsApp screens are inside Messages, and the real WhatsApp (when that is the chosen app) is listed in Android Apps. In School the real WhatsApp would then be unreachable by menu (Android Apps is hidden where touch is off): pin it from the card if wanted there. Pinned apps and more than ten entries still show without a number.
- Hiding the stock launcher (Trebuchet) was a mistake and is switched off: Android binds its gesture and recents service inside that app at every start (`com.android.quickstep.TouchInteractionService`), so with it hidden the phone showed "Trebuchet keeps stopping" repeatedly after a reboot (log: the launcher3 process died and restarted over and over, with a 30 minute back-off). It was unhidden again by the update. To keep its old window from coming back we rely on the go-to-our-home-screen after unlock, and a reboot has cleared the stale task. Still open: make that robust (a launcher watchdog, or ask the DumberOS maker to make Phone Guard the recents provider or to disable Trebuchet's home activity).

## Calling menu and sound test (owner, 2026-11-08, late)
- The green key at the home screen, and the Phone entry in the main menu, now open a calling menu: 1 Recent calls, 2 Contacts, 3 Dial a number (the green key again goes straight to Recent calls; digits 4 to 9 and 0 start dialling). Back returns to wherever it was opened from. (The Nokia green key at idle shows recent calls only; this follows the owner's request.)
- Settings > Sound test (no SIM needed): plays a 3-second two-note tone through 1 the loudspeaker as music uses it, 2 the call speaker (speakerphone), 3 the earpiece, to try the "reduce telephony earpiece volume" setting. The owner changed that setting (before the settings snapshot was taken and the phone has rebooted since, so no difference showed); to find the setting's name, toggle it again with the phone in front of me and diff.
- Test slip noted: an injected OK on the dial screen tried a call to a stale number; no SIM, failed at once.
