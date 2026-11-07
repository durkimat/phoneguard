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
| 21 | Layered protected-app rules | Section 4e. Camera and Clock are default-allowed but editable. |
| 22 | Parents may add to the protected list, never remove the hard set | Section 4e. |
| 23 | Pairing design, app id `io.github.durkimat.phoneguard` | Section 4f. App id cannot change later without breaking installs. |

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

### Display options per mode
- A mode can switch the screen to **grayscale** (owner's request: School, maybe Night).
- Mechanism (proposed): Android's accessibility colour-correction setting in monochromacy mode
  (secure settings `accessibility_display_daltonizer_enabled` and `accessibility_display_daltonizer`).
  Writing it needs the WRITE_SECURE_SETTINGS permission, which a normal app cannot get by itself.
  Routes: one extra `adb shell pm grant` during provisioning, or a device-owner self-grant if that
  works (unverified). If neither route works the option is unavailable rather than faked.
- It is a soft control: the child can switch colour correction off in Settings (Settings is
  reachable). The controller watches the setting and re-asserts it for the rest of the mode.
- Tested on the J36 (2026-11-06): writing the setting over adb turns the whole screen grey, and
  the camera view too. So the effect is display-wide. Still to test: re-asserting it from an app
  once permission is granted, and the device-owner self-grant route.
- Grayscale only changes the display output (it does not change what apps do).

### Timetable
- Each weekday has a list of time segments, each pointing at a mode. Uncovered time defaults to
  the parent's chosen base mode (suggested: Day). The editor can copy one day onto others, so
  "day templates" are an editor convenience, not a stored concept.
- **Holiday switch:** one switch per child. While on, every School segment behaves as Day.
  Takes an optional date range (from and until) so it cannot be left on by mistake. **Decided 2026-11-07.**
- Authored in HA with our own storage and a week-editor card (reusing the heating card's
  patterns). Calendars are deliberately not used: the child can edit the family calendars.
- Resolution happens in HA. The phone receives absolute time instants for a rolling 14-day window
  plus the mode definitions, which avoids timezone and clock-change logic on the phone.
  Pushed again whenever the timetable, mode definitions or holiday switch change.
- The phone sets exact alarms for each boundary and re-evaluates on boot, on alarm, and on sync.

### Losing contact with HA
- The phone keeps enforcing the pushed window.
- After the window runs out it repeats the **same weekday from the last pushed week**, so a weekday
  does not inherit a Sunday's pattern and school days stay school days. **Decided 2026-11-07:** it
  keeps repeating indefinitely, it does not stop or fall back to Emergency mode.
- HA raises an alert when a phone has not reported for a set time.

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
- Decided 2026-11-07: no second code type in v1. Releasing a phone offline uses the recovery key
  shown at pairing (section 4f).
- The controller runs as a foreground service (permanent low-priority notification). Needed to hold
  grayscale (rig test: the settings watcher only fires while our process is running) and accepted
  by the owner on 2026-11-07.

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

### Companion app findings (docs and manifest check, 2026-11-06)
Question asked: could the Companion app be the whole transport, so Phone Guard needs no webhook?
- **Phone to HA is possible only through an intent bridge.** The user registers an intent action in
  the Companion app (sensors, "Last update trigger"; the app must be restarted after saving). When
  another app broadcasts that intent, the Companion app fires the HA event `android.intent_received`
  with the action and any extras. Not documented: size limits, extra key names, sender package,
  whether the app must be running, minimal-flavour behaviour.
- **HA to phone** is `command_broadcast_intent` (`intent_action` and `intent_package_name`
  required, optional `intent_class_name`, `intent_extras` as comma-separated `name:value` pairs
  with type suffixes). No permission is documented; the docs do not say it works on the
  minimal flavour.
- The bridge does not verify who sent an intent, so any app can send it. Our signatures would
  have to carry authentication, which the envelope already does.
- Other exported Companion receivers (`UPDATE_SENSORS`, `REQUEST_SENSORS_UPDATE`,
  `REQUEST_ACCURATE_UPDATE`) only trigger local sensor refreshes and carry no payload. The
  Companion app has no content provider other apps can use, and no way for another app to write a
  custom sensor value.
- **Companion flavours and Google (source check, 2026-11-06).** The full flavour (package
  `io.homeassistant.companion.android`) does depend on Google libraries: play-services-location,
  Firebase Cloud Messaging, play-services-home/threadnetwork/wearable, and GMS-backed Cronet. Its
  docs say it "requires Google Play Services". Its location code uses the fused location client and
  returns nothing if that fails. The minimal flavour (`...minimal`) has no Google dependencies, uses
  the platform location manager, and receives push over a websocket instead of FCM.
  - The docs say minimal "does not support location tracking", yet the minimal source contains a
    platform location provider and its own high-accuracy location service. The docs may be stale.
    Test minimal's location on the J36 before assuming it is missing.
  - Full on microG is unsupported by the maintainers (issue closed "not planned": support only on
    Google-certified devices; a request for a Google-free build was declined in 2020). It may still
    work through microG, but expect problems.
  - Minimal's websocket push has a known open issue on a de-Googled phone (connection stops until
    the app is opened; a test build reports a fix, Nov 2026). Nudges therefore may be unreliable;
    the design already treats them as optional.
  - Not read: minimal manifest, websocket code, callers of the Play Services availability check.
- A possible cheap later feature: the Companion app's "Last used app" sensor reports foreground
  app usage to HA without any Phone Guard code (if usage reporting is ever wanted).

**Decision:** keep the signed webhook as the data channel. The bridge would need a manual
registration step in the Companion app, is fire-and-forget (no reply path, so pairing and sync
need a second correlated message), has unknown size limits, and would make enforcement-adjacent
traffic depend on the Companion app staying alive. The envelope is transport-agnostic, so a
Companion-bridge transport could be added later without changing the protocol.

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

## 4e. Protected apps

Why: a hidden app behaves almost as if uninstalled. It cannot run, receive broadcasts, or fire
alarms and reminders. Hiding a system service (SystemUI, telephony, WebView, the keyboard,
microG) can crash or break the phone, and hiding an app with background duties (for example the
Clock, whose alarms would stop) quietly breaks features.

### Layers
1. **Only packages with a launcher icon can be managed.** Services and providers are never touched.
2. **Dynamic protected set**, computed on the phone: the current default Home app, dialer, SMS
   app and keyboard, the system WebView provider, and Phone Guard itself.
3. **Static protected list** of package names shipped with the controller, with a DumberOS
   profile (it ships its own apps). Built from the real package list of the target phone.
4. **Parents may add** to the protected list in HA but can never remove entries from layers 1-3.
5. **Safe failure:** if the controller cannot confirm the protected set is intact, it applies no
   hiding. Hiding is always reversible.

### Never hideable (hard set)
- Dialer, SMS/Messaging, Contacts (these also cover emergency calls).
- Emergency alerts (cell broadcast).
- Settings (DumberOS's own changelog notes that disabling it stopped the phone booting).
  What she can change is limited with device-owner restrictions instead of hiding.
- Launcher, keyboard (including the T9 keyboard), system WebView, package installer, permission
  controller.
- microG (GmsCore and helpers): hiding it breaks notifications, maps and location for other apps.
- HA Companion app and Phone Guard, so location and control keep working.
- The DumberOS updater, so OTA updates keep arriving.

### Protected by default, editable by a parent per mode
- **Clock/alarm:** allowed in every mode by default. Changing it shows a warning, because a
  hidden Clock does not ring alarms (important for Night mode).
- **Camera:** allowed by default, can be switched off per mode (for example School).

### Everything else
Per-mode flags as in 4c, including the store app.

### Real package list (Qin J36 running DumberOS, captured 2026-11-06)
The phone is the Google-free (microG) build. Findings from `pm list packages -s`, the launcher
activity query and `dpm list-owners`:
- **There is no device owner today**, so Phone Guard can take the role. `ro.debuggable` is 0.
- **No real Play Store.** `com.android.vending` has no launcher icon (very likely the microG
  Companion stub), `com.google.android.gms`/`gsf` are microG. The store-like app is
  `com.dumbermini.apps`. Other install routes: the browser (Jelly) and Files.
- `eu.dumbdroid.deviceowner` is a **system-UID priv-app** (`/system/priv-app/DumbdroidAdmin`),
  not a device owner despite the name. It provides the App Blocker UI (a settings page and a
  "Play Store blocked" screen). Overlap and interference with Phone Guard is still to be checked.
- `com.android.managedprovisioning` is present (QR provisioning support is plausible, untested).

Only 13 packages have a launcher icon. Classification:

| Package | Treatment |
|---|---|
| `com.android.dialer`, `com.android.messaging`, `com.android.contacts` | Hard protected |
| `com.android.settings` | Hard protected |
| `com.android.documentsui` (system file picker) | Hard protected (has an icon but is a service) |
| `com.google.android.gms` (microG settings entry) | Hard protected |
| `io.github.sspanak.tt9` (T9 keyboard) | Hard protected (the dynamic keyboard rule also catches it) |
| `com.android.deskclock` | Protected by default, editable with warning (alarms) |
| `com.dumbermini.davx` (Google contacts sync helper) | Protected by default, editable with warning (hiding stops sync) |
| `org.lineageos.aperture` (Camera) | Allowed by default, editable per mode |
| `org.lineageos.glimpse` (Gallery) | Per-mode |
| `org.lineageos.jelly` (Browser) | Per-mode; also a download route, and the default for links |
| `com.dumbermini.apps` (app list) | Per-mode; acts as the "store" |

Hard protected without an icon (never touched by layer 1, listed for clarity): `eu.dumbdroid.*`
(updater, deviceowner, settingspanel, bugreportuploader), `me.phh.treble.app`,
`com.android.webview`, `com.android.vending`, `com.android.inputmethod.latin`.

### DumberOS App Blocker and app list (source check, 2026-11-06)
Sources: `miki151/device_owner` (package `eu.dumbdroid.deviceowner`) and
`DumberOS/more_apps_client` (package `com.dumbermini.apps`). Partly read; some files unread.
- **App Blocker** is a platform-signed, system-UID priv-app. It does NOT register as device owner,
  profile owner or delegate (matches `dpm list-owners`: none). It hides apps with
  `DevicePolicyManager.setApplicationHidden` under its own policy entry, and its PIN is optional,
  a salted SHA-256 hash in SharedPreferences, enforced only by its UI.
- **Collision: `DISALLOW_INSTALL_UNKNOWN_SOURCES`** is the one user restriction it sets, and it
  clears it when its Play Store restriction is turned off. That restriction is global and
  last-writer-wins, so App Blocker can undo ours. Phone Guard should re-assert it, and the
  provisioning guide should say to leave App Blocker's Play Store restriction on or unused.
- **Hide-policy coexistence:** its UI changes only its own entry, so another admin's hide should
  survive it (the code and README say so; framework resolution unverified on the device).
- **Undo risk:** any system-UID app can call `setApplicationHidden` directly. The PIN does not
  stop that. Not a child-reachable path unless she finds a system-UID app that exposes it.
- **App list (`com.dumbermini.apps`)** is a GPL-3.0 fork of Droid-ify (an F-Droid client) with
  privileged install permissions (INSTALL_PACKAGES, update-without-user-action). It installs with
  PackageInstaller sessions. Repositories are user-added; no default repo was found in the files
  read. So it is a privileged, silent-capable installer: the quarantine design must treat
  installs from it as the main route, and per-app windows for it matter. Unread: Downloader code.
- `com.dumbermini.davx` is DAVx5 (davx5.com, a CalDAV/CardDAV sync client), per the owner. It
  was not in any public DumberOS or miki151 repo, so it is probably a bundled build. The owner
  is likely to disable or uninstall it; if so, drop it from the protected list.
- Licences: `device_owner` has no licence (treat as unlicensed); `more_apps_client` GPL-3.0;
  the patches/manifest repos show none. Phone Guard must not copy their code.

### Known limits and checks
- Settings is reachable in every mode with a normal launcher (a launcher can only hide icons).
  A phase 2 launcher could omit it.
- Check that adb can still unhide an app a device owner hid; if not, the controller needs a
  panic/release action.

## 4f. Pairing (decided 2026-11-06)

App id: `io.github.durkimat.phoneguard`.

### Flow
1. **HA "Add phone":** choose the child profile and, optionally, the phone's Companion app
   registration (used only for nudges). HA creates a device id and a one-time pairing code (valid
   10 minutes), and shows a QR containing: protocol version, cloudhook URL, device id, the code,
   and HA's public key. The QR holds no long-term secret.
2. **Phone scans the QR** (ZXing, Apache-2.0, no Google dependency). Fallback: an adb intent
   carrying the same payload (also useful during provisioning and testing).
3. **Key generation:** the phone creates an ECDSA P-256 keypair in the Android Keystore (not
   exportable) and posts a signed `pair` request with its public key, device model, controller
   version and device-owner status.
4. **Confirmation:** phone and HA each show the same 4-digit number (derived from the code and
   the phone's public key). The parent confirms in HA that they match. Without confirmation the
   pairing never completes. Outside an open pairing window HA rejects `pair` requests cheaply.
5. **Reply:** signed by HA and checked against the public key from the QR. It carries the
   override-code seed (stored encrypted) and the first schedule.
6. **Recovery key:** HA shows a long random key once, to be saved in a password manager. The
   phone keeps only a hash. Entering it on the phone authorises release or re-pair offline.
   (Re-pairing must never be open, or a child could pair the phone to her own fake server.)

### Message envelope
JSON in the request body (not headers; Nabu Casa header forwarding is unverified):
request `{v, did, ctr, n, p, s}` with `p` the payload as a JSON string and `s` an ECDSA signature
over `v|did|ctr|n|p`; reply `{v, did, n, t, p, s}` echoing the nonce, carrying server time `t`,
signed by HA over `v|did|n|t|p`. HA rejects counters at or below the highest seen. The counter is
persisted before sending. Android backup is disabled for the controller, so old state and counters
cannot be restored.

### Revocation and loss
- **Release phone:** a signed command. The controller removes restrictions and the device-owner
  role and stays as a normal app.
- **Forget device:** HA drops the device. The phone keeps enforcing its last schedule until it
  is re-paired with the recovery key.
- If HA is lost with no backup and no recovery key, the phone is orphaned: factory reset and
  re-provision. HA backups matter; the recovery key is the second safety net.
- The pairing payload is small enough to fit in a QR-provisioning admin-extras bundle later, so
  one scan could provision and pair. Not planned for v1.

### Open
- Whether the J36 camera reads a QR code from a monitor (needs a test).
- Whether a device owner can silently grant itself camera permission for scanning.

## 4g. Location requests (decided 2026-11-07)

Location comes from the Companion app, not the controller. Baseline is its normal 15-minute
update. To save the small battery, real-time tracking is a **boost**, not a mode:
- A parent button in HA for one-off "where is she now" (`request_location_update`).
- A stepped boost like the heating boost: each press adds time (5, 10, 15 minutes, and so on up to a
  cap). While the boost runs HA sends `command_high_accuracy_mode` on, then off when it expires.
  Pressing again extends it; a cancel button turns it off at once.
- Built in HA (a script or our integration), against `notify.mobile_app_<phone>`, so no controller
  change is needed. The controller only has to keep the Companion app alive (protected from
  force-stop, never hidden).

## 5. Open questions

1. Transport details and authentication (section 4).
2. DECIDED 2026-11-07: hide, not suspend. Hidden apps vanish and post no notifications; suspended
   apps stay visible, greyed, and say "blocked by work policy" (J36 lab test).
3. DECIDED 2026-11-07: notifications from hidden apps are silently lost in restricted mode.
   That is wanted, not a problem.
4. Hidden apps vanish from Trebuchet (tested). D-pad navigation works "ok-ish" (owner, 2026-11-07):
   Trebuchet is not designed for it. A custom launcher (phase 2) could improve this.
5. PARKED. Could HA manage DumberOS launcher settings? Source check (2026-11-06, branch
   lineage-21.0 of DumberOS/android_packages_apps_Trebuchet): the launcher is a privileged
   system_ext app (com.android.launcher3, a Lineage Trebuchet). Settings sit in private
   SharedPreferences and SQLite. It declares no managed app restrictions. Its ContentProviders
   require signatureOrSystem permissions. A device owner can therefore not change them. The
   launcher's "Trust apps" hide only removes apps from its own list and does not use
   PackageManager hide/suspend. Revisit only if we build our own launcher (phase 2) or the
   DumberOS developer adds managed restrictions.
6. Provisioning order: device-owner setup fails if accounts exist. Define the sequence for a
   microG build. DEFERRED: write a detailed install order and method once the controller works.
   The owner expects it to need developer access and adb, and wants it kept as simple as possible.
7. Parent release: the app needs a PIN-protected "release this phone" action, and a documented
   last-resort wipe if the recovery key is lost. DECIDED 2026-11-07: a last-resort wipe must exist.
   The owner expects a recovery-mode wipe (volume keys on boot) is already possible and cannot be
   blocked, which matches threat R1 (detect, don't prevent). To verify and document, not build.
8. Usage/screen-time reporting to HA: WANTED (owner, 2026-11-07; it exists in Family Link and is
   useful). Needs usage-stats access, and a conversation with the child about privacy. Design in a
   later pass: per-app foreground time per day, posted to HA, no content.
9. Calls/contacts limits: NOT WANTED (owner, 2026-11-07). Not in scope.
10. Licence: Apache-2.0, already public. Nothing more to publish unless the licence requires it.
    Our code uses no GPL code (the Droid-ify fork and the DumberOS launcher are only observed,
    not reused), and ZXing is Apache-2.0, so no obligation arises. Recheck if we ever copy code
    from a GPL project.

## 6. Threat model (DRAFT)

Owner decisions (2026-11-06): R1 accepted as "detect, do not prevent"; the privileged app list
(`com.dumbermini.apps`) is hidden by default outside parent-opened install windows; debugging is
switched off after provisioning with an HA "maintenance window" to re-enable it; the out-of-scope
list below stands as written.

### Adversary and scope
- **The adversary is the child:** 14, clever, at home, with physical access, time, search engines,
  possibly a friend or family PC. No root exploits, no hardware attacks (chip-off, JTAG).
- **Assets to protect:** the schedule and app restrictions; parent approval authority; the
  controller itself (keys, state); visibility, meaning HA notices when something is wrong.
- **Out of scope (stated plainly):**
  - content inside approved apps (messages, browsing, video);
  - other devices (a friend's phone, a hotspot, a PC);
  - a determined attack with a PC and unlocked-bootloader tools (see R1).

### Attack paths, mitigations, residual risk
| # | Path | Mitigation | Residual |
|---|---|---|---|
| 1 | Factory reset from Settings or the power menu | `DISALLOW_FACTORY_RESET`; check the DumberOS power-menu reset button exists after setup | Low if the check passes |
| 2 | Factory reset or reflash via recovery/fastboot (bootloader is unlocked) | Cannot be stopped in software | **R1: high impact, needs knowledge or a PC.** Detect by heartbeat loss, alert, re-provision |
| 3 | Safe mode | `DISALLOW_SAFE_BOOT`; verify it holds on this build | Low |
| 4 | adb / developer options | `DISALLOW_DEBUGGING_FEATURES` after provisioning; `ro.debuggable` is 0; no `testOnly` flag on the controller; a "maintenance window" in HA lifts it for you | Low |
| 5 | Remove or disable Phone Guard | Device-owner apps cannot be uninstalled; `setUserControlDisabledPackages` blocks force-stop and clear-data | Low |
| 6 | Clear device owner | Only the app or a factory reset can; do not ship `testOnly`; protect the PIN/code paths | Low |
| 7 | Change the clock | `DISALLOW_CONFIG_DATE_TIME`, auto time zone only, anchor to `server_time`, detect clock jumps and report drift | Low (fake cell tower out of scope) |
| 8 | Stay offline so nothing changes | Schedule is enforced locally; HA alerts on last-seen; pending boosts simply wait | Low. A stale schedule repeats |
| 9 | Second user, guest, work profile | `DISALLOW_ADD_USER`, `DISALLOW_ADD_MANAGED_PROFILE`, `DISALLOW_USER_SWITCH` | Low |
| 10 | Change the Home app | Pin it with a persistent preferred activity | Low |
| 11 | VPN or private DNS to cut HA off | Restrict VPN and private DNS configuration (only if it does not break your needs) | Low. She keeps only the old schedule |
| 12 | Sideload via browser or Files | `DISALLOW_INSTALL_UNKNOWN_SOURCES` (re-asserted: App Blocker can clear it), plus quarantine of any new package | Low. Short visible window |
| 13 | **Install anything via the privileged app list** (`com.dumbermini.apps`, user-added repos, silent installs) | Hide that app outside parent-opened install windows; quarantine catches the result anyway | **R2: medium.** Depends on the windows being enforced |
| 14 | A cloned or repackaged app under a new package name | New package, so quarantined | Low |
| 15 | Turn grayscale or other display settings off in Settings | Controller re-asserts while the mode lasts | Accepted (soft control) |
| 16 | Settings is reachable in every mode | Limit by restrictions, not hiding; a phase 2 launcher can omit the icon | **R3: accepted** |
| 17 | App Blocker UI changes state | Our hides sit under our own policy entry; re-assert shared restrictions; check on the device | Low, to verify |
| 18 | Steal the pairing key | Private key lives in the Android Keystore, not exportable; app storage not readable without root | Low |
| 19 | Replay or forge HA messages or nudges | Signed requests with counters, echoed nonces, signed replies; nudge carries nothing | Low |
| 20 | Brute-force an override code | Single-use HOTP codes, small window, rate limit and lockout back-off | Low |
| 21 | Approve her own apps via HA | Parent-only approval by `context.user_id`, parent-only view; do not use the family tablet login | Medium if HA users are loose. Depends on HA hygiene |
| 22 | Webhook endpoint flooded or probed | Reject unsigned requests cheaply; rate limit | Low |
| 23 | A Phone Guard bug breaks the phone | Protected-app layers, safe failure (no hiding if the set cannot be confirmed), hides persist across crashes | Medium until tested |
| 24 | A DumberOS OTA changes behaviour | Re-run the checklist after each OTA. Device-owner state lives in userdata | Accepted (single-maintainer supply chain) |

### Detection and alerts (HA)
- Phone not seen for N hours; controller no longer device owner or no longer enforcing.
- Clock drift above a threshold; mode applied does not match the schedule.
- Restriction changed or re-asserted (for example App Blocker cleared unknown sources).
- New app installed (always); override code used; repeated wrong code attempts.

### Residual risk summary
- **R1** reflash or recovery reset with an unlocked bootloader: detect, do not prevent.
- **R2** the privileged installer: hidden outside install windows.
- **R3** Settings always reachable: constrained by restrictions only.
- Content in approved apps and other devices stay with the parent.

### Red-team checklist (once built, you try it as her)
- Every row above that says "verify", plus reset, safe mode, clock, offline, guest user,
  adding a repo in the app list, and reboot during a mode change.
- Check that no restriction blocks emergency calls, alarms, or microG-dependent apps she needs.

### Earlier notes

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

To build the protected list (section 4e), capture the package list and launcher apps:

```
adb shell pm list packages -s
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

Results so far (J36 with DumberOS): `dpm list-owners` reports no owners; `ro.debuggable` is 0;
`ro.product.model` reports `DumberOS`. See section 4e for the package classification.

Grayscale test (section 4c). adb shell can write this setting; revert straight after:

```
adb shell settings put secure accessibility_display_daltonizer 0
adb shell settings put secure accessibility_display_daltonizer_enabled 1
adb shell settings put secure accessibility_display_daltonizer_enabled 0
```

(Value 0 selects monochromacy. The second line turns it on, the third turns it off.)

### Lab app results on the J36 (2026-11-07, first pass, tested by hand)

The lab app (`prototype/`) was device owner on the J36 running DumberOS.

| Area | Result | Consequence for the design |
|---|---|---|
| Hide (`setApplicationHidden`) | Works. Gallery vanishes and unhide restores it. DumberOS App Blocker lists it as "Restricted by another controller such as parental controls or a work administrator". | Hiding is the right primitive. App Blocker sees our hides but does not fight them. Still to check: can App Blocker unhide it? |
| Suspend | App goes grey but stays listed; App Blocker shows nothing; launching says "blocked by work policy". | Suspend is visible to the child and still shows the app. Use hide, not suspend. |
| Protected apps | Hiding or suspending the dialler is refused (log shows `blocked`). | The protected-app layer works. |
| Factory reset / safe boot | Power menu has no reset or safe-mode entry on this build, so the restriction could not be tested. Probably already removed by DumberOS. | Still set both restrictions, but record that the J36 gives no test. Retest on another build later. |
| Date and time | Greyed out by the restriction. | Works. |
| Add user | Reported as "worked". | Works (assumed to mean the restriction blocked it; confirm if unsure). |
| Debugging restriction | Works as a lock, but is **destructive**: setting it switched off developer options, OEM unlock and USB debugging, and unticking it did not turn them back on. They had to be re-enabled by hand. The 3-minute auto-revert only clears the restriction, it cannot restore those switches. | Treat as one-way for the child. For a maintenance window, HA must tell the parent that debugging must be re-enabled by hand on the phone. Test again later. |
| Other restrictions (VPN, private DNS, USB, apps control, accounts, unknown sources) | Not tested. | Test later. |
| Grayscale | `adb shell pm grant ... WRITE_SECURE_SETTINGS` was needed. The device-owner self-grant and the other routes failed. With the grant, the grey switches work. | The real controller needs the adb grant, or a provisioning route that grants it. Record as a setup step. Re-assert watcher result still unknown. |
| Force stop protection | `setUserControlDisabledPackages` stopped force stop. | Works. Clear data not checked. |
| Battery exemption | The system prompt appeared (assumed granted). Self-grant result not recorded. | Needs the log line to confirm. |
| Heartbeat, Doze, exact alarms | Overnight test replaced by a forced-Doze test in the rig (see below). | See rig result. |
| Home pin | Inconclusive by hand (only one launcher); see the rig row below, where a second launcher was added. | See rig row. |
| Camera | Device-owner self-grant of CAMERA worked (`call=true`, `now=true`), unlike the Android 12+ expectation. | The controller can grant itself CAMERA, so QR pairing needs no prompt. |
| QR scan | Read quickly from a monitor. | Works. |
| Keystore | `level=software` (no hardware-backed key on this chipset). | Signing works. A root-level attacker could extract the key, which is out of scope. |
| Companion nudge | `command_broadcast_intent` reached our receiver almost instantly, including with the screen off. | Works as the sync-now nudge. Sender identity (`from=`) not recorded. |
| Network | Plain HTTPS POST works (httpbin echoed body and the `X-PG-Lab` header). Typing URLs on the J36 is impractical, so the lab app now takes network tests by `adb shell am broadcast`. | See next row. |
| Nabu Casa webhook (2026-11-07, driven over adb) | Through the Remote UI domain (`<id>.ui.nabu.casa/api/webhook/<id>`), POSTs of 1 KB, 64 KB, 256 KB and 512 KB all returned 200 in 23 to 348 ms, and six 1 KB posts 5 s apart all returned 200 (about 25 to 50 ms). HA ran the automation 9 times; the six small ones were read back intact. A plain webhook automation returns an empty body. HA's webhook trigger does not expose request headers at all. | Body-only envelope confirmed as the right choice. 512 KB is not a limit worth worrying about. The reply test needs a custom integration (a webhook handler can return a body). Not tested: the separate `hooks.nabu.casa` cloudhook URL, and long-run rate limits. |
| Rig run (2026-11-07, `rig/`, driven over adb) | 14 restriction keys set and read back as effective: factory reset, safe boot, date/time, add user, add managed profile, unknown sources, VPN, private DNS (`disallow_config_private_dns`), USB file transfer, apps control, modify accounts, credentials, user switch, mobile networks. DumberOS itself already sets `no_add_private_profile`, `no_add_managed_profile` and `no_add_clone_profile`. Date settings screen shows "Enabled by admin" on the time and time-zone rows. | Restrictions work on this build. We must clear only our own keys, never DumberOS's (the DPM clear call only removes ours). |
| Force-stop protection (rig) | With `setUserControlDisabledPackages` on Gallery, tapping Force stop in Settings shows "Blocked by work policy"; without it, the normal "Force stop?" dialog appears. (The device-owner app's own Force stop is always greyed, so it is not a valid test target.) | Works. Use it for the controller and, optionally, for the HA Companion app. |
| Grayscale re-assert (rig) | Watcher re-asserts within about one second of the setting being switched off, **but only while the app's process is running in the foreground**. As a plain cached broadcast-receiver process it never fired. Screenshots cannot show the filter (applied after capture). | The controller must run a foreground service (or equivalent) to hold grayscale. Add a periodic re-assert on the alarm as a backstop. |
| Home pin (rig) | With Trebuchet pinned via `addPersistentPreferredActivity` and a second launcher installed, the Home button still opens Trebuchet even after `set-home-activity` and the Settings screen show the other launcher as selected. | Pin holds, but the Settings radio is misleading: the child may think she changed it. Acceptable; note it in the HA UI help. |
| Battery exemption | Already exempt (`exempt=true`, standby bucket 5 = exempted, exact alarms allowed). | Good for the heartbeat. Whether the DO can self-grant is still unknown (it was exempted by hand earlier). |
| Forced Doze (rig, 2026-11-07) | Phone unplugged (simulated), screen off, `dumpsys deviceidle force-idle`: six 1-minute exact alarms all fired on the minute (`late=0s`, `deviceIdle=true`). The app was battery-exempt with exact alarms allowed. | Exact alarms survive forced deep Doze when exempt. Caveats: forced idle is not a real night, and the exemption may be hiding the usual limit on `setExactAndAllowWhileIdle` (about one per 9 minutes when not exempt). Still worth one real overnight run before relying on it. |
| Companion full flavour on microG (J36, 2026-11-07, release 2026.11.1, signing key matches the minimal app) | Works. After enabling the "Background location" sensor (off by default), `device_tracker` reported `home` with GPS accuracy 8 m within a minute. `command_broadcast_intent` reached our receiver in about a second, including after the process was killed and the screen was off. `FirebaseCloudMessagingService` is bound and microG's `McsService` (GCM) is running, so push is going through microG. No Google account was added to microG. | The full flavour is usable on this DumberOS/microG build, so section 4d's "unsupported" note is superseded for this device. Use the full flavour. Caveat to watch: long-run push reliability and battery cost of microG's connection. |
| Companion minimal: location (same day, before the above) | The minimal flavour has a "Geolocation sensors" section with only "Geocoded location"; Background location, Single accurate location and Zone do not exist in that build. | Lack of feature in minimal, as the docs said. |
| One Companion registration per phone | Registering the full app replaced the minimal app's HA registration (the old entry was deleted). Both apps derive the same device id. | Only one Companion flavour can be linked to a phone. Uninstall the other. |
| Location cadence (2026-11-07) | The full app's "Sensor update frequency" is already at its default "Updates every 15 min". Real-time mode uses the owner's existing pattern: `request_location_update`, then `command_high_accuracy_mode` on for 15 minutes, then off (the Find Matthew/Karen scripts). The minimal app was uninstalled; only the full app remains. | No change needed. A "Find her phone" script is the same two commands against `notify.mobile_app_dumbphone2`. |

Overall: the core control (hide, protect, restrict, force-stop protection) works on this device. Items marked "test later" are not blockers for starting the real controller, but the overnight alarm test and the debugging-restriction recovery should be done before relying on them.

## 8. Testing plan (outline)

Emulator first, then a spare/reset phone, then the J36. Nothing goes on the daughter's phone
until it works end to end, and a stock ROM backup exists.

## 9. Implementation status (controller core, 2026-11-07)

Built under `controller/`, verified on the J36 with the rig (`rig/run_controller.sh`):
- Mode and schedule engine (pure Kotlin, unit tested): segments, same-weekday pattern after the
  window, boost as a union, warnings only when a change removes apps, DST-correct pattern edges.
- Enforcement: hide and unhide only apps we hid ourselves (never touches apps hidden by DumberOS
  App Blocker or anything else), layered protected apps, default restriction set
  (factory reset, safe boot, date and time, add user, add managed profile), force-stop protection
  for the controller and Companion app, grayscale held by a foreground service.
- One evaluate entry point on every trigger (alarm, boot, time or timezone change, package change,
  config change), one exact alarm for the next change or 10-minute warning, 30-minute safety re-check.
- Storage is device-protected so it works after a reboot before the PIN is entered.
- Debug build only: `testOnly`, a rig command channel, and a debug clock offset for jumping through
  the timetable. The release build has none of these.

Rig result: 40 checks passed (Emergency with no config; night, school, day; boost; warning; window
rollover with the weekday pattern; new-app quarantine within about 5 s; grayscale re-assert).
Not yet built: pairing and transport, HA integration, single-use override code, PIN-protected
release, usage reporting, "tamper" detection and alerts, release-build signing and install order.

## 10. End-to-end prototype findings (2026-11-07)

Built and run against the real Home Assistant (core 2026.11.0) and the J36:
- `controller/` (phone) and `ha/` (integration `phoneguard`, card `phoneguard-card`), with a stand-in
  server and the rig for repeatable tests.
- **Nabu Casa cloudhooks cannot carry replies.** The cloudhook URL (`hooks.nabu.casa/...`) answers
  200 with an empty body at once. Our protocol needs the signed reply in the response, so the phone
  posts to the remote UI route instead: `https://<id>.ui.nabu.casa/api/webhook/<webhook_id>`. That
  returns the handler's body (checked). It depends on Nabu Casa remote access being on. Supersedes
  the earlier "cloudhook URL" wording in 4d and 4f. The webhook id is a 64-character secret and every
  message is also signed, so knowing the URL is not enough.
- HA's webhook trigger exposes no request headers, so the body-only envelope is right.
- Pairing, SAS confirmation, config push, app approval, boost, cancel, new-app quarantine and
  nudge-driven sync all work through Nabu Casa. The nudge arrives within seconds. The 15-minute poll
  runs on its own.
- Release from HA is implemented (the phone restores everything and gives up device owner) but not yet
  run on the live pairing.

Implementation notes that matter later:
- State lives in device-protected storage and the receivers and service are direct-boot aware, so
  enforcement resumes after a reboot before the PIN is entered. Not yet tested by an actual reboot.
- The phone converts HA's boost expiry to its own clock using the server time in the reply.
- Phone-side counter is persisted before sending, so a lost reply never reuses it.
- HA state is published as a plain `sensor.phoneguard_<child>` state with the JSON in one attribute
  (not a registered entity), so it disappears from the entity registry. Make it a real entity later.
- Not built: phone-side recovery key and offline release, single-use override code, webhook rate
  limiting, week editor in the card, usage reporting, real entities, real release build and signing.

## 11. Lockdown and launcher (measured 2026-11-07, J36)

- `DevicePolicyManager.setStatusBarDisabled(true)` works on this DumberOS build with the stock
  launcher: the swipe that normally opens quick settings and notifications does nothing. Turned off
  again, the shade returns. It does not need a custom launcher.
- Hardware keys on the J36 keypad (`mtk-kpd`): 0-9, star, hash, Enter, D-pad up/down/left/right,
  Volume up/down, Menu, Back, Send (call). `mtk-pmic-keys`: Power. The touch panel also reports Home,
  Menu, Back and some letters.
- Usage access (`GET_USAGE_STATS` app-op) cannot be self-granted by a device owner; it is an adb
  setup step like WRITE_SECURE_SETTINGS.
- Card 0.3.0 adds several devices grouped by person, copy settings between devices (timetable,
  holiday, restrictions, per-mode grey screen and everything-allowed; never app lists), configurable
  Day mode, restrictions panel and screen time by app.

### Lock-task and touch-overlay experiments (2026-11-07, J36, stock Trebuchet as the locked launcher)

Lock-task (`setLockTaskPackages` plus `setLockTaskFeatures(0)`, started with `ActivityOptions.setLockTaskEnabled`):
- It only engages when the launcher is started as a **new task** (`NEW_TASK | CLEAR_TASK`). Started into an existing task, nothing locks.
- Blocked: the pull-down shade and quick settings, starting a non-allowed app (Settings refuses with
  error 101), and the power menu (no power off, restart or lockdown).
- Allowed: apps on the list open normally (dialler tested). Home returns to the launcher. Recents still
  opens but lists only the locked-task apps.
- **Problem: alarms.** The clock alarm rings but its notification is hidden, so there is nothing on
  screen to snooze or dismiss. Options: let our launcher show an alarm screen from the notification
  (needs notification access, one more adb grant), test lock-task `NOTIFICATIONS` together with a
  disabled status bar, or keep alarms out of School. Not yet decided or tested.
- Not testable here: incoming calls (no SIM).

Touch overlay (a transparent full-screen `TYPE_APPLICATION_OVERLAY` window that consumes touches):
- Blocks touches, including injected ones, in a real app. Hardware keys are unaffected.
- With it on, D-pad focus moved through the dialler's controls and OK activated a tab, so
  keypad-only navigation works in at least that app.
- Needs the "draw over other apps" app-op, which a device owner cannot self-grant: one more adb setup
  step (`appops set <pkg> SYSTEM_ALERT_WINDOW allow`).

WhatsApp without touch (web search): no non-touch WhatsApp client turned up. WhatsApp for KaiOS has
ended. Android keypad phones (Smilyphone, the Dumber Mini) run the stock WhatsApp app; a reviewer
describes moving a cursor with the D-pad to reach Send. So stock WhatsApp with keypad only has to be
tested by us.

### Launcher variants (design requirement, 2026-11-07)
The owner may want several "dumb" launcher styles later, for example a later Nokia look that is not text
only. Build the launcher as a small engine with replaceable skins: one place owns what the launcher
must do (current mode's apps, key handling and focus, status line data, notification counts, alarm and
call handling, touch and shade policy), and each skin only draws it. The skin is a per-mode setting
next to "launcher: normal or Phone Guard". The first skin is the monochrome Nokia list style from the
mock-up.

### Decision on alarms in School (2026-11-07)
School excludes the Clock app, and School is silent, so nothing can ring and there is no alarm screen
to build under lock-task. Hiding the Clock removes all its scheduled alarms, so none can fire (checked:
0 registered while hidden). Unhiding it re-registers them at the right times (checked with a 03:00
alarm: back at 03:00 the next day), so a morning alarm still works after School. A silent sound
profile alone would not have stopped alarms (they use their own volume); hiding the Clock does.
Consequences: the Clock must be manageable (listed in `unprotect`) so a mode can hide it; alarms set
for School hours are skipped, not delayed; the launcher shows the time itself.

### Nokia launcher, first build (2026-11-07)
Code: `controller/.../launcher/`. `LauncherModel` (engine: screens, key handling, menu; plain Kotlin,
10 unit tests), `LauncherSkin` (the interface a skin implements), `NokiaSkin` (first skin: monochrome
grey list style, drawn on a 480 by 640 design grid), `LauncherData` (reads mode, time, battery, signal,
missed calls and new messages), `LauncherActivity` (Home activity, keypad only, touch ignored).

Checked on the J36 with the keypad over adb:
- Home screen matches the mock-up (status line, clock, date, mode banner, counts, softkeys).
- Menu: numbered entries, black selection bar, scrolling, Enter or 1 to 9 to open, Back to return.
  Digits on the home screen start dialling. Back on Home opens Contacts.
- The menu lists exactly the apps the mode leaves visible: in School only Phone, Messaging, Contacts
  and Camera; Browser, Clock and DAVx5 are gone.
- Inside lock-task (no shade, no power menu, Settings refused) the launcher works, key 1 opens Phone
  and Home returns to the launcher.
- A device owner can silently grant the launcher's own read permissions (call log, SMS, phone state).

Gotchas found:
- Installing a second Home app makes Android forget its default Home and ask which to use on the Home
  key. The controller must set the Home app explicitly (persistent preferred activity) for every mode,
  never leave it to the system.
- Allowed apps show first-run permission prompts (Contacts asked for notifications). The keypad did not
  answer one reliably, and with touch off it would be stuck. The controller should pre-grant the
  runtime permissions of every allowed app (device owner can grant for other apps) before School.
- Counts come from the call log and SMS inbox. WhatsApp unread counts need notification access
  (an adb grant) and are not built.

### Per-mode lockdown and mode override (built 2026-11-07)
- Each mode has: `launcher` (default or nokia), `touch`, `shade`, `kiosk`, plus the existing grey
  screen and app list. School defaults to nokia, touch off, shade off, kiosk on; everything else is open.
  Boost takes the least restrictive value of each setting. Emergency (no config) never locks anything.
- The controller applies them on every evaluation: Home app pinned explicitly, status bar, touch
  overlay, lock-task with the apps the launcher menu shows (plus calling plumbing), and pre-grants
  the allowed apps' non-sensor permissions. Leaving the mode puts everything back.
- Checked on the J36: 18 rig checks, including School and Day round trips, Settings refused, touch
  blocked inside the dialler, Clock hidden then restored, Home key reaching the right launcher.
- Mode override: HA forces a mode by adding `override` to the config it sends. It beats the timetable
  until set back to Auto. Checked live: forcing School on a Saturday locked the phone down within
  about ten seconds and Auto restored it.
- Setup needs these adb grants once: device owner, `WRITE_SECURE_SETTINGS` (grayscale),
  `GET_USAGE_STATS` (usage), `SYSTEM_ALERT_WINDOW` (touch blocking).
