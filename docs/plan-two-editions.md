# Phone Guard: one firmware, two editions: plan v0.1 (draft for the owner)

Written 2026-11-14 from the owner's brief, the repo's SPEC.md, BACKLOG.md, docs/companion-research.md,
docs/integration-plan.md, docs/licensing.md and what we learned flashing ims1..ims34 today.
Nothing in here is built. Items marked **(verify)** are things I believe but have not confirmed from an official page or on the phone.

"Phone Guard" from here on means the whole combination: the phone image + the Phone Guard app (+ optionally a companion).
The standalone-app idea is dropped (the owner's call: too many lost features, and retro launchers already exist).

---

## 0. The one-paragraph version

Build ONE image and ONE app. The two products are two **states of the same phone**, chosen at first boot and changeable later:

* **Open phone**: nothing locked, no companion, every feature configured on the phone with the keypad. Aimed at adults who want a cut-down retro phone.
* **Family phone**: the same phone, enrolled to a parent's companion on Cloudflare; Phone Guard becomes device owner, the lockdown and the schedules switch on, and the parent configures things (including the awkward ones: Spotify, email, WhatsApp) from a web page.

The difference is a switch in the app's state, not a different build. The companion is an optional module that is either absent (Open) or paired (Family). The owner's priority is Family, so the plan builds the shared foundation only as far as Family needs it, and Open falls out of it almost for free.

---

## 0a. Decisions made so far (owner, 2026-11-14)

1. **Cloudflare accounts are parent-owned.** The parent owns the account, the data, and the GDPR/Cloudflare-terms responsibilities directly. The project runs no multi-tenant service and holds no family's data. (Closes the old "whose account?" question.)
2. **Home Assistant is no longer required**, but an **optional HA integration is very desirable**, as a **later** feature: enrolment would offer "use Home Assistant instead of Cloudflare" (install the HA add-on/integration). **Not in the first release; the design must keep the door open** (section 3.4).
3. **Focus first on getting the Cloudflare version working**, with HA kept modular so it can be added later; existing HA code is reused where it can be; if the two backends can be maintained in parallel (features added to both together) we do that.
4. **Audience:** not decided. Test path: **the owner's own phone is wiped back to a "factory installed" Phone Guard** and used to test enrolment from scratch. Public outreach (a post on the dumbphones subreddit) is **not soon**, so there is time to settle licensing and the setup journey first.

Still open: see section 8.1 (the numbered items not listed above).

---

## 1. The two editions side by side

| | Open phone | Family phone |
|---|---|---|
| Who | adults, enthusiasts with a Qin J35/J36 | a parent giving a child a locked-down retro phone |
| Companion | none (can be added later) | parent's Cloudflare companion (required) |
| Where things are set up | on the phone: keypad menus, T9, QR import | parent's web page; child cannot change locked things |
| Device owner / lockdown | **off** (Android Settings reachable, any app installable) | **on** (hidden apps, restrictions, lock task, time integrity) |
| Modes (School/Day/Night) | optional, local timetable, user can edit | parent-defined, enforced, signed |
| PIN / lock | user's own PIN (the themed pad, already built) | parent's PIN set from the companion (not built yet) |
| Release / undo | the user changes it themselves | parent-authorised release or recovery key; factory reset as last resort |
| Updates | user installs a new image/app | parent approves; app updates silent via the companion; image updates see section 9 |
| Data held outside the phone | none | the parent's own Cloudflare account only |

The things both share, and which are therefore built once: launcher and skins/themes, dialler and call screen, Messages, WhatsApp bridge, camera, music (Internet Radio, Spotify), email/calendar, Wi-Fi/Bluetooth screens, PIN pad, boot animation, power saver, T9.

---

## 2. How "same firmware" works

### 2.1 Layers
1. **Image** (system partition only; the phone's own vendor/kernel stay): AOSP/Lineage 21 via TrebleDroid + our patches, SystemUI changes (PIN pad hand-over, no stock lock screen), boot animation/logo, `pg_provision` first-boot service. Identical for both editions.
2. **Phone Guard app** (priv-app, signed with its own release key): launcher, features, a **local policy engine**, a **local config store**, and an **edition state machine**.
3. **Companion client** (a module inside the app, dormant in Open): the signed sync protocol (today's SPEC section 4d/4f), backend-agnostic. Backends: *none*, *Cloudflare* (default for Family), *Home Assistant* (kept as an optional adapter for power users; the owner's own setup).
4. **Backend** (Cloudflare Worker + Durable Object + a parent web page): outside the firmware.

### 2.2 The edition state machine (the key design item)
```
FIRST BOOT --choose--> OPEN ----enrol----> FAMILY
                          ^                   |
                          +---release---------+   (parent-authorised, or recovery key)
```
* **FIRST BOOT**: today `pg_provision` makes Phone Guard device owner and skips the wizard unconditionally. Change: it only sets the *plumbing* (priv-app grants, roles, home); **device owner is set at enrolment**, not at first boot.
* **OPEN**: Phone Guard is home, PIN pad, themes; no device owner; every setting editable on the phone.
* **FAMILY**: device owner on; enforcement from the signed config; locked settings greyed out in the on-phone menus ("set by your parent").
* **Open to Family without a wipe** needs a privileged "become device owner" helper (a root-run init service that a priv-app can trigger through a protected system property). **(verify on the phone: set-device-owner on a device that already has a user and accounts is normally refused; our root service may bypass it.)** If it cannot be done, the fallback is "enrol = guided factory reset", which is acceptable because enrolment normally happens on a fresh phone anyway.
* **Family to Open** (release): clear device owner, drop restrictions, keep the app as a normal launcher. Already designed (SPEC 4f "Release phone").

### 2.3 One config model
A single versioned **profile document** (modes, timetable, app matrix, allowed contacts, connector settings, radio rules, appearance). In Open it is edited on the phone; in Family it is authored in the companion and delivered **signed**; the phone stores and enforces it locally, so it works offline. Fields carry a `lockedByParent` flag. This is the same data HA holds today (`sensor.phoneguard_*`), but backend-neutral. Design rule from the existing backlog: keep the protocol and policy engine free of HA-specific code.

---

## 3. The Family companion on Cloudflare

### 3.1 Whose account? (DECIDED)
**Parent-owned.** Each family's Worker, storage and data sit in the parent's own Cloudflare account, so UK GDPR and Cloudflare-terms responsibility are the parent's, and the project holds nobody's children's data. The cost is that the parent needs a Cloudflare account and one guided deploy step, which is why that step is the first thing to prove (section 8.2). The setup page must explain, in plain words, what "your account, your data" means and what Cloudflare's free plan covers.

### 3.2 Parts
* **Worker** (API the phone and the parent page talk to). Verifies Ed25519/ECDSA signatures with WebCrypto.
* **Durable Object (SQLite)** per family: ordering, device state, counters, queue of commands for an offline phone (spike 3 showed the phone catches up).
* **Parent web page** (static, served by the same Worker/Pages): dashboard (mode now, battery, last seen, location), timetable, apps, contacts, connectors, release, recovery key. Works on iPhone and Android as a normal web page / home-screen web app.
* **Parent login**: Cloudflare Access email one-time PIN covers a second parent (free up to 50 users **(verify the terms for use inside a consumer app)**); the primary parent is the Cloudflare account owner. Passkeys are the alternative.
* **Updates**: the Worker (or R2) serves the Phone Guard APK + SHA-256 from GitHub releases; the app is on its own release key (no platform key in the pipeline, per `docs/integration-plan.md`).
* **Transport**: unchanged from SPEC 4d: the phone polls (about every 15 minutes inside its radio check-in windows, on boot, on connectivity return); events post immediately; push is only an optional "sync now" nudge. This avoids needing FCM and is battery-friendly. iPhone parents get web push only if the page is added to the Home Screen **(verify)**, so nothing may depend on push.
* **Sealed credentials**: anything secret entered by the parent (email password, API keys) is encrypted **in the parent's browser to the phone's public key**; the Worker only relays ciphertext, the phone stores it in the Android Keystore. The backend never sees a password.
* **Free-tier limits and ToS: not yet checked on Cloudflare's own pages.** The backlog notes D1 limits being enforced from 1 September 2026; keep storage behind a thin layer so Durable Object SQLite or D1 can be swapped.

### 3.3 What stays from the HA design
Pairing (QR + 10-minute code + confirmation digits + recovery key), the signed envelope with counters/nonces, override codes verified offline, the modes/timetable/app-matrix semantics, the threat model. These move to a protocol v2 spec that does not mention HA.

### 3.4 Keeping Home Assistant possible later: the "backend port"
Goal: Cloudflare first, but adding an HA backend later is a new adapter, not a rewrite of the phone or of the features.

* **A backend port on the phone side**: the app talks to one small interface (`pair`, `sync`, `event`, `fetchConfig`, `fetchUpdate`, `sealedCredential`, `release`). `CloudflareBackend` implements it first; `HomeAssistantBackend` later (today's webhook code, moved behind the same interface). No feature code may call a backend directly or mention a backend by name.
* **A backend-neutral schema as the source of truth**: the profile document (modes, timetable, app matrix, contacts, connectors, radio/location rules), the event list, and the connector declarations are defined once in a versioned schema (JSON Schema). Each backend and each parent UI is generated from or checked against it.
* **Parity by construction**: every new feature is defined in the schema first. A shared **conformance test suite** (test vectors for signed messages, config documents, sync sequences) runs against each backend; a feature that one backend does not support must be listed as unsupported in a capability table rather than silently missing. This is how "added to both at the same time" is enforced rather than hoped for.
* **Parent UI per backend**: the Cloudflare parent page is the reference UI. The HA card and integration (existing code: `ha/custom_components/phoneguard`, `ha/www/phoneguard-card`, about 1,600 lines of Python plus the card) later become the second UI over the same schema; reuse what fits (hub logic, timetable semantics, Spotify sign-in, power/measurement sensors, tests), port the rest.
* **Enrolment choice (later)**: the "Start setting up" page asks "Cloudflare (recommended) or Home Assistant"; the HA path installs the add-on/integration and then the same QR pairing flow applies, the phone only learning a different backend address and key.
* **What the first release must avoid**: letting Cloudflare-specific assumptions leak in (Durable Object ordering semantics, a Cloudflare-only login, storing config in places only a Worker can read). Rule of thumb: if it cannot be expressed in the schema and the backend port, it is a backend detail and must stay hidden behind them.

---

## 4. The enrolment journey (Family), from a fresh phone

The owner's idea of a "Start setting up" static page on GitHub is right, and I would make it the front door for **both** editions. Hosting: GitHub Pages (static, no server, nothing stored). Everything personal happens in the visitor's own browser.

**Stage 0: Before you start (the page checks).**
What you need: a Qin J35/J36, a USB cable, a PC (Windows/Mac/Linux), a Cloudflare account (Family only), ~45 minutes. The page asks: *Open* or *Family*? Detects the phone model; explains risks (data loss, bootloader).

**Stage 1: Back up and unlock.**
Back up the stock firmware (needed anyway for restoring and, see section 8, for the proprietary files). Unlock the bootloader if not already (Qin phones: **(verify the exact steps and risk)**). Guided with screenshots.

**Stage 2: Flash.**
Goal: no tools to install. Today we flash with `fastboot` and `mtkclient`. Option: **WebUSB fastboot in the browser** (as some ROM installers do) **(verify it can do our sequence: fastbootd, resize-logical-partition, flash system, erase userdata and md_udc)**. The mtkclient/BROM steps (bootloader text patch) are an optional "advanced" appendix, not part of the main path (the stock orange warning screen is cosmetic).

**Stage 3: First boot (phone).**
No Android wizard. A first-run screen in the Phone Guard style asks, with the keypad: language, time zone, Wi-Fi (the Wi-Fi screen already exists) or "I have a SIM with data", and **"Open phone" or "Family phone"**. (The phone must be online to enrol; with the SIM it already is.)

**Stage 4: Pair (Family).**
1. In the browser, the setup page connects the parent to **their** Cloudflare (Cloudflare OAuth, or a pre-filled API-token link) and deploys the Worker. *This is the least proven step* (see section 8): the research notes say the REST upload with Durable Object migrations and OAuth sign-up inside the flow were not tested.
2. The page shows the parent's companion address and sets a short-lived enrolment token.
3. The phone (Family chosen) shows a **QR code and an 8-character code on its own screen**.
4. The parent scans it with **their own smartphone** (iPhone or Android), which opens `https://<their companion>/enrol#<token>`; they sign in, see the child's phone model and a 4-digit match number that must equal the one on the phone, and tap Confirm.
5. The phone becomes device owner, receives its first signed config, and shows "Set up by <parent>". The parent is shown the **recovery key once** (to save in a password manager).

*Why the phone shows the QR rather than scanning one:* the current design has the phone scan a QR, which needs the keypad phone's camera to read a monitor **(open item in SPEC 4f)**. Showing it on the phone's 480x640 screen and scanning with the parent's phone is easier and does not need that test. Both directions can be supported; the typed 8-character code is the fallback.

**Stage 5: Guided "finish setting up" checklist (companion).**
Not a settings maze: a short checklist with sensible templates ("School / Day / Night" presets, "age 9-11 starter"): the timetable, who may call/text, which apps, location rules, then **connectors** (section 5). Each item has a plain-language "what this does", a test, and a status light.

**Stage 6: Handover and day 2.**
A one-page "what your child will see" and "what to do when X" (lost phone, forgotten PIN, locked out, new phone). Updates arrive silently; release and re-pair paths are documented.

### Journey for Open phone
Same page, Stage 0-3. Then an **on-phone setup wizard** (Wi-Fi, time, optional PIN, theme, home layout, and a "set up a service" menu). No Cloudflare. The page can also produce **settings cards**: QR codes generated entirely in the visitor's browser (nothing sent anywhere) that the phone scans to import Wi-Fi, calendar URLs or mail server settings, so people do not type long strings with T9. An Open user can later choose "Connect to a companion" to become a Family phone, or to use a companion for backup only **(decision: do we offer a middle "Open + companion" state?)**.

---

## 5. The complicated services: one pattern, then each service

### 5.1 The pattern (a "connector")
Each service is a **connector** that declares: what the parent needs to do outside our system (with screenshots), the inputs, a one-line test ("send a test email", "play 5 seconds"), and its health states ("sign-in expired"). The companion renders a generic guided wizard from that declaration; secrets go through the sealed-credential path (3.2); the phone reports health back. Adding a service later means writing a connector, not new screens. In Open the same declaration drives the on-phone wizard.

### 5.2 Per service (what is hard)
* **Spotify.** Needs a Spotify developer app (Client ID); today the Client ID is a setting and sign-in is via HA. Cloudflare variant: the parent's own Worker hosts the OAuth redirect, so each family's token stays in their account; tokens are handed to the phone sealed. **Risk (verify):** Spotify development-mode apps are limited to a small number of allow-listed users; a public product cannot rely on one shared app, so each family makes their own app, and the wizard must make that painless (copy-paste steps with the exact redirect URL to paste). Playback needs the Spotify account to be Premium.
* **Email.** IMAP/SMTP with an **app password** is easy to relay sealed. Gmail needs 2-step verification and an app password; Outlook/Microsoft now needs OAuth **(verify)**, which is a bigger build (OAuth app registration). Plan: app-password providers first, wizard presets for Gmail/iCloud/Fastmail/custom; OAuth later.
* **Calendar.** Read-only ICS URLs first (easy); CalDAV/write-back later (Google needs OAuth).
* **WhatsApp.** Pairing is a code typed into "Linked devices" on the parent's own smartphone; the companion shows the code the phone generated. Unofficial-library risk is already recorded; keep the parent informed.
* **Signal, others.** Later, one connector each.
* **Internet radio, weather, Wi-Fi, Bluetooth:** no account; settings only.

### 5.3 Principle
The parent should never have to understand a token. Every step is "open this link, press this, copy this", with a live check that shows green when it worked.

---

## 6. Safety, privacy and abuse (must be designed in, not added)

* **Stalkerware risk.** A phone that reports location and usage to another person's account is exactly what stalkerware does. Mitigations to design now: enrolment requires **physical possession and on-device consent** (the phone displays the pairing; nothing can silently enrol); a **persistent on-phone indicator** when managed; the child can always see "managed by <parent>" and what is shared; Open phones never report anywhere unless the user chooses a companion.
* **Parent-owned data.** No project-run database (section 3.1). Minimise what the Worker stores (state, not message content: WhatsApp/SMS text never leaves the phone).
* **Legal.** UK GDPR household exemption/parent responsibility, ICO Children's Code intent, Cloudflare terms of service (not read), no data-controller role for the project. Needs a real read by the owner (and a lawyer if it is ever offered to the public). Companion-research 8.4 lists the open items.
* **Threat model.** SPEC section 6 already assumes a determined child; one caveat for any public edition: **the bootloader stays unlocked ("orange state")**, so a child with a PC and the right tools can re-flash. The lockdown is policy-level, not cryptographic, and the docs must say so honestly.
* **Recovery.** Lost parent account, lost recovery key, Cloudflare account closed, parent hand-over to a second parent: each needs a documented path; "factory reset + re-enrol" is the last resort.

---

## 7. Roadmap (priority: Family; shared foundations first)

**Phase 0: Decide and de-risk (before building).** Section 8. Output: decisions logged, spikes pass/fail, protocol v2 spec drafted.

**Phase 1: Edition model in the app and image (shared).** Edition state machine; `pg_provision` stops forcing device owner; first-run screen (Open/Family, language, time zone, Wi-Fi); config store with `lockedByParent`; Open-edition on-phone wizard skeleton. *Exit:* a freshly flashed phone boots into Open, and a debug command can flip it to Family and back.

**Phase 2: Protocol v2, the backend port and the shared schema.** Define the profile/event/connector schema and the conformance test vectors (section 3.4); move today's HA webhook code behind the backend port; Phone Guard reports location, battery and state itself (no HA Companion app). *Exit:* the owner's current HA setup still works on the new client **and** the conformance suite passes against it, so the Cloudflare backend in Phase 3 has a fixed target. (The HA backend then stays frozen at parity-for-v1 until its own later phase.)

**Phase 3: Cloudflare backend MVP.** Worker + Durable Object: enrol/pair, sync, events, signed config, location, app updates, offline queue, release, recovery key. *Exit:* pair a phone, change a mode from a laptop, see it applied; kill the Worker's network, phone keeps enforcing.

**Phase 4: Parent web page + "Start setting up" page.** Dashboard, timetable, apps, contacts; GitHub Pages guide; Cloudflare connect/deploy; QR enrolment; checklist and templates. *Exit:* a tester who is not us gets from a fresh phone to a working Family phone using only the page.

**Phase 5: Connectors.** Spotify, email (app passwords), calendar (ICS), WhatsApp pairing, then OAuth-based providers.

**Phase 6: Hardening and release.** Red-team checklist, user build (Rooted debugging off), privacy/legal docs, update path for the system image (section 9), licence fixes (section 8), then the Open edition public release.

Open-edition-only work that Family does not need (full on-phone wizard polish, settings cards, "connect to companion later") can wait until after Phase 4.

---

## 8. What must be settled before starting the companion plan

### 8.1 Decisions for the owner
1. ~~Parent-owned Cloudflare account~~ **DECIDED: parent-owned.**
2. **Does Open ever talk to a companion?** (pure Open vs optional "Open + companion for backup/remote config"). Still open; not needed for Family v1.
3. ~~Keep Home Assistant?~~ **DECIDED: optional later backend, designed in from the start (3.4); not in v1.**
4. ~~First public audience~~ **PARTLY DECIDED: your own wiped phone is the first test; public outreach (dumbphones subreddit) is not soon.** Still to say: a private beta with a few friends before any public post?
5. **Phones beyond the Qin J35/J36**: other MediaTek Qin/Duoqin models need their own vendor work (IMS, audio, FM); say "not now" explicitly.
6. **Name and domain**: "Phone Guard" and any trademark checks; a domain for the setup page/companion (GitHub Pages address is free but long); logo (Daddy Bot is yours).
7. **Support model**: who answers a parent whose phone won't boot (docs only, community forum, none).
8. **Spotify**: accept "each family creates a Spotify developer app" for now?
9. **Parent PIN workflow**: set by the companion through the device-owner reset-token path (design already outlined by the NAS session); confirm that is the model.
10. **Minimum features for Family v1**: modes/timetable, apps, contacts, location, PIN, updates, release; everything else after.

### 8.2 Spikes (each should end in pass/fail with notes)
| Spike | Why |
|---|---|
| Phone shows QR, parent's smartphone scans and enrols | the enrolment keystone |
| Cloudflare: deploy a Worker + Durable Object into a *brand-new* user's account via OAuth/API token from a static page; read free-tier limits and ToS | the "easy for parents" promise depends on it (companion-research 8.3 never ran the deploy) |
| Open to Family device-owner conversion without a wipe | decides if enrolment can be "just a screen" |
| WebUSB fastboot flashing from the browser on a J36 | decides if "no tools to install" is real |
| Sealed credential round trip (browser encrypts, phone decrypts from Keystore) | the secret-handling pattern |
| Spotify OAuth hosted on a family Worker; developer-app user limits | removes the biggest connector risk |
| Phone-initiated location + battery/state reporting without the HA Companion app | removes a Home Assistant dependency (backlog item) |
| Cloudflare outage / account suspended behaviour on the phone | proves the offline-first claim |

### 8.3 Groundwork debt in what exists
* HA coupling: the nudge via the HA Companion app, the person/map via the Companion app, `sensor.phoneguard_*` carrying the whole config; all to be replaced by phone-owned equivalents.
* Parent PIN workflow, the release/user build (not `testOnly`, no debug receiver), and recovery key flow (partly designed in SPEC 4f).
* An update path: app updates through the companion are designed; **system image updates have no mechanism** (today a PC flashes it); decide whether an on-device updater is needed for Family (a parent cannot re-flash a child's phone every release).
* The proprietary-files problem (next).

### 8.4 Licensing: the gate for any public image
From `docs/licensing.md`: our image contains **Qin/MediaTek proprietary files** (IMS apps/jars, carrier config, native libs) that cannot be redistributed, and the **TrebleDroid licence is unclear**. So a public "open ROM" cannot simply be a downloadable `system.img`. Options, to pick before Phase 6 (not needed for the owner's own phone):
1. Publish source, patches and a **builder/installer** that merges the user's *own* stock-firmware backup's proprietary files into the image locally (Stage 1 already asks them to back up the stock firmware; the page can drive that);
2. Publish the image **minus** the proprietary files plus a first-boot step that pulls them from a stock backup on the phone/PC;
3. Ask Qin/Duoqin and MediaTek for permission (unlikely to be answered).
Also: settle the TrebleDroid licence with its maintainers, and keep the NOTICE files for Apache components.

### 8.5 Housekeeping
* Backlog/doc updates: mark the "standalone app" item as dropped, and re-scope "Move off HA to Cloudflare" to this plan.
* Keep the encrypted signing-key bundle current whenever keys change; never in git.
* The repo is public: no personal data, no secrets (history rewrite for the phone number is under way).

---

## 9. Risks, in my order of worry

1. **Enrolment friction** (Cloudflare account + deploy + flash): the whole "very easy" goal rests on the Stage 4 spikes.
2. **Image distribution** (licensing): can block the public Open edition entirely.
3. **System image updates** for phones in children's hands.
4. **Spotify/email provider rules** (developer-app limits, OAuth requirements) changing under us.
5. **Misuse as stalkerware** and the legal exposure of hosting any family's data (hence parent-owned accounts).
6. **A determined child with a PC** (unlocked bootloader).
7. Support load if the audience grows past friends.

## 10. Test rig and gating (from the owner's notes)
* **The owner's phone is the enrolment test rig**: wiped back to a "factory installed" Phone Guard (first-boot state, no data, no pairing). Phase 1's exit test is exactly that: flash, first boot, choose Open/Family, pair. Keep a one-command way to return the phone to that state (flash + wipe + the first-boot provisioning), and keep the recovery key and flashing tools to hand in case an enrolment spike leaves it unusable.
* **Public release gate** (before any Reddit post): licensing decision (8.4), a tested "Start setting up" path that someone who is not us can follow, a user (non-debug) build, privacy/threat docs. No outreach until then.

## 11. Suggested next step
Run the three Phase 0 spikes that decide the shape of enrolment, in this order, all on the owner's phone and a test Cloudflare account: (1) Cloudflare deploy into a fresh parent account from a static page (the one thing the "easy for parents" promise depends on; needs the owner to create or lend a Cloudflare account, which I cannot do for them), (2) phone shows QR, parent's smartphone scans and enrols against that Worker, (3) Open to Family conversion without a wipe. Meanwhile settle the remaining decisions in 8.1. Then write protocol v2 + the schema (Phase 2 design) and start Phase 1.
