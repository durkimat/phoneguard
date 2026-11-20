# Family Chat 1.0: first-release plan (draft for the owner)

Nothing here is released or approved. The release needs the owner's go; PC publishes.

## What 1.0 contains
- Groups only (no one-to-one chats), by group id. Send and read text; the parent's name shown instead of "Me".
- Setup, once, by the parent: child's and parent's names, link as a linked device (pairing code), create the admin group "FamilyChat-<child>" and bind it by id, set a PIN, keep-connected (battery exemption) in the app's own words.
- Parent control from the admin group, from the parent's own phone: `add group <name>`, `remove group <name>`, `groups`, a number to choose between groups with the same name. Acks go to the admin group. The admin group is never shown to the child nor allowed as a chat.
- Always-on connection: foreground service with a minimal notification; reconnect after boot.
- Local PIN (salted PBKDF2, on the phone only) for parent-only screens.
- Not in 1.0: media, calls, a companion/Worker, remote config, other languages, a Play Store listing.
- Screens: the owner's approved 12-screen mockup (visual work not started). Skeleton and tested non-visual parts are on branch familychat-core (SetupFlow, PinStore, ConnectionService, PrefsSettings).

## Identity
- Module `:familychat`, applicationId `io.github.durkimat.familychat`, label "Family Chat" (no "WhatsApp" in the name; release script fails if it appears).
- Own signing key `~/.familychat-release/` (created 2026-11-20 by the script, certificate SHA-256 28770c2a…0114). OWNER MUST BACK UP THIS KEY: lost key = no updates. Never Phone Guard's key.
- Version code = major*10000+minor*100+patch (1.0.0 = 10000).

## Release script and steps
`FC_VERSION=1.0.0 ./release-familychat.sh` (refuses without the bridge AAR; deletes any old APK first so a failed build can't hand over a stale one; runs :wa-core and :familychat tests; signs; checks package, not testOnly, no WhatsApp in the label, bridge packaged). Then, by hand: copy APK to /artifacts as `familychat-1.0.0.apk`, `sha256sum` to `.sha256`, verify with `-c`, check versionName with aapt, hand PC the commit hash + changelog text. Push to main by fast-forward after a secrets scan.

## GitHub release assets
`familychat-1.0.0.apk`, `familychat-1.0.0.apk.sha256`, `NOTICE.txt`, and release notes (changelog + the risk paragraph below). Tag `familychat-v1.0.0`. Phone Guard releases keep their own tags.

## NOTICE text (draft)
```
Family Chat
Copyright the Family Chat authors. Licensed under the Apache License 2.0 (see LICENSE).

This app is NOT made by, endorsed by or connected to WhatsApp or Meta. "WhatsApp" is a trademark of its owner.

It connects to WhatsApp as a linked device using the unofficial open-source library whatsmeow
(https://github.com/tulir/whatsmeow), version go.mau.fi/whatsmeow v0.0.0-20261107160454-79dc2a8c33df,
licensed under the Mozilla Public License 2.0 (https://www.mozilla.org/MPL/2.0/). The library's source is
available at the address above; any changes to its files are published with this app's source.
Other components: Go standard library and golang.org/x modules (BSD-3-Clause), protobuf (BSD-3-Clause),
gomobile (BSD-3-Clause), and the Android SDK libraries (Apache-2.0).

Risk: using an unofficial client is against WhatsApp's terms of service. WhatsApp can block or ban
the account it is linked to, without notice. Use an account you can afford to lose; the app limits
messages per minute and per day to lower, not remove, that risk. Messages are held on the phone only,
and only for groups the parent has allowed.
```
(Before release: regenerate the third-party list from `go.sum`/`go list -m all` rather than trusting the line above.)

## Risks and open items
- Account-ban risk is the main one: wording above stays in the app's setup screen too, and in the release notes.
- Battery: the prototype-day result decides how the always-on promise is worded.
- Bridge is arm64 only.
- whatsmeow is pinned to one commit; bump deliberately with the bridge tests.
- Needs hardware testing: linking, admin group binding, commands from the parent's phone, reconnect after reboot.
- Google/F-Droid listings: not planned.
