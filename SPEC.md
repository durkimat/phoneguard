# Phone Guard — design spec (DRAFT v0.1)

Status: design only. No code yet. Nothing here touches the live Home Assistant instance.

## 1. Purpose

Let a parent control a child's Android phone from Home Assistant, replacing Google Family Link:
time-based modes (for example "dumb phone" hours vs wider access), per-mode app allowlists,
and a PIN-protected local override. Location tracking is out of scope for Phone Guard itself
(the HA Companion app, minimal flavour, covers it).

## 2. Decisions so far

| # | Decision | Notes |
|---|---|---|
| 1 | Name: **Phone Guard** | No clashing HA project found (checked 2026-11-06). Re-check before publishing. |
| 2 | Target Android 11+ (API 30+) | "Reasonably new". Raise if a feature needs it. |
| 3 | Runs on any such Android build | Must not depend on DumberOS features (App Blocker, updater, launcher). |
| 4 | No Google dependency | Works on microG / degoogled builds. No Firebase push, no Play Store. |
| 5 | **Device owner only ("Tier A")** | No accessibility/overlay/admin-lite fallback: a back door a child will find. If not device owner, the app enforces nothing and shows a "not provisioned" screen. |
| 6 | Provisioning routes all end in device owner | adb `dpm set-device-owner`; QR provisioning at first boot; on-phone wireless debugging. |
| 7 | Controller first, launcher second | The device-owner app is not a launcher. Any launcher works; ours (if built) is phase 2. The DumberOS launcher may be enough. |
| 8 | Schedule authority: Home Assistant | HA pushes the full timetable; the phone enforces it locally and offline. |
| 9 | Local override | PIN-protected, on the phone. |
| 10 | N modes | Design for several modes; two is the minimum useful case. |
| 11 | Remote access: Nabu Casa | Hard dependency. No design effort for other routes. |
| 12 | Licence: Apache-2.0 | Owner's decision. Public repo (HACS needs one). |
| 13 | microG is present | DumberOS installs it; the child wants some Google-dependent apps. Provisioning order: make the controller device owner BEFORE any account is added. |
| 14 | Transport: hybrid | Controller calls an HA webhook (poll + on connectivity change + Companion nudge); signed messages, per-device secret paired by QR. Proposed, details still to confirm. |
| 15 | Per child policy; install-then-quarantine | Section 4a/4b. |
| 16 | Modes: Day, School, Night, plus built-in Emergency | Section 4c. No app groups in v1. |
| 17 | Scheduling: own storage plus a week-editor card in HA | Section 4c. Calendars are not used (the child can edit them). |
| 18 | Holiday switch turns the school schedule off | Section 4c. |
| 19 | Boost (bonus time) and single-use local override codes | Section 4c. |
| 20 | 10-minute warning before a mode becomes more restrictive | Section 4c. |

## 3. Architecture (outline)

- **Controller app** (Android, Kotlin): device owner. Applies the current mode with
  DevicePolicyManager (hide/suspend apps, block install/uninstall, safe boot, factory reset,
  debugging; pin the preferred Home app). Holds the timetable, PIN, and the HA link.
- **HA integration** (`custom_components/phone_guard`): exposes each phone as a device with
  entities (mode selector, override, current mode, provisioned/enforcing status, last seen,
  battery). Owns the timetable and pushes it to the phone.
- **Launcher** (phase 2, optional).

## 4. Transport (options considered; the decision is in section 4d)

Nabu Casa gives remote access to the HA API, so the phone connects outward only.
MQTT is out unless a broker is reachable, which Nabu Casa alone does not provide.

Observed by the owner: HA can already send an instant command to a phone (for example to change
tracking granularity). That is the HA Companion app's notification-command channel, which on a
phone without Google runs over a persistent websocket to HA. Two-way transport therefore already
exists via the Companion app, and the controller could use it rather than build its own.

Candidate designs (to verify before choosing):
- A. Ride the Companion app. HA sends notify.mobile_app_<phone> commands. The Companion app has a
  broadcast-intent command that can deliver an intent to another app, so the controller would receive
  mode changes that way. Phone to HA uses the Companion app's webhook/sensors, or the controller's
  own calls. Risk: any app on the phone could forge the intent, so every message must be signed
  (shared secret, HMAC, expiry/nonce). Also depends on the Companion app staying alive and on
  its minimal flavour package name (".minimal" suffix noted in community posts).
- B. Controller runs its own websocket/API client to HA over the Nabu Casa URL, with its own
  credential. Fully under our control and independent of the Companion app, but we build and
  maintain the connection, reconnect and auth ourselves.
- C. Hybrid: Companion app for location and nudges; controller holds its own authenticated link
  for timetable and status, with the nudge only telling it to sync now.

Not verified: exact command_broadcast_intent syntax and behaviour on the current minimal build;
the Companion app's own documentation is the place to check.

## 4a. Device, child and parent mapping

- **Child profile** (integration): name, link to a HA `person` entity, default timetable, modes,
  and app approvals. Policy is **per child**; a device may carry overrides.
- **Device**: a paired phone belongs to one child profile.
- **Phone identity**: no HA user for the phone. It authenticates to its webhook with a
  per-device secret and signs messages. Deleting the device in HA revokes it.
- **Parents**: a list of HA users who may approve. HA has no per-service permissions, so the
  integration checks `context.user_id` against the parent list on every approval call, and the
  approval UI lives on a parent-only view (not on family dashboards or the shared tablet).
- **Pairing**: "add phone" in HA shows a QR (webhook, device id, secret) scanned by the
  controller. If QR provisioning works on the target build, one scan could provision and pair
  (admin extras bundle). Untested.

## 4b. App control

Decided: install-then-quarantine.
- The store stays available. Any package not yet approved (including sideloaded) is hidden
  immediately by the controller; a periodic reconcile backs up the install broadcast.
- Parents approve in HA (actionable notification plus a parent-only view); approval assigns the
  app to modes. Approval is per child.
- Updates of an approved app do not need re-approval. Uninstalling is allowed unless the app is
  on the protected list. Unknown sources stay blocked.
- A short visible window between install and hide is accepted.
- The store app is itself just an app: it is only reachable in per-app time windows that the
  controller enforces, so installing is limited by the same mechanism.
- Published as a JSON-string attribute (nested attributes lose false/0/null).

Store options on a microG build (researched 2026-11-06, third-party sources, to be tested):
- microG replaces Play Services but does not include Google's Play Store. microG Companion
  (formerly FakeStore) has no interface and only handles licence checks and install requests.
- The real Play Store (Phonesky) on microG is a known pain point (its signature check often
  fails) and is not officially supported.
- Aurora Store is the usual way to get Play-hosted apps. Anonymous logins were reported broken
  in 2023 and may be fixed; a real Google account login is the fallback.
- F-Droid is an alternative for open-source apps.
- The DumberOS Google variant ships with the Play Store; switching variants is an OTA step.
- To test on the phone: which store app works end to end, and whether the controller can detect
  and hide installs coming from it.

## 4c. Modes, schedule and overrides

### Modes and apps
- Modes are named per child. Initial set: **Day** (unrestricted list of approved apps), **School**
  (a short allowlist), **Night** (for example alarm clock and a music app). The design supports
  more than three modes.
- **Emergency** is a built-in mode, always present and not part of the timetable. It allows a
  minimal set (calls, messages, and whatever the parent adds, for example maps). It is what the
  phone falls back to when it has no usable schedule at all (never paired, corrupt data).
- **No app groups in v1.** Each approved app carries a simple per-mode flag (a small matrix of
  app by mode). Approving an app asks which modes it is allowed in, with Day as the default.
  Groups can be layered on later as a convenience without changing the model, because a mode is
  already just a list of allowed apps.
- A protected list (dialer, messages, Settings, system UI, emergency calling, Phone Guard,
  the HA Companion app, the launcher) is allowed in every mode and cannot be hidden.

### Timetable
- Each weekday has a list of time segments, each pointing at a mode. Uncovered time defaults to
  the parent's chosen base mode (suggested: Day). The editor can copy one day onto others, so
  "day templates" are an editor convenience, not a stored concept.
- **Holiday switch:** one switch per child. While on, every School segment behaves as Day.
  Optional "until" date so it cannot be left on by mistake (suggestion, to confirm).
- Authored in HA with our own storage and a week-editor card (reusing the heating card's
  patterns). Calendars are deliberately not used: the child can edit the family calendars.
- Resolution happens in HA. The phone receives absolute time instants for a rolling 14-day window
  plus the mode definitions, which avoids timezone and clock-change logic on the phone.
  Pushed again whenever the timetable, mode definitions or holiday switch change.
- The phone sets exact alarms for each boundary and re-evaluates on boot, on alarm, and on sync.

### Losing contact with HA
- The phone keeps enforcing the pushed window.
- After the window runs out it repeats a recent pattern. Owner's wording: "repeat the previous day".
  Proposed refinement for confirmation: repeat the **same weekday from the last pushed week**,
  so a weekday does not inherit a Sunday's pattern and school days stay school days.
- HA raises an alert when a phone has not reported for a set time.
- Open: whether the repeat should ever stop (for example after many days).

### Temporary override: boost
- A parent gives bonus time from HA: an extra N minutes with a chosen mode (default Day).
- While a boost runs, allowed apps are the **union** of the scheduled mode and the boost mode,
  so a boost never reduces access. It ends at an absolute expiry time, enforced on the phone, and
  can be cancelled from HA.
- It is wall-clock minutes, not metered use: v1 has no usage tracking, unlike Family Link's
  bonus time which counts real screen time.

### Local override: single-use code
- When HA is unreachable a parent can still unlock the phone with a one-time code, generated in HA
  ahead of time (HOTP-style: derived from the device secret and a counter, verified offline).
- A code works once. Using one advances the counter, which also invalidates earlier unused codes.
- The grant is a per-device setting (suggested default: Day mode for 30 minutes), then it reverts.
- Rate limiting and a lockout back-off after wrong attempts.
- Open: whether a second code type (longer) should also authorise "release this phone" offline.

### Warnings
- A phone notification 10 minutes before the phone moves to a more restrictive mode, and before
  a boost ends. The controller's own notifications are always allowed.

## 4d. Transport design (decided)

The hybrid design: the phone only pulls and posts signed data. Nothing HA sends changes the
phone's behaviour directly.

### Shape
- One webhook endpoint in the integration, reached through the Nabu Casa cloudhook URL.
  Message types: `pair`, `sync`, `event` (new app installed, mode changed, override code used,
  tamper detected).
- **Polling is the mechanism**: sync about every 15 minutes (low battery impact), on connectivity
  return, on boot, and on a nudge. Events (for example a new app) are posted immediately.
- **Nudge** is an optional speed-up: HA asks the HA Companion app to send an intent to the
  controller meaning "sync now". It carries no data, so a forged nudge only causes a
  rate-limited sync. If nudges fail, the next poll delivers everything. Instant location updates
  stay a Companion app concern and are not part of this transport.
- Sync, phone to HA: current mode, schedule version held, enforcement status (device owner,
  restrictions applied), battery, app inventory changes (hash, not the full list each time),
  clock offset, controller version, timezone.
- Sync, HA to phone: the 14-day schedule window, mode definitions, approved-app matrix,
  active boost, server time.

### Security
- Pairing: HA shows a QR with the webhook URL and a one-time pairing code (valid about 10
  minutes). The phone generates an ECDSA P-256 keypair in the Android Keystore and registers
  only the public key. HA also supplies its own public key in the QR.
- Every request is signed by the phone and carries a counter. HA rejects counters it has seen.
  Every reply echoes the phone's nonce and is signed by HA, so replays and forged servers
  cannot change the schedule.
- Override codes need a separate symmetric seed, set at pairing, because the phone verifies
  them offline.
- The webhook ID is treated as a secret, but signing protects the data, not the URL.
- Simpler alternative if needed: one shared HMAC secret (weaker: it would also sit in HA storage).

### Time integrity and travel
- The controller blocks manual date/time changes as device owner and anchors to HA's
  `server_time` plus the device's monotonic clock. A clock jump or reboot forces a re-sync.
- Timezones: HA computes the schedule as absolute instants, so the phone needs no timezone
  logic. Per child there is a setting: schedule in the **home timezone** (default, predictable) or
  **follow the device's timezone** (the phone reports its zone each sync and HA recomputes and
  pushes). A phone that is offline abroad keeps the old window until it syncs. Travel can also
  be handled with the holiday switch or a temporary boost.

### To verify before building
- Nabu Casa cloudhooks: rate limits and payload size. Not found in documentation; to check on
  the Nabu Casa support site or by testing. The HA core mobile_app integration already uses a
  cloudhook (created through the cloud component, URL stored in the config entry; creation
  fails if the user is logged out or cloud is disconnected, and signing out unregisters hooks).
  Whether replies come back through a cloudhook as expected still needs checking against the
  current `homeassistant/components/mobile_app` source.
- Companion app `command_broadcast_intent` reaching the controller on the minimal build
  (package name has a `.minimal` suffix in community posts). Test on the J36.
- Background reliability: Doze and vendor battery management. `setUserControlDisabledPackages`
  (device owner, Android 13+ behaviour) should stop the user force-stopping the controller and keep
  it out of the restricted standby bucket; whether a device owner can also exempt itself from
  battery optimisation without a user prompt needs checking in the current developer docs.
- A WorkManager periodic job has a 15-minute minimum and can be delayed by Doze; the design
  tolerates that (enforcement is local, boundaries use exact alarms).

## 5. Open questions

1. Transport details and authentication (section 4).
2. Hide vs suspend per app. Hidden apps vanish and post no notifications; suspended apps stay
   visible, greyed. Which apps get which treatment in each mode?
3. Notifications from hidden/suspended apps in restricted mode: acceptable to queue silently?
4. Whether the DumberOS launcher handles hidden apps and keypad navigation. Needs a test on the phone.
5. PARKED. Could HA manage DumberOS launcher settings? Source check (2026-11-06, branch
   lineage-21.0 of DumberOS/android_packages_apps_Trebuchet): the launcher is a privileged
   system_ext app (com.android.launcher3, a Lineage Trebuchet). Settings sit in private
   SharedPreferences and SQLite. It declares no managed app restrictions. Its ContentProviders
   require signatureOrSystem permissions. A device owner can therefore not change them. The
   launcher's "Trust apps" hide only removes apps from its own list and does not use
   PackageManager hide/suspend. Revisit only if we build our own launcher (phase 2) or the
   DumberOS developer adds managed restrictions.
6. Provisioning order: device-owner setup fails if accounts exist. Define the sequence for a
   microG build.
7. Parent release: the app needs a PIN-protected "release this phone" action, and a documented
   last-resort wipe if the PIN is lost.
8. Usage/screen-time reporting to HA: wanted? Needs usage-stats access, and a conversation
   with the child about privacy.
9. Calls/contacts limits during restricted mode: optional, hard.
10. Licence and whether to publish.

## 6. Known limits (threat model, to complete)

- Software cannot stop a reflash through the bootloader or recovery from a PC. The target
  phone's bootloader will be unlocked. Accepted risk, mitigated by HA alerts when the phone
  stops reporting as provisioned or goes quiet.
- A device owner can be cleared only by the app itself or a factory reset.
- Never hide or suspend Settings or other critical system apps (DumberOS's own changelog notes
  that disabling Settings stopped the phone booting).
- Background services may be killed by vendor battery management: needs a foreground service
  and a battery-optimisation exemption.

## 7. Prerequisites to check on the real phone (Qin J36)

```
adb shell getprop ro.treble.enabled
adb shell getprop ro.vendor.build.version.release
adb shell getprop ro.product.first_api_level
adb shell getprop ro.vendor.build.security_patch
adb shell uname -r
adb shell getprop ro.board.platform
```

After DumberOS is installed, also check how locked down the build is (the build README uses
`userdebug` targets and the launcher manifest has `android:debuggable="true"`):

```
adb shell getprop ro.build.type
adb shell getprop ro.debuggable
adb shell getprop ro.adb.secure
```

A debuggable build may allow routes round device-owner restrictions (for example adb root).

## 8. Testing plan (outline)

Emulator first, then a spare/reset phone, then the J36. Nothing goes on the daughter's phone
until it works end to end, and a stock ROM backup exists.
