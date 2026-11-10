# Bundled hardware + software: research notes (2026-11-10)

Research only. Nothing built. Sources are web searches and one page fetch; items marked (unverified) need checking
with a supplier or a lawyer before anyone relies on them.

## 1. What "bundled" could mean

| Route | What we do | What a supplier does | Effort |
|---|---|---|---|
| A. Flash retail phones | Build ROM, flash units, resell | Nothing (they don't know) | Lowest. Legal status unclear (see 4) |
| B. Agreed firmware from an existing OEM | Supply our system app / ROM; OEM builds it into their image | Ships standard hardware with our image | Needs an OEM relationship; they must agree to device-owner / privileged app |
| C. ODM white-label | Choose a catalogue or lightly customised design, add our software, brand it | Hardware, SoC board-support package (BSP), certification help, manufacturing | Medium. MOQs in the thousands |
| D. Own hardware design | Everything | Contract manufacturer | Years, large capital |

Alibaba-type listings for Android 4G keypad phones show MOQs from 2 (samples) to 1,000-5,000 (OEM/ODM), customised
logo or packaging often 3,000-10,000, unit prices roughly $18-20 for basic keypad models and $55-75 for flip or
touchscreen ones (supplier-stated, unverified). That is the realistic shape of route C.

## 2. Is the owner's assumption right ("OEMs sell hardware and software with source")?

Partly. ODMs usually sell hardware plus a vendor-supplied Android image built on the SoC maker's BSP (MediaTek,
Unisoc, Qualcomm). Source and BSP access is normally under NDA and per-project; the Linux kernel is GPL so kernel
source is owed on request, but ODMs are often slow or incomplete (unverified). The practical question for each
supplier is whether they hand over a buildable tree, or only signed images plus a customisation service.
Route A/B teams (DumberOS) work from community ports (LineageOS/Treble) because no vendor tree is available.

## 3. DumberOS as a precedent

Findings (dumbermini.com, GitHub miki151/dumberos_build; product and price pages were rate-limited and not read):
- Small company, two named founders (Sweden, London). Sells direct through a Shopify store, 2-year warranty,
  14-day no-questions refund. No legal entity details found.
- Hardware is not their own: the Qin F21 Pro and an "R77 Pro", plus a "Dumber Mini"; maker not named. Their About page
  says they hope to release their own keypad phone. So today they are software-first on someone else's hardware.
- Software: AOSP/LineageOS 14-based, de-Googled with microG by default, Play Store or Aurora as options, tt9 keypad
  input, Organic Maps, a built-in app blocker, OTA updates from Settings, "fully open source" claim.
- The build repo is scripts only (no licence file seen); whether the OS source and apps are public and under what
  licence is not established.
- No parental-control features beyond an app blocker. Known gaps: no Wi-Fi calling, no eSIM, many banking apps fail
  Play Integrity (a de-Googled / uncertified device cannot pass it).
- They do not offer flashing as a service because units get bricked.

Reading: they show a small team can sell a flashed keypad phone with a refund/warranty promise, and that the hard
parts are Google compatibility, warranty and hardware supply. They may be a partner or a base as much as a
competitor (unlicensed repo, so ask first). Their audience is adults who want fewer distractions, not parents.

## 4. What would be needed (for routes B/C)

- Hardware: radio bands for UK/EU, battery, a keypad layout that fits our key handling, a bootloader that can be
  unlocked once and then relocked with our own signing key (AVB), A/B partitions for safe OTA (unverified per model).
- Software: our own build of AOSP/LineageOS (or the vendor tree), with the Phone Guard controller as a privileged
  system app and device owner set at build time. A signed OTA channel we host. A curated app source (our own
  approved-apps catalogue, F-Droid style) if there is no Play Store.
- Regulatory (UK first, then EU), to confirm with a specialist:
  - UK: Product Security and Telecommunications Infrastructure Act applies to makers, importers and distributors of
    connectable consumer products including phones; security requirements (no default passwords, published security
    contact, stated minimum support period, statement of compliance) fall on manufacturers; importers/distributors
    must not supply non-compliant products and must report failures.
  - EU: Radio Equipment Directive cybersecurity requirements (EN 18031 series) apply from 1 Aug 2025; Cyber
    Resilience Act reporting duties started 11 Sep 2026 and most of the rest applies from 11 Dec 2027.
  - Plus (not researched): CE/UKCA radio testing, SAR, battery safety and transport, WEEE and battery regulation,
    GDPR for any data we hold, and consumer law (warranty, returns).
  - Key legal question (unverified): if we change the software on someone else's radio device and sell it, do we become
    the "manufacturer" in law, and does the original certification still hold? Route A is most exposed to this.
- Money and operations: stock, returns/RMA, customer support, security-update commitments for years.
- Google: without Google Mobile Services licensing (a Google agreement, compatibility test suites and a reported
  per-device fee of a few dollars up to tens of dollars; figures unofficial) the phone cannot ship the Play Store or
  pass Play Integrity. Reported small-OEM difficulty is old and anecdotal.

## 5. Problems it would solve

- Enrolment: device owner built in, so no QR scan, no ADB, no factory-reset step, no Play Protect warning, and (inference)
  no Android developer-verification issue, since verification is enforced through Google's services on certified devices.
- Tamper resistance: locked bootloader with our key, factory-reset protection under our control.
- Hacks go away: the Bluetooth pairing dialog automation and overlay, accessibility key interception, the SMS
  role gap and the GitHub self-update can be proper platform features or a signed OTA.
- Hardware fit: radios and battery tuned for check-in windows; one SKU to test and support.
- Policy risk: no dependence on Play policy for a parental device-owner app.
- Simple parent story: unbox, power on, pair with the family account.

## 6. Problems it would introduce

- Becoming a manufacturer (or importer) with the legal duties above, and long update/support obligations.
- Capital: MOQs, stock, certification fees, returns.
- No Google certification means no Play Store, weaker app compatibility (push notifications via microG or our own
  channel), no Play Integrity (banking and some messaging apps fail).
- Supply and BSP risk: an ODM can stop a model, or deliver an unbuildable tree.
- Two products to keep alive (bundled phone and Play-store companion).
- Slower iteration on hardware.

## 7. Open questions

1. Will an ODM (or Qin/Douqin) agree to build our image and give us a buildable tree, bootloader signing keys and OTA
   control? At what MOQ and per-unit extra?
2. Which SoC and what Android version do candidate keypad phones use, and do they support AVB relock?
3. Legal: manufacturer status under UK/EU rules when flashing existing devices; household GDPR line (carried over).
4. GMS: feasible at small volume, or do we deliberately ship without it and own the app catalogue?
5. DumberOS: licence of their source, their hardware supplier, and appetite to collaborate on parental features.
6. Retail/network: do UK carriers need any approval for a child-oriented device (not researched)?
7. Unit economics: a rough price band for a parent-bought phone versus a Play-store app subscription.

## 8. Suggested next steps (research only)

- Email the DumberOS founders (licence, supplier, collaboration).
- Request samples and BSP/OTA/bootloader details from 2-3 ODMs including the Qin maker.
- Get a short consultation with a UK product-compliance adviser on "software-modified resale" and PSTI.
- Check GMS licensing route with a Google-authorised distributor.

## 9. Has a small or enthusiast group done this? (2026-11-10)

Short answer: yes, in pieces. No one in the enthusiast scene gets everything (vendor source, Google certification,
own-key locked bootloader) on commodity hardware, but small companies do ship custom-OS phones commercially.

- Light Phone III: small US company, Qualcomm SM4450, built by Foxconn as contract manufacturer, own Android-based
  LightOS, no Google apps. Shows the contract-manufacturer route works for a small brand (unit volumes and Foxconn terms unknown).
- Punkt MC02 (Swiss): MediaTek Dimensity 900, Apostrophy OS (AOSP 13 based) built by a partner company. Reviewers say
  the OS runs on one device only. ODM not identified (a reader guess says it is a stock Chinese ODM board; unconfirmed).
- Murena (/e/OS): sells Fairphones with /e/OS preinstalled; forum reports say bootloader can be relocked on official
  /e/OS builds for FP3-5 (not community builds) and Play Integrity can fail after updates. Relies on Fairphone's openness.
- GrapheneOS: Pixel-only for years because Pixels allow relock with a custom verified-boot key. In March 2026 Motorola
  announced a partnership (porting work mostly Motorola's, first compatible devices not before 2027). Shows even
  enthusiast projects needed an OEM's cooperation.
- Small certified route: Google GMS partner ODMs (e.g. Longcheer) and MediaTek's GMS Express programme (2017) give
  small brands pre-certified Android builds. Qualcomm has no equal found. Source: vendor pages, dated.
- Not found: any enthusiast group that obtained vendor BSP, certified, and relocked with its own key on a commodity
  keypad phone. DumberOS and community ports run unlocked or with test keys on retail hardware.

## 10. How Dumber OS is built (read from their public build repo, 2026-11-10)

Sources: github.com/miki151/dumberos_build (README, buildbot_unified.sh, apply_patches.sh, sign_target_files.sh,
local_manifests_treble/*), their install blog post (2025-10-18), FAQ, and phhusson/treble_experimentations wiki page for the F21 Pro.

- It is a Treble GSI-style system image: LineageOS 21 (Android 14) with TrebleDroid/phh patches (device/phh/treble and
  vendor/interfaces at android-14.0), built with `lunch lineage_vanilla31` (or "30" for the F21 Pro) -> `make systemimage`.
  Output is one signed system.img. Only the system partition is replaced; stock vendor, boot and firmware stay.
  Why Android 14 runs over a stock Android 13 vendor is not explained on any page read. Treble's vendor/framework
  interface split is the general mechanism (inference); TrebleDroid exists to patch per-vendor quirks.
- MediaTek device (they use mtkclient for backups). The phh wiki page notes copying the stock keylayout for the T9 keypad.
- Their own components are repos pulled into the tree: DumbdroidUpdater (in-OS updater), DumbdroidPanel, and
  miki151/device_owner built as packages/apps/DumbdroidAdmin, i.e. a device-owner app baked into the system image
  (the same shape as our controller). Also treble_app (phh settings app), microG via lineageos4microg, MindTheGapps option.
- Patches live in lineage_patches_unified and the manifest repo dumberos_manifests (neither read). Launcher, app blocker
  and settings source were not found on any page read.
- Flashing (user guide): enable OEM unlock, `fastboot flashing unlock`, erase userdata+metadata, `fastboot flash system
  dumberos.img`, reboot. No vbmeta or AVB key handling appears in the scripts or the guide, and relocking is never mentioned.
  Evidence therefore points to an UNLOCKED bootloader on these phones, not a relocked one. Whether units Dumber sells
  pre-installed ship locked is not established (check on our own phone: `getprop ro.boot.verifiedbootstate`,
  green=locked, orange=unlocked).
- Signing covers APKs/APEXes with a custom key (sign_target_files_apks), not the boot chain.
- OTA: no server or protocol documented; the updater app is the mechanism. Builds appear run by hand by one developer;
  no CI seen. No licence file seen.
