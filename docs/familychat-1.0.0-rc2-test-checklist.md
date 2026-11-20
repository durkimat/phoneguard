# Family Chat 1.0.0-rc2: test checklist (for the owner)

Install `familychat-1.0.0-rc2.apk` over rc1 on the child's phone (same signing key, so it updates in place). Use a spare WhatsApp account first if you can. All of rc1's checklist still applies (setup, commands in `FamilyChat-<child>`, sending, notifications, read receipt, reconnect, reboot, keyboard layout). New in rc2:

1. Look: green screens, sand chat background, light green bubbles for the child, white for others; new icon (white bubble with a padlock on green, and the themed icon on Android 13+).
2. Receive a sticker (static and an animated one), a photo, a GIF, from your phone into an allowed group: each appears in the chat; the animated sticker moves; the GIF loops silently. Tap a photo: full screen with sender, group, time and caption.
3. Receive a video: the card says "Tap to download . <size>" and its length. On mobile data only it should say "Connect to Wi-Fi to download this video" (Admin > "Download videos only on Wi-Fi" is On); on Wi-Fi it downloads, then plays. Switch that setting Off and try on mobile data.
4. Admin > Chat options > "Show pictures, GIFs and stickers" Off: new media shows only as [photo], [sticker], [GIF], [video]. Back On.
5. Nothing is downloaded from a group that is not allowed, or from the admin group. Pictures are not in the phone's gallery (open Photos: nothing from Family Chat).
6. Reactions: react to a message from your phone: the emoji appears under it with a count. Long-press a message on the child's phone: react with a thumbs up: in WhatsApp the group sees a reply "<child>: <thumbs up>" quoting the message (not a real reaction).
7. Reply: long-press > Reply: the banner shows, send: others see a quoted reply with the child's name. A reply sent from your phone shows its quote above it on the child's screen.
8. Edit and delete: send a message, long-press: Edit (within 15 minutes) changes it for everyone and shows "edited". Delete for everyone removes it for everyone. Others' messages offer neither. Edit and delete from your phone: the child's screen follows (edited / "This message was deleted").
9. Stickers: tap the smiley: Happy, Words, Animals tabs. Tapping one sends "Sent by <child>" and then the sticker, in that order, in the group.
10. Photos and videos are OFF by default: no "+" button. In the admin group send `photos on`: reply "Photos and videos: on" and the "+" appears (also in Admin > Chat options). `photos off` reverses it.
11. With photos on: "+" > Photos: pick a picture, add a caption: the screen says "Everyone will see: <child>: <caption>". Send: the group sees the picture with that caption; with an empty caption "<child>:". Check the received photo has no location (look at its details in WhatsApp).
12. "+" > Videos: pick a video under 5 minutes: "Preparing video" then "Sending video... N%". A video over 5 minutes is refused with a plain message. The received video is smaller (about 720p). Record one with "+" > Camera as well; and take a photo with the camera.
13. Wallpaper: the picture button at the top right of a chat: try Sand, Mint, Sky, Lilac, Dots, Stripes, Confetti, Night (text stays readable) and "From my photos"; "Use this wallpaper" keeps it for that chat only; no PIN needed.
14. Limits: send many things quickly: after about five a minute you should see "Sending too fast. Try again in a minute." and nothing half-sent (a sticker's label is never sent without the sticker).
15. `Phone Guard` is unchanged: its WhatsApp, if installed, answers `photos on` in its admin group with "Not supported here".
