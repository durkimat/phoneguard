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

## 3. Architecture (outline)

- **Controller app** (Android, Kotlin): device owner. Applies the current mode with
  DevicePolicyManager (hide/suspend apps, block install/uninstall, safe boot, factory reset,
  debugging; pin the preferred Home app). Holds the timetable, PIN, and the HA link.
- **HA integration** (`custom_components/phone_guard`): exposes each phone as a device with
  entities (mode selector, override, current mode, provisioned/enforcing status, last seen,
  battery). Owns the timetable and pushes it to the phone.
- **Launcher** (phase 2, optional).

## 4. Transport (to decide)

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
