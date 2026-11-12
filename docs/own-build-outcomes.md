# Own build: which problems and hacks it should address (tracked)

Started 2026-11-12. Sources: `docs/workarounds.md` (hacks 1-11), BACKLOG.md (phone problems), `docs/own-build-viability.md` §11.
"Baseline" = the plain Lineage 21 / TrebleDroid image built on 2026-11-11 (test-keys, no Phone Guard integration).
"Integrated" = a later build with our own signing keys, Phone Guard as a platform-signed privileged app, and our other changes.
**Confidence is a prediction, not a test result.** Update the Status column after each real test; never mark "Verified" without a test on the phone.

Status values: Untested, Verified-works, Verified-fails, Dropped.

## A. Blockers (a failure here stops the project)

| ID | Item | Why it matters | Baseline expectation | Plan if it fails | Confidence | Status |
|---|---|---|---|---|---|---|
| B1 | **Bluetooth audio** (A2DP music, headset calls) | Owner: a blocker | Unknown. TrebleDroid's MT6761 Bluetooth audio patches (sysbta, audio policy) are among the ~15-20 rejected patches we still have to triage | Triage the rejects for audio/BT first; compare with Dumber's vendor/system audio config (extracted, read-only); test with the owner's headphones at first boot | Medium (see rejected-patches-triage.md: vendor already has the BT audio HAL) | Untested |
| B2 | **VoLTE calls** (no 3G on Smarty/Three) | No voice without it | Expected to FAIL: MediaTek IMS apps and vendor services missing | Port ImsService, MtkGbaService, MtkTelephonyAssist, CarrierConfig, mediatek-res and the framework jars from the phone's original stock system | Medium | Untested |
| B3 | **Wi-Fi calling** | Owner wants it; not a blocker | Expected to fail until B2 works | Same port plus EPDG/IWLAN services | Low-medium | Untested |
| B4 | Boots, modem registers, mobile data, SMS | Basics | Unknown | Debug with logs | Medium | Untested |

## B. The 11 registered hacks (docs/workarounds.md)

| # | Hack | Addressed by our build? | Needs | Confidence | Status |
|---|---|---|---|---|---|
| 1 | Accessibility service presses Bluetooth "Pair" | Yes, pair directly | Privileged app, BLUETOOTH_PRIVILEGED | Medium | Untested |
| 2 | Hidden Bluetooth calls by reflection | Mostly | Privileged app (hidden-API exempt) | Medium-high | Untested |
| 3 | BluetoothAdapter enable/disable | Already fine as device owner | Nothing new | High | Untested on our build |
| 4 | Self-grant WRITE_SECURE_SETTINGS | Yes, pre-grant in image | Privileged-permission allowlist | High | Untested |
| 5 | Visualizer(0) spectrum | No | Custom audio hook would be needed | Low | Untested |
| 6 | Start Spotify behind a cover | No (Spotify Web API limit) | n/a | High that it stays | n/a |
| 7 | Force-stop idle apps | Yes | Platform signature | High | Untested |
| 8 | WhatsApp via whatsmeow bridge | No (WhatsApp limit) | n/a | High that it stays | n/a |
| 9 | Cover and touch-blocker overlays | Partly | Platform signature, kiosk/lock-task, app rework | Medium | Untested |
| 10 | WifiManager.setWifiEnabled | Already fine as device owner | Nothing new | High | Untested on our build |
| 11 | Mobile data switch | Yes | Privileged telephony permission; needs a SIM test | Medium | Untested |

Possibly revived: flight mode via the proper privileged API (owner's call); FM radio (FMRadio built in, hardware path unchecked, low).

## C. Phone problems seen on the Dumber image

| Problem | Addressed by our build? | How | Confidence | Status |
|---|---|---|---|---|
| NFC service loops every 15 s (no NFC chip) | Yes | Leave the NFC package and features out of the image (disable-user did not stop it; uninstall did) | High | Untested |
| **Unlock (PIN) screen does not match the theme** | Likely | See D below | Medium | Untested |
| **White screen after unlock; stock launcher flashes** | Likely | See D below | Medium-high | Untested |
| Launcher ANR on boot/update | Already fixed in the app | Evaluation moved off the main thread | High | Fixed in app, re-check on new ROM |
| SMS default-app gap | Yes | Preset the default SMS role holder | Medium-high | Untested |
| Self-update | Works already; cleaner with signed OTA | Later | High | n/a |
| Enrolment (QR, ADB, factory reset) | Possible | Phone Guard set as device owner at first boot | Medium | Untested |
| Keypad layout | Probably fine | Generic layout; check on first boot | Medium | Untested |
| T9 text input | Yes | Add tt9 | High | Untested |
| Slow first boot, power behaviour | Unknown | | Low | Untested |

## D. Unlock screen, white screen after unlock, stock launcher flash

What the BACKLOG found: the PIN pad is Android's own secure window. A trust agent would let us keep the PIN for boot and skip it by day, but
`PROVIDE_TRUST_AGENT` is signature|privileged, so an ordinary app cannot provide one. `setKeyguardDisabled` is refused while a PIN is set. The
stock launcher (Trebuchet) stays in the task stack and Android falls back to it; hiding it breaks Quickstep ("Unable to create service
TouchInteractionService").

With our own build we control all of these:
1. **Theme the PIN screen**: SystemUI is in our tree, so a runtime overlay (RRO) can set keyguard colours, or we can patch the layout. Colours and fonts are easy; reshaping the pad is more work. Medium-high.
2. **Trust agent or a proper lock policy**: as a privileged app Phone Guard can provide a trust agent, which gives "unlocked by day, PIN at boot" without dropping security. Medium; needs design and testing.
3. **No stock launcher**: build without Trebuchet/Launcher3QuickStep so Phone Guard Home is the only HOME. That removes the fall-back to the stock launcher and the brief flash. Risk: gestures/recents (Quickstep) are not needed on a keypad phone, but a failing launcher leaves no home screen, so keep a recovery path. Medium-high.
4. **White screen**: most likely the window background shown while our launcher starts after keyguard exit. Fix the launcher theme's window background (works on any ROM, test on Dumber first) and, in our build, make the launcher persistent/priority-started. Medium-high.

Only item 4 can be started now; the rest needs the integrated build.

## E. Order of work

1. Baseline flash: B4, then B1 (Bluetooth audio), then B2/B3.
2. Triage the 58 rejected patches with B1/B2 first.
3. Integrated build: own keys, Phone Guard as priv-app, no NFC, no Trebuchet, tt9, themed keyguard, SMS role, device owner at first boot.
4. Re-test every row; record the result here.

## F. Legal and security (not bugs)

Test-keys baseline is for the desk only. The MediaTek apps are proprietary (redistribution needs legal advice). The TrebleDroid patch licence is unread.
