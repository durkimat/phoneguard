# Family Chat 1.0: first-release plan (draft for the owner)

Nothing here is released or approved. The release needs the owner's go; PC publishes.

## What 1.0 contains
- Groups only (no one-to-one chats), by group id. Send and read text; the parent's name shown instead of "Me".
- Setup, once, by the parent: child's and parent's names, link as a linked device (pairing code), create the admin group "FamilyChat-<child>" and bind it by id, set a PIN, keep-connected (battery exemption) in the app's own words.
- Parent control from the admin group, from the parent's own phone: `add group <name>`, `remove group <name>`, `groups`, a number to choose between groups with the same name. Acks go to the admin group. The admin group is never shown to the child nor allowed as a chat.
- Always-on connection: foreground service with a minimal notification; reconnect after boot.
- Local PIN (salted PBKDF2, on the phone only) for parent-only screens.
- Not in 1.0: media, calls, a companion/Worker, remote config, other languages, a Play Store listing.
- Screens (built 2026-11-20 from the owner's approved mockup, plain Android Views, no Compose): Chats, Conversation, Admin PIN, Admin menu (groups, names, admin group help, stay connected, change PIN, About and risks, Unlink), the shared group picker (search, chips, A-to-Z list) and the six setup steps. Admin sub-screens are one activity that closes when left. Test APK: familychat-0.9.0-screens-test.apk.
- Also built: real account linking (pairing code), boot receiver, local admin-group commands (add group / remove group / groups / number), reply wording in wa-core WaAcks.
- Known gaps for 1.0: no launcher icon yet, no new-message notification, no read receipts back to WhatsApp, boot start of the foreground service is blocked on Android 15+ (fine on 14).

## Identity
- Module `:familychat`, applicationId `io.github.durkimat.familychat`, label "Family Chat" (no "WhatsApp" in the name; release script fails if it appears).
- Own signing key `~/.familychat-release/` (created 2026-11-20 by the script, certificate SHA-256 28770c2a…0114). OWNER MUST BACK UP THIS KEY: lost key = no updates. Never Phone Guard's key.
- Version code = major*10000+minor*100+patch (1.0.0 = 10000).

## Release script and steps
`FC_VERSION=1.0.0 ./release-familychat.sh` (refuses without the bridge AAR; deletes any old APK first so a failed build can't hand over a stale one; runs :wa-core and :familychat tests; signs; checks package, not testOnly, no WhatsApp in the label, bridge packaged). Then, by hand: copy APK to /artifacts as `familychat-1.0.0.apk`, `sha256sum` to `.sha256`, verify with `-c`, check versionName with aapt, hand PC the commit hash + changelog text. Push to main by fast-forward after a secrets scan.

## GitHub release assets
`familychat-1.0.0.apk`, `familychat-1.0.0.apk.sha256`, `NOTICE.txt`, and release notes (changelog + the risk paragraph below). Tag `familychat-v1.0.0`. Phone Guard releases keep their own tags.

## NOTICE text (now the file NOTICE.txt at the repo root; the app's About screen links to it on main)
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

## rc2 (2026-11-20): richer chat
- Receive: pictures, stickers (animated WebP too), GIFs (short looping videos) are fetched by the bridge as they arrive (up to 5 MB, live messages only, never history) into app-private storage; videos show "Tap to download . size", are fetched on tap (up to 100 MB, Wi-Fi only by default), and play in the app (VideoView). A cache cleaner removes unreferenced files and the oldest beyond 200 MB.
- Reactions shown under messages; the child's reaction is a quoted reply "<child>: <emoji>" (a real WhatsApp reaction carries no name, so it would look like the parent's). Replies quote. Edit (own text, within 15 minutes) and delete for everyone (own only: keys are sent as "from me", so another person's message cannot be touched). "edited" label.
- Labelling rule in one place (wa-core WaLabels, run by the transport): a sticker is preceded by "Sent by <child>"; photos and videos carry "<child>: caption" ("<child>:" when empty). Each item counts as one send and a burst is refused whole.
- Send: bundled stickers (36, our own artwork: Happy, Words, Animals), photos (upright, scaled to 1600 px, re-saved as JPEG so EXIF/GPS is gone), videos (Media3 Transformer to H.264/AAC 720p, then a remux that drops location; up to 5 minutes and 100 MB). "+" shows only when the parent allows photos and videos (default OFF; admin menu or "photos on"/"photos off" in the admin group). Photo picker and the system camera (no storage or camera permission; a small private content provider gives the camera its file).
- Per-chat wallpaper (8 original ones + "From my photos"), new green look, new icon (white bubble with a padlock cut out, adaptive and themed).
- New library: androidx.media3 transformer and effect 1.4.1 (Apache-2.0), listed in NOTICE.txt.
- Not in rc2: GIF search, voice notes, calls, location, opening links, an animated sticker pack (the bridge flag exists), thumbnails sent with photos (receivers see the picture after download).
- Media through an unofficial client raises WhatsApp's attention: limits stay at 5 sends a minute and 200 a day; a sticker costs two, a photo or video one.

## rc6 (2026-11-21): sticker library and chat themes
- Static stickers: 371 from Microsoft Fluent Emoji, 3D style (MIT), pinned commit 1ffb34c7..., 256 px source upscaled to a 512 px lossy WebP each (the 3D PNGs are 256 px; WhatsApp wants 512), picker downscales. Tabs: Recent (last 24 sent, kept on the phone), Animated, Happy, Love, Animals, Food, Sports, Party, Weather, Words (our own art; our own Happy and Animals designs are kept at the end of those tabs). Search over the CLDR name and keywords.
- Child-safety deny list in `controller/familychat/stickers/deny.json` (alcohol, smoking, weapons, drugs, gambling, adult or suggestive, rude gestures, death, flags and religious or political symbols, creepy insects), applied by `select.py` to Fluent's names and keywords and checked again by unit tests against the committed `selection.json`; hand-picked exclusions for taste. When unsure, left out.
- Animated: 53 from Google's Noto animated emoji (CC BY 4.0, credited in NOTICE.txt and About), fetched once by `stickers/build-stickers.py`, re-encoded (256 px, every second frame) to about 110 KB each, sent with the animated flag; no live GIF search and no new network use by the app.
- The library is generated by `stickers/build-stickers.py` (Pillow + network once, cached) and not committed (about 12 MB); `release-familychat.sh` runs it when missing.
- Label rule: a sticker gets "Sent by <child>" unless it directly follows the child's own sticker (the last message in the chat, under 2 minutes, no reaction since): `WaLabels.needsLabel`, tested. Limits unchanged (5/min, 200/day); a sticker without the label is one send.
- Chat theme: "My bubbles" (six colours, own bubbles only) and 32 wallpapers in five groups drawn in code, plus "From my photos"; per chat, no PIN; earlier choices (sand, mint, sky, lilac, dots, stripes, confetti, night, photo) still work. Dark and mid-tone wallpapers get light text or a white chip behind names and times.

## rc7 (2026-11-21)
- Sticker tray and the Chat theme wallpaper grid use the full width: `TileGrid` takes the row and picks the column count from it (`Rich.columns`: at least 4, about 84 dp per tile; wallpaper tiles at least 3, about 100 dp), tiles fill each row evenly, one vertical scroll, tabs scroll sideways. Tested for 360, 412, 800 and 1280 dp.
- `help` now answers with the commands on one line and the current settings, one plain line each (groups, photos and videos, video download, pictures/GIFs/stickers, read receipts, connection, version). One shared function, `WaAcks.help(photos, status)`, fed by a small `WaAcks.HelpStatus` that each app implements; Phone Guard supplies only its groups, connection and version. The command list is unchanged, so COMMANDS_VERSION stays 3 and there is no new upgrade note.
