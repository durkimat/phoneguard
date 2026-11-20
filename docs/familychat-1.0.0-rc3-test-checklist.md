# Family Chat 1.0.0-rc3: test checklist (for the owner)

rc3 is a small change on rc2: the admin group now tells you about every command, including `photos on`, `photos off` and the new `help`, and an upgraded phone says so once. Everything in the rc2 and rc1 checklists still applies.

1. **Upgrade from rc2 (or rc1)**: install `familychat-1.0.0-rc3.apk` over the old one (same key, it updates in place). Open the app once and wait for it to connect. In the `FamilyChat-<child>` group one message appears: `» Updated to 1.0.0-rc3. New: photos on, photos off, help. Send help for all commands.`
2. **Once only**: close and reopen the app, switch airplane mode on and off, restart the phone: the note must NOT come again.
3. **`help`**: send `help` in the admin group: the reply lists, one per line, add group <name>, remove group <name>, groups, photos on, photos off, help.
4. **Unknown word**: send something else (for example `hello`): the reply says "I did not understand. Use: add group <name>, remove group <name>, groups, photos on, photos off or help."
5. **Other commands still work**: `groups`, `add group <name>`, `remove group <name>`, `photos on`, `photos off` (replies as before).
6. **Admin screen**: Admin > Admin group lists all six commands.
7. **Fresh setup** (only if you unlink and set up again): the "Ready" message in the admin group lists all six commands, and NO "Updated to" note follows it.
8. **Phone Guard** (if its WhatsApp is set up with an admin group): `help` lists only add group, remove group, groups and help; `photos on` answers "Not supported here".
