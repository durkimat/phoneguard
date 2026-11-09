# Apps on the phone, and updating Phone Guard (decision, 2026-11-09)

## Decision: keep it simple
- **People install their own apps with Aurora Store on the phone** (Spotify, WhatsApp and the rest are all there, and Aurora updates them).
  Phone Guard does not search stores, parse apps or run installs for other apps: that would mean following each store's changes forever.
- **HA only controls access.** A new app starts hidden. It appears in "Modes and apps" with a NEW tag; the parent ticks it in the modes
  where it is allowed (or switches on "Allow new apps automatically" for a mode). The Running Apps screen and the power saver work
  on whatever is installed.
- **HA does two jobs for the phone.** Updating Phone Guard itself (with the phone out in the world and no adb, this is how a new
  version gets onto it), and **removing an app** the parent does not want: a cross on the app's row in "Modes and apps", with a
  confirmation. Protected apps (phone, messages, settings, Home Assistant, Phone Guard) cannot be removed. Nothing is ever installed
  for other apps from here.

## Updating Phone Guard
- `controller/publish-release.sh 0.2.0 "notes"` builds, signs (key in `~/.phoneguard-release`, never in the repository; back it up,
  Android only accepts updates signed by the same key) and creates a GitHub release with the APK and its SHA-256.
- The HA integration checks GitHub's newest release every 6 hours (service `phoneguard.check_updates` checks now). The card's
  "Updates and removals" section shows the version on the phone, the newest release and notes, and an **Update this phone** button.
  Nothing installs until a parent presses it.
- The phone waits for Wi-Fi, 25% battery (or charging) and storage; downloads over https; checks the SHA-256; installs silently as
  device owner; reports back at its next check-in. Android refuses an update signed with a different key. The stock launcher stays as
  a fallback so a bad build cannot leave the phone without a home screen.

## Not built, on purpose (kept in the backlog)
Store search (F-Droid, IzzyOnDroid, official downloads), install and remove of other apps from HA, driving Aurora from HA, uploads with
signer checks, a child's request flow. They were built and tested briefly (an install and a removal of Calculator You worked end to end),
then removed to avoid a maintenance burden; see git history before 2026-11-09 evening for the code.
