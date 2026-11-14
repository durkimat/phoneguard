# FM radio (2026-11-14)

The stock Qin firmware has an FM radio: `/system/system_ext/priv-app/FMRadio/FMRadio.apk` (com.android.fmradio 2.0, MediaTek's FM Radio, Duoqin-signed) with `/system/system_ext/lib64/libfmjni.so`, which opens the kernel's `/dev/fm`. The vendor image has `fm_hidl_service`, `init.fmradio_drv.rc` and the `/dev/fm` label; the kernel has the fm_* threads. So the hardware path exists; NFC does not exist on the phone (owner confirmed) and is irrelevant.

Inputs (not in git): `/archive/phone-backups/own-build-inputs/fmradio-stock-20261114/` (apk, jni lib, SHA256SUMS), staged on the builder at `/src/phoneguard-build/apps/fm/`.

## What is in the image (ims23)
App `FMRadio` (presigned, privileged, system_ext), `libfmjni.so` in /system_ext/lib64, privapp allowlist for its 5 privileged permissions (ACCESS_BROADCAST_RADIO, CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING, MOUNT_UNMOUNT_FILESYSTEMS, START_FOREGROUND_SERVICES_FROM_BACKGROUND). No SELinux work yet: first run shows what is denied.

## Known unknowns
- SELinux: stock system_ext policy defines `hal_mtk_fm_client` (members: system_server) and `mtk_fm_radio_service`; the vendor labels `/dev/fm` as `fm_device`. The app probably needs its own domain with access to fm_device; vendor types cannot be named from our system_ext policy, so the usual route is to join an attribute the vendor policy already grants (hal_mtk_fm_client).
- Audio: FM audio goes through the MediaTek audio HAL (libaudiofmtconv in the vendor); Android 14's audio policy may need FM tuner devices in the vendor config (unchecked). Wired headphones act as the antenna.
- Phone Guard integration: Phone Guard does not know about FM; its menu lists launcher apps; a keypad-friendly screen is a later feature.
- First test (PC session): `am start com.android.fmradio/.FmMainActivity`, logcat for FmService/libfmjni/avc, /dev/fm access.

## Result (ims31, 2026-11-14)
Works at software level with the Lineage FMRadio + our `libfmcust`: /dev/fm opens (MT6631), power up, mute, seek and tune OK, RDS supported (no data yet), no crash, no SELinux denials once the app runs in `platform_app` (build/sepolicy/seapp_contexts). The three fixes needed: (1) libfmcust (the JNI dlopens it; the stock firmware builds it into its own lib), (2) `fake_chan` must be a non-NULL table (seek dereferences it), (3) seapp mapping so the vendor policy allows /dev/fm.
- Headset-less use: `persist.sys.pg.fm_builtin_antenna=1` (patch 0014) skips the "plug in headphones" check; the chip runs in short-antenna mode. With the property 0 the app refuses to start. The owner heard audio on the LOUDSPEAKER (software rendering to STREAM_MUSIC, no earpiece/speaker toggle in the app); seek stops were at 99.7, 90.6, 99.1, 91.2 MHz with only the USB cable as antenna, i.e. possibly noise-floor hits: retest elsewhere / with headphones.
- To do: tune the RSSI threshold/desense values in libfmcust (`seek_lev`, `rssi_th_l2`) to avoid stopping on noise; Phone Guard Radio-screen integration (FM as a media source, speaker toggle, presets, RDS name), see BACKLOG.
