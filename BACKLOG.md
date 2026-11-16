# Phone Guard backlog

Things agreed or parked, newest first within each group. Not a promise of order. See `SPEC.md` for the design.

## Agreed, not started

### Move off Home Assistant to Cloudflare (added 2026-11-14) — RE-SCOPED 2026-11-14 to "one firmware, two editions"
**Superseded by the plan** `docs/plan-two-editions.md` (copy of /archive/phone-backups/plans/plan-phone-guard-two-editions.md, v0.1). Owner decisions (2026-11-14): ONE image and ONE app with two states, **Open** (no companion, nothing locked) and **Family** (device owner + a parent-owned Cloudflare companion); Cloudflare accounts are **parent-owned** (the project runs no multi-tenant service and holds no family's data); **Home Assistant is no longer required** but an optional HA backend/integration is wanted LATER (enrolment would offer "use Home Assistant instead of Cloudflare"): keep it modular now (backend port on the phone, a backend-neutral schema with shared conformance tests, HA code reused later, features kept in parity) but do NOT build HA support in the first release; focus first on the Cloudflare version. The owner's phone is wiped back to a "factory installed" Phone Guard as the enrolment test rig; public outreach is not soon. Implications for the image/app (no work started until the owner says go): `pg_provision` stops forcing device owner at first boot (device owner is set at enrolment), a first-run Open/Family screen, the edition state machine, a spike for becoming device owner without a wipe.
Phase 0 findings that touch the phone (2026-11-14, plan section 13): a brand-new workers.dev subdomain needs about 80 s before HTTPS works, so the enrolment screen must tolerate 'not reachable yet' for a couple of minutes and retry with backoff; a browser page cannot call the Cloudflare API (CORS), so the setup journey needs a helper program on the parent's PC or the Deploy-to-Cloudflare button (decision pending with the owner).
The older notes below stay for detail.
Owner wants Phone Guard's backend to move from Home Assistant to Cloudflare once Phone Guard is integrated in the image.
Research already exists: `docs/companion-research.md` §4 and §8.3 (Worker + Durable Objects, Cloudflare Access for a second parent, deploy script built but not run).
Things this touches, to settle when it is picked up:
- Updates of Phone Guard itself (today: HA checks GitHub releases, phone installs silently as device owner). The Worker (or R2) would serve the APK + SHA-256; the APK is signed with the release key, so keep the app on its OWN key (see `docs/integration-plan.md`): no platform key in the update pipeline.
- Commands/state channel (modes, apps allowed, check-ins, removals), push or polling, offline queue (spike 3), auth (device token + Cloudflare Access for parents), data kept (usage, location?) and privacy.
- The HA card and integration are replaced by a parent web page on the Worker; keep HA integration optional if wanted.
- Cloudflare terms were not read; free-plan limits (Workers, Durable Objects) to check.
- Image side: first-boot provisioning (device owner, backend URL/enrolment token) should be backend-agnostic so this move needs no new image.

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
- Deployed to the live HA with a restart (owner authorised it while asleep). Backups of the previous files on HA: /root/phoneguard-bak-20261107 and /root/phoneguard-bak-20261107b. The test device's allowed list was set to the owner's own number (447700900123) so the test chat keeps working.
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
- Fixed (2026-11-08): group senders showed numbers after a relink. WhatsApp sends names in a separate history bundle that can arrive after the messages, so the name was not yet known when each message was stored. Now the bridge says "history" after each bundle and the app looks the stored senders' names up again a few seconds after each bundle and each connection (store.renameSenders via Bridge.NameFor), then redraws. Verified on the J36 without relinking: 8 of 8 messages from others in Dunne Chat and 280 of 280 across all groups now carry a name.

## Names in WhatsApp lists (owner's requests, 2026-11-08)
- Settings > WhatsApp > "Names in lists": how a group sender's name is shortened in the one-line chat previews: first+last initials (default: Kevin Dunne -> KD; many names use the first and last), all initials, first name up to 5 letters, or full name. One-word names stay as they are, a bare number shows as its last four digits. The full name still shows in the header when a message is opened (messages now carry their sender separately from the text). Unit tested (NameStyle).
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
- The envelope symbol is kept for email, which is a later feature (themed inbox, symbol and count on the home screen like the others, a tap opens the email inbox). **Designed 2026-11-13: see [docs/email-calendar.md](docs/email-calendar.md)** (own IMAP/SMTP provider, send and receive, no HA dependency, hidden in School, calendar stays visible). The old "set up from HA" idea is superseded; credential entry is an open question there.

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

## Sound test results and volume UI (owner, 2026-11-08, late)
- Sound test result (owner, by ear): the loudspeaker (music route) works; the call speaker and the earpiece sound much the same as each other. Meaning: the "reduce telephony earpiece volume" change helps the music loudspeaker but the call routes may still be limited, or the call speaker route is not really switching to the speaker. To investigate: compare the audio route Android reports during each test (`dumpsys audio`: communication device, speakerphone state, which output is active), try `AudioManager.setCommunicationDevice` with the built-in speaker (the newer way instead of setSpeakerphoneOn), and test on a real call when a SIM is in.
- Volume slider (owner): when the volume changes (hardware keys, or Settings), Android's own volume slider appears over the Nokia screens, out of keeping. To investigate options: (a) our own themed volume bar drawn by the launcher and the camera/music screens, with the system slider suppressed (the volume keys reach our screens when they are in front, so we can handle them: adjust the stream ourselves and show our bar, though a stream-volume change from another app still shows Android's); (b) hide the system volume panel (a device owner cannot switch it off directly; a setting in Lineage or an overlay would be needed, ask the DumberOS maker); (c) a Settings > Volume screen with bars for ringer, media and call volume in the theme. Suggested: (a) plus (c).

## Dial pad, call key from anywhere, lock-screen media (2026-11-08)
- Phone entry and the green key now open the dial pad directly: type a number; left key = Contacts, right key = Recent calls (with a number typed they become Call / Clear). Back from either list returns to the pad. End goes home. The old "Recent calls / Contacts / Dial a number" sub-menu screen (Screen.PHONEMENU) is now unreachable: remove it in a tidy-up.
- DialActivity answers android.intent.action.CALL_BUTTON (goes to the empty dial pad). The system's green key from another app sends an unaddressed CALL_BUTTON that lists the stock dialler and ours in a chooser, so F2Service also intercepts KEYCODE_CALL when we are not in front and no call is live, and opens the pad. adb-injected keys do not reach the accessibility filter (F2 shows the same), so this needs a real key press to verify; a "call key" line is written to the phone's log for each press.
- Lock screen: Settings.Secure media_controls_lock_screen is now switched off with lock_screen_show_notifications (saved and restored together).
- End key: in our screens End goes to the home screen (Nokia style); in other apps the system setting end_button_behavior=2 just sleeps the screen. Decide whether End should also go home from other apps.
- Owner wants our own themed volume bar / volume page (replaces the Android slider): see the earlier backlog item; to build next.
- Dial pad exit (2026-11-08): the right key is now Recent, so the pad had no Back. End (hang-up) used to be swallowed by the system (end_button_behavior=2 sleeps the screen before an app sees it): in Nokia modes it is now set to 1 (home; saved and restored by EndKey in Lockdown.kt), and LauncherActivity.onNewIntent turns a home request into goHome(). Safety net: with nothing typed, d-pad Up/Down also leave the pad. Green key on any Nokia screen now opens our pad (Action.CallKey no longer opens the stock call log).
- Trebuchet crash dialog ("Unable to create service TouchInteractionService ... MANAGE_ACTIVITY_TASKS") recurs whenever the system rebinds Quickstep, which happens after each of my test installs (process restart). Stock launcher is still not home. Not fixed; owner-visible only after installs/reboots.
- Dialler navigation (2026-11-08, owner's design): left key cycles Dial pad -> Calls -> Contacts -> Dial pad (label shows the next stop); right key is Back (leaves the dialler to where it was opened from; with digits typed on the pad it is Clear first). OK or the dial key places a call. The hang-up key is KEY_POWER on this phone (getevent), so an app cannot make it "go home"; power stays lock. Removed the EndKey setting. Trying power_button_long_press=3 powered the phone OFF (no menu): do not probe those values again without the owner holding the phone; stock values are restored (settings deleted).
- Boot is clunky: Trebuchet shows for a while and our launcher took ~20 s to appear after a reboot. To investigate (what holds HOME at boot; direct-boot start of our process; make our launcher the first home).
- Open question from the owner: should the home screen's right key really be Contacts? Revisit.

## Spotify sign-in from HA (2026-11-08, deployed, awaiting the owner's test)
- HA: spotify.py (PKCE, pending sign-ins in memory for 10 minutes, one-use state), websocket command phoneguard/spotify_start (admin only; the card passes window.location.origin, HA builds https://<origin>/api/phoneguard/spotify_callback), an unauthenticated callback view that exchanges the code (state proves it is ours), keeps the refresh token with the child (spotify_refresh, never in the card's view, copy list or set_child), service spotify_signout. Config sent to the phone gains spotifyRefresh. Card v0.16.0 (resource ?v=0.16.0): Spotify section with status, Sign in, Sign out, and the exact redirect address to register. 28 HA tests pass.
- Phone: Config.spotifyRefresh; Spotify.adopt takes a new refresh token once (remembers the one HA sent so a rotated local copy is not overwritten), HA clearing it signs the phone out. The on-phone sign-in still works as a fallback.
- Owner must: add https://<their HA address>/api/phoneguard/spotify_callback to Redirect URIs in the Spotify app. The card shows the exact address. Spotify refuses http addresses (so the local http://192.168... address cannot be used).
- Backup of the previous HA files: /root/phoneguard-bak-20261108i on HA.

## Themed volume bar (2026-11-08, installed, awaiting the owner's look)
- keys/VolumeBar.kt: the side-button service (which sees keys with the screen on) now takes the volume keys, changes the stream itself (call when a call is on, music when music plays on the phone, otherwise the ringer) and draws a small panel under the status bar for 1.6 s: a speaker, ten blocks, the stream name (CALL, MUSIC, RING), in the launcher's theme colours and font; a crossed speaker when the ringer is off. It is a non-touchable overlay, so it shows over any app. The camera keeps the volume keys as its shutter. A debug-only broadcast io.github.durkimat.phoneguard.DEBUG_VOLUME (--ez up true/false) shows it without a key.
- Limits: not while the screen is off; Spotify playing on another device (Echo) still changes the ringer here (the Music screen's up/down changes that device). Idea: in the Music screen, make the volume keys control the playing device.
- Android's own slider is not shown because the key is consumed before the system sees it; the quick settings volume and Settings > Sound are unchanged.

## Music extras and tidy-up (2026-11-08 night, installed but not looked at on the phone)
- Home screen right key is Music (when the mode shows Music; otherwise still Contacts). Back from the Music menu returns home when it was opened that way.
- Volume keys while the Music screen is open and Spotify plays on another device: the themed bar shows that device's level (named, e.g. LOUNGE) and changes it in steps of 10 (LauncherActivity.remoteVolume, set in onResume, cleared in onPause).
- Now playing: # cycles repeat (off / all / one), * shuffle as before; the hint lines show both states. Long titles, artists and albums pause, scroll to the end and restart (marquee in NokiaSkin; the view redraws every 90 ms only while a line is too long).
- Music menu: "Sleep timer: off / 15 min / 30 min / 60 min" row; selecting steps it. At the end it pauses Spotify (any device) and local playback (music/SleepTimer.kt, lives in the app process). The Now playing screen shows "sleep in N min".
- Removed the unused PHONEMENU screen and its code. 176 unit tests pass.
- Not yet seen on the phone: it was locked after an adb End-key test (the End/power key locks it, and I cannot unlock a secure lock). Check the Music screen, the marquee and the home screen label tomorrow.
- Lesson: do not send KEYCODE_ENDCALL over adb on this phone, it sleeps and locks it (hang-up is the power key).

## Wi-Fi symbol and start-up delay (2026-11-09)
- Wi-Fi symbol (dot and three arcs) in the status line on every Nokia screen: solid when connected to Wi-Fi, faint when not; not drawn in the locked-down mode (School, i.e. kiosk). Follows the connection through a network callback while the launcher is in front.
- Found overnight: the phone could not join Wi-Fi (NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD from 03:05; the router's password or security changed). Spotify and HA sync then looked "signed out" but only had no network; the Spotify sign-in was intact. Idea: show "No Wi-Fi" more loudly, and warn in the HA card when the phone has not checked in for a while.
- After each install the app's first start takes ~5 s on the main thread and Android sometimes shows "Phone Guard isn't responding" (input focus timed out). Probably cold code after an install, but the same could make a slow boot. Added timing: Controller logs "EVAL slow (...)" with the stages that took over 150 ms, and "waited Nms for another evaluation" if the evaluation lock was busy. Read these at the next boot (adb shell logcat -d -s PhoneGuard).

## Apps and modes: the matrix is the only authority (2026-11-09, built and tested on the test page, NOT deployed)
- Owner's design: no separate approvals or "everything allowed". Visibility = the ticks in the Modes and apps table. Select-all tick at the top of each mode column; a per-mode "Allow new apps automatically" option (off by default: a new app stays hidden until ticked). Mode buttons (Auto, Day, School, Night) sit above the columns and the live mode is lit; the boost is one button "Boost Day +15 min" (each press adds 15, x ends it). The old Boost, Mode and New apps waiting sections are gone; mode behaviour rows moved to a folded "Mode settings" section in the same columns; new apps are highlighted with a NEW tag (click it to mark as seen), plus a "1 new" badge on the folded heading.
- HA: Hub.apply_app_policy (an allowAll mode becomes that mode with every current app ticked, once; auto_new ticks first-seen apps; `seen` tracks what the policy has processed), Hub.set_mode_all + service phoneguard.set_mode_all, set_app_mode marks the app as seen, copy_settings copies auto_new instead of allowAll. The phone is unchanged (it always receives allowAll false and an explicit list). 31 HA tests pass. Card v0.18.0.
- To deploy: copy hub.py, __init__.py, services.yaml and the card, restart HA, bump the card resource to ?v=0.18.0. First sync after that turns Day into an explicit list of today's apps (nothing visible changes; new apps then need a tick).
- More ideas: a "Mark all as seen" button, per-app long-press info, move Weather/Spotify/WhatsApp under one "Extras" heading, a banner when the phone has not checked in for an hour.

## Spotify playback test on the phone (2026-11-09, owner present, passed)
- Playlist list loads, a playlist plays on the phone ("on DumberOS (here)"), next track, shuffle state, repeat cycling (off/all/off) and scrolling titles all work. The owner confirmed the music is audible.
- Cause of "selecting a playlist opens the Spotify app": the phone only appears in the account's device list while Spotify's own app is running here, and Spotify cannot be started invisibly (SpotifyMediaBrowserService refuses connections from other apps: "connection failed"). Cold start now: show "Starting Spotify...", open Spotify for ~1 s, bring our screens back, then poll the device list (it took ~8 s) and play. If the app is already running the playlist starts at once. Idea: start Spotify once when the phone boots or when Music is first opened, so it is ready (battery cost to measure), or look for a quieter way.
- Music screen: removed the volume slider (the themed bar shows on key presses); larger title, artist and album, bigger play/pause symbol, progress bar moved down.
- Displayed repeat/shuffle state can lag a few seconds behind a key press (the player is polled every 3 s).
- Scrolling titles stuck about once a second (2026-11-09): the Music screen ran a full launcher refresh every second (call log, messages and contacts queries on the main thread). It now looks only at the player, off the main thread, and redraws with the last home information. Measured while a long title scrolled: 251 frames in 6 s, 0 janky frames, 99th percentile 12 ms. The scroll is also a seamless loop (a gap, then the start comes back in behind the end).
- Dummy screen for Spotify's cold start (owner's idea): tried a virtual display and launching Spotify onto it. Android refuses (SecurityException): an untrusted virtual display only accepts activities that allow embedding, and trusted displays need a signature permission. Other quiet routes checked: Spotify's media browser service refuses our connection; the App Remote SDK would need its AAR (not on Maven Central) and shows an authorise screen the first time. Remaining ideas: keep Spotify running in the background from boot (battery cost to measure), or ask Spotify's app protocol (AppProtocolRemoteService) through the App Remote SDK after checking its licence and source.
- Spotify cold start without the flash (2026-11-09): a full-screen theme-coloured "Starting Spotify..." panel (keys/ScreenCover.kt, an accessibility overlay from the side-button service) goes up before Spotify is launched and comes down when the phone is listed (or after 20 s at most). Screenshots every 0.4 s through a cold start show only the panel, then the Now playing screen; the phone was listed after 5 s.
- Owner's idea, Spotify offline mode as a parked state: Spotify has no external way to switch offline mode, so it would mean driving its Settings screens through accessibility (fragile, needs window-content access, hidden under the cover panel) and the phone is not a Connect device while offline. Park it until a battery measurement shows the idle Spotify cost is worth it: plan is one unplugged stretch (3 to 4 h) without Spotify and one with it idle in the background, compared with the power diary and batterystats (rig/battery_report.sh).

## Power and memory saver (2026-11-09, built, HA deployed, phone installed)
- Idea (owner): close apps that have not been used for a while, selectable in HA, with care that anything needing the background keeps running.
- Feasibility test: a plain Android "kill background processes" does nothing to Spotify, which keeps a media foreground service even while paused (process state 4 for 10+ minutes). Suspending the app (setPackagesSuspended) also left the process alive. Hiding it for about 0.6 s and showing it again (setApplicationHidden true then false) ends the process cleanly and the app comes back visible, with its data and login. That is what IdleReaper.stop does.
- IdleReaper (phone, GuardService runs it every 3 minutes): an app is idle when max(last used, last seen playing) is older than the mode's "Close unused apps after" (Never, 10, 30, 60 min; default 10). Last used comes from Android usage statistics; "playing" is remembered by the saver itself from the media sessions each run. Only apps used in the last 24 h are touched, and the same app is not stopped again within 30 minutes unless it was used since.
- Never stopped: protected apps (phone, messages, contacts, settings, Home Assistant, our own); the "Keep running" list (default Clock, DAVx5, WhatsApp; edited in HA); an app that is playing or buffering music; an app with an ongoing foreground-service notification that is not a media player (a download, navigation, recording, a call); an app already hidden by a mode.
- HA: new "Power and memory" section (per-mode idle time, a Keep running tick per app, an explanation of what is never closed). The per-mode value is idle_min (config idleMin), the child list keep_running (config keepRunning). update_child now rolls back keys that did not exist before a rejected change. Card v0.20.0, 34 HA tests; phone tests for the idle rule.
- Logged on the phone: "idle apps stopped after N min: ...". Not yet shown in HA.
- To do: show what was closed (count and names) in the card, measure the battery effect with the unplugged test (with and without the saver), consider a memory-pressure rule (close the least recently used apps when free memory is low), and a per-app idle time. Care points still to check: apps that use exact alarms or run jobs (none of ours), a WhatsApp call, DAVx5 sync, Aurora downloads (covered by its ongoing notification).
- Checked on the phone after the saver had closed Spotify (no process): Music, Playlists, a playlist starts it behind the "Starting Spotify..." panel, the phone is listed after about 8 s and the music plays. Paused again afterwards.

## Running Apps screen and a simpler Power and memory section (2026-11-09)
- Phone: Logs & Settings now has "Running Apps" (not in School). Android only shows an app its own process, so "running" is inferred: a music session (playing or paused), an ongoing foreground notification, or an app used or heard in the last 3 h and not stopped since (IdleReaper.running). Left key = Kill (hide and show again, as the saver does), OK = switch to it. Where the phone has its own screen OK goes there, not to the real app: Spotify opens our Now playing, the dialler the dial pad, Messages, Contacts, the Camera, our WhatsApp when its simple screens are in use. Anything else launches normally. Checked on the phone: Spotify listed as paused, OK opened Now playing, Kill ended its process and the list read "Nothing running".
- HA card v0.21.0: Power and memory is now a single "Close unused apps after" row per mode and a short Protected apps list: chips with an X to remove, a pull-down of installed apps plus Add. No per-app tick table.
- Possible next: show the same running list in HA, the screen-off clean-up, the memory rule, reporting (see docs/power-and-memory.md).

## Reporting and screen-off clean-up (2026-11-09)
- Phone: IdleReaper now keeps a ledger (reaped.log: time, package, app name, reason, "would" flag) sent to HA with each sync, and runs two rules: idle (every 3 min) and screen off (an alarm set when the screen goes off, for the mode's screen-off time, fires IdleReaper.sweep if the screen is still off; cancelled when the screen turns on). Report-only (config idleDryRun) records what would be closed and closes nothing.
- HA: new per-mode setting "Close apps when the screen is off for" (screen_off_min, default 5; Never/2/5/10/30), child switch idle_dry_run, and the phone's ledger kept per device (last 300) with a 24 h summary in the Power and memory section (closed count by reason, most-closed apps, the latest few). Card v0.22.0, 36 HA tests.
- Music screen with nothing playing no longer offers to open Spotify: OK (or the left key, labelled Playlists) opens the playlist list when signed in; otherwise the hint says to sign in from the Music menu. The Spotify app is only ever opened by us behind the cover panel.
- Not yet seen working: the screen-off sweep itself (it needs the phone to sleep, which locks it; test when the owner can unlock), and reaped entries reaching HA after a long idle period.

## Music symbol on the home screen, scrolling symbol row (2026-11-09)
- A pair of beamed quavers joins the home-screen symbol row (texts, WhatsApp, missed calls, notifications) while music is playing on this phone (a playing media session; not in School). Tapping it (touch modes) goes straight to Now playing; Back from the Music menu then returns to the home screen. The row refreshes when the player's notification changes and on the minute tick.
- Owner's rule for a wide row: if the symbols are ever wider than the screen the row starts at the left, drifts gently left and back (pausing at each end), and a finger can drag it; a drag pauses the drift for 5 s. Checked with 5 symbols on the phone. Owner expects this row may become a running-app selector later.
- Debug-only broadcast io.github.durkimat.phoneguard.DEBUG_BADGES --ei n 5 shows test symbols (n 0 clears).

## Owner's idea, to plan: Wi-Fi and Bluetooth screens in the Nokia menus (keypad driven, no Android Settings)
- Wi-Fi: on/off, saved networks (connect, forget), nearby networks, join with a password typed on the keypad. A device owner may add and enable networks through the Wi-Fi manager.
- Bluetooth: on/off, paired devices (connect/disconnect, forget), pair a new device. Pairing may still raise Android's own small confirmation dialog; headset profile connection may need hidden APIs.
- Both also useful for School/Night (parent could lock them per mode).

## Wi-Fi and Bluetooth in the Nokia menus (2026-11-09)
- Settings now has Wi-Fi and Bluetooth (also the Wi-Fi symbol in the status line opens Wi-Fi on touch).
- Wi-Fi: on/off row, then saved and nearby networks (">" connected, "(saved)", "(not here)", "*" for secured and unsaved). OK on a network: Connect / Join (open) / Join with password / Change password / Forget. The password is typed on the keypad on the text screen (abc, ABC, 123 only; * has a long symbol set; no word prediction). Needs the location switch: Android refuses the scan list with "Location mode is disabled" otherwise, so the Wi-Fi screen lends location on while it is open and LocationLock.giveBack puts it back when the screen is left (checked: location_mode returned to 0). The password is never logged.
- Bluetooth: on/off works from our screen (device owner), device search lists what it finds, paired devices with Connect/Disconnect/Forget, Pair for new ones. Connect, disconnect and forget use hidden calls and may be refused (message "Android refused"); to be tested with the owner's AYIMA once it is in pairing mode.
- Not tested by me: joining a network with a password (a password is not something I may type; the owner types it on the keypad as the real test of the entry), forgetting a network (it would drop the phone off Wi-Fi), pairing.
- Idea: let a parent lock Wi-Fi and Bluetooth per mode (School).
- Bluetooth pairing panel (2026-11-09): Android shows its own touch dialog (com.android.settings BluetoothPairingDialog) when pairing. Our side-button accessibility service (now allowed to read window content, only used for this dialog) covers it with a panel in the phone's style showing what it asks (name, pairing code) with Pair (OK or left key) and Cancel (Back), and ticks "allow contacts and call history" automatically (harmless for audio). Needs a real pairing to check; the owner can forget AYIMA on the Bluetooth screen and pair again. PIN-entry pairing (legacy devices) is not handled.
- Bluetooth pairing, worked on the phone with the AYIMA (2026-11-09): Android's dialog is Settings' BluetoothPairingDialog with buttons positive_button / negative_button and a Switch (phonebook_sharing_message_confirm_pin) for contacts sharing, none of the standard button ids. The panel (Pair = OK or left key, Cancel = Back) works. To stop the system dialog showing at all: a theme-coloured cover goes up the moment Pair is chosen on our screen and stays through the dialog (replaced by the Pair/Cancel panel, then "Pairing...") until Android reports the bond settled (bonded or none), with a 20 to 60 s safety timeout. Device-initiated pairing still shows the dialog for a fraction of a second before the panel covers it. PIN-entry pairing is not handled.
- Lesson: every reinstall causes a "not responding" box on the first start; always check and clear it (force-stop, Home) after an install.
- Bluetooth: connected devices are found through the A2DP and headset profiles (BluetoothManager.getConnectedDevices only supports GATT, so the "> " marker never showed before). The status line has a Bluetooth rune next to the Wi-Fi symbol: solid when a device is connected, faint when Bluetooth is on with nothing connected, hidden when off or in School. It updates on the Bluetooth connection broadcasts.
- Proposal for installing, removing and updating apps from HA (and Phone Guard self-update through GitHub releases) written: docs/app-management.md (2026-11-09). Waiting on the owner's answers to the open questions.

## Installing, removing and updating apps from HA; Now playing touch (2026-11-09)
- Phone: AppInstaller (jobs come in the settings as appCommands: install, update, remove; each done once; results go back as appResults). Waits for Wi-Fi, 25% battery (or charging) and storage; https download with a SHA-256 check; installs through a package-installer session (silent as device owner); protected apps and Phone Guard cannot be removed. Phone Guard can update itself the same way.
- HA: store.py (F-Droid search and package lookup; GitHub latest release), jobs queue per phone (hub.queue_app, results merged, log), services remove_app, cancel_job, update_phone, check_updates, websocket phoneguard/store_search and store_install (HA looks the app up, downloads once to learn its checksum, queues the install). An update check on the GitHub releases of durkimat/phoneguard every 6 h. Card v0.23.0 "Apps and store" section: update banner, jobs waiting, recent results, installed apps with a cross to remove, store search with Install.
- Releases: controller/publish-release.sh VERSION "notes" builds and signs with the key in ~/.phoneguard-release and creates the GitHub release with the APK and its .sha256 (the key stays on the owner's computer, so no CI secrets are needed). Version name and code come from -PpgVersion.
- Now playing: tap the big play/pause (and the prev/next words) when touch is on; it flips at once. "Playing here" replaces "on <device> (here)". The line under the artist is now the playlist ("Playlist: study") when playing from one, else the album unless it only repeats the song or artist.
- Not tested yet: an actual install and removal through the loop (needs the owner's OK for one real download).
- App install loop tested on the phone through the HA card (2026-11-09, owner approved Calculator You 3.1.2 from f-droid.org, 3.2 MB): search, Install, queued, the phone installed it at its next check-in ("done installed Calculator You") and hid it (new apps start hidden: the matrix rule), then Remove. Found and fixed: a hidden app looks uninstalled to a plain package lookup, so the first remove said "was not installed"; the remover now looks for uninstalled packages too, shows the app again, then uninstalls (done, "removed Calculator You", process gone in about 10 s).
- Store review result: F-Droid alone has none of the mainstream apps. The store now searches three sources together (card v0.24.0): an Official downloads list (WhatsApp, Signal, Telegram; extend ST.OFFICIAL), F-Droid, and IzzyOnDroid (about 1,400 more apps, index cached daily in /config/phoneguard_cache, checksums come from the index so nothing has to be downloaded to check them). Results say which source they come from. Aurora Store stays on the phone for Play-only apps (Spotify). Not built: install from a link (needs the package name read from the APK), a child's request-an-app flow.
- Idea under review (2026-11-09): drive Aurora Store from HA instead of file uploads. Checked on the phone: Aurora answers market://details?id=PKG links (a "Continue to Aurora Store?" confirmation, then its page for the app, with Install/Update/Open/Uninstall buttons; it is a Compose screen, so buttons are found by their text). Phone Guard's accessibility service could press those behind a cover panel. HA would send "install/update PKG from Play" (the parent pastes a Play link or package name), the phone does the rest and reports. Aurora stays installed but hidden, unhidden only while a job runs. Risks: depends on Aurora's screens and on its anonymous Google login (Play terms are grey); pin the Aurora version.

## Apps: stripped back to Aurora plus access control (owner decision, 2026-11-09)
- Removed: HA store search (official/F-Droid/IzzyOnDroid), install and remove jobs for other apps, the remove cross and the installed-apps list, the websocket store commands, the remove_app service, the phone's remover and its install of other apps. Kept: the new-app rule (hidden until ticked), Aurora for users, and updating Phone Guard itself (publish-release.sh, GitHub release check every 6 h, "Phone Guard updates" section with Update this phone, phone installer limited to its own package).
- The experiments are in git history (commit "Store: official downloads, F-Droid and IzzyOnDroid searched together" and before). docs/app-management.md now records the decision.
- Self-update tested on the phone (2026-11-09): HA queued the update (phoneguard.update_phone, with an address and checksum override for a build not on GitHub), the phone downloaded 28 MB over https from HA's Nabu Casa address, checked the SHA-256, installed it silently as device owner and came back as 0.1.1 within about 30 s; still device owner, services bound, no ANR box. Finding: a debuggable/test-only build cannot be installed through an app's package installer (INSTALL_FAILED_TEST_ONLY, adb's -t only), and the job said "done" because the app cannot see the result of replacing itself. The test used a release-type build signed with the debug key; this phone now runs that non-test-only 0.1.1 (so adb `dpm remove-active-admin` no longer works; HA's Release does; debug builds still install with `adb install -r -t`).
- A release signed with the real release key cannot update this phone (different signing key): phones meant for updates through HA must be provisioned with the release-signed build.
- Decision (owner, 2026-11-09): carry on with debug builds installed over adb on the test phone; move to the formal release process (release-signed build on the phone, then updates from HA) once the app is further along. Until then do not press "Update this phone" for v0.2.0 on the test phone (different signing key). v0.2.0 is published on GitHub; the next release will need its version raised.
- Spotify sign-in from HA tested (2026-11-09): the owner pressed Sign in on the card through the Nabu Casa address, approved at Spotify, got the 'Signed in' page; HA stored the refresh token (card shows the account name), the phone took it up at its next check-in ('spotify: signed in from HA') and the playlists load on the phone. No password was typed on the phone.

## Spotify sign-in from a local address (2026-11-09)
- Problem (owner): HA is often used at a local http address (192.168...), and Spotify refuses a plain-http return address.
- Fix (card v0.26.0): the return address no longer comes from the browser's address. HA uses its own https address (Nabu Casa or the external URL, via get_url) even when the card is open at the local IP; the card shows the exact address to register. If HA has no https address at all, a paste-back route: register Spotify's loopback address http://127.0.0.1:8888/callback once, press Sign in, approve at Spotify, the browser ends on a "can't connect" page whose address holds the code, paste that address into the card and press Finish sign-in (service phoneguard websocket spotify_finish reads the state and code). 38 HA tests.
- Not tested on the real flow: the manual route (the owner's HA has Nabu Casa, so the automatic route applies).
- WhatsApp "Auto" (2026-11-09, owner): Auto used the real WhatsApp app whenever it was installed and allowed, even with our embedded WhatsApp linked, so the WhatsApp row vanished from Messages. Auto now keeps our screens once ours is linked (the real app only until then); "Real app" still forces the real one. Card help text updated (v0.29.0). Checked on the phone: the WhatsApp row is back in Messages.
- Development builds: the self-update test left the phone at version code 101, so debug builds numbered 100 were refused as downgrades (INSTALL_FAILED_VERSION_DOWNGRADE, and my installs looked silent because only the last line was shown). The default development version is now 0.1.9 (code 109); releases are 0.2.0 and up. Also: the phone's app is no longer debuggable after the test, so `adb run-as` cannot read its files (logcat still works).

## WhatsApp read status follows the main phone (2026-11-09)
- Before: unread stars only cleared when the chat was opened on the phone; read-elsewhere signals were ignored.
- Now: the Go bridge (wabridge) listens for the main phone marking a chat read (events.MarkChatAsRead, only "read" acted on) and for our own account's read notices (ReceiptTypeReadSelf, or a read receipt that is from us), and tells the app through a new Listener.OnRead(chat, ts). The app clears unread marks for that chat's messages up to that time (WaStore.markReadUpTo). A Go unit test covers the events (one-to-one, group, mark-unread ignored, others' read receipts ignored). AAR rebuilt with build.sh; app built and installed.
- To check with the owner: read a chat on the main phone, then open WhatsApp on this phone (the client only connects every 30 minutes in Day, or when the WhatsApp screen is opened) and see whether the star has gone. If WhatsApp only sends the signal in some cases (read receipts off, for example) it may need another look.
- WhatsApp read status diagnostics (2026-11-09): the bridge now logs (masked, on the phone only) 'app state synced', 'chat marked read/unread elsewhere' and 'read notice of ours', and the app logs 'read elsewhere ... stars cleared / nothing to clear', to see what WhatsApp actually sends. Stale stars from before the feature will not clear by themselves: WhatsApp only sends the signal when the read happens.

- WhatsApp read sync, both ways: opening a chat in our app now sends read receipts and a mark-chat-read patch (wabridge `MarkRead`, `WhatsApp.readChat`), so the main phone clears its unread count. Needs owner test.

- Over-long text now scrolls forwards then back (NokiaSkin.marquee): title bars on every screen, the selected list row, and the Music fields. Anything over 3 screen widths is cut with "...". Unselected rows still just end in "...". Needs owner check on the phone.

- Music: spectrum analyser (Spectrum.kt, Visualizer on the output mix, 16 bars with held peaks) can replace the progress bar. Swap with key 7, a touch on the bar, or Music menu "Bar shows". Choice kept per phone. Only live when playing on this phone (audio elsewhere is silent here). Needs owner test; if Visualizer is refused the log says "spectrum: not available".

- Play on: if this phone is not in Spotify's device list, the list now starts Spotify here first (behind the cover) and waits up to 9 s for the phone to appear, as playing a playlist already did.

- Spectrum v2: largest FFT (1024), bands by pitch in Hz (50 Hz-16 kHz), dB levels with a treble lift and a scale that follows the loudest recent band. v1 used the smallest FFT (340 Hz per bin) so the low bars all read the same bins.

- Spectrum v3: the FFT is now computed here from the raw waveform (Hann window, float) because the Visualizer's own 8-bit FFT clips on bass (low bars moved regardless of the music). Scale floor stops silence being amplified; range 34 dB.

- Bluetooth: "Connected" was shown as soon as Android accepted the request. Now the phone checks the profiles for up to 12 s and says "Not connected (in use?)" if the device never joined (e.g. another phone holds the speaker).

- Spectrum v4: each bar shows its strongest bin rather than the band average, plus a 1.6 power curve, to separate the middle bars for vocals and simple accompaniment.

- Spectrum v5: silence gate at -54 dB (the 8-bit waveform's rounding noise was being lifted into moving bars after a song ended), and bars are cleared as soon as nothing is playing on this phone.

- Music: the play/pause symbol no longer flips back and forth after a press (the expected state is held 5 s while Spotify catches up). With the spectrum on, a slim progress bar sits between the elapsed and total times.

- Spectrum v6: fixed scale (-56 to -8 dB) instead of following the loudest band, so quiet music looks quiet. Logs the loudest band every 5 s ("spectrum: loudest band") to diagnose bars that carry on after a song ends.

- Spectrum v7: captured as played (the default normalised scaling made quiet music look loud), scale -52 to -10 dB, 1 dB treble lift per bar, 9 blocks tall, faster fall. Calibrated from logged levels (loud bass about -18 dB, mids -30, treble -40).

- Now playing: tap left/right (or 4/6) skips the track on key release; hold for 0.5 s and it winds 10 s a second (30 s after five steps) until released (Spotify seek or the media session's seekTo). Volume keys there now redraw lightly instead of a full refresh. Built, not yet installed or tested.

- Radios per mode: designed in docs/radios-per-mode.md (On / Saver / Off per radio per mode, check-in windows, keep-on list, spike first for the switches and a two-night baseline). Not built.
- Other devices (Pixel 3 etc.): input layer (on-screen keypad, touch only in the bottom area, tall-screen scaling), role-based discovery instead of package-name lists, a Doctor self-test, QR provisioning, then bring-up on a Pixel 3 (stock Android 12 first, LineageOS if sensor-permission grants block us). Goal: other similar families.

## Radios per mode: v2 design (owner, 2026-11-09)
Simplified to one per-mode "radios sleep when locked"; check-ins follow loc_every; child intent restored on unlock; Settings > Connections (Wi-Fi, Bluetooth, Mobile data, Flight mode); status icons get a diagonal slash for off. See docs/radios-per-mode.md. Phone to be connected for the spike.

## FM radio as a music source (owner, 2026-11-09: after radio-power testing)
Idea: Music switches between sources behind one interface (Spotify now, FM radio, later others), with the spectrum if possible.
Findings so far (test phone, MT6761): the FM driver is in the image (`/vendor/etc/init/init.fmradio_drv.rc` loads `fmradio_drv_*.ko`, `/dev/fm` exists, owner media:media mode 660) but there is **no FM app or tuner library** in the system (no fmradio package, no broadcastradio HAL, no `android.hardware.radio` feature). An ordinary app (our uid) cannot open `/dev/fm`.
To prove before promising: (1) can we get the stock MediaTek FM app/native lib onto it, and would it run (it needs system/media privileges and SELinux rules; probably not for a normal app); (2) does the FM audio pass through Android's mixer, or straight to the codec by analogue/I2S path. If it bypasses the mixer, `Visualizer(0)` hears nothing and the spectrum cannot work for FM. Headphone lead usually acts as the aerial. Source interface to design first: `MusicSource` (play/pause, station list, now playing, volume, spectrum available?) so Spotify becomes one implementation.

### FM radio research (2026-11-09)
Test phone FM chip is the **MediaTek MT6631** combo connectivity chip (Wi-Fi, Bluetooth, GPS and FM in one; the owner's "MTK8766V" is probably this). On this phone: kernel module `fmradio_drv_mt6631` is loaded, `/dev/fm` exists (`media:media`, mode 660, SELinux `fm_device`, enforcing), and nothing in userspace uses it (no FM app, no `libfmjni`).
Options found: (1) the stock MediaTek FM app from the original Qin firmware or another MT6631 phone, but it needs the `media` uid, an SELinux allow and its proprietary JNI, so it must be part of the ROM, not a normal install; (2) RevampedFMRadio (github.com/iusmac/RevampedFMRadio), an open-source revival of the AOSP FM app with MediaTek support (`libmtkfmjni`), built into a ROM, with SELinux rules; (3) NextRadio and Motorola FM Radio will almost certainly not work: they rely on the maker's own FM service or signature, not on a generic interface; (4) ask the DumberOS maintainers whether the stock FM app can be included. Audio routing on MediaTek is often direct to the codec, so the spectrum may not be possible. Unverified: whether the phone has root or a writable system partition.

### Internet radio as a music source (owner, 2026-11-09, instead of FM)
Research notes below; options and recommendation were given to the owner in chat. Flight mode was dropped the same day (no supported route; workaround rejected).

### Menu sluggishness (2026-11-09) - fixed
Cause: every key press and timer ran a full `refresh()` (230 to 600 ms on the main thread: WhatsApp conversations about 200 ms, status info about 90 ms, the app list, contacts, call log and messages 15 to 75 ms each).
Fix (LauncherActivity): data is now held and read on a background thread in groups (INFO, CONTACTS, CALLS, CONVOS, WA, APPS, SCREEN), only when a group has changed. Changes are flagged by content observers (SMS, call log, contacts), WhatsApp and notes listeners, package and time broadcasts, network changes, and explicit calls after our own actions. `redraw()` reads nothing; `refresh()` redraws then reads whatever is flagged. Nothing is read while the launcher is in the background (observers only set flags). The 20 s timer is now a 60 s INFO flag.
Measured: 10 down-presses gave 12 frames, 0 janky, median 7 ms. A new missed call shows its badge within about a second without polling.
To keep it this way: new data a screen needs goes in a group with an invalidation source; never read data inside `redraw()`, `dispatchKeyEvent` or `skin.render`.

### Music Assistant (owner idea, 2026-11-09)
Home Assistant has a capable add-on/integration, **Music Assistant** (many providers, players, queues, radio, library). Consider later whether the phone could be a Music Assistant player or use it as a source instead of building our own sources: it could replace the Spotify Web API wiring and our radio directory. Not explored; needs a look at its player protocol (Sendspin / Snapcast / universal player), what runs on a phone, and whether it works with the sleeping radios. Decide after internet radio is working, so the music-source interface fits either.

### Internet radio (built 2026-11-09, stages 1 to 4 of the plan)
Done: `music/Radio.kt` (Radio object + RadioService: Media3 ExoPlayer, MediaSession for headset buttons, foreground media-playback service, wake lock so it plays with the screen off; verified playing on the phone with the spectrum). Config: per-mode `radio` off/presets/any (HA default "any" for testing; card row "Internet radio"), optional `radio_stations` list from HA (https only), else the 23 verified UK stations in `assets/radio/stations.json`. UI: Music menu "Radio" row, Radio preset list, Source screen (key 0 on Now playing switches between Radio, Spotify playlists, Spotify play on), radio Now playing (LIVE, presets 1-9, left/right previous/next preset, OK pauses and lets go of the stream). Navigation: Home's Music key goes straight to Now playing when something plays and Back returns home; via the Music menu Back returns to the menu.
Not done: Browse and Find station (radio-browser.info; only for mode "any"); HA editor for the station list (list can be set in the child's data as `radio_stations`); station "now playing" text only shows where the stream sends it (BBC HLS and some commercial streams send none); the music-source interface was kept light (Radio is a second source beside Spotify in LauncherActivity.nowPlayingRaw), refactor to a real `MusicSource` interface when a third source arrives (Music Assistant?); Bauer streams (Magic, Kiss, Greatest Hits, Planet Rock, Absolute) need an `aw_0_1st.skey` query parameter or they answer 500, so their addresses may change; radio-sleep spec: playing radio must count as music playing (hold radios on); data use roughly 25 to 60 MB an hour.

### Radio power saving: built, checks outstanding (2026-11-09)
Built and verified on the phone with fake unplug: sleep after grace, windows for sync, intent kept. Needs the phone unlocked to check: restore at unlock, the new status symbols and the Connections screen (screenshots), Browse/Find on the phone. Needs a SIM phone: mobile data switching and the data symbol. Not done: hotspot as a keep-on reason, a "Radios rest while locked" footer on Connections, radio on-time columns in PowerLog and the HA report, the one-night baseline (sleep on vs off) to prove the saving, the Pixel/other-device work.
Deploy note: the HA integration and card changes (radio setting, station list, radios_sleep) are in ha/; they take effect when copied to HA and Home Assistant restarted. The phone gets them on its next sync after that.

### Radio presets (2026-11-09)
Presets 1 to 9 are the first rows of the Radio list. In "Any station" mode a digit held on radio Now playing (400 ms) stores the playing station there: it swaps places with the station already in the list, or if it came from Browse/Find the station it displaces moves to the end, so nothing is lost (`Radio.arrange`, 1 test). The child's choices are on the phone only (`radio` prefs, "slots"); no way yet to reset them to the parent's list. `*` on Now playing is unused on radio. Adding a station to the end of the list (without a number) is only in Browse/Find results (`*`).

### Bug (owner, 2026-11-09): switching radio to Spotify left Now playing stuck on radio, no controls — fixed, not yet tested on the phone
Cause: Now playing showed the radio whenever a station was chosen, even stopped, so Spotify playing was hidden and the controls went to the radio. Fix: `nowPlayingRaw` shows the radio only while it plays; otherwise Spotify/the local app if that is playing; a paused station only when nothing else plays. Starting Spotify from our screens (play, transfer) now releases the station (`Radio.release`). To check on the phone: play a station, start a playlist from Source > Spotify playlists, and start Spotify from the Spotify app or another device with a station paused.

### Run Phone Guard as a normal app on the owner's own phone (idea, 2026-11-09)
Without device owner the app already enforces nothing, so it is safe to install. Needed: a LAUNCHER entry for the Nokia screens (today only a HOME role), a way to leave (Back is swallowed), a first-run permissions step (device owner normally pre-grants 44), and an on-screen keypad for a touch phone (the same piece the Pixel plan needs). WhatsApp bridge works on a real arm64 phone. Not started.

### Music restructured around providers (owner design, 2026-11-10)
Music menu = providers (Radio, Spotify, each with a play or pause symbol) plus the common Sleep timer and Bar shows. A provider row opens that provider's own Now playing screen ("the provider's app"); the left key is Menu (its sub-menu: Radio = Presets, Find station, Browse stations; Spotify = Playlists, Search, Play on, Sign in/out; both end with Sleep timer and Bar shows), the right key is Back. OK plays and pauses. Keys: 0 sleep timer on both; Spotify 7 bars, 5 play, 4/6 skip, * shuffle, # repeat; Radio * bars, 1-9 presets (hold to set). Home's Music key goes straight to whichever provider plays, and Back returns home; through the Music menu Back returns to it. An idle provider screen says what to do and shows "Radio is playing" / "Spotify is playing" if the other one is. The old Source screen and the "stuck on radio" class of bug are gone (each screen shows only its own provider). Checked in the emulator (the data symbol also shows there thanks to its virtual SIM); not yet on the real phone. A real `MusicSource` interface would be the next tidy-up when a third provider arrives.

### Launcher ANR after a slow boot (seen 2026-11-10)
After a cold boot the dialog "Phone Guard isn't responding" appeared (LauncherActivity, "Waited 5000ms for KeyEvent", our process at 90-100% CPU). The controller log shows BOOT_COMPLETED at 19:29:56 (boot itself was slow) and `EVAL service start` ran on the main thread (same pid and tid) around 19:30:26, doing many DevicePolicyManager binder calls while system_server was busy. At the same time com.android.nfc was in an ANR loop every 15 s ("failed to complete startup"; no NFC device node exists, the GSI declares NFC features anyway), which loads system_server. Next steps: move boot/service-start evaluation off the main thread, batch or skip unchanged DPM calls (restrictions are re-set on every evaluation), and decide whether to disable com.android.nfc on this hardware. Not yet reproduced a second time; check whether the NFC loop existed before this boot.

### Launcher ANR after a boot or an update: found and fixed (2026-11-11)
- Cause (from the saved ANR trace, "Input dispatching timed out"): the main thread was inside BootReceiver.onReceive -> Controller.evaluate -> Enforcer.grantOwnPermissions -> DevicePolicyManager.setPermissionGrantState, waiting on the system. An evaluation takes about 4.5 s on this phone right after an update (about 20 synchronous permission grants), longer after a slow boot, so any key press or broadcast in that window passed Android 5 s limit. The 10 Nov boot ANR above was the same call (EVAL service start on the main thread). The com.android.nfc loop (an "ANR in com.android.nfc" every 15 s) is separate; it loads system_server but is not the cause.
- Fix: Controller.evaluateAsync runs every evaluation on one worker thread (queued requests are folded together), used by the boot/update receiver (goAsync), the alarm receiver, GuardService (service start, package change), the Re-check button and the call screen. grantOwnPermissions only asks for a permission that policy has not already granted. Checked on the phone: two installs with keys sent straight afterwards gave no ANR box; the evaluation runs on its own thread.
- Still to check: a real cold boot (not done: needs a reboot). The NFC loop is still there; disabling com.android.nfc needs the owner go-ahead and a reboot.

- Cold-boot check of the ANR fix and NFC (2026-11-11 15:36): rebooted with the fix installed and the owner unlocked at once; no Phone Guard ANR, the launcher came up much faster. "pm disable-user --user 0 com.android.nfc" did NOT stop the NFC loop (Android keeps restarting the persistent process: "ANR in com.android.nfc ... failed to complete startup" every 15 s). "adb shell pm uninstall -k --user 0 com.android.nfc" did: no restarts after 15:39:32. Reversible with "adb shell cmd package install-existing com.android.nfc". Not yet confirmed across a second reboot. Any new system image for this phone should drop the NFC package or its features (the GSI declares NFC but there is no device node).

## Email and calendar designed; own-build findings (2026-11-13)
- Design written: [docs/email-calendar.md](docs/email-calendar.md). Phases: spike, calendar (ICS), email receive, email send, extras. Owner decisions (updated later 2026-11-13: calendar editing is in v1; password typed with T9 for the MVP, QR-code setup later): no Family Link constraints assumed, no HA dependency, send and receive, no attachments/HTML formatting, 15 minute sync tied to the radio-sleep windows, Email hidden in School and Calendar visible.
- Gmail app on the own build (microG): not needed for the design above, but worth a spike if the owner ever wants the real app. Family Link needs real Play services and is not expected to run on microG.
- **DROPPED (owner, 2026-11-14): the standalone Phone Guard app for ordinary phones** (too many lost features; retro launchers already exist). Original note: Standalone Phone Guard for ordinary phones (replacing Family Link) judged not straightforward: an APK-only, no-root install is limited to soft controls (usage access, accessibility, VPN/DNS filtering, overlays); strong controls need device owner (adb or QR provisioning). Parked.
- Sound profile per mode needs design first (School is already silent, no parent calls).
- Own-build image gaps found on the first system-only flash (for the NAS session): no IMS/VoLTE (calls fail, no Network > Calls entry), mobile data PDN rejected (16F1 on mob.asm.net), SMS untestable, earpiece untested, Treble app QtiAudio crash, F2 side button and Call key do nothing, no Home key, long wait on the Qin logo / unlocked-bootloader screen before the boot animation. Bluetooth A2DP works after the TrebleDroid BT patch (first connect to a speaker succeeded after a reboot).
- Appearance settings (every option in the Mac theme-lab mock-up, with parent control) and the device journey walker (`rig/journey.py`) are built; Appearance screens and Messages/dial/Weather in the Mac look still need a visual check on the device.

## Email and calendar built (2026-11-13, owner away)
- Built on branch feat/email-calendar and merged to main; see the Status section of [docs/email-calendar.md](docs/email-calendar.md). Calendar works on the real phone (events added, shown in agenda and month, repeats expand). Mail works end to end with a demo mailbox; **never tried with a real account**: first job when the owner is back is a real Gmail app-password sign-in, then send and receive, then delete/mark unread.
- Still to do: ICS subscriptions (read-only calendars); try DAVx5 for Google write-back; QR set-up; timed background mail check inside the radio check-in windows (needs the radio design's window hook); new-mail sound/notice (waits for the Sound profile design); Outlook/Microsoft needs OAuth; edit this event only for repeating events; attachments; search.
- Known rough edges: the Event page head line is one line (long when/where truncates); time pickers step by 15 minutes (no typed times); no Next/Previous in the message view; deleting in Sent uses the Trash folder like the Inbox.


## Data use tracking (owner, 2026-11-12: to plan, not started)
- Idea from the owner, noted while testing the own-build baseline. Scope not yet given; nothing decided.
- Questions to settle with the owner: (1) mobile data only, or Wi-Fi too; (2) total only, or per app (Spotify, WhatsApp bridge and updates are the likely heavy ones); (3) who sees it (the child on the phone, the parent in HA, or both); (4) only a report, or also a monthly allowance with warnings and a cut-off (ties to the planned mobile-data switch, hack 11 in docs/workarounds.md); (5) which period (billing month, which day it resets on the carrier plan).
- Things to check when planning: Android's NetworkStatsManager (per-app and per-network totals; needs usage access and, for mobile, a subscriber id, which a device owner may be allowed); whether the own build's privileged app route makes it simpler; the carrier's own count (Smarty) is the one that matters for a limit, so ours is an estimate; needs a phone with a SIM to test, so it follows the SIM stage of the own-build tests (docs/own-build-outcomes.md).
- Likely home: a report sent to HA at check-in, shown on the phoneguard card; keep the data on the phone and send totals only.

### Now playing on the home screen (owner idea, 2026-11-14)
Show the current track or station (title, artist, play state) on the Phone Guard home page in every skin, including Classic Mac, probably replacing or extending the small music note symbol in the status row (`Badge MUSIC`, `LauncherData.musicPlaying`). Rules: long text scrolls (the screens already have `marquee`), per-mode rules apply (hidden where a mode hides music or in School), nothing is drawn when nothing is playing, and the home screen's layout must not move for it (a line near the clock/date or beside "Next up"). Data already exists: `m.music` (title, artist, playing, station for Internet Radio, source = radio/spotify). Test: a layout/golden test per skin, plus the source-guard test (no hard-coded strings) added in 0.3.11.

### FM radio: not working, parked (owner, 2026-11-14)
Owner: "I couldn't get the FM radio to pick up a station even outside with USB-C earphones plugged in. Its station search failed. So for now we'll stick with internet Radio... perhaps we'll come back to the FM radio later, but for now make a note it's not working." The Music menu entry is now **Internet Radio** (Phone Guard 0.3.9); the FM app and libfmcust are no longer in the image (apps.mk lines commented out; the patches and sources stay in the repo).
- **What works** (docs/fm-radio.md): Lineage's MediaTek-derived FMRadio app + our `libfmcust.so` (build/fm/fmcust.c: chip 0x6631, band 87.5-108 MHz, 100 kHz) with `fake_chan` a non-NULL empty table; the app runs as `platform_app` (build/sepolicy/seapp_contexts) so the vendor policy lets it open `/dev/fm`; power up, mute, seek and tune calls return OK, no SELinux denials, no crash; headset-less use via `persist.sys.pg.fm_builtin_antenna=1` (patch 0014) with the chip's short-antenna mode; audio reaches the loudspeaker (software rendering to STREAM_MUSIC).
- **What failed:** outdoors with USB-C earphones the seek found no station; earlier indoor seeks (USB cable as antenna) stopped on noise at 90.6/91.2/99.1/99.7 MHz.
- **Not verified:** libfmcust's cust values are stubs (empty fake-channel table, default thresholds) and the JNI logs no RSSI, so seek threshold/desense/antenna selection (built-in short antenna vs headset) are unproven; USB-C earphones are very probably not the FM antenna on this phone (the FM chip's antenna is the 3.5 mm headset ground on a normal phone; this phone may have no jack).
- **Ideas to resume:** take the real values (RSSI thresholds, softmute, band, antenna switch) from the original Qin firmware's FM customisation (the stock libfmjni has them built in: `/archive/phone-backups/own-build-inputs/fmradio-stock-20261114/libfmjni.so`; try that library with the Lineage app instead of ours); add an RSSI/spectrum readout (FmNative.getRssi) to prove reception; test with a real 3.5 mm headset antenna or an analogue USB-C adapter if the phone has one; check whether the board has an FM antenna pad at all.

### FM radio as a media source (owner, 2026-11-14: once the FM app loads on the phone)
The own-build image now carries Lineage's MediaTek-derived FMRadio app (docs/fm-radio.md); first test is that it loads, powers up /dev/fm, tunes and reports RSSI without errors. If it does, wire it into Phone Guard as a **media source** beside Spotify and the internet radio (the music code has a light source interface, `LauncherActivity.nowPlayingRaw`; the backlog already says to refactor to a real `MusicSource` interface when a third source arrives: this is that third source).
- Needs wired headphones as the antenna: the owner has none yet (will look for some), so real reception cannot be heard until then; a software check of tune/seek/RSSI is enough for now.
- Design questions: how the keypad tunes (presets from HA, up/down seek, number entry), RDS station name/text on the now-playing screen, FM audio routing (the stock vendor audio HAL's FM path vs recording and playing back through the speaker; speaker use needs the headphone antenna anyway), modes (allowed per mode like other sources, off in School), sleep/idle rules for the radio, what happens when headphones are unplugged.
- Control path: either drive the stock FM service (bound service in com.android.fmradio) or talk to it through a small bridge; the FM app itself would stay hidden from the launcher.

### SIM data monitor with a home-screen widget (owner, 2026-11-14: wanted as a new feature once the integrated image is settled)
Decisions so far (owner): in **Settings** enter the SIM plan's **start date** and **allowance** (amount); a **discreet, functional monitor widget on the home screen**; **hide it in School mode** (maybe). Where on the home screen is still open: review options before building.
- Settings fields: plan start date (billing day of month, or exact date for a fixed-term plan), allowance (GB/MB), unit; optional: rollover, warning thresholds (e.g. 80%/100%), plan type (monthly, fixed total, daily cap). Per SIM if dual-SIM (the J35/J36 has two slots): choose which SIM it tracks.
- Measuring: `NetworkStatsManager.querySummaryForDevice` for the mobile network over the current period (start date to next renewal). Needs the subscriber id and `READ_NETWORK_USAGE_HISTORY` / usage access; the privileged-app route (docs/integration-plan.md) now gives `READ_PRIVILEGED_PHONE_STATE` so it should be possible without platform signing: verify on the phone. Carrier's own meter may differ (rounding, what counts); show it as "estimate" or allow a calibration offset ("carrier says X used today").
- Widget placement options to review (mockups on the prototype before touching the live launcher): (a) a thin usage bar under the signal/battery strip, no text; (b) small text beside the signal bars ("3.2 / 10 GB"); (c) a row on the home page under the clock, shown only above a threshold or when a soft key is pressed; (d) a dedicated screen on a hard key (not on home at all), with only a coloured dot on home when near the limit. Constraints: tiny screen, Nokia-style launcher, keep it quiet; the Nokia themes must all work.
- School mode: widget hidden by mode config (field on each mode in the HA card, like other per-mode settings).
- HA: send totals only at check-in, show on the phoneguard card; parent can set the plan remotely too (later, with the Cloudflare move: see that item).
- Related: Data use tracking above (per-app breakdown, Wi-Fi vs mobile, who sees it); keep this item and that one in step.

### Enrolment bundle + keyguard re-evaluation (design input, 2026-11-14; no phone work until the owner says go)
- Cloudflare accounts are parent-owned: no project-run service and no fixed Worker URL in the app. The phone must be told its family's Worker address.
- Bundle JSON `{v:1, u: workerUrl, t: token, k: pin}` (under about 200 chars; `k` = first 16 bytes of SHA-256(serverKey); the phone fetches the full key from `/api/info` over HTTPS and checks it against the pin).
- Delivery order: (a) helper (or WebUSB adb) pushes the file at setup to a known path; `pg-provision.sh` moves it to the app's device-protected dir and deletes it (needs a sepolicy read rule); (b) SMS `pg1:<url>:<token>` from the parent's phone, with the two-sided match confirmation; (c) camera QR later. No rendezvous service. Typed tokens are 8-digit numeric; 10 alphanumeric chars are fine inside bundles.
- SystemUI patch to add: re-evaluate the keyguard when the credential state changes (set PIN while screen off showed Home with no keyguard; clearing a PIN left the stock lock screen up until a second sleep/wake). Make the DPC reset/clear path take effect at once (`resetStateLocked`, or hide when not secure and the lockscreen is disabled).

### TEST BACKLOG: ims38 checks (owner, 2026-11-14: parked; ims38 flashed without wipe, boots, 0.3.14, pgprov "already device owner", heads_up=0, no pg_provision avc seen in logcat)
1. Set a PIN with the screen off: first wake shows the PIN cover (patch 0018).
2. `locksettings clear` drops the stock lock screen at once (patch 0018).
3. Enrol push: `adb push pg-enrol.json /data/local/tmp/`, reboot: pgprov log "enrol:", app log "enrol: bundle stored", file deleted. The PC session can do this one without the owner.
4. Regressions: no-PIN screen off after 60 s, call/star/OK keys, heads-up off, 112.
5. avc check for pg_provision once Rooted debugging is back on (dmesg closed, root off now).

### BUG: screen-wake cycle still present on ims38 while plugged into the PC over USB (owner: not now, enrol first)
Screen lights up, dims, goes off, repeats: the same symptom as before. The ims37 BOUNCER_DOZING/keyguard fix did not cure it; `doze_enabled` reads null. Check whether it only happens on USB power (charging wake/dock dream/stay-awake setting) versus battery, and capture `dumpsys power` and logcat across a cycle.

### Enrol UX decisions (owner, 2026-11-14)
- Setup tool v1 is a desktop app (Windows/Linux/macOS) and does NOT flash in v1. Model is J36 only (not J35).
- Address and token reach the phone by QR code (the phone scans it), with a typed short code as the fallback. Phone Guard therefore needs a camera QR scan plus typed-code entry on the enrol screen. Keep the `pg-enrol.json` push as the dev/test route.
- Details in `~/HA/plan-phone-guard-two-editions.md` section 14.

### Status line wording with a boost (PC session observation, 2026-11-14)
The status page and notification read "Night + boost until Sun 07:00" when a Day boost of 20 minutes is active. `Controller.evaluate` builds it as `<mode name> + boost` and `until` = `Engine.nextChange(...)`, which looks like the next scheduled change, not the boost end. Better: "Night, boosted to Day until 20:20" (name the boosted mode and the boost's own end), then the next scheduled change. Check what `nextChange` returns when a boost is active before changing; shared with the HA path, so keep the HA wording in step.

## Email and calendar from the wizard: phone side built (2026-11-15, app 0.3.20)
- WorkerLink opens `secrets["email.password"]` and calls `Mail.setup` only when a fingerprint of account+password changed (wrong password: not retried until a new one is sent; network failure: config re-sent at the next check-in). Parent removes the account: phone removes it (only if parent-provisioned). Reports `state.mail {configured, error?}`.
- Calendars: `config.calendars` + `secrets["calendar.<id>"]` become read-only ICS subscriptions (`calendar/Ics.kt` parser with RRULE/EXDATE/RECURRENCE-ID/TZID, `calendar/Subscriptions.kt` store + hourly fetch at the companion check-in, https only, 5 MB cap, link sealed with the Keystore Vault, never logged). Shown in the launcher beside the phone's own calendar via `CompositeCalendar`; not writable. Reports `state.calendars [{id, ok, events, error?}]`.
- Not live-tested: needs a real mailbox and calendar link on the J36. Windows time-zone names fall back to the phone's zone. DAVx5 write-back is a separate later item.
- Calendar subscriptions verified live on the J36 with app 0.3.20 (PC session, 2026-11-15): a real Worker, a public iCal link (83 events) reached the phone's Calendar screen; disconnecting emptied it. Email still untested: needs the owner's mailbox and app password typed in the wizard. Subscription events live in our own store, not CalendarContract, so other apps (or DAVx5 write-back) will not see them.

## IDEA (parked, not started): ship the image without third-party proprietary bytes (2026-11-15, owner)
The owner says stick with the current image for now; for a handful of hobbyist users it is unlikely to matter, and any commercial use would need to be far more robust anyway. Do not start unless the owner says go.
- Qin/MediaTek (the 27 files in installer/proprietary-manifest.json): at build time record the block ranges of each file inside system.img, zero them in the published image, and publish an offsets map with md5/sha256 per file. The installer pulls the files from the user's own phone (adb pull, or from the stock firmware zip if stock blocks it), checks them against the manifest, and writes them into the image at the recorded offsets before flashing. No match, no install (wrong firmware gets a clear message). Needs vbmeta/verity off and ext4 extents mapped at build (debugfs/filefrag).
- Better than encrypting the image with a key derived from the file contents: that still ships an encrypted copy; zeroing ships none.
- TrebleDroid parts (prebuilt app, overlays, scripts): same mechanism where each file is byte-identical to something they publish (installer downloads from their repo at a pinned commit with a hash). Modified files need a delta of only our changes, or our changes moved into our own files. Needs from the NAS: every TrebleDroid file in the image with upstream path/commit and modified-or-not.
- Open checks: (a) not tested that a STOCK J36 lets adb pull all 27 files without root (the PC test on our userdebug image pulled all 27, which proves nothing about stock); (b) size discrepancy: the PC measured 20,230,636 bytes for the 27 files on the phone, but the docs say 15,305,037 (15.3 MB, from the input folder). Find out which is right (the phone number may count different files or sizes) and fix docs/licensing.md, docs/image-redistribution.md and docs/releases/image-20261114.md (and the GitHub release text).
- Result of comparing the 27 files on the J36 (ims38) with the stock originals (2026-11-15): 21 are byte-identical (all jars, .so libraries, XML). Six APKs differ: ImsService (7,011,955 B vs 2,158,188 stock), mediatek-res (same size), MtkGbaService, MtkCapCtrl, MtkTelephonyAssist, MtkCarrierConfig. Cause: `android_app_import` with the platform certificate re-signs and repacks them (ims/module/Android.bp). So for the zeroing idea those six need a patch or a different approach. The 27 files total 20.2 MB in the image vs 15.3 MB stock: fix the docs (licensing, image-redistribution, release notes) to say 15.3 MB of stock files plus about 4.9 MB of repacking, or whichever number is wanted.

## Two editions, Phase 1 built (2026-11-15, app 0.4.0, image ims39; owner go relayed by the PC session and confirmed by the owner in the NAS session)
- Design and status: [docs/editions.md](docs/editions.md). Branch feat/editions. Device owner is no longer set at first boot: `pg-provision.sh` does plumbing, then waits for the app's request (`Settings.Global pg_owner_req`) after a confirmed Family pairing. Edition state in `Edition.kt`; Open phones use `OpenConfig`; first-run, Phone options and Phone type screens in the launcher.
- Needs live tests on the J36 (wipe flash): first boot to first-run screen; Open path (Wi-Fi/Bluetooth toggles, force-stop, Android Settings, WhatsApp pairing, mail, options); Family path (pair, sync, DO granted by root service on a first-boot phone, override from a Worker); Release then Open; guided erase; pg_provision/avc denials; the screen wake cycle on USB.
- Not done: local timetable/modes for Open, per-app allow list in Open, calendar link entry on the phone, hiding apps in Open, silent updates (Open never reports).
- build.sh patch loop now tolerates a patch that sits under a later one (0012 under 0018), which had stopped the build.

## Worker updates for existing Cloudflare instances (owner: go ahead AFTER the end-to-end testing, 2026-11-15)
Today: the phone app updates via the parent page (check GitHub, update a phone); the wizard redeploys over an existing Worker and keeps its secrets (`keep_bindings`, setup-tool/engine.mjs; read, not tested). Missing:
- A Worker version and a minimum phone-app version in `/api/info`; "Update available" on the parent page; the phone checks compatibility.
- A wizard "Update my Worker" mode that reads the deployed Worker's version first (and test a real re-run over a live Worker, secrets and families kept).
- Data migrations for the per-family database (only migration tag v1 exists): add a migration step or keep changes backward compatible; a written rule that a Worker change must work with the previous phone app.
- Image updates still need a PC re-flash (separate item).
Do not start until the owner's end-to-end test is finished.

## ims41 live results and what remains (2026-11-15, PC session)
ims41 all pass on the J36 (wipe flash, Family pair/sync/override/boost/release, Open path, guided reset); release image-20261115 published (--latest=false). Fixed: Sound option applies, mobile data state/toggle, readable confirmations, data fail-safe after pairing, confirm cursor on "No, go back", doze/wake cycle.
Remaining: explanation lines on confirm screens still show row numbers; IntentReceiverLeaked not proven fixed; Open WhatsApp/email/PIN untested; Open lacks timetable/modes, allow lists, hiding apps, calendar entry on the phone, silent updates; flash from a stock phone untested; Windows/macOS installer untested.


## ims42 / 0.4.4 (2026-11-15, from the owner's end-to-end run)
Done in this build (needs the PC's live test on the J36):
- WhatsApp relink: `logged_out` (link removed on the main phone) now closes the stale bridge, clears the pairing code and lets Link work at once; the exact "deleted device" error on pair/connect also resets the bridge and retries once. Errors reach the screen in plain words, never the library text.
- WhatsApp list label: history / app state synced count as connected ("Getting your chats..." while importing); a deliberate disconnect (idle timer, between checks) reads "Idle: updates every 30 min or when opened"; only real failures say "Not connected".
- First run is a 3-step wizard (Wi-Fi, Time and language, Phone type) with Skip/Next/Back and a "N of 3" title; time zone and language are Phone Guard lists (Settings has them too, at the end of the list). Time zone uses `AlarmManager.setTimeZone` (SET_TIME_ZONE). Language uses the activity manager's persistent configuration through a hidden call (CHANGE_CONFIGURATION; the manifest declares `usesNonSdkApi` so a system app may call it). Both are new privileged permissions, listed in the privapp XML. **Untested on a device.** If the language call is refused, the log says `regional: language not set`; the fallback is a `persist.sys.locale` set by pg-provision (needs a sepolicy rule) and a reboot. The language list is English variants first, then a few European languages; Phone Guard's own screens stay English.
- "Family companion" screen (EnrolActivity) is in the phone's own theme (bars that fill when focused, keypad navigable, Scan / Connect / Disconnect). Default look comes from the theme chosen on the phone.
- Spotify: when the app is not installed Phone Guard says "Spotify is not installed on this phone" instead of failing silently, and the sync state carries `spotifyInstalled` (true/false). The Worker/wizard side (PC) can show it.
- Spotify Connect spike (paper only): docs/spotify-connect-spike.md.
Not done / notes:
- WhatsApp history is requested only at link time (30 days, newest 20 per chat, allowed chats only), by the Go bridge. Keeping metadata for chats skipped at link time so they can be imported when the allow list grows is not cheap (it is in the bridge's history handler, not the Kotlin side); parked. The PC session is reordering the wizard so "who may message" comes before linking, which avoids the problem.
- The hands-on librespot build was not run (see the spike note).

### ims42 live results (2026-11-15, PC session)
No-wipe flash on the owner's J36: boots in ~110 s, app 0.4.4, device owner kept, SET_TIME_ZONE and CHANGE_CONFIGURATION granted, doze_enabled=0. Time zone (Paris and back to London) and language (English UK, no `regional: language not set`) both verified. WhatsApp list shows no false "Not connected". Published: release v0.4.4 (APK 0.4.4 + companion 0.5.1) and image-20261115-ims42. Owner's Cloudflare is at 0.5.1.
Still untested on a device: the first-run wizard screens (needs a wipe flash), the themed Connect screen, and WhatsApp relink after removing the device on the main phone.

## 0.4.5 (2026-11-15): WhatsApp history waits for the allow list
- The Go bridge now keeps the link-time history in memory (at most 20 bundles, never on disk, never logged) from the moment of pairing. The app brings the companion's allow list up to date first (an immediate sync), then calls `releaseHistory`; if the companion is unreachable the bridge releases it itself after 90 s with whatever list the phone has. Go tests cover: allow-all arriving after the history, nothing allowed (nothing imported), and the bound/timeout.
- Pressing "Get code" on the Link screen syncs first ("Checking settings...") so the code is only shown once the list is fresh.
- Sync state now carries `wa: {linked, status, all, allowed}`. APK only (no image change); the AAR was rebuilt (Go toolchain installed under ~/.local/gotool on the build PC).

Spotify Connect (librespot) rejected 2026-11-15: Spotify's audio-key restriction (see docs/spotify-connect-spike.md). v0.4.5 parent-page app update proven live (Wi-Fi-gated install within seconds, device owner kept). Possible next spikes (owner decides): easier/automatic Spotify app install; Web Playback SDK in the WebView (needs Widevine).

## 0.4.6: adb-callable sync
`am broadcast -n io.github.durkimat.phoneguard/.SyncReceiver` makes the phone sync with the companion now (protected by DUMP, so only shell/root can send it; rate limited to one per 3 s). Used by the setup tool's Spotify install. `spotifyInstalled` is already in the sync state (0.4.4).

## Simple Spotify (spike result, owner decision pending)
Playlist/track links start with `spotify:...:play`, media-session controls work, now playing from the session. See docs/spotify-simple.md. If chosen: favourites list in the parent page (config), phone launches the intent then goes Home, MusicController for the rest; Web API mode stays optional.

## 0.4.7: screen-lock PIN (parent override + child on the phone)
Phone side of the PC's contract: `config.lock {rev, op set|clear, secret "lock.pin"}` is applied once per revision (a newer one always overrides the child) through the device-policy reset-password token; the token is created while no lock exists (active at once), otherwise Settings > Screen lock shows "Allow parent help" (Android asks for the current PIN once). Failures keep the config being re-sent until applied. Child: Settings > Screen lock form (current PIN if set, new, repeat, Remove PIN with a second press); the phone keeps only a salted PBKDF2 hash (key-store sealed) to check the "current PIN", never the PIN; a PIN set outside Phone Guard cannot be verified, so the child is told to ask the parent. Open phone: the row opens Android's own set-PIN screen. State: `state.lock {supported,set,by,rev,error}`. "Your parent changed your PIN" shows when the child opens the screen after a parent change. Untested on a device (APK only; no new permissions).

## 0.4.8: keypad access to the home screen's symbols
D-pad Up on the home screen highlights a symbol (an outline in the theme's ink); Up/Left go back, Down/Right forward (past the last leaves the highlight), OK or the left soft key opens it, End or the right soft key leaves it, any other key (digits, Call) leaves it and does what it always did. Targets, top to bottom: signal/data -> Connections; Wi-Fi -> Wi-Fi list; Bluetooth -> Bluetooth list; battery -> battery page (only where the mode shows Settings); weather -> forecast; each notification symbol -> its inbox, chat list, call log, notes, mail or Now playing. A symbol the mode hides (Wi-Fi, Bluetooth, Settings, weather, badges) is simply not in the list, so nothing blocked is reachable; the targets are Phone Guard's own screens, never Android Settings. First Up lands on the weather when shown, otherwise the first symbol.

## 0.4.9: SIM data widget (owner's design: "option 5, label left, bar right")
Home screen, one line under the status strip: "4.8 GB left" (bold 26, x 14, baseline 78) and a bar x 230..466, y 58..80, 2 px outline, filled with the share USED, in the theme's ink. GB with one decimal from 1 GB (carrier GB: 1 GB = 1000 MB), MB below ("820 MB left"), "0 GB left" with a full bar when over. At 10% or less left (the warning threshold is a setting) an inverted banner replaces it: "Data low: 900 MB left of 10 GB". Not shown without a plan (allowance), a SIM, or when the mode switches it off.
- Settings > Mobile data (also the Data page reached with Up + OK on the widget): used, left, allowance, renews; "Change plan" form: allowance GB (decimals ok), renewal day 1-28 or exact start date, warn at %, correction MB (add/subtract to match the carrier), SIM (only with two SIMs; default the data SIM). Plan lives on the phone (device-protected prefs "dataplan").
- Measure: NetworkStatsManager mobile summary for the SIM's subscriber id (READ_PRIVILEGED_PHONE_STATE, already in the privapp list) from the period start to now, plus the correction; cached a minute.
- Mode key: `dataWidget` (boolean, default true) on each mode; a boost that shows it wins. The Worker mode editor and validation need the same key.
- Sync state: `state.data = {used, allowance, since, until}` (bytes, epoch ms) once a plan is set. Remote setting of the plan by the parent: later.

## 0.4.10: weather above the clock, Now playing line
Weather group (62 px symbol + bold 46 temperature) now centred on y=124, under the data strip and above the clock, always. The old weather slot (y 392) shows, in this order: Now playing while music is on or paused (play triangle or two pause bars at x 24..48, one scrolling "Artist - Title" line from x 64, baseline y 402, size 32, room 392; radio shows "station - now playing"; a progress bar x 24..456, y 422..430 only when the length is known), else the next event, else the rain note (moved there from under the weather, because there is only about 20 px between the weather group and the clock). Only where the mode allows music (the same rule as the music symbol). The weather tap target and the Up-key outline follow the new position (y 90..158). Classic Mac look: its frame clips y<94 and the data line sits at 104..126, so the weather stays lower (centre y 372) and the slot moves to y 440 (the rain note is not shown there). The home screen refreshes about every 5 s while music is playing so the bar moves.

## 0.4.11: rain indicator in the weather band
Band y 90..158 (Mac: centre y 372): current weather at the left (symbol cx 56, temperature bold 46 at x 100, shrunk if wider than 100 px), a 2 px divider at x 206, then rain due (rain symbol size 50 at cx 244, time bold 36 and "N% chance" 24 at x 282) or dry ("Dry" bold 36 and "H 16°  L 8°" 24 at x 222). Rule (`WeatherData.rainBand`): rain due when any of the next 6 forecast hours is >= 30%; the time is the first such hour, the chance the highest over 3 hours from it; raining now reads "Now" (the chance over the next 3 hours, or the condition name if none is given; snow or thunder keep their own symbol); otherwise dry with today's high/low ("Dry" alone if unknown); no forecast yet = the 0.4.10 centred group. The two text lines are drawn so the first glyphs' ink edges line up (the left bearing of each is measured with the skin's own font and cancelled). The old rain note in the music/next-event slot is gone. Tap and Up-key outline cover the whole band. Not seen on a screen.

## 0.4.12: weather band geometry on the clock's colon
The band's divider stands on the clock's colon (measured at draw time from the clock's own font and the time shown; kept to 232..252, default 242 for the monospace default) with the same 22 px gap each side: the temperature's INK ends at divider-22 (outline bounds, not rounded text bounds), the symbol sits 14 left of its ink edge; the forecast block starts at divider+22 (rain symbol size 44 centred 22 in, text 10 after it; dry text at the edge), both text lines ink-aligned. Geometry constants and invariants are in `BandGeometry` (tested); the colon measurement itself needs real fonts, so it is not unit-tested.

## 0.4.13: home screen alignment pass (owner is picky)
Left ink edge column 14 for the signal bars, the data text (ink-measured) and the left soft-key triangle (shifted +1); right ink column 466 for the battery nub (+1), the data bar and the right soft key; the dry text moves two columns right (ink at 265 = line pixels 241..242 + 22 gap); the weather band moves down 4 px (cy 128, Mac stays 372) so the gap below the data strip (about 18) matches the gap above the clock (about 19). Based on PC measurements of 0.4.12; not measured here (no emulator).

## 0.4.14: alignment fixes from the PC's 0.4.13 measurements
The data text started at column 30 instead of 14: the ink-outline helper measured the glyph with whatever text alignment the last drawn text had left in the shared paint (the centred date), which shifted the outline by half a glyph. The helpers now set left alignment and no letter spacing first. The weather band moves 2 px lower (cy 130; Mac 372): band ink 102..157, gaps about 21 / 22 between the data strip (ends 80) and the clock's top ink (180).

## 0.4.15: small fixes from the PC's emulator pass
Screen lock page: the PIN rows read "New PIN" / "Repeat PIN" (the 4 to 8 digits hint is on the status row), and a PIN that cannot be saved or removed brings the page back with a short reason ("Could not set the PIN. Ask your parent", or "Confirm your PIN once first" when a lock already exists) instead of dropping into Settings. The home screen's left soft key reads "Select" while a symbol is highlighted (the skin used the HomeInfo label). Open: the battery highlight only exists where the mode shows Settings (intentional); "0 GB left" over the allowance kept as specified (the owner may prefer "0 MB").

## 0.4.16: menu restructure (owner approved)
Main menu has no "Logs & Settings": [Phone, Messages, Contacts and pinned apps where they exist,] Email, Calendar, Music, Camera, Android Apps, Weather (only with a location, kept after Android Apps), Classic Games, Calculator, Call log, Settings, each under the same mode/edition rules as before. Settings = numbered groups: 1 Look (Theme, Font, Menu style, Appearance), 2 Sound and typing (Predictive text, Speakerphone, Sound test), 3 Connections (Wi-Fi, Bluetooth, Mobile data: N GB, Battery: N%), 4 This phone (Screen lock, Time zone, Language, Phone options [Open], Phone type, WhatsApp, Android settings), 5 Notifications and 6 Running Apps (where the mode shows them). Mobile data in Connections opens the plan page whose first row is the on/off switch (SIM phones). Lists remember their row until the phone goes home. Key goals tested: Call log = Menu 8, Mobile data = Menu 9 3 3, Screen lock = Menu 9 4 1, Theme = Menu 9 1 1 (with the full set of built-ins). `Screen.LOGSET`/`LOGSET_PKG`/`logItems` are gone; `extraItems` holds Notifications/Running for Settings.

## 0.4.17: confirmation pages
On the "Are you sure?" pages (Open phone choice, erase to Family) the explanation lines are plain text: no number, the cursor skips them, and only the two real choices are selectable (number keys still never choose there). Open text re-wrapped: "Nothing is locked." / "No parent controls." / "Changing this later" / "erases the phone." (there is no separate Family "Are you sure?" page; the Family choice goes straight to pairing).

### Off-disk backup of the irreplaceable items (owner, 2026-11-15)
Owner request ("so I don't forget; I'll do it tomorrow = 2026-11-16"): the owner plans to give the NAS docker container a OneDrive cloud mount and back the critical data up into it. Items: DumberOS restore kit, J35 userdata dump (personal: encrypt before it goes to the cloud), J36 dump zip, the app signing keys (/src/keys-pg: losing them means installed phones can't be updated without a reflash), the phone-backups tree. Today everything is on ONE disk (/dev/sdc1, btrfs, single device, no snapshot or offsite job found). When the mount exists: a small scripted copy (rsync with checksums, client-side encryption for the personal data and the keys), a restore test, and a note in docs on where the backups live. Also: /artifacts/pc-backups-20261115 (a world-readable duplicate that includes the J35 personal data) is to be deleted only after the owner says so; the copy filed in /archive/phone-backups/pc-backups-20261115 is the one to keep (mode 700 for the personal folder).

- Resolved 2026-11-15 (size discrepancy, open check (b)): both figures are right. The 27 proprietary files as they are on the phone's own firmware total 15,305,037 bytes (15.3 MB); as installed in the ims43 image (measured from the built system.img with debugfs) they total 20,230,636 bytes (20.2 MB), because the build re-signs and repacks six APKs (ImsService 2,158,188 -> 7,011,955). Categories: 6 APKs, 11 framework jars, 6 native libraries, 4 permission/config XML. Docs and release notes now give both numbers.

## 0.4.18: Aurora Store, the one app Phone Guard installs (owner decision 2026-11-15, relayed by the PC session)
Replaces the 2026-11-09 rule "other apps are never installed from here" with ONE pinned exception. `StoreApp` hard-codes the package (com.aurora.store), the address (https://f-droid.org/repo/com.aurora.store_76.apk), the sha256 (fd9c75d9...c4d11), size (9,360,088) and version (4.8.4, code 76); a command of kind `install` is accepted only if all of pkg/url/sha match (case-insensitive hash), and the phone then runs the pinned values, not the sent ones. Same waits (Wi-Fi, battery 25%, storage) and silent session install as updates; already at that version = "done, already installed". After a successful install, and at every boot or app update, `ensureGrants`: POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES and MANAGE_EXTERNAL_STORAGE are granted through the device owner (the two app-ops may not be grantable that way: unverified; if not, they are set by pg-provision.sh at the next boot of an image that has the new script, ims44+). The new app lands hidden until a parent ticks it for a mode (existing rule). The idle-app closer already skips apps with a foreground-service notification (downloads), so Aurora is not stopped mid-download; Running Apps kill by hand is unchanged. State: `state.store = {installed, version}`. Aurora's first-run welcome and Anonymous login stay a one-time manual step. Untested on a device.

## 0.4.19 / ims44: Aurora findings from the J36
state.store said not installed and the DPM grants were never tried because the store is hidden (a plain package lookup does not see a hidden app): it now looks up uninstalled/hidden packages too and checks FLAG_INSTALLED. One log line summarises the grants ("app store grants: granted=... not granted=..."). The boot script lists the package with `-u` (hidden apps do not show in `path`), sets REQUEST_INSTALL_PACKAGES, tries MANAGE_EXTERNAL_STORAGE with `--uid` and without, logs what `appops get` then says, and grants POST_NOTIFICATIONS. ims44 carries the script.

### Maps, transit and a possible car mode: design note (owner, 2026-11-15; to bear in mind, nothing to build now)
Owner request: keep this in view when designing and building. Not scheduled.

**Maps and transit direction.** Scope is walking navigation, finding places and public transport for the children (they use Citymapper today). No driving navigation on the J36.
- Transit is decided: a native keypad "Transport" screen on top of Transitous (free MOTIS API; tested live by the PC session: UK trains and buses work, about 64 KB for a 3-option plan, timetable data, not real-time). It does not depend on the map choice.
- Map is still open: our own Mapsforge screen (PC mockups to follow in /artifacts/mockups-20261115/) or CoMaps, natively or as a fork. A time-boxed CoMaps trial on the J36 is waiting for the owner.

**AARE (possible later, for the car).** https://github.com/prototux/AARE, GPL-3.0, Kotlin, active: an open reimplementation of the phone side of Android Auto, with no Play Services. It works with CoMaps, OrganicMaps, OsmAnd and any AndroidX Car App Library app. The owner may use it in the car later.

**Design constraints to keep true in current work**
1. Do not break what AARE needs: USB accessory/host, Bluetooth and Wi-Fi staying on while driving, location, foreground services. The idle-app closer already skips apps with a foreground-service notification (`IdleReaper.busyPackages`); keep that true for navigation and AARE. "Radios sleep when locked" must never drop a car connection.
2. A future "Car" mode (maps, music and AARE allowed, location on, screen-off allowed) should be expressible with the existing per-mode matrix: no new mechanism, so keep the per-mode keys flexible.
3. A map screen of our own will not appear on a car screen unless it implements the Car App Library. That is a reason the owner may prefer CoMaps.

## 0.4.20: weather band, H/L beside the current temperature (owner, 2026-11-16)
"Dry" touched the degree sign of the line below, so the high and low moved: stacked ("H 16°" over "L 8°", size 20) to the left of the divider, after the temperature, the right-hand side now holds only the forecast. "Dry" alone is centred on the band; rain-due text is unchanged and H/L shows in both cases (none without a daily forecast). The temperature shrinks only if symbol + temperature + stack do not fit left of the divider. `WeatherData.highLow()`; the dry `RainBand.line2` is now empty. Not seen on a screen yet: needs a pg480 check. The Aurora grants work (appops request watcher, drop MANAGE_EXTERNAL_STORAGE) becomes 0.4.21.

## 0.4.21 / ims45: weather labels, data strip centring, Aurora app-op watcher (owner feedback + J36 test, 2026-11-16)
- H/L labels have no space ("H16°", "L8°"): the stack is one character narrower and the symbol + temperature group moves right by that width (everything stays right-flush at divider - 22, 10 px temp-to-stack gap).
- The data strip was hugging the status line (gaps 10 above, 21 below, measured on 0.4.20): in the non-Mac looks it moves down 5 px (`dataY(mac)`: bar 63..85, text baseline 84, low banner 60..88 with its text baseline 81; the Up-key target follows). Mac stays at `DATA_MAC_SHIFT` (46) from the old rows: not remeasured.
- Aurora, J36 test (owner present): with only POST_NOTIFICATIONS (device owner) and REQUEST_INSTALL_PACKAGES (adb) Aurora downloaded and installed an app from Play. MANAGE_EXTERNAL_STORAGE is NOT needed and is no longer requested or set (boot script, `ensureGrants`, log line).
- Finding: the REQUEST_INSTALL_PACKAGES app-op is dropped when Phone Guard hides and shows the app again (setApplicationHidden), so a boot-time grant is not enough. `pg-provision.sh` now runs a watcher as long as the phone is a Family phone (also the owner branch that used to exit): every 3 s it looks at Settings.Global `pg_appops_req` (non-empty and not 0 = apply now, then reset to 0), and every ~60 s it re-checks; `aurora_ops` sets the op and POST_NOTIFICATIONS only when `appops get` is not "allow" and logs one line `aurora appops (boot|request|poll): was [..], now [..]`. The app writes the request (`StoreApp.requestOps`) after an install/boot/update (`ensureGrants`), when the matrix shows the store (Enforcer) and after the idle-closer's hide/show of it. APK-only phones on an older image have no watcher: they keep the op only until it is dropped.

## 0.4.22: centre axis (owner feedback via PC, 2026-11-16)
The screen's centre axis is the weather divider = the clock's colon (measured at draw time, default x 242 = ink columns 241..242). On the home screen: the data bar's left edge now starts on it (`BandGeometry.dataBarLeft(div)`, right edge stays 466, so about 224 px wide; the fill is the share used of that width); the date and the symbol row are nudged onto it (`DATE_NUDGE` 1.5, `BADGE_NUDGE` 2.5, plus `axisShift` = measured colon - 242 for other fonts/skins; a scrolling too-wide symbol row is not nudged). The "N GB left" words stay at x 14 and shrink if they would come within 10 px of the bar (not measured: no emulator here). The clock stays where it is; the low banner, now-playing line and the left-anchored items are unchanged.

## 0.4.23: Classic Mac layout like the others, data words right-aligned (owner feedback via PC, 2026-11-16)
- Mac: the weather band is now above the clock like every other look (`weatherCy(mac)` 174), the clock baseline is 26 px lower (`clockY(mac)` 296, ink 217..296 per the PC's measurement), the date stays. The Now playing / next event / rain note slot is the same 392 in every look (it takes the space the Mac band used to have, rows 344..399).
- Data words ("4.8 GB left") are right-aligned: their ink ends at divider - 22, the same line as the weather group below, 22 px from the bar; shrunk only if wider than 14..(divider-22).
- Low-data banner text baseline 81 -> 83 so the text is centred vertically in its box (60..88; the earlier 81 left the ink about 1.5 px high).

## 0.4.24: data words were drawn tiny in 0.4.23 (never released)
Cause: the divider (clock colon) was measured AFTER the words' paint was set, and measuring it leaves the shared Paint at the clock's size (120), so the words' ink looked 4x too wide and were shrunk to fit. Now the divider is measured first; the size rule is a pure function (`BandGeometry.dataTextSize`, `dataTextRight`) with a test that "12 GB left", "4.8 GB left" and "999 MB left" (150/172/171 px) stay at 26. Lesson: any helper that measures text mutates the shared Paint; set the paint after, not before.

## ims46: cheaper app-op watcher (PC review, 2026-11-16)
The ims45 watcher read Settings.Global `pg_appops_req` every 3 s in a root loop on every Family phone (about 20 binder calls a minute, all day). Now: a loop that wakes every 10 s and does one native `stat` of `/data/system/users/0/settings_global.xml` (the file that holds Settings.Global); only when its modification time changed does it read the key. If the file cannot be stat'ed from the service's SELinux domain, it reads the key once a minute. The check of the app-op itself runs every 5 minutes (was 1). An init.rc property trigger was the first choice but an app cannot set a system property, so the request stays a Settings.Global write. Not measured on a device (CPU time of pgprov over 10 idle minutes, before and after, is for the PC session: `ps -o time,args -p $(pidof pg-provision.sh)` or `ps -A -o time,args | grep pg-prov`, twice 10 minutes apart); the first-run loop (before an edition exists) still polls every 3 s until the choice is made. Phone Guard APK unchanged (0.4.24).

## 0.4.25: weather widget is one centred group, no divider line (owner, 2026-11-16)
The vertical line is gone (Mac too). The widget is [symbol, temperature, H/L stack] + `WEATHER_SPACER` (22 px) + [rain-due block or "Dry"], and the whole group (measured from ink widths) is centred on the axis (the measured clock colon, default 242), whatever the digits or the right-hand text; with no forecast the current weather alone is centred. Geometry lives in `BandGeometry` (`leftWidth`, `rightWidth`, `groupWidth`, `groupLeft`, `tempSize`, tests for wide-left + Dry, narrow-left + rain-due, no forecast); the temperature only shrinks if the group would not fit between 14 px margins (worst case about 450 of 452 px). Tap target / Up-key outline: unchanged, the full band (8..472), not the group's bounds. The data strip stays symmetrical about the axis independently (words end at axis - 22, bar starts on the axis): the owner may ask for something else. Not released: ims46 does not contain it (ims47 only if an image is rebuilt anyway).

### Offline Spotify playlist (owner, 2026-11-16: "we'll come back to this, an excellent feature")
Let a favourite Spotify playlist be downloaded for offline listening, with a sync that brings it up to date (could run overnight). Benefits: far less mobile data, probably less battery. First assessment (PC session): use Spotify's OWN Premium Download on the playlist (no ripping, no librespot: Spotify's terms and DRM); Spotify keeps downloaded playlists in sync whenever it is online; keep "Download using cellular" OFF so it is Wi-Fi only. Phone Guard's part: (1) a one-time way to switch the chosen playlist to Download (UI automation, or a parent step); (2) a night sync window: Wi-Fi allowed, charging, start Spotify, let it sync, close it again (the idle-app closer and "Radios sleep when locked" must not interfere; a per-mode "sync window" setting); (3) offline PLAYBACK: our Web-API based player cannot control Spotify without internet, so offline needs media-session controls (cmd media_session / MediaController) plus `spotify:playlist:<id>:play` deep links (the "Simple Spotify" spike result: playlists and tracks autoplay by deep link, albums and artists do not). Unknowns to test on a device: whether Spotify syncs downloads in the background unattended, storage (about 3-5 MB a song), Spotify's 30-day online re-check, Premium required, and how Spotify behaves with our Aurora-installed copy (no Play Services). Nothing to build now.

## 0.4.26: data strip is one centred group (owner, 2026-11-16: "let's centre the data strip and see how it looks")
[words] + `DATA_SPACER` (22) + [bar, `DATA_BAR_W` 200 px, 2 px outline, fill = share used, rows 63..85]: the whole group is centred on the axis (measured colon, default 242), the words keep the bold 26 size (shrunk only above 230 px, never needed: "10.5 GB left" is about 190). The old rules (bar starts on the axis, words end at axis - 22) are gone. The group shifts a little when the digits change, as the weather widget does. Alternative if the owner prefers a bar that never moves: fix the words zone to the widest text and right-align the words in it. Low-data banner unchanged.

## Handoff 23 imported (2026-11-16): companion 0.8.1
Default mode "School" -> "School/work" (Worker defaults, parent page, docs, golden-schedule.json), and a test fix in protocol.test.mjs (match the PIN as a whole value; epoch-ms `now` containing "4821" false-positived). Worker tests all pass on Node 22 (vectors 16, timetable 9, schedule 17, hub 52, track 15, spotify 7, enrol 1, update 11, passkey 21, protocol 85). Phone side needs no change: the app takes mode names from the config, and only comments and test fixtures say "School". PC's older copies of docs/install/open-phone.md and docs/releasing.md were NOT taken (ours are newer: new menu structure; the companion-only-releases section).

## Handoff 24 imported + 0.4.27: the phone fetches Spotify through Aurora (storeinstall), 2026-11-16
Companion 0.8.2 (Worker, page, docs) imported (tests: hub 53, protocol 85, others as before). Phone side (0.4.27, spec `spec-storeinstall.md`): the sync state reports `storeInstall: true`; a `storeinstall` app command (only `com.spotify.music`) is run by `StoreInstall` on the app-job thread: it waits (stays queued, nothing reported) while there is no Wi-Fi, no store, no screen helper, a call, or the phone is locked; otherwise it shows Aurora if it was hidden (and asks the root helper for its install app-op), covers the screen with the themed panel "Getting Spotify...", wakes the screen (6 min wake lock), opens `market://details?id=` in Aurora and every 4 s reads the front window through F2Service (`F2Service.readScreen()`, `clickLabel()`: ACTION_CLICK on the node or its first clickable parent) and follows `StoreDrive` (core, pure, tested): Aurora's first-run pages (Anonymous, Next, Finish, Allow), "Continue to Aurora", Install (max 4 presses), Android's installer Install, "Installer permission" screen (asks the helper again, gives up after 6 looks). Done = the package appears; failures in plain words ("the app was not found in the store", "the app store could not reach Google Play", "Android did not let the install go ahead", "the app store did not finish in time" ...) after about 4 minutes, never retried here (the Worker retries at most every 6 h). At the end, always: cover removed, wake lock released, Aurora hidden again if it was hidden, back to the home screen; a job cut short by a restart is tidied at the next start/boot (`StoreInstall.cleanup`). Then `Controller.evaluateAsync` so the new app lands hidden until a parent ticks it. Limits/unknowns, untested on a device: Aurora's labels in a language other than English; a PIN-locked phone (the job waits, an accessibility service cannot type the PIN); a lock-task/kiosk mode (School) might refuse to start Aurora in the foreground; Aurora's real first-run pages and Spotify's store page layout (the reference script worked on the J36 and emulator for a calculator); the install app-op needs an image with the watcher (ims44+/ims46).

## 0.4.28: hidden apps are seen (PC finding on the J36, 2026-11-16)
(a) `MusicController.spotifyInstalled` used a plain `getPackageInfo`, which does not see an app hidden with `setApplicationHidden`: the phone said "Spotify missing" for a hidden Spotify (parent page "app missing", a needless storeinstall). Now `Enforcer.installed()` (MATCH_UNINSTALLED_PACKAGES + FLAG_INSTALLED), the same tolerant check the store code uses. (b) The apps list sent to the parent is `launcherPackages` (apps with a launcher icon: a hidden app has none) plus the packages WE recorded hiding. A user-installed app that was hidden but not in our record (record lost, e.g. data cleared, or hidden by an older install/tool) was therefore missing from the list, and could never be unhidden either. `Enforcer.hiddenUserApps()` finds hidden non-system apps not in our record and `applyApps` takes them over (log line "taking over hidden apps: ..."): they then show in the list as hidden and the mode decides again. System apps hidden by something else are still left alone. No code path unhides an app that is not in our record, so `hidden:false` for Spotify means it was actually visible (its mode's allow list includes it, or it was unhidden by hand).

## 0.4.29: all Spotify playlists, not only the first 50 (owner via PC, 2026-11-16)
`Spotify.playlists()` asked `/me/playlists?limit=50` once, so a playlist past the first 50 never appeared (the owner's favourite). Now it pages with `offset` 50 at a time up to `PLAYLIST_CAP` 300 (stops when Spotify's `next` is null); the first page is shown at once while the rest loads; a later page that fails ends the list there; one log line "spotify playlists: N loaded" (with "(cap of 300 hit)" when it was cut). "N tracks" labels and the order are unchanged. Ordering (pins / recent first) is a separate design, not done: Spotify's own pins are not in the Web API, so it would be a phone-side pin list plus recently played.

## 0.4.30: playlist pins and recent-first (owner approved the design via PC, 2026-11-16)
On the Playlists screen: `*` pins or unpins the highlighted playlist (a "* " marker before its name, a notice "Pinned" / "Unpinned", at most 50 pins, "Pin list is full" otherwise; the cursor stays on that playlist after the re-sort; the hint "* pin" shows at the bottom when it fits). Starting a playlist (from Playlists, or a playlist in Search results) stores its last-played time (newest 100 kept). Order (pure `PlaylistOrder.order`, tests): pinned first (most recently played first, else Spotify's order), then playlists played in the last 14 days (most recent first), then the rest in Spotify's order; a pin or play for a playlist not in the list is ignored but kept (a restored playlist comes back pinned). Stored on the phone only (SharedPreferences "music": `pl_pins`, `pl_played`); no Worker change. Includes the 0.4.29 paging (PC releases 0.4.30 only).

### Now Playing is slow to update when playback starts (owner, 2026-11-16, Spotify) — investigate, not started
A few seconds pass after the music starts before the Now Playing screen shows it. Look at the delay between playback start and the refresh: the poll interval of the Web API state (`spPolledAt`, the 800 ms redraw after `SpPlay`), `wakeSpotify` (this phone only shows as a device once Spotify is running), whether a media-session callback (MediaController) could update it at once instead of polling, and invalidate/refresh of the music info. 0.4.30 confirmed working by the owner (playlist back, pinned).

### Volume bar jumps from 2 bars to zero, no 1-bar level (owner, 2026-11-16) — investigate, not started
Look at the steps and mapping in `keys/VolumeBar.kt`: how the bars are quantised against the stream's volume index (a lowest non-zero step may round down to 0 bars, or the bar count and the index range may not line up), and whether the key steps skip a level.

## Spotify offline downloads use a limited "offline device" slot (owner warning, 2026-11-16)

Not started; nothing to build yet. Recorded from the PC session.

- Spotify offline downloads use one of the account's few "offline device" slots (5 as far as known). There is no central place to manage them.
- Before reflashing, erasing or factory-resetting a phone that has downloaded Spotify playlists, turn offline listening off on it first (Spotify settings, remove downloads). Otherwise the slot stays used.
- Evidence: on 2026-11-16 the owner's account showed "You're out of offline devices" when the J36 tried its first download. The owner freed a slot on another device.
- When downloads ship, put this warning in `docs/install/flash.md`, the troubleshooting doc, and any erase/reset warning in the app.
- Design decisions from the owner: phone-only UI (no parent console); Wi-Fi only (Spotify's "Downloads over cellular" stays off); no storage limit for now, with a per-app breakdown on the Mobile data page instead. PC is offering mockups of the Playlists screen (offline marker, `#` to download); the spec follows their answer.

## 0.4.31: Spotify playlist downloads (phone side built, untested on a device)

APK-only (image ims46 stays current). Spec from PC, owner-approved. Playlists screen: `↓ ` downloaded (automatically pinned, sorts in the pinned block), `* ` pinned, `… ` downloading; `#` toggles download or remove (removal restores the previous pin state, recorded as `prePinned`); `*` on a downloaded playlist says "Remove the download to unpin"; footer hint "* pin  # offline". No Wi-Fi gives an immediate "Connect to Wi-Fi first" (no queueing); also "Unlock the phone first" and "Phone Guard keys are off". `PlaylistJob` opens `spotify:playlist:<id>` in the Spotify app (then the open.spotify.com address as a fallback), `PlaylistDrive` (pure, tested) reads the button states "Download Playlist:", "Downloading Playlist:", "Downloaded Playlist:", handles the "You're out of offline devices" dialog (OK, then reports no free slot), waits up to 10 min. Removal confirmation is a guess (any "Remove download"/"Remove"/"Yes" button) until PC probes the real labels on the J36. The record lives in the "music" prefs (`pl_offline`). When Spotify's list cannot be loaded and there are downloads, the screen is "Offline: downloaded playlists" and OK sends `spotify:playlist:<id>:play` to Spotify (untested without internet). Not in this build: the Mobile data "By app" breakdown (own APK; it needs the usage-access app-op, so provisioning would have to grant it via `cmd appops set <pkg> GET_USAGE_STATS allow`, which is an image change).


## 0.4.31 tested on the J36 (PC, 2026-11-16)

Download (~24 s) and removal (~9 s) both worked; the real removal dialog is "Remove from downloads?" with CANCEL / REMOVE (our matcher already handles it, case-insensitive). `↓` renders fine. Paging confirmed ("spotify playlists: 75 loaded"). Not yet tested: the "Offline: downloaded playlists" list and offline playback (need no internet). By-app data breakdown: owner to decide, not built.

Nice-to-have (not started): on the selected row the long-name marquee scrolls the marker away with the name; keep the marker fixed at the left and scroll only the name (look at how the Playlists rows are drawn and marquee in `NokiaSkin.kt`).

## 0.4.32: offline fixes from PC's J36 offline test (2026-11-16)

(1) Playlists load failure now shows the "Offline: downloaded playlists" list whenever there are downloads (it used to keep the stale list because it only fell back when the old list was empty). (2) Playing a downloaded playlist falls back to `spotify:playlist:<id>:play` when the Web API is unreachable (devices call fails), when no target device exists, or when `/me/player/play` returns a non-2xx code. (3) The marker (↓ … *) stays fixed at the left of the selected row while only the name scrolls. Still untested on a device. Caveat from PC: `svc data disable` is undone within seconds (probably RadioPolicy), so a clean offline repro needs Wi-Fi off plus SIM data off at policy level, or the emulator's airplane mode.

## 0.4.33: Now Playing after an offline play (owner, via PC, 2026-11-16)

After `playOffline` starts Spotify, the launcher comes back after 1.5 s on the Music (Now Playing) screen instead of dropping to Home (`jobKeepsScreen` holds the screen for 4 s, then the media session refreshes). Untested on a device; the emulator's airplane mode is the planned test.

## 0.4.34: offline screen polish (PC emulator test of 0.4.33, 2026-11-16)

PC confirmed on the emulator (airplane mode): the offline list shows with the fixed `↓` marker, and after an offline play the launcher shows Now Playing, not Home. Fixes: the offline title was clipped at 480x640, so it is now "Offline playlists"; a missing Spotify app (ActivityNotFoundException) now shows the notice "Spotify is not installed" (posted 1 s later so the Now Playing redraw does not wipe it). Spotify actually playing a download offline is still only seen on the J36 earlier (by hand).

## ims47: TT9 is the default keyboard (PC, owner's J36, 2026-11-16)

Bug: after a flash the default input method was the AOSP LatinIME (on-screen keyboard, half the small screen); TT9 was installed but never selected, and nothing in the image set it. Fix in `pg-provision.sh` (`ime_ensure`): at every boot, and every 5 minutes while the watcher loop runs (Family phones), if TT9 is installed and `default_input_method` or `enabled_input_methods` is not TT9 alone, write both Settings.Secure keys with `cmd settings` (no sepolicy change) and log one `keyboard (boot|poll)` line. `show_ime_with_hard_keyboard` deliberately untouched (hiding the IME could hide TT9's candidate bar if the keypad counts as a hardware keyboard). Image ims47 carries APK 0.4.34 and this script; kits `flash-{system,wipe}-ims47-20261116`. Untested on a device; Open phones only get the boot check.

## Voice input module (spec: /artifacts/voice-spec-20261116/spec-voice.md; owner "go" NOT given, nothing built)

PC does the Worker routes (`POST /voice/transcribe`, WAV 16 kHz mono 16-bit, header `X-Voice-Mode: dictation|command`; 200 `{text, quality, seconds}`; 403 voice_off, 413 too_long, 429 used_up, 503 unavailable; Worker enforces 15 s / 500 KB, language en, vad_filter) and the parent-page toggles (per mode: "Voice typing" and "Voice commands", both OFF in School/work, ON elsewhere; when off, F2 hold does nothing, mic hidden, Worker refuses). NAS does the phone side. Order agreed: (1) `VoiceCapture` + mic button (Write screen third focus stop `writeField=2`, reached with Down from the body; hold OK ~250 ms to record, release to transcribe) + first use in "Where to?" search; (2) F2 re-mapping with ONE pure, fake-clock-tested gesture machine shared by `F2Service` and `LauncherActivity`: single press = camera after a ~400 ms double-press wait (constant to tune), double = torch, hold 600 ms = speak (dictation if an editable field is focused, else command); camera/speak off while locked; in our camera single press stays the immediate shutter; (3) home-screen command intents. Early J36 checks: background mic start from F2 hold (show the overlay first, then AudioRecord, plus a microphone-type foreground service), `ACTION_SET_TEXT` vs paste into TT9/Spotify fields (keep caret, refuse password fields), editable detection (focused input node, plus an input-method window via `flagRetrieveInteractiveWindows`). Release notes must say F2 behaviour changes (single press now opens the camera: pocket launches possible with the screen on).

## Screen-off torch (double press F2) — later image, not started

Today with the screen OFF neither F2 hold nor double press does anything (owner, 2026-11-08: Android drops non-wake keys while non-interactive; the raw key does arrive at the hardware). Screen ON (locked or not) the torch works from `F2Service`. For the screen-off torch: option (b) a PhoneWindowManager patch (`interceptKeyBeforeQueueing` sees F2 with the screen off; recognise the double press there and toggle the torch through CameraManager without waking the screen; needs the gesture timing in system_server, a second tested copy in Java); option (a) mark F2 as a wake key in the key layout (wakes the screen on the first press, which is not wanted for a torch). Plan: option (b) as its own image (ims48) after the voice work. Camera and speak stay disabled while locked/off.

## Owner decisions relayed through the NAS session (2026-11-16, handover from PC)

1. Voice work: GO. PC builds the Worker side first; the NAS builds the phone module (phase 1: mic button + transcribe + first use in "Where to?" search; F2 re-mapping after).
2. Torch with the screen OFF: only if it has no implications like battery drain from watching for the button. Assessment: none standing (the window manager already receives every key before queueing; the patch adds one comparison for F2 and a timer only after an F2 press; no polling, no wake lock; the torch itself drains while on and still auto-offs after 10 min). So plan ims48 after the other items are finished; By-app data permission (usage access) goes into the same image.
3. Single F2 press = camera, with a proximity block (no launch when the front is covered): ACCEPTED.
4. Double-press window about 400 ms (tunable): ACCEPTED.
5. Accuracy test with the children's recordings: SKIPPED for now (test with real use).
6. Flash ims47 (TT9 default, system-only kit keeps data): YES (PC flashes).
7. Remove CoMaps and its ~480 MB of maps from the phone: YES (PC removes it).
8. Mobile data "By app": grant usage access in the next image (provisioning), i.e. with ims48.

## 0.4.35: voice typing, phone side phase 1 (built; untested on a device; Worker route not live yet)

Package `voice/`: `VoiceWav` (16 kHz mono 16-bit WAV, level bars), `VoiceCapture` (AudioRecord in memory only, max 15 s), `VoiceText` (insert at the cursor with one space), `VoiceReply` (contract parse and plain messages: "Voice is off in this mode", "That was too long", "Voice is used up for today", "Voice isn't working right now", "Didn't catch that", "Connect first"). `WorkerLink.voiceTranscribe` POSTs the WAV to `/voice/transcribe` with headers `X-Voice-Mode`, `X-Pg-V`, `X-Pg-Did`, `X-Pg-Ctr`, `X-Pg-N`, `X-Pg-S`; the signature is the sync's `phoneMessage(V, did, ctr, n, p)` with `p = "voice:<mode>:<sha256hex(body)>"`; the counter is now shared with the sync through one synchronized `nextCtr`. Write screen: a mic button at the bottom right when the phone is paired to the Worker (`model.voiceOn`); Down from the text reaches it (`writeField = 2`, Up/Back return); hold OK ~250 ms starts recording (buzz), let go sends, Back/End cancel; a tap says "Hold OK and speak". Not yet: the per-mode "Voice typing" config gate (the mic shows whenever the phone is paired; the Worker refuses with voice_off), the "Where to?" search consumer, F2 mapping (phase 2), command mode (phase 3).

## 0.4.36: voice typing matches Worker 0.8.3 (PC)

Route is `POST /api/voice/transcribe` (0.4.35 used `/voice/transcribe`). The mic follows the synced mode config booleans `voiceTyping` and `voiceCmds` (per mode, always present; off in School/work, on elsewhere; absent = on): `Engine.voice(cfg, now)` and `LauncherData.voiceTypingAllowed` (also needs the phone to be paired to the Worker). The mic is hidden in the Wi-Fi password field (`LauncherModel.voiceShown`); the "Voice commands" flag is read but unused until phase 3. Replies: 409 = replay (the phone skips its counter forward), 200 per day per phone. Untested on a device; Worker 0.8.3 not deployed yet (PC waits for the owner's confirmation to deploy, flash ims47 and remove CoMaps).

## 0.4.37: mic button is a small plain glyph (owner feedback on 0.4.36, via PC)

The boxed, filled mic overlapped the character counter and was too big. Now a small plain microphone in the footer (centre x 396, left of the counter), a thin outline when it has the focus, the "hold OK and speak" hint, and the text area is back to 11 lines. The ims47 kit scripts' header text said "0.4.24, cheaper Aurora app-op watcher" (stale text from the ims46 kit); fixed to "0.4.34, TT9 forced as the default keyboard" and SHA256SUMS regenerated; system.img is unchanged (dc69a5fd...) and verified to hold the ime_ensure script and APK 0.4.34 (315605fa...).

## 0.4.38: side button gestures (F2 gesture machine and remap; owner-approved 2026-11-16; NOT installed on the J36 until the owner says so)

New pure `core/F2Gesture` (fake-clock tests, 7): one press = camera after a 400 ms wait for a second press, two presses = torch, hold 600 ms = speak (not built yet: policy `speak = false`, so the hold does nothing, not the torch), hold on the second press also speaks. One shared `keys/F2Actions` (policy: camera needs no call, not locked, mode offers it, front not covered; torch works locked or not but not while our camera has the flash) used by `F2Service` and by `LauncherActivity` (which only sees the key when the service is off). `keys/Proximity` listens to the proximity sensor only from the key-down until the gesture is decided. Inside our camera one press is the immediate shutter (no machine). Quick revert: `F2Gesture.MAPPING = Mapping.LEGACY` (two presses camera, hold torch, one press nothing). Docs: "The side button" in day-to-day.md. Release notes must say the buttons change: a single press now opens the camera (pocket launches possible when uncovered), and the torch moves from hold to double press. Screen-off torch still a later image (ims48). Next: speak (overlay, mic from the background, dictation into other apps' fields), then phase 3 commands, then Where to?.

## 0.4.39: F2 hold = speak (dictation), phase 2 of the voice work (built; untested on a device; owner away)

`keys/F2Speak`: the hold (600 ms) shows the "Listening..." panel (a visible window of ours, which is also what should let the microphone open while another app is in front), buzzes, then records (150 ms later, so the buzz is not in it); letting go sends it. If a text box has the focus the words are typed into it (mode "dictation", needs voiceTyping): in another app by `F2Service.insertText` (reads the text, `ACTION_SET_TEXT` with the new words at the cursor, cursor set after them; if the app refuses, the words are copied and `ACTION_PASTE` is tried, and the clipboard is cleared after 3 s); on our own Write screen by `LauncherActivity.dictate` (our screens have no accessibility text field). Password boxes are refused. Otherwise it is command mode (needs voiceCmds, 8 s max); `VoiceCommands.run` only SHOWS what was understood for now (phase 3 actions come next). The hold does nothing when voice is off for the mode, the phone is locked, in a call, unpaired or the mic permission is off. Worker `command` is parsed (`VoiceReply.Heard.command`). To check first on the J36 (PC): audio is not silent when held inside Spotify (background mic; if silent add a microphone-type foreground service), `ACTION_SET_TEXT` into Spotify search with TT9 composing, editable detection (`findFocus` input); if it picks command mode in a text box, add `flagRetrieveInteractiveWindows` and treat a visible keyboard window as a text box.

## 0.4.40: spoken commands, phase 3 (built; untested on a device; owner away)

`voice/VoiceCommand` (pure parse of the Worker's `command` JSON: call, play, timer, navigate, else unknown; lengths and ranges re-checked) and `NameMatch` (fuzzy: exact > all words > starts with > contains > the contact's name inside what was said; shortest wins a tie). `F2Speak` in command mode hands the command JSON to the launcher (`LauncherActivity.EXTRA_VOICE`), which acts through our own screens: call = look in the phone's contacts, open the dial pad with the number filled in and the notice "Call X? Press the call key" (nothing rings until the child presses call; same rules as dialling by hand); play = the child's own playlists first (fuzzy), then a Spotify search by kind, then the normal SpPlay (needs Spotify signed in and the mode's music on; "play Spotify" resumes); timer = `AlarmClock.ACTION_SET_TIMER` with SKIP_UI (starts silently in the clock app; "Timers are not available here" if the clock app is hidden or refuses); navigate = "Where to? is coming soon" until that app exists. Unknown = "Sorry, I didn't catch that". Tests: 4 more (VoiceCommandTest). To check on the J36: the timer intent with the stock clock app, play by name with a real playlist, call resolves a contact.

## 0.4.41: "Where to?" phase A (text screens; built, untested on a device; owner away)

Menu item "Where to?" (shown when the phone is paired to the Worker and the mode is not locked to one app: `LauncherData.whereShown`). Flow: Write screen headed "Where to?" (TT9-style typing, the mic button, the voice dictation all work) -> OK searches (`WorkerLink.geo("search")`, `near` = the phone's position or home when known) -> **Places** list ("Hexham Station, Northumberland") -> OK plans the way (`geo("route")`) -> **Journeys** list titled "From my position|home to X": the preferred kind first (walk under 2 miles / 45 min, else public transport; each row "08:12  53 min  1 change  LIVE") -> OK -> **Legs** list ("Walk 8 min to Stop A", "08:25 Bus 321 to Hatfield (25 min)") titled "LIVE times" or "TIMETABLE times". Back goes up one level; from Places back to the typing with the words kept. Spoken "navigate to X" opens the typing screen with X filled in; the child still picks the place. Origin (`WhereOrigin`): only a position the phone ALREADY has and is under 20 minutes old (no new fix is requested, so the mode's location rule is respected), else the parent's home place, said plainly as "home" in the title; neither = "I don't know where you are". Errors in plain words (`WhereReply`: Connect first, Used up for today, Maps aren't working right now). Pure parts tested (`Where`, `Polyline`, 5 tests + 4 model flow tests in WhereFlowTest). NOT done yet: the map picture (needs map tiles through the Worker or a schematic route drawing), the heading arrow, the per-mode on/off setting (PC is writing spec-where-to.md), icon, nearest-stops/leave-in-N-minutes. Polyline decoding (precision 6) is ready for the map.

## 0.4.42: Where to? schematic route map (built, untested on a device; never looked at on a screen)

From the legs list, OK (soft key "Map") opens a plain route drawing, no map tiles: the legs' polylines (precision 6) fitted into the box with a flat projection (`MapFit`, north up), walking legs dotted, vehicle legs solid, the leg in focus thick and drawn on top, a dot at the start and a square at the end, the leg's line of words under it, "n/m" bottom right. Left/Right (Up/Down) step the focus through the legs, OK/Back return to the legs list on that leg. A journey whose legs have no geometry says "No map for this one". Tests: `MapFitTest` (4) and a model flow test. To check on the emulator/J36: it looks right at 480x640 (dotted lines, the thick leg, caption not overlapping the soft keys, the start/end markers). Still to do: position dot and heading arrow (magnetometer), tiles (needs a Worker route and a provider decision), nearest stops.
