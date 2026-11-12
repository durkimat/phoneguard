# Triage of the 58 rejected TrebleDroid patches (2026-11-12)

The 58 patches that did not apply to Lineage 21 (list: `/out/logs/reject-analysis.tsv` on the build container). "NEEDED" there only means the
change is not already in Lineage; it does not mean this phone needs it. This triage is by reading the patch titles against what we know about the phone,
not by testing. Where it says "check", the answer needs the running phone.

Facts used (read-only, from Dumber's vendor_a and our built system.img):
- SoC MT6761, vendor is Android 12 (vndk 31), `ro.board.first_api_level=30`. Stock HIDL HALs: audio 6.0/7.0, bluetooth 1.1, **bluetooth.audio 2.1**, and MediaTek's bluetooth.audio 2.2, all declared in the VINTF manifest.
- Dumber's system and our image both contain the system-side Bluetooth audio service (`android.hardware.bluetooth.audio-service-system`, "sysbta"). Our Bluetooth stack lacks the switch that selects it (reject BT 0002).
- Our image still ships NfcNci; no NFC chip, so NFC patches do not matter.

## A. Likely needed: check first (10)

| Patch | What it does | Why it may matter here | Test |
|---|---|---|---|
| Bluetooth 0002 | Optionally use the sysbta software A2DP HAL | Only a fallback: the vendor has its own Bluetooth audio HAL 2.1. Needed if vendor A2DP fails on Android 14 | Pair headphones, play audio |
| Bluetooth 0004 | Don't abort when the real-time priority can't be set | Bluetooth audio thread aborting would kill A2DP | Same |
| frameworks_av 0015 | Don't crash on unknown audio devices | Android 12 vendor audio HAL under Android 14 policy | Boot log, audio out |
| frameworks_av 0016 | Don't ignore valid sources in an audio route | Same area (routing to speaker/headset/BT) | Route to BT and speaker |
| system_core 0020 | Support devices without cgroup v2 | MT6761 kernel is old; init may fail to set up cgroups | First boot |
| bpf 0001, Connectivity 0003 | No-bpf use case, optional IBpfMaps | If the kernel lacks the needed eBPF, data, tethering and netd misbehave | Mobile data, Wi-Fi |
| base 0015 | fnmatch-style matching for RRO overlays | TrebleDroid's IMS and telephony overlays select by property patterns | Check the MTK IMS overlay applies |
| opt_telephony 0009 | Conditionally revert "block binder thread until incoming call" | Some MediaTek IMS stacks need it | Incoming VoLTE call |
| system_core 0019 | Ignore dm-verity setup issues | Depends on how vbmeta is handled for our system image; Dumber already boots a modified system | First boot |

Already known on the phone: Dumber's image (built with TrebleDroid pieces) boots, pairs Bluetooth and runs mobile radio on this hardware. So the stock vendor
side works with an Android 14 system in general; the open question is whether the same is true with our own build.

## B. Contingent: apply only if the matching symptom appears (20)

| Patch | Symptom that would justify it |
|---|---|
| Bluetooth 0003 (eSCO unit size), 0006 (disable features/commands by property), 0007 (cap LE vendor version) | Bad headset call audio, controller commands failing, BLE devices failing |
| frameworks_av 0006 (trimmed port names), 0021 (mic while recording), 0023 (echo on calls) | Specific audio route failure, or echo on calls |
| opt_telephony 0007 | Wrong preferred network type in settings (LTE-only choice ignored) |
| Connectivity 0004 | Missing traffic arrows in status bar (cosmetic) |
| base 0001 (vendor mismatch warning), 0002 (brightness fallback), 0006 (persistent property override), 0013 (proximity sensor NaN), 0032 (MediaTek power hints on touch), 0035 (helper other patches use) | Warning toast, bad auto-brightness, proximity during calls, sluggish touch |
| build 0002 | Build error about vendor calls |
| Settings 0003, 0004, 0005 | Settings crash, enterprise Wi-Fi options |
| system_core 0006 (panic into recovery), 0024 (logd kernel) | Boot-loop behaviour, log level |

## C. Not relevant to this phone (23)

Samsung, Asus and Xiaomi camera, touch, flash, miscpower HAL and fingerprint-on-display patches (frameworks_av 0003, 0009, 0014, 0019; base 0010, 0025,
0030, 0039, 0045; native 0007, 0008; Settings 0002; Bluetooth 0005), Huawei Kirin signal (opt_telephony 0005, 0006), cutout fix (base 0022), audio HAL V2
(av 0018), AVB 1.0 / Vboot1 fallback (core 0002, 0003; the phone uses AVB 2), TrebleDroid's own data OTA (core 0010; we do our own), apex on 5.0 kernels
(core 0014), vndk v28 sepolicy (build 0005; vendor is vndk 31), NFC no-VR-service crash (Nfc 0001; we drop NFC).

## D. Already handled or gone (5)

FILE-GONE: build 0001, core 0011, core 0012 (target code no longer exists). IN-HISTORY: base 0028; opt_telephony 0011 is covered by our shim (patch 0004).

## What this means

- **Bluetooth audio is better placed than I feared.** The phone's vendor already provides the Bluetooth audio HAL, so the normal Android 14 path may simply work. The two Bluetooth patches that matter (sysbta switch, real-time priority) are small and can be reapplied by hand if headphones fail. Confidence moves from low-medium to medium.
- **Real risks are boot and data, not features:** cgroups (core 0020) and bpf (bpf 0001, Connectivity 0003) depend on the stock kernel, which we have not inspected. If the first boot fails or mobile data does not come up, these are the first suspects.
- **No patch work is needed before the flash.** Apply A-list items only when a test points to them, so the baseline tells us what is actually broken. Each patch is applied by hand against Lineage 21, so keep them as `build/patches/*.patch` files.
- Still to do: read the kernel version and config (cgroup2, bpf) from the running phone via `adb` during the flash session.

## Update 2026-11-12: kernel evidence (`/archive/phone-backups/phone-state-20261112/`)

- Kernel 4.19.191, built 4 Aug 2026 (identical to Qin's own stock kernel (see own-build-viability.md section 16); our flash does not touch boot, so we keep it). cgroup v2 is mounted next to v1; BPF is present (CONFIG_BPF, BPF_SYSCALL, BPF_JIT, CGROUP_BPF =y, bpffs mounted); DM_DEFAULT_KEY, DM_VERITY, FS_ENCRYPTION, F2FS are on.
- So the cgroup (core 0020) and no-bpf (bpf 0001, Connectivity 0003) patches in group A are now **unlikely to be needed**: the first-boot and mobile-data risk drops. Unconfirmed: which BPF programs are loaded (adb shell is not root).
- Bluetooth: the vendor BT HAL (bluetooth-1-1) and the system-side `system.bt-audio-hal` are running on the Dumber image; A2DP source is enabled. No sysbta property was set, so Dumber uses the normal route as well.
- Dependency to remember: our "own" system image runs on Dumber's boot/kernel and the stock vendor. A fully independent build would also need our own kernel/boot image.
