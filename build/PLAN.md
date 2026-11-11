# Own-build plan: LineageOS 21 GSI with TrebleDroid (draft 2026-11-11, stage 1)

Goal: one `system.img` (Android 14) for the Qin J35 (MT6761, A/B, dynamic partitions, stock Android 12 vendor, unlocked
bootloader). Only the system partition is ever written (see docs/recovery-guide.md golden rules). Nothing here touches the phone.

## Verified against upstream (2026-11-11)
| Item | Finding |
|---|---|
| LineageOS branch | `lineage-21.0` exists, last commit 2026-10-25 (LineageOS/android) |
| LineageOS wiki (build page) | needs 400 GB free for lineage-21+, **64 GB RAM** for 21+, SSD recommended, Python 3, JDK bundled in the source tree |
| TrebleDroid | `device_phh_treble`, `vendor_interfaces` have branch `android-14.0`; org last pushed Jun-Oct 2026 (device_phh_treble 2026-10-14). Last TrebleDroid GSI release ci-20250617 (Android 16) |
| Lineage + Treble glue | TrebleDroid's own scripts do not target Lineage 21. The public, working glue is AndyCGYan/lineage_build_unified + lineage_patches_unified, branch `lineage-21-td` (patches last changed 2025-09-14). It is what `lineage_vanilla31`-style Dumber builds descend from |
| Licences | AndyCGYan repos: **no licence file** (GitHub reports none). TrebleDroid treble_experimentations: LICENSE "other" (not read). Same all-rights-reserved concern as for Dumber (docs/own-build-viability.md section 1) |

## DECIDED 2026-11-11: patch path B (TrebleDroid official patches, android_14.0.0_r17, 171 patches), not Andy's.
Rejects are listed in /out/logs/patch-rejects.txt after sync and fixed by hand, one at a time. `build.sh sync` fetches and applies the patches itself.

## Open items before the sync
1. **RAM**: container cap 48 GB vs Lineage's 64 GB (host has 60 GB). Plan uses `-j12`; drop to `-j8` if the final link steps run out of memory.
2. **Target** `arm64_bvN`: confirmed 64-bit (`ro.product.cpu.abilist=arm64-v8a` read from our super.bin backup, 2026-11-11).
3. **Container**: `erofs-utils kmod xxd` missing (Lineage wiki list); no git identity for `builder` (needed by repo and `git am`).
   Packages need a container rebuild (your call); a git identity I can set now.
4. **Licence of TrebleDroid's patch zip**: repo LICENSE is "other", not yet read. Read it before any distribution.

## Files
- `local_manifests/treble.xml` - TrebleDroid project list (copied into `.repo/local_manifests` by `build.sh sync`).
- `build.sh` - `sync | build | publish`; downloads TrebleDroid patches-for-developers and applies them.

## Commands (build container, over SSH to port 2222; work lives on NVMe)
```
scp -P 2222 -r phoneguard/build builder@192.168.1.138:/src/phoneguard-build      # plus build.sh at /src/build.sh
ssh -p 2222 builder@192.168.1.138
git config --global user.name "..." ; git config --global user.email "..."       # repo needs an identity
tmux new -s lineage 'bash /src/build.sh sync 2>&1 | tee -a /out/logs/sync.log'     # NOT until you say go
tmux new -s lineage 'bash /src/build.sh build 2>&1 | tee -a /out/logs/build.log'
bash /src/build.sh publish                                                        # -> /artifacts/lineage-21-<date>-arm64_bvN/system.img + .sha256
```
Output: `/out/target/product/*/system.img` (NVMe), copied by `publish` to `/artifacts/` (HDD, visible from the workstation as /artifacts).
Not flashed anywhere: flashing is a separate step under docs/recovery-guide.md (fastbootd `flash system`, slot B first, never without your OK).

## Patch-source paths compared (2026-11-11, from the public repos)
AndyCGYan `lineage_patches_unified` (branch lineage-21-td) is 263 patches: 186 `patches_treble_td`, 53 `patches_platform`, 14 `patches_treble`, 7 prerequisite, plus "personal" sets we would not use.
A sample of 12 of the 186 treble_td patches: 9 authored by phh (Pierre-Hugues Husson), 2 by Alberto Ponces, 1 by Peter Cai, 1 by Andy: they are mostly TrebleDroid's own hardware-compat work rebased onto Lineage 21.
The 53 `patches_platform` are Andy's taste (SystemUI theming, navbar, launcher tweaks); not needed for a keypad phone.
| Path | What it is | Gets | Costs |
|---|---|---|---|
| A. Andy's repos | Lineage 21 + his rebased TrebleDroid patches | Works now; one-man, unlicensed, last updated 2025-09 | Dependency and licence doubt |
| B. TrebleDroid `patches-for-developers.zip` (android_14.0.0_r17, ~0.5 MB) | Official, from the upstream authors, for AOSP 14, applied to Lineage 21 | No third-party layer | Rejects against Lineage's changes must be resolved by us, once per Lineage update |
| C. Plain AOSP 14 + TrebleDroid (their `treble_manifest` android-14.0 + `replace.xml`) | The official TrebleDroid GSI recipe, no Lineage | Smallest dependency set; matches what TrebleDroid tests | No Lineage extras (Lineage apps, security cherry-picks on lineage-21.0); AOSP tags stop at android-14.0.0_r75 |

## First-boot test goals (owner, 2026-11-11)
Carrier: Smarty (Three network). Calls already work on the stock Dumber image, and Three has no 3G, so VoLTE works with the stock vendor.
1. **VoLTE call must still work on our image** (the main risk of the telephony patches left out). Read-only checks: `dumpsys telephony.registry`, `dumpsys telephony_ims`, `getprop | grep -i ims`.
2. **Wi-Fi calling** (owner would like it; feasibility unknown). Vendor declares `persist.vendor.mtk_wfc_support=1`, `volte_support=1`, `ims_support=1`, `ro.telephony.iwlan_operation_mode=AP-assisted`. Smarty decides network-side and publishes only a partial device list, so the result is not predictable.
3. Keypad keys (stock keylayout), mobile data (APNs: Lineage's merged list is used), Bluetooth.
Before any flash: extract Dumber's current `system` from the backup's super.bin as the known-good image; flashing wipes userdata.
