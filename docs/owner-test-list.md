# Owner test list (things only a person with the phone can check)

Kept up to date as work goes on. Tick off and tell me results; nothing here blocks the build work.
Last updated 2026-11-14 (images up to ims21).

## Calls and messages
- [ ] Call audio both ways on a VoLTE call (earpiece and speaker): ims21 fixes the audioserver crash that broke the audio up (stable through a 25 s call); you still need to LISTEN and judge quality, both earpiece and speaker.
- [ ] Phone Guard's own call screen appears for outgoing AND incoming calls (outgoing verified by the PC session on ims21).
- [ ] Receive a call from another phone; end it from both sides.
- [ ] Wi-Fi calling: does Settings > Network > SIM show a Wi-Fi calling entry (needs wfc props; not verified at all).
- [ ] SMS: already verified both ways with the new SIM (ims7). Re-check once on the final image, and MMS if you use it.
- [ ] Voicemail / call forwarding (supplementary services over UT) not tested.

## Data
- [ ] Browser/web page on mobile data with Wi-Fi off (the PC session proved ping/curl on ims7; after a wipe data needs the phone to count as provisioned, which ims15 now does: check it).
- [ ] Your main SIM (Smarty on Three): dropped from plan as pointless; skip unless something odd shows.

## Phone Guard integration (ims15)
- [x] Phone Guard is the only home screen after boot; Home key returns to it. (ims15+, you tested)
- [ ] Nav bar hidden and Phone Guard soft keys visible (verified by the PC session on ims20; look yourself).
- [ ] Green call key from home with digits typed places the call (call permissions are pre-granted since ims19; not yet checked by a person).
- [ ] No setup wizard on a freshly wiped phone; Phone Guard is device owner (Settings > Security shows "managed").
- [ ] Phone Guard first-run flow as a system app: it may ask for runtime permissions (SMS, contacts, location, BT, mic...). Pre-granting them in the image is a to-do.
- [ ] T9 input (TT9) selected and working in Phone Guard text fields.
- [ ] Lock screen / PIN, and the white flash after unlock (known problem to fix).
- [ ] Bluetooth pairing and audio to a real speaker/headset (B1 blocker): the adapter works (checked), a real pairing + music + call audio over BT has not been tried on our image.
- [x] Camera: opens and saves a photo (PC session, ims21). Check picture quality and the dithered preview yourself.
- [ ] Join Wi-Fi (the phone sees your home network; needs the password), then Wi-Fi calling test.
- [ ] Email and Calendar: set up one real account / calendar (menu shows both since 0.3.1).
- [ ] Boot: Qin logo -> warning -> animation timing and the fade-out when ready (stopwatch).
- [ ] Hard keys and the keypad behave (F2 key etc.).

## FM radio (when the app launches: ims26/ims27)
- [ ] With the PC's USB cable left in (needed for adb and flashing; it may act as an antenna by coupling), the PC session reports tune/seek/RSSI in short-antenna mode.
- [ ] Manual test with the USB-C headphones plugged in at the right moment (they are digital; check whether the FM chip or the audio path accepts them at all and whether anything is heard); try the app's speaker/headset switch.
- [ ] If there is a 3.5 mm socket or an analog adapter, the long (wired) antenna mode is the normal case: try it.

## Things to decide
- [ ] Phone Guard signing: privileged app on its own release key (recommended, docs/integration-plan.md) vs platform key.
- [ ] Should Phone Guard's own call screen replace the stock Dialer/Messages or sit alongside?
- [ ] Back up the signing keys off the NAS, encrypted: /archive/phone-backups/signing-keys-20261114 (and the Phone Guard release key in ~/.phoneguard-release).
- [ ] Push the local commits to GitHub (many are unpushed).
