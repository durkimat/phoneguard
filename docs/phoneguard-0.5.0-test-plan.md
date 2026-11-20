# Phone Guard 0.5.0 test plan: admin-group commands (WhatsApp section only)

Test APK: /artifacts/phoneguard-0.5.0-wa-admin-test.apk (built before the FamilyChat- prefix change; behaviour for Phone Guard is the same). Needs the Worker changes in /artifacts/worker-handoff-wa-admin-20261120.md for the full path; without them the phone keeps changes locally and re-sends them each sync.
1. The admin group has the id in the profile (`adminGroup`); a message from the parent's own phone there is read as a command, from anyone else or from the child's phone it is ignored.
2. `add group <name>`: the group is allowed at once on the phone; reply in the admin group: "» Added: ...". `remove group <name>`, `groups`, an unknown word (usage line), a name that does not exist.
3. Two groups with one name: a numbered list is sent; replying with the number picks it.
4. Same-sync ordering: the change leaves in the NEXT sync request (state.wa.admin.changes), before the Worker's config is applied; with the Worker answering later the allow list never briefly reverts (unit test WaPendingTest).
5. Worker answers: applied (change dropped from the queue), superseded (a newer companion edit wins: the phone says so in the admin group), rejected.
6. Authority: commands change only the allowed groups/tag; no other setting can be changed or loosened by chat.
7. The child's name tag is put in front of every message by the bridge (also for commands' acks no tag).
8. History is ignored: old messages in the admin group are never replayed after a reboot or re-link.
