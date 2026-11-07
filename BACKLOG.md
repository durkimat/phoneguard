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
