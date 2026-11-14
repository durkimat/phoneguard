# Build it yourself: can a user make their own image? (scoping, 2026-11-14)

Question: could our tooling, on the **user's** side, (1) pull the stock files from their own phone, (2) download LineageOS and TrebleDroid from the public sources, (3) apply our patches, (4) build and sign, (5) flash? That route needs no redistribution of anyone else's files. This page records what the repository can do today, measured on our own build host. Companion to [image-redistribution.md](image-redistribution.md). Nothing here was changed in the build.

**Verdict: ordinary parents: no. Enthusiasts and people willing to rent a cloud machine: yes, with the steps and caveats below, none of which has been run from a clean machine yet.**

## (a) What the build needs today, and is it one command?

Measured on our host (Ubuntu 22.04 container, 16 cores, 60 GB RAM with a 48 GB container cap, NVMe):

| Item | Value |
|---|---|
| OS | Ubuntu 22.04 (the container in `build/compose.yaml` lists the apt packages; any Linux with them works; **not** Windows/macOS directly) |
| Disk | source tree 162 GB (of which `.repo` 73 GB) + build output 101 GB + ccache 6.7 GB, about **270 GB used**; LineageOS says 400 GB free |
| RAM | LineageOS 21 asks for 64 GB; we cap at 48 GB and use `-j` of all 16 cores (drop to `-j8` if it runs out of memory) |
| Download | the `repo sync` fetches about 73 GB (gentle: 8 jobs) from GitHub and Google |
| Time | **cold first build not recorded** (it was spread over many iterations). With a warm ccache our incremental rebuilds took 4 to 10 minutes. A cold Lineage build on 16 cores is typically several hours (their estimate, not measured here); the sync is hours too, depending on the link |
| Result | `system.img` (about 2.35 GB), flashed with [the installer](../installer/README.md) |

**Not one command today.** `build/build.sh` has three stages (`sync`, `build`, `publish`) and is written for our container: it assumes `/src`, `/out`, `/ccache`, a copy of `build/` at `/src/phoneguard-build`, a git identity, tmux, and the private inputs staged by hand (`proprietary/ims-stock-android13-20261112`, `apps/phoneguard.apk`, optional `keys-pg`, `branding/bootanimation.zip`). It has **never been run end to end on a clean machine**: our tree grew step by step (TrebleDroid's patch script left 58 reject lines that were resolved by hand, then our 17 patches in `build/patches` were added in place).

**Can it take the installer's stock-files folder automatically?** Not yet, but it is a small change: the installer's backup stores each pulled file under `stock-files/` with `/` replaced by `__` (for example `system__priv-app__ImsService__ImsService.apk`); `build.sh` expects the same files in a real directory tree and has the folder name hard-coded. A small converter step (un-flatten the names, check the file list against `installer/proprietary-manifest.json`, refuse if any is missing) would make it accept the folder. Two cautions: (1) the files must come from a firmware version close to the one we tested (our notes record original firmware sha256 `aabb1148…5283`; other batches may differ, and our notes are inconsistent on whether the vendor side is Android 12 or 13); (2) whether `adb pull` of those files works on an unrooted stock phone is untested.

## (b) Signing, and what a different key breaks

* `build.sh` **does not generate keys**: it copies `/src/keys-pg` if it exists, otherwise the build falls back to **AOSP's public test keys** (anyone can sign a system app with those). `build/tools/make-keys.sh OUTDIR` makes a set (RSA 2048, valid about 27 years); a self-builder must run it once, keep it private and back it up (lose it = wipe to change keys).
* What is signed with the platform/release set: the MediaTek IMS apps (they share the phone UID, see the redistribution page) and the other platform-signed system apps. A different key is therefore fine for them: the image is built with the user's own key throughout.
* **Phone Guard itself does not depend on that key**: `build/apps/module/Android.bp` installs `phoneguard.apk` as `presigned: true`, signed by the project's own release key (the signing-keys note that says "platform-signed" is out of date on this point). So a self-built image that embeds the **public release APK** keeps the normal update path: the companion/Home Assistant "update Phone Guard" downloads the GitHub release signed by the same key and Android accepts it. Two things to keep true: the build must embed a *released* APK (not one the user signed), and Phone Guard's privileged permissions are granted by package name through the allowlist in the image, not by the key.
* Image updates (a new Android security patch, new patches of ours) for a self-built phone mean: pull the new repo, rebuild with the same keys, flash with `--keep-data`. No in-app updater can replace the system image. The app update path above is separate and works.

## (c) Is anything mirrored in our repo?

No LineageOS or TrebleDroid source is in our repository. `build.sh sync` fetches from the original sources: `LineageOS/android` (branch `lineage-21.0`), TrebleDroid's `device_phh_treble`, `vendor_interfaces`, `vendor_hardware_overlay`, `treble_app` (from `build/local_manifests/treble.xml`) and TrebleDroid's release asset `patches-for-developers.zip` (tag `android_14.0.0_r17`, checksum logged) with their own `apply-patches.sh`. What we publish is only our diffs, overlays, scripts and docs. The prebuilt `TrebleApp.apk` and the overlays come from those fetched sources.

## (d) Cloud option

The same script could run on a rented Linux VM: about 32 vCPU, 64 to 128 GB RAM, **400 GB SSD**, Ubuntu 22.04, a few hours for the first build. Rough cost guess (not looked up): several hours at around one to two dollars an hour, plus the disk and the download, so **in the order of ten to thirty dollars for a first image**, less on a spot instance; the machine is then deleted (keep the keys and the output). Missing for this to be "one script": the stock-files converter (a), a bootstrap script that installs the packages, makes keys, pulls the pinned sources and runs sync and build unattended, and a first full clean-machine run to find what breaks.

## (e) Pinned or latest?

**Pin it.** Today only TrebleDroid's patch zip is pinned (tag `android_14.0.0_r17`); `lineage-21.0` and the TrebleDroid projects (`android-14.0`, `pie`, `master`) follow their branch tips, and our patches apply by context, so they **break when upstream moves** (we saw this when a direct edit shifted context). Added today: [`build/pinned-manifest.xml`](../build/pinned-manifest.xml), a full `repo` manifest of all 1,437 projects at the exact upstream commits our tree was synced to (22 projects carry local TrebleDroid patch commits, so their pin is the upstream commit underneath, and the patches are re-applied by `apply-patches.sh`). It is generated from our working tree and **not yet tested by a fresh sync**: the next step is `repo init -m` with it on a clean machine and a build.

## What would make this real, in order

1. A clean-machine run of the pinned sync and build (also proves the reject fixes are reproducible, or captures them as patches).
2. Stock-files converter and a `PROPRIETARY_DIR` input to `build.sh`.
3. One bootstrap script (packages, `make-keys.sh`, sync, patches, build, copy to a kit folder with `SHA256SUMS`), runnable on a rented VM.
4. Hand the kit to the installer (`installer/pg-install.mjs`), which already backs up and flashes.
