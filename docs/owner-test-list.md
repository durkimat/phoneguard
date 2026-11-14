# Owner test list (things only a person with the phone can check)

Kept up to date as work goes on. Tick off and tell me results; nothing here blocks the build work.
Last updated 2026-11-14 (images up to ims15).

## Calls and messages
- [ ] Call audio both ways on a VoLTE call (earpiece and speaker): ims7+ registers on IMS, a 123 call connected (HD), audio not judged by a person yet.
- [ ] Receive a call from another phone; end it from both sides.
- [ ] Wi-Fi calling: does Settings > Network > SIM show a Wi-Fi calling entry (needs wfc props; not verified at all).
- [ ] SMS: already verified both ways with the new SIM (ims7). Re-check once on the final image, and MMS if you use it.
- [ ] Voicemail / call forwarding (supplementary services over UT) not tested.

## Data
- [ ] Browser/web page on mobile data with Wi-Fi off (the PC session proved ping/curl on ims7; after a wipe data needs the phone to count as provisioned, which ims15 now does: check it).
- [ ] Your main SIM (Smarty on Three): dropped from plan as pointless; skip unless something odd shows.

## Phone Guard integration (ims15)
- [ ] Phone Guard is the only home screen after boot; Home key returns to it.
- [ ] No setup wizard on a freshly wiped phone; Phone Guard is device owner (Settings > Security shows "managed").
- [ ] Phone Guard first-run flow as a system app: it may ask for runtime permissions (SMS, contacts, location, BT, mic...). Pre-granting them in the image is a to-do.
- [ ] T9 input (TT9) selected and working in Phone Guard text fields.
- [ ] Lock screen / PIN, and the white flash after unlock (known problem to fix).
- [ ] Bluetooth pairing and audio (B1): never re-tested on our image since the baseline fix (patch 0005).
- [ ] Boot: Qin logo -> warning -> animation timing and the fade-out when ready (stopwatch).
- [ ] Hard keys and the keypad behave (F2 key etc.).

## Things to decide
- [ ] Phone Guard signing: privileged app on its own release key (recommended, docs/integration-plan.md) vs platform key.
- [ ] Should Phone Guard's own call screen replace the stock Dialer/Messages or sit alongside?
- [ ] Back up the signing keys off the NAS, encrypted: /archive/phone-backups/signing-keys-20261114 (and the Phone Guard release key in ~/.phoneguard-release).
- [ ] Push the local commits to GitHub (many are unpushed).
