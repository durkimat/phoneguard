# No pop-ups policy (2026-11-14)

Owner: Android can raise all sorts of pop-ups (permission/admin notices, crash boxes, USB prompts); fixing them one by one is whack-a-mole. This is the layered catch-all plus a detector so leaks are found by a log, not by someone seeing them.

## Layers
1. **Heads-up banners off, always** (`Settings.Global heads_up_notifications_enabled=0`): set by pg-provision.sh at first boot and re-applied by the app at every evaluation whatever the mode/config (`NoPopups.baseline` in Controller.evaluate; the old per-mode `HeadsUp.set` stays for release/restore). Every notification still lands in the shade and on Phone Guard's own Notifications screen; nothing slides over the screen. This covers the 'Apps can access your location', 'Phone Guard has location access', 'Controlled permissions', USB-debugging and charging notices (all importance-4 heads-ups).
2. **Crash / 'isn't responding' boxes off** (`Settings.Global hide_error_dialogs=1`): same two places.
3. **The device owner no longer posts its location notice** (image patch 0016: DevicePolicyManagerService.showLocationSettingsEnabledNotification is a no-op) and the app no longer makes DPC grants the image already made (0.3.8): the notices are not just hidden, they are never created.
4. **Permission prompts** cannot appear: runtime permissions are pre-granted by `/system/etc/default-permissions`; 'Allow USB debugging?' exists only on debug builds (the release build has debugging off).
5. **Detector** (always on, metadata only): `NoteService` logs every notification posted (`notice: package id channel cat importance`, plus the title for system packages) and `F2Service` logs every other app's or the system's window that comes to the front (`window: package/class`) into Phone Guard's own log (`Log.log`). After a test run pull the log and read the `notice:` / `window:` lines: whatever is not Phone Guard's own is a leak to fix.

## Deliberately NOT done
- `StatusBarManager.disable(DISABLE_NOTIFICATION_ALERTS)`: it would also silence the SMS/message sounds the child needs (the notification sound of Messages), and needs the STATUS_BAR permission; revisit only if sounds are wanted off.
- A SystemUI patch that blocks every full-screen intent: it would also block incoming-call screens and Wireless Emergency Alerts. If one is ever needed it must allow-list calls (CATEGORY_CALL/ALARM) and the cell-broadcast package.
