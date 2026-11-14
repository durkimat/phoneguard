# Signing keys (2026-11-14)

Phone Guard images are signed with our own keys, not AOSP's public test keys. Phone Guard will be a platform-signed priv-app, so the platform key must be ours before it goes in: changing a platform key later means every device must be wiped.

- Generate: `build/tools/make-keys.sh OUTDIR` (RSA 2048, unencrypted PKCS#8 so unattended builds can sign; certs valid ~27 years). Set made on 2026-11-14: releasekey, platform, shared, media, networkstack, bluetooth, nfc, sdk_sandbox, cts_uicc_2021, testkey.
- Location (never in git): `/archive/phone-backups/signing-keys-20261114/` (mode 700) and a copy on the build container at `/src/keys-pg`. **Back up off the NAS, encrypted.** Lost key = wipe every device to change keys; leaked key = anyone can sign a system app.
- Build: `build/build.sh` copies `/src/keys-pg` into `vendor/phoneguard/keys`; `ims.mk` then sets `PRODUCT_DEFAULT_DEV_CERTIFICATE := vendor/phoneguard/keys/releasekey` (the build takes platform/shared/media/... from the same directory). Without the keys it falls back to the AOSP test keys.
- Not covered: APEX payload keys (unchanged, AOSP test apex keys; they are not tied to app signatures), AVB/vbmeta (bootloader stays unlocked and the image is flashed with the vbmeta already on the phone), OTA signing (later).
- First flash with the new keys needs a userdata wipe if any app installed under the old keys shares a uid with platform apps (signature mismatch). Before flashing, check the vendor/product apps' signers (PC session list: ims7/signers/).
