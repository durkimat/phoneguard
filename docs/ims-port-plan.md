# MediaTek IMS port plan (VoLTE and Wi-Fi calling), 2026-11-12

Why: on the baseline the SIM and LTE register but voice is out of service (`ImsService not up yet` 1473 times, call fail `CM_SER_UNAVAILABLE`), and the modem rejects every mobile data activation (cause `16F1`, unknown). Smarty/Three has no 3G, so VoLTE is required.
Source decision (owner, 2026-11-12): use the phone's OWN original Qin stock system (`qin-j35-original-firmware`, Android 13, sha256 aabb1148...5283), not Dumber's files. These are Qin/MediaTek proprietary, kept out of git; the owner accepts using them on this hardware (redistribution terms are a later, separate question).

## What stock has (inventory: `build/ims/inventory.py`, read-only)
- **A full MediaTek phone stack:** `MtkTeleService` (8.7 MB), `MtkTelecom`, `MtkTelephonyProvider`, `MtkMmsService`, and a MediaTek-modified `telephony-common.jar` (4 MB), `ims-common.jar`, `telecom.jar`. These REPLACE AOSP's phone stack. We cannot take them: they are Android 13 and our system is Android 14. We keep Lineage's own telephony and add only the IMS layer.
- **The IMS layer (round 1 set, `build/ims/round1-files.txt`, 27 files):** apps `ImsService`, `MtkGbaService`, `MtkTelephonyAssist`, `MtkCapCtrl`, `mediatek-res`; 11 jars `mediatek-*` (framework, common, ims-base, ims-common, ims-extension-plugin, telephony-base, telephony-common, telecom-common, carrier-config-manager, wfo-legacy, services); sysconfig `com.mediatek.ims.config.xml`, permissions `com.mediatek.wfo.legacy.xml`, `privapp-permissions-mediatek.xml`, `system-ext-permissions-mediatek.xml`; native libs `vendor.mediatek.hardware.mtkradioex.{rsu,se,sim}-V1-ndk`, `libccci_util_sys`, `lib_remote_simlock`, `android.hardware.radio-V1-ndk`, and `vendor.mediatek.hardware.mtkradioex@3.0` in system_ext.
- Already running in the vendor image and on our baseline: `volte_imsm_93`, `volte_md_status`, `wfca`. Nothing binds to them because the system side is missing.
- Extracted from the owner's own backup into `/archive/phone-backups/own-build-inputs/ims-stock-android13-20261112/` (27 files, MANIFEST.sha256, read-only) and copied to the build container at `/src/phoneguard-build/proprietary/ims-stock-android13-20261112/`. Not in git, not in /artifacts.

## How it goes into the build (round 1, to implement next)
1. Prebuilt modules in a small `PLAN/ims/` product makefile + `Android.bp`: `android_app_import` (privileged, presigned, no dex preopt) for the five apps; `java_import` + `PRODUCT_BOOT_JARS` (and the platform bootclasspath fragment) for the jars; `PRODUCT_COPY_FILES` for the xml files and native libs.
2. Make sure TrebleDroid does not switch IMS off: its `phh-on-boot.sh` clears `persist.mtk_ims_support` / `persist.mtk_epdg_support` on devices with `/vendor/bin/mtkmal`; check ours and set what stock sets.
3. Keep the treble `mtkims_telephony` overlay (already in the image).

## Expected trouble (why it is iterative)
- The jars and apps were built for Android 13 against MediaTek's own telephony-common. On Android 14 with Lineage's telephony-common some calls will be missing (`NoSuchMethodError`, `ClassNotFound`). Dumber had to re-dex theirs. Our answer is small compatibility patches (like patch 0004 for `setOutOfService`) or shims, found one round at a time from logcat.
- Boot classpath changes break the whole boot if wrong; keep `restore-quick.sh` close.
- SELinux denials for the new apps (read from logcat, then add rules).
- Data failure `16F1` may or may not clear once IMS works: test again after round 1; if not, look at the APN and the radio HAL.

## Round loop
Build (~5 min incremental, longer with new modules) -> `flash-system-only.sh` (userdata kept) -> boot without SIM (check no boot loop, ImsService starts) -> SIM in (power off first) -> registration, data, call. Needs the owner only for flashing and the SIM steps.
Success for round 1: `ImsService` bound, IMS registered over LTE (VoLTE capable), a call connects with the earpiece working, SMS in and out, mobile data up.
