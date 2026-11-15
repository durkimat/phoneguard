# ims39 (editions, app 0.4.0): what was checked, and what is left for the phone

Checked on the NAS and PC (no phone): 308 unit tests pass (new: first-run screen, Open choice with confirmation, Family start, Wi-Fi from first run, Phone type and erase confirmation, Phone options cycling, OpenConfig permissiveness); release checks ok; image builds (SELinux policy compiles with the new `appops_service` rule); image contents verified: new `pg-provision.sh` (owner-on-request), PhoneGuard.apk 0.4.0 (33,899,301 bytes), `MASTER_CLEAR` in the priv-app allowlist.

NOT run yet (needs the J36, wipe flash `flash-wipe-ims39-20261115`):
1. First boot reaches the first-run screen; no device owner (`cmd device_policy list-owners` empty); `logcat -s pgprov` shows "plumbing done, no device owner".
2. Open path: choose Open, confirm; Wi-Fi/Bluetooth toggles; Running screen Kill (force-stop); Android Settings opens; Phone options change sound/notifications; WhatsApp pairing; email setup; usage-stats app-op granted by the script; no avc denials for `pg_provision` or the app.
3. Family path on a first-boot phone: pair against a Worker; `pg_owner_req` set; root service sets device owner (check the "Android refused" case with an account present); sync, override and boost then work; a second wipe flash plus Release returns to Open; pairing refused on Open.
4. Phone type, Switch to Family, Erase and restart: phone erases and returns to first-run (needs `MASTER_CLEAR` to be honoured for the broadcast).
5. No-wipe flash (`flash-system-ims39-20261115`) over the current Family phone: still FAMILY (already device owner), nothing changes.
6. The screen wake cycle on USB (open backlog item).
Wizard/installer: tell the parent that after flashing, "on the phone choose Open phone or Family phone" (Family opens the Connect screen).
