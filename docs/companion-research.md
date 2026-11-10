# Companion app / hosted parent service: research notes (2026-11-10)

Research only. No build work started. Sources were official Google and Cloudflare pages
where possible; items marked **UNVERIFIED** were not confirmed from an official page.

## Direction agreed with the owner

- Two routes: **open source** (parents install and set up themselves, very low-tech) and a
  possible later **commercial bundled phone**. Keep the second in mind throughout; it needs
  its own product research (hardware, Android image, relockable bootloader, warranty).
- No project-run multi-tenant service. Each family's data sits with the parent in an
  existing provider's account, so GDPR responsibility stays with the parent and the provider.
- Parent app must work for **iPhone and Android** parents. Target market **UK and EU** first.
- Parent creating a free provider account is acceptable **if the app drives the workflow**,
  and it must handle both new and existing accounts.
- A **Play Store build with fewer features** is acceptable; the bundled phone keeps the full
  set. Which features drop decides how acceptable this is.
- Child messaging later: a filtered view linked to a parent's account, or the child's
  existing full phone. Spotify limits accepted for now.

## 1. Android developer verification (developer.android.com/developer-verification)

- Rule: apps on **certified** devices must come from registered developers.
- **30 Sept 2026:** enforced for installs from participating stores (Play, Samsung, Xiaomi,
  Honor, OPPO, vivo, Transsion) in **Brazil, Indonesia, Singapore, Thailand**, Android 7+.
  Global expansion "in 2027". UK/EU not yet covered.
- Sideloaded APKs and other stores are **not** covered by the 30 Sept date (FAQ), though
  other pages word it more broadly. Treat as coming.
- **ADB installs are exempt** ("free to install apps without verification with ADB").
- **Device-owner / QR provisioning: not addressed on any page read. UNKNOWN.**
- Unverified apps can still be installed through an **advanced flow**: developer mode, a
  "not being coached" check, restart, **one-day wait**, then biometrics or PIN. The wait
  does not apply to ADB. Whether it is live today is **UNVERIFIED** (announced for August).
- Accounts:
  - Full distribution: $25, government photo ID and proof of address for individuals;
    organisations also need a D-U-N-S number (up to 28 days) and a verified website.
  - Limited distribution (hobbyist): free, no ID, but capped at 20 devices (one page says 50)
    and cannot be converted to full distribution.
- **Open-source app registration exists.** Optional. Register the package name and signing
  key fingerprint so users keep the normal install experience.
- Enterprise apps on managed devices are exempt. We are not an enterprise.

Conclusion: register the package and signing key (full distribution; limited is too small).

## 2. Google Play policy, feature by feature

### SMS and call log (answer/10208820)
- Allowed for the **default SMS handler** (read/send/receive SMS and MMS) and the **default
  Phone handler** (READ_CALL_LOG, WRITE_CALL_LOG, SEND_SMS).
- Exceptions list has **no parental control entry**. "Remote control of user phone" and
  "family or device locator" are listed as invalid uses.
- So a **non-default** monitoring app very likely cannot read SMS or call log on Play.
- Our launcher is the phone's messaging and dialling UI. Becoming the **default SMS and
  Phone app** would put us in the permitted category. This is my inference, not a Google
  statement. It means implementing the full default-handler role, including MMS.

### AccessibilityService (answer/10964491)
- `isAccessibilityTool` is only for disability tools. Launchers, monitoring apps and
  automation tools are explicitly **not** eligible.
- Other apps can use the API with an in-app **prominent disclosure and affirmative
  consent**, a Play Console declaration and a demo video.
- Autonomous initiate/plan/act is prohibited. Fixed rule-based actions ("if X, do Y") are not.
- Need to audit what F2Service really does, in particular the pairing key presses listed in
  `docs/workarounds.md`. Anything that taps screens for the user is the riskiest part.

### Monitoring apps (answer/12955211 and the Stalkerware section of Malware policy)
- Allowed only if "exclusively designed and marketed for parents to monitor their children"
  (or enterprise). Manifest flag `isMonitoringTool=child_monitoring` on every version code.
- Requires a **persistent notification** while running, a unique app icon, disclosure in the
  store description, and prominent in-app disclosure and consent.
- Must not hide its behaviour. It cannot be marketed for tracking other adults.
- The store listing must not **link to a non-compliant APK hosted outside Play**. This
  directly shapes the two-build plan: the Play listing cannot point at the full direct build.
- The flag is a classification. It does not itself grant SMS or accessibility exceptions.

### Other
- Device and Network Abuse: no self-update outside Play, no downloading executable code from
  outside Play, no installing other apps without explicit consent.
- Families policy applies when children are a **target audience**. It would stop us
  requesting location or hardware identifiers. The target audience should be declared as
  parents, but this needs checking with Play support. **UNVERIFIED.**
- **No Play policy page on device admin / device owner found.** No statement allowing or
  forbidding a consumer device-owner app.
- Personal developer accounts need a closed test with 12 testers for 14 days before
  production. Organisation accounts are generally exempt.

## 3. Device-owner enrolment and Play

- QR enrolment takes a download location and SHA-256 checksum for **any** DPC, so a
  Play-hosted app is not required for QR. Needs a factory reset.
- `afw#` identifiers must be **requested from Google by an EMM**. Not available to us.
- The Android Enterprise docs are written for company-owned devices and IT admins. No consumer path.
- Forum reports (Dec 2025, Jan 2026) of Play Protect blocking self-hosted DPC APKs during QR
  enrolment and of `afw#` failures. Anecdotal, no official answer.
- Open question for a Play build: if device-owner setup uses our APK but updates come from
  Play, the signing keys must match. **UNVERIFIED.**

## 4. Cloudflare as the parent-owned backend

- Free tier is workable for a family. Durable Objects are on the free plan (SQLite-backed
  only). D1 free-plan limits become **enforced from 1 Sept 2026**. Exact caps conflict
  between sources; check the official pricing page. Keep storage behind a thin layer.
- Cloudflare Access: free for up to 50 users, email one-time PIN (suits a second parent).
  Free-plan terms for use inside a consumer app **UNVERIFIED**.
- **Deploy to Cloudflare button**: needs a **public GitHub or GitLab repo** and clones it
  into the user's own GitHub/GitLab account, so the parent would need a GitHub account.
  It provisions D1, Durable Objects and KV automatically, but D1 migrations must be scripted.
  Rejected as the main route.
- **Pre-filled API token links work.** The URL carries permission keys, but the user must
  still finish creating the token in the dashboard. No Durable Object key listed.
- **OAuth for third-party apps exists** (authorisation code with PKCE, scopes map to token
  permissions, free plans included). The user signs in and approves on Cloudflare's own
  consent screen, and the setup page then deploys the Worker with REST. Private clients
  work for the owner's own account. A public client needs a logo, URL, scopes and DNS
  verification, and becoming public is **permanent**. Whether a brand-new user can sign up
  inside the flow is **UNVERIFIED**.
- Worker upload with bindings and migrations through REST (`PUT .../workers/scripts/{name}`,
  multipart metadata) is documented, but the exact schema was not read in full.
- Cloudflare's terms of service were **not read**.

## 5. iPhone parents (from earlier searches)
- Web push only for a web app added to the Home Screen, after a tap. An EU-specific limit
  was reported but not confirmed. The phone's check-in windows make push optional.

## 6. Provider alternatives considered
Cloudflare (preferred to spike), Supabase (free projects pause after inactivity), Firebase,
self-hosted container, keep Home Assistant, Matrix/ntfy-style relay (not researched in
depth), project-run service (rejected). Design the server as TypeScript core with storage
and push adapters so the host can change.

## 7. Suggested validation steps before any build
1. Throwaway Cloudflare account: private OAuth client, deploy a Worker with a Durable
   Object through REST, check a brand-new account can sign up inside the flow, read the terms.
2. Emulator or spare phone: QR enrolment of the debug APK from a URL, on Android 13, 14, 15.
3. Write the feature list against Play policy: what survives as default SMS/Phone handler
   and a non-tool accessibility service, and what is dropped.
4. Ask Play policy support whether a consumer device-owner parental-control app is allowed,
   and whether `isMonitoringTool=child_monitoring` plus default-handler status is enough.
5. Register the package and signing key under developer verification.
6. Legal review of the household exemption and controller/processor wording.

---

## 8. Validation results (2026-11-10, second pass)

### 8.1 Feature audit against Play policy (from the manifest and `F2Service.kt`)

| Feature / permission | Play position | Play build |
|---|---|---|
| Dialler: we already hold the Dialer role (InCallService, DIAL), so READ_CALL_LOG / CALL_PHONE sit in the permitted "default Phone handler" group | likely OK | keep |
| SMS: READ_SMS / SEND_SMS but **no SMS handler components** (no SMS_DELIVER, WAP_PUSH, SENDTO), so we are *not* the default SMS app | **blocked as it stands**; permitted only if we become the default SMS handler, including MMS | build the SMS role, or drop SMS |
| Accessibility, key filtering (side button, volume, call key) | allowed on the non-tool path with in-app disclosure, consent and declaration; it reads no content | keep, with disclosure |
| Accessibility, **Bluetooth pairing dialog**: reads another app's window, clicks its buttons, and covers it with our own panel (SYSTEM_ALERT_WINDOW) | highest risk: reads other windows, acts for the user, and the cover hides a system dialog | drop in the Play build; let the system dialog show |
| `ACCESS_BACKGROUND_LOCATION` (child tracking) | needs declaration and prominent disclosure; fits `isMonitoringTool=child_monitoring` | keep, declared |
| `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES` | restricted, declaration needed; plausible as core function of a supervised launcher | keep, declared |
| `REQUEST_INSTALL_PACKAGES`, `DELETE_PACKAGES`, GitHub **self-update** | self-update outside Play is prohibited; installing apps needs explicit consent | remove self-update; installs via Play only |
| `USE_FULL_SCREEN_INTENT` | restricted to calling/alarm apps on Android 14+; we are the dialler | keep |
| `WRITE_SECURE_SETTINGS` self-grant, hidden BT reflection, Visualizer(0), WifiManager.setWifiEnabled (see `workarounds.md`) | not named in policy; hidden-API and ADB-grant tricks are fragile | review each; drop what breaks |
| WhatsApp bridge (unofficial whatsmeow) | third-party service terms and ban risk; unlikely to pass review | drop in the Play build |
| Persistent notification and unique icon (stalkerware rules) | **required** for `isMonitoringTool` | add (we already run a foreground service) |

Net: the Play build is plausible. The biggest cost is building a real default SMS handler role; without it, messaging is the casualty.

### 8.2 QR enrolment in the emulator: could not be completed
- The emulator image has `com.android.managedprovisioning` but **no setup wizard** (`ro.setupwizard.mode=DISABLED`), so the real "six taps then scan" screen is absent.
- I fired the same intent the wizard fires after a scan (`PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`, with component name, `http://10.0.2.2:8099/phoneguard.apk` and its SHA-256 checksum). Provisioning parsed the extras and reached the "Device already provisioned" check, so the **payload shape is accepted**.
- It refused because the system keeps its own provisioned flag, which adb cannot reset (resetting the settings and rebooting changed nothing). The download, checksum verification and install steps were therefore **not exercised**.
- Needs a real test: a spare phone factory-reset to the welcome screen, or an image that ships a setup wizard. Android 13, 14 and 15 are the versions to try, and Play Protect behaviour can only be seen on a real Google-certified device.
- The emulator was returned to its normal state and the test web server stopped.

### 8.3 Cloudflare spike: built, local part tested
- `spikes/cf-onboard/`: a Worker plus a SQLite-backed Durable Object. **Tested locally** with `wrangler dev`: state persisted across requests (count 0, 1, 2).
- `deploy.mjs` deploys it with REST only and a bearer token (no wrangler, no GitHub). **Not run**: it needs a real Cloudflare account and I cannot create accounts. `README.md` there has the pre-filled token link and the four steps for you.
- Still untested: the REST upload with Durable Object migration, Cloudflare OAuth as the sign-in step, brand-new user sign-up inside that flow, Cloudflare's terms.

### 8.4 Not done by me (needs the owner)
- Cloudflare account steps (above) and the OAuth client (needs a Cloudflare account).
- Developer registration with Google (account creation and ID verification).
- Contacting Play policy support. Draft question:
  "We are building a parental-control launcher for a supervised child's phone. It runs as device owner (enrolled by QR code), is the default Dialer, will declare `isMonitoringTool=child_monitoring`, uses a non-tool AccessibilityService only for hardware key handling, and shows a persistent notification. Is a consumer parental-control app that is device owner permitted on Google Play, and is default SMS and Phone handler status sufficient to request READ_SMS and READ_CALL_LOG?"
- Legal review (household exemption, controller/processor wording).
