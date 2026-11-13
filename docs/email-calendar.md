# Email and calendar (design v1, 2026-11-13)

Replaces the one-line "Email: future feature" note in BACKLOG.md. Design only; nothing is built.

## Decisions (owner, 2026-11-13)
- Assume **no Family Link constraints**. Email works with an ordinary external provider and nothing else in the way.
- **No dependency on Home Assistant** in the new pieces (a later project moves off HA). This supersedes the old "set up from HA" note for email.
- Email **sends and receives**. No sender controls or parent filtering for now.
- No attachments and no HTML formatting for now (HTML mail is shown as plain text, remote images never loaded).
- Sync delay of **15 minutes** is fine. It may become configurable, but it must follow the radio-sleep design ([radios-per-mode.md](radios-per-mode.md)).
- Defaults agreed: Email is **hidden in School**; the Calendar stays visible there (it doubles as a timetable).
- Gmail first, but the UI and the back end are provider-independent.

## Shape
UI -> `MailProvider` / `CalendarProvider` -> local store. The UI never knows which service is behind a provider.
- `MailProvider`: folders, list messages (headers), fetch plain-text body, send, mark read/unread, delete.
- `CalendarProvider`: list calendars, events in a date range, (later) add/edit an event.
- Local store (SQLite/Room): recent headers and plain-text bodies only, last N days (default 30). Calendar events for a window around today.
- Fakes of both providers drive the unit and journey tests; a mock IMAP server (for example GreenMail) tests the real mail provider.

## Email back end
Chosen: **own IMAP/SMTP provider** on a permissively licensed library (K-9's mail module is Apache-2.0, or Jakarta/Angus Mail). Licence and API fit to be checked before use.
Rejected for now: Gmail REST API (Gmail-only, restricted-scope verification), embedding the K-9/Thunderbird engine and UI (heavy, UI coupled), mirroring the Gmail app's notifications (read-only, needs the app).
- **Auth**: strategy per account type: password, app password, OAuth (XOAUTH2). Family Gmail starts with an **app password** (needs 2-step verification on the account), which avoids OAuth app verification. Outlook/Microsoft needs OAuth later. iCloud, Fastmail and Yahoo use app passwords.
- **Sync** piggybacks on the radio design: poll inside the existing check-in window, and once on unlock. In a mode where radios sleep when locked, mail arrives at the next window (never later than the location interval); with the phone in use it polls every 15 minutes. IDLE (push) only while the screen is on, if at all; no always-on foreground connection.
- **Send**: SMTP, queued if offline and sent at the next window (the offline queue spike already exists for HA; reuse the idea).
- **Delete** moves to the provider's Trash, never expunge.
- New-mail notice and sound follow the Sound profile design (not yet written).

## Calendar back end
Order of build:
1. **ICS URL provider** (read-only, own parser such as ical4j). Google's secret iCal address needs no sign-in, so Gmail calendars work on day one. Recurrence expansion is the main work.
2. **Android calendar store provider** (`CalendarContract`): reads whatever any sync app has put there (DAVx5, ICSx5), so write-back and CalDAV come "free" without our own sync code. Check the own-build image includes the calendar provider.
3. **CalDAV provider** (read/write) only if write-back is wanted and 2 is not enough.
Assumed: read-only in v1 (add-event is phase 4). Confirm.

## UI (simple elements only; existing launcher chrome and keys)
### Email
- **Email** (home menu entry, envelope icon, unread count badge). Opens a short list: Inbox, Sent, Drafts, New message.
- **Inbox**: list rows, sender over subject (marquee on the selected row), extras column = time and an unread dot. Select opens the message. Left soft key = Options, right = Back. Number keys jump (for example 1 = top).
- **Message**: plain text, scrolls with Up/Down and the scroll bar. Left soft key = Reply, Options = Delete, Next, Previous, Mark unread, Forward.
- **Compose / reply**: reuse the Messages text entry (T9). Recipient picked from Contacts or typed (no controls yet), subject (auto "Re:" on reply), body. Send = left soft key.
- Home screen: unread count on the Email icon, and a notice for new mail.
### Calendar
- **Agenda** (default): list with day header rows, then rows of time and title; extras = place.
- **Month**: GRID layout, 7 columns, dots under days with events; D-pad moves, Select opens the day.
- **Event**: callout with title, time, place, notes.
- Home screen: a "Next up" line and reminder notices.
- Later: add-event form built from list rows, each opening a small picker.
### Modes
- School: Email hidden (and its sync not run), Calendar visible and read-only. Per-mode visibility uses the existing mode app-visibility mechanism, so the parent can change it.
- Sync respects "Radios sleep when locked", Wi-Fi/data intent, and any mode with live location.

## Phases
0. Spike when the phone is back on: IMAP login with an app password on the own build, TLS/CA trust, timing of a poll inside a check-in window; confirm the calendar provider is present.
1. Calendar: ICS provider, store, Agenda, Month, Event, home "Next up".
2. Email receive: account setup, store, Inbox, Message, badge, sync in windows, unlock sync.
3. Email send: compose/reply, queue, Sent/Drafts.
4. Extras: CalendarContract provider, OAuth providers, add event, configurable poll interval.
Each phase adds golden navigation tests, journey tests (JourneyTest setups) and a walker pass.

## Open questions
- **Credential entry without HA.** An app password is long; typing it with T9 is painful, and the old rule was "no passwords typed on the phone". Options: a parent enters it once at setup via adb/a setup screen with a full keyboard; scan a QR code from the parent's phone or PC; a small local web page served on the LAN during setup. Needs a decision.
- Where credentials live: Android Keystore-wrapped storage, never in the config JSON.
- Threading (conversation view) deferred; plain list first.
- Is read-only calendar OK for v1?
- Number of days kept and a mailbox size cap (storage on this phone is small).
- Per-account parent visibility of mail: none now; revisit if needed.
