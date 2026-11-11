# NAS workstation hand-off (2026-11-11)

Work is moving from the owner's mini PC to an always-on Linux workstation container on the MOS NAS.

## Where things are
- NAS 192.168.1.138. Containers (stack `android-build`, compose in `phoneguard/build/compose.yaml`):
  - `claude-workstation`: SSH port 2223, user `matthew` (UID 500), home `/home/matthew` (NVMe), `/archive` (HDD, 17 GB: phone-backups, Local Sites), `/artifacts` shared. Claude Code logged in (Pro account).
  - `android-build`: SSH port 2222, user `builder`; /src /out /ccache on NVMe, 48 GB memory cap. Lineage sync NOT started.
- All keys in the `AUTHORIZED_KEY` env var (`|` separated): my build key, the owner's `nas_workstation` key, and the workstation's own key (so it can ssh to the build container: `ssh -p 2222 builder@192.168.1.138`).
- Same paths as the old PC: `~/HA`, `~/Projects`, `~/powerengine`, `~/esphome`, `~/phone-backups` (symlink to /archive), `~/.android` (debug keystore), Android SDK in `~/Android/Sdk`.

## Not on the NAS / not done
- Phone USB work (adb, fastboot, mtkclient) stays on the old PC; the phone is plugged in there.
- Node, Go, esphome not installed in the image yet. Gradle JDK for Phone Guard unchecked (image has JDK 17 and 21).
- `~/powerengine` and `~/Projects/powerengine` differ (controller newer in the first, card newer in the second); both copied, unmerged.
- Do NOT start the Lineage `repo sync` (100 GB+) without the owner's go-ahead.
- Phone issues still open: NFC crash loop (needs a reboot, owner undecided), Phone Guard ANR (see BACKLOG.md).
- Owner pushes to GitHub manually; commit locally only. Co-Authored-By trailer on commits.
- Planned: drop the old build key from AUTHORIZED_KEY after migration; encrypted host-side backup to OneDrive.
