# Open and Family editions: how it works (Phase 1, built 2026-11-15, app 0.4.0, image ims39)

Owner decisions (2026-11-15): the edition is chosen once, on the phone's first-run screen. Open to Family later is a guided factory reset; Family to Open is the parent's Release. Open never reports anywhere and has no companion. Open updates of Phone Guard may ask for a confirmation tap (no silent install).

## States
`FIRST_BOOT` (nothing chosen), `OPEN`, `FAMILY` (`Edition.kt`). A phone that already was a device owner before this version counts as FAMILY. The edition is stored in device-protected preferences and mirrored to `Settings.Global pg_edition` (open or family) so the first-boot service knows when to stop waiting.

## Image (`pg-provision.sh`, init service `pg_provision`)
1. **Plumbing, always:** skip the Lineage wizard, system look, no-pop-up baseline, usage-access and draw-over app-ops (root `cmd appops`), mobile data retry, hand a pushed enrolment bundle to the app, start Home. **Phone Guard is no longer made device owner at first boot.**
2. **Owner on request:** while no edition is recorded the script polls `Settings.Global pg_owner_req` every 3 s. When the app writes `1` (after a confirmed Family pairing) the script runs `cmd device_policy set-device-owner` as root (uid 0 counts as "adb") and writes `0`, or `-1` if Android refused. The loop ends when `pg_edition` is open or family. An already-owner phone gets only the plumbing.

## App
* **First-run screen** (launcher screen `FIRSTRUN`, cannot be left until a choice): Wi-Fi, Time and language (Android Settings), Open phone (asks "Are you sure?"), Family phone (opens the existing "Connect to the family companion" screen). A SIM with data needs no step.
* **Family:** `Edition.becomeFamily` runs after a confirmed pairing (companion or Home Assistant): asks for owner, waits up to 90 s, records FAMILY, evaluates. If Android refuses (accounts or extra users present) the phone stays as it was and says why.
* **Open:** `Store.config` returns `OpenConfig` (one permissive mode, a few options) instead of a parent's config, so every feature that asks "what is allowed" works and `Controller` runs `evaluateOpen` (no hiding, restrictions, lockdown or reporting). Android Settings is offered where touch is allowed (always, in Open).
* **Settings on an Open phone:** Phone options (Sound, Notifications, WhatsApp checks, Camera, Internet radio, Power saver radios, Spotify Client ID), Phone type (explains that a lost PIN means erasing, and "Switch to Family (erases)" with a confirmation that sends the factory-reset broadcast; needs `MASTER_CLEAR`, listed in the priv-app allowlist).
* **Release:** `Link.releaseWith` ends with `Edition.afterRelease` (the phone becomes Open). Pairing is refused on an Open phone.
* `IdleReaper.stop` (Running screen "Kill") works without a device owner through `forceStopPackage`.

## Services on an Open phone
| Service | Open |
|---|---|
| Email | works on the phone (T9 address and app password, existing screens) |
| Calendar | the phone's own calendar works; parent-style ICS subscriptions are Family-only for now |
| Spotify | type your own Spotify developer Client ID under Phone options, then Sign in (opens the browser; touch needed) |
| WhatsApp | links on the phone (existing pairing screen); everyone allowed |
| Timetable and modes | not in Open for now (one permissive mode; options above) |
| Hiding apps, restrictions, lock task, silent installs, screen-time reaper | Family-only |

## Not done in Phase 1
Local timetable/modes for Open, per-app allow lists in Open, an on-phone calendar-link entry, optional Open + companion. Conversion without a wipe (Spike 3) is not needed.
