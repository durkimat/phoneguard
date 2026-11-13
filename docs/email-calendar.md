# Email and calendar (design v1, 2026-11-13)

Replaces the one-line "Email: future feature" note in BACKLOG.md. Design only; nothing is built.

## Decisions (owner, 2026-11-13)
- Assume **no Family Link constraints**. Email works with an ordinary external provider and nothing else in the way.
- **No dependency on Home Assistant** in the new pieces (a later project moves off HA). This supersedes the old "set up from HA" note for email.
- Email **sends and receives**. No sender controls or parent filtering for now.
- No attachments and no HTML formatting for now (HTML mail is shown as plain text, remote images never loaded).
- Sync delay of **15 minutes** is fine. It may become configurable, but it must follow the radio-sleep design ([radios-per-mode.md](radios-per-mode.md)).
- **Credentials (decided 2026-11-13):** MVP types the app password on the phone with T9 (a one-off). Later: a **QR code** shown on the phone or scanned from another device so setup is easy; the HA parent console may offer it too but is never required.
- **Calendar v1 includes editing** (add, change, delete events), not just viewing.
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

## Calendar back end (editing in v1)
An ICS feed cannot be written to, so v1 needs a writable store. Chosen: the **Android calendar store (`CalendarContract`)** as the main provider, read and write.
- **Writable sync**: a sync app fills and syncs the store; we edit through `CalendarContract` and the sync app pushes changes back. **DAVx5** (CalDAV, including Google via its own sign-in) is the candidate; it is GPL-3 but a separate installed app, so no licence issue for us. To verify on the own build: it installs and syncs without Play services, and Google sign-in works on microG.
- **Local calendar**: a calendar on the device only (no sync) works with no extra app. Useful as the fallback and for tests.
- **ICS subscription provider** (read-only, ical4j or similar) for calendars we only want to see (school terms, sports fixtures). Read-only calendars show no edit options.
- **Own CalDAV provider** is only needed if DAVx5 proves unsuitable. Google's CalDAV needs OAuth, so it would not be first.
Edit rules for v1: edits apply to the whole series (no "this event only" exceptions yet); delete asks for confirmation.

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
- **Event**: callout with title, time, place, notes; Options = Edit, Delete.
- Home screen: a "Next up" line and reminder notices.
- **Edit / add event (v1)**: a form made of list rows, each opening a small picker: Title (T9), Date, Start, End (or Duration), All day, Place, Repeat (none, daily, weekly, monthly, yearly), Reminder. Save = left soft key; Options on an event = Edit, Delete. A calendar is chosen when more than one is writable.
### Modes
- School: Email hidden (and its sync not run), Calendar visible and read-only. Per-mode visibility uses the existing mode app-visibility mechanism, so the parent can change it.
- Sync respects "Radios sleep when locked", Wi-Fi/data intent, and any mode with live location.

## Phases
0. Spike when the phone is back on: IMAP login with an app password on the own build, TLS/CA trust, timing of a poll inside a check-in window; confirm the calendar provider is present; try DAVx5 (install, sync, Google sign-in on microG).
1. Calendar: `CalendarContract` provider (read and write), local calendar, Agenda, Month, Event, edit/add/delete form, home "Next up".
2. Calendar extras: ICS subscriptions (read-only).
3. Email receive: account setup (T9 password), store, Inbox, Message, badge, sync in windows, unlock sync.
4. Email send: compose/reply, queue, Sent/Drafts.
5. Extras: QR-code setup, OAuth providers, configurable poll interval, single-instance recurring edits, optional parent-console setup.
Each phase adds golden navigation tests, journey tests (JourneyTest setups) and a walker pass.

## Open questions
- Does DAVx5 (or another sync app) work on the own build with Google, without Play services? If not, fall back to our own CalDAV provider, which needs OAuth for Google.
- Where credentials live: Android Keystore-wrapped storage, never in the config JSON.
- QR design: phone shows a QR for a one-off page, or scans one from a laptop; decide in phase 5.
- Threading (conversation view) deferred; plain list first.
- Number of days kept and a mailbox size cap (storage on this phone is small).
- Per-account parent visibility of mail: none now; revisit if needed.
