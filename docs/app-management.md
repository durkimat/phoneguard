# Installing, removing and updating apps from Home Assistant (proposal)

Why: once the phone is out in the world there is no adb. Everything that adb does today (installing an app, removing one, putting a new
Phone Guard on the phone) has to be possible from HA, safely, with the parent in charge.

## Two jobs, one mechanism
1. **Apps the parent chooses** (a game, a map app, a school app): install and remove from the Phone Guard card.
2. **Phone Guard itself**: an "Update available" prompt on the card, one press to update a phone.

Both use the same on-phone installer, because Phone Guard is the device owner: a device owner may install and remove apps silently
through Android's package installer (no prompts on the keypad), and may replace itself.

## On the phone: the installer
- A new list in the settings HA already sends: `installs` (package, name, version, download address, SHA-256, size) and `removals` (packages).
- The phone does the work only on Wi-Fi and with enough storage and battery: downloads to its private storage, checks the SHA-256 against
  the one HA sent (HA's message is signed, so the hash can be trusted), then installs through a PackageInstaller session. Android itself
  refuses an update signed by a different key than the installed app, so an app can never be swapped for an impostor.
- Removal: the same installer's uninstall call; never for protected apps (phone, messages, settings, Home Assistant, Phone Guard).
- Each result (done, failed, why) goes back in the next sync and shows on the card. New apps arrive under the existing matrix rules:
  hidden until a parent ticks them in a mode (or "Allow new apps automatically" is on for that mode).
- Phone Guard updating itself is the same call with its own package. The app restarts; device-owner status is kept. The stock launcher
  stays installed as a fallback, so a bad build cannot leave the phone without a home screen.

## In HA: the card
- **Apps** section: installed apps with an X to remove (not for protected ones), and "Install an app": choose from your own catalogue.
- The catalogue is what the parent adds: upload an APK to HA (kept in /config/www/phoneguard/apks, never in the repository) or paste a
  direct download address and its SHA-256. HA records the package name and version from the APK itself. We do not host other people's apps.
- Later: browse an open-source store (F-Droid's published index) from the card; the index and each app's signature are verified.

## Updating Phone Guard (the "PowerEngine-style" route: git, releases, a check in the integration)
- GitHub Actions on a tag builds the signed release APK and attaches it to a GitHub Release with its SHA-256 (the same kind of release
  workflow as PowerEngine's, including the approval step and "CI green first").
- The Phone Guard integration in HA checks the GitHub Releases of the repository every few hours, like HACS does. If a release is newer
  than the version a phone reports, the card shows "Update available: 0.4.2" with the release notes and an **Update this phone** button.
  Nothing installs without a parent pressing it (an "update automatically at night" switch could come later, off by default).
- HA sends the phone the GitHub asset address and hash; the phone downloads direct from GitHub (no load on HA or Nabu Casa).
- The update is allowed in Night or when the parent presses it, never during School, and best when plugged in. After the update the phone
  reports its new version; if it has not checked in 10 minutes later HA says so.
- Test phone first: each phone can be on a channel (stable or test), so a new build goes to a spare phone before the child's.
- The release signing key must be backed up safely: Android will refuse updates signed with any other key.

## Safety rules
- Only a parent (HA admin) can start an install, removal or update; the phone only accepts these in HA's signed settings.
- HTTPS only, hash checked, size limit, storage check; unknown sources are not offered.
- Protected apps and Phone Guard cannot be removed from the card.
- Every action is logged on the phone and shown on the card.

## Order of work
1. On-phone installer and remover, tested on a small APK (needs an adb-free test with HA sending the command).
2. The Apps section on the card (catalogue, upload, install, remove, results).
3. Release pipeline on GitHub, the update check and the Update button, tested on a spare phone.
4. Open-source store browsing; optional automatic night updates.

## Open questions
- Is "PowerEngine apk" the Phone Guard APK (what this assumes), or is there a separate Android app in the PowerEngine project?
- Should an update ever be automatic, or always a parent's press?
- Which phone is the test phone for new builds?

## Which store? (review, 2026-11-09)
Checked by searching F-Droid for the apps a family wants: it has none of the mainstream ones. WhatsApp, Spotify, YouTube, Signal, Google Maps, Snapchat and the like are not there (searches return add-ons, unofficial clients and look-alikes). It is right for small tools, notes, clocks, maps (OsmAnd, Organic Maps), a browser (Fennec, Brave via its own site), podcast players, keyboards and simple games.

| Source | What it has | Automatable from HA? | Notes |
|---|---|---|---|
| F-Droid | about 4,000 open-source apps | yes (search and index, with checksums) | clean, safe, but no mainstream apps |
| IzzyOnDroid | about 1,200 more, some free closed-source apps | yes (same format as F-Droid, 16 MB index) | a good second source |
| Official vendor downloads | WhatsApp (whatsapp.com APK), Signal (latest.json with SHA-256), Telegram, Firefox, Brave and others publish their own APKs | yes, from a short curated list | the safe way to get the mainstream apps that publish APKs |
| Add by link | anything with a direct https APK address | yes (HA fetches it once to learn its checksum) | for the parent's own choices |
| Google Play (through Aurora Store) | everything, including Spotify | no (it logs in to Google as a guest through an unofficial route and needs its own screens) | keep Aurora installed as the fallback for Play-only apps; it is a touch app, so only in modes with touch |
| APKMirror, APKPure and similar | re-hosts of Play apps | not reliably | third-party copies; not recommended |

Decision: the Apps and store section searches F-Droid and IzzyOnDroid together (results say which store), has an "Official downloads" list for
the mainstream apps that publish APKs, and a "Install from a link" box. Aurora stays on the phone for what the others cannot supply. A child
could be given the same screen in HA to ask for an app; the request goes to a parent to approve (later).
