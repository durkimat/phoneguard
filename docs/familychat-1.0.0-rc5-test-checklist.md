# Family Chat 1.0.0-rc5: test checklist (for the owner)

rc5: anyone in the admin group can send commands, and the admin help screen shows why a command might get no answer. Install over rc4 (same key). Earlier checklists still apply.

1. **Dad's commands**: Dad (another WhatsApp account that is a member of `FamilyChat-<child>`) sends `help`: the reply (starting with the double arrow) lists the six commands. Then `groups`, `add group <name>`, `remove group <name>`, `photos on`, `photos off`: each answered. A number reply to "Two groups are called ..." works from Dad too.
2. **Linked account**: the same commands from the phone that owns the linked WhatsApp account still work.
3. **Nothing else acts**: the child's own app and its own replies never act as commands (no loop of replies). A person who is NOT in the group cannot post there at all.
4. **Same second**: send two commands quickly one after the other (for example `add group Football` then the number `2`): both are answered.
5. **Wrong clock**: if a phone's clock is ahead, later commands are still answered.
6. **Setup screen (step 5)** and **Admin > Admin group** say: "Everyone in this group can send instructions, so only add adults you trust. Never add <child>'s own number." The help example shows Dad sending `remove group Cousins` and the reply "Removed: Cousins".
7. **When silent**: open Admin > Admin group and read the bottom lines: "Last command accepted: <time>", "Last admin message: <time> command accepted" or "message ignored (<reason>)" (history, older than the last command, sent by this device, our own reply, empty), and "Connection: up/down". Send a command, reopen the screen, and note what it says. Tell us the lines if a command gets no answer.
8. **Phone Guard** is unchanged: only its linked account can send commands in its admin group (the new switch is off there).
