# Family Chat 1.0.0-rc1: test checklist (for the owner)

Install `familychat-1.0.0-rc1.apk` on the child's Android 16 phone (check the .sha256 first). Use a spare or test WhatsApp account first if you can. This is a release candidate, not a release.

1. Install and open. The icon is a white speech bubble on teal, the name "Family Chat". Setup starts by itself.
2. Setup 1-6: tick the risk box; PIN (try mismatched PINs and a 3-digit PIN: both refused); names; link (number with country code, code appears, type it in WhatsApp > Linked devices; Continue only after it says linked); make the group FamilyChat-<child> in WhatsApp, tap Look again, it is listed; Use this group; pick groups with the search box and the chips (nothing ticked at first); Finish. Android asks about notifications: try both Allow and refuse (the app must work either way).
3. In the admin group you should see a reply starting with a double arrow: "Ready. Commands: ...".
4. From YOUR phone, in FamilyChat-<child>, send: `groups`, `add group <name>`, `remove group <name>`, an unknown word, a name that does not exist, and two groups with one name (reply with the number). Check each reply's wording and that the child's chats list changes at once. A message typed in the admin group by anyone but you must do nothing.
5. Sending: open a group, type a message. It appears on the right in teal; in WhatsApp it arrives as "<child>: text". Messages you send from your own phone show on the child's screen under your name, not "Me".
6. Notifications: send a message from your phone to an allowed group with the app closed. A notification "Messages" appears; tapping opens that conversation. Nothing for the child's own messages, nothing for the admin group, nothing for a group not allowed.
7. Read receipt: open the conversation; your phone should show the message read (blue ticks / unread cleared). In Admin > "Tell WhatsApp when read", switch Off, send another message, open it: it should stay unread on your phone. Switch back On.
8. Admin: the lock button asks for the PIN; five wrong tries make it wait 30 s. Admin menu shows only chosen groups; "Add or remove groups" works with search; "Admin group" shows the help; Change PIN; About and risks shows the warning, the version 1.0.0-rc1 and the licence text.
9. Keyboard and screen edges: with the keyboard open the message box stays above it (conversation); PIN pad, setup fields and the picker's search box are not hidden by the keyboard or the bars; check both portrait and, if you like, landscape.
10. Stay connected: Admin > Stay connected: tap it and allow "Unrestricted"/"Don't optimise". Also in Android Settings > Apps > Family Chat > Battery set Unrestricted, and turn OFF "Pause app activity if unused" and "Remove permissions if app is unused". Some makers' phones also have an "Autostart" or "Background activity" switch: allow it.
11. Reconnect: switch airplane mode on for a minute, off again: new messages arrive within about a minute without opening the app.
12. Reboot: restart the phone, unlock it, wait two minutes, do not open the app: a message sent from your phone should still notify. If it does not, note that: opening the app starts it again.
13. Unlink: Admin > Unlink this device: asks first, then returns to setup; the device disappears from WhatsApp > Linked devices (or remove it there).
Report anything odd with the time it happened.
