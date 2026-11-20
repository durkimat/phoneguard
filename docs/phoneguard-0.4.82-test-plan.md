# Phone Guard 0.4.82 test plan: shared WhatsApp library (refactor, no behaviour change)

Test APK: /artifacts/phoneguard-0.4.82-wacore-test.apk. The WhatsApp store, transport and command parser moved into the `:wa-core` library module. Nothing should look or behave differently.
1. Update from 0.4.81 over the top: the phone keeps its WhatsApp link (no re-link), allowed numbers and "g:Name" groups.
2. Receive a message from an allowed number and an allowed group: it appears with the sender's name, unread star, tune (not for history).
3. Send a reply (tag name in front when the profile asks for it); a group message shows under "Me" for your own messages.
4. Message from a number or group that is NOT allowed: nothing stored or shown.
5. Read on the main phone clears the star here; opening a chat tells WhatsApp it was read.
6. Missed/declined call note still appears in the chat; resync (names, history) still works.
7. After a reboot the link and the lists are still there; unlink (logged out on the main phone) wipes and shows not linked.
8. Memory and battery: the idle disconnect still happens about two minutes after the last look.
