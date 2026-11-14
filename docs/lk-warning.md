# Hiding the "Orange State / unlocked" bootloader warning (analysis, 2026-11-14; nothing flashed)

Source: `/archive/phone-backups/qin-j35-20261110/partitions/lk_a.bin` (and lk_b.bin), read-only analysis with a disassembler in a scratch venv (capstone).

## Facts
- `lk_a.bin` = 512-byte MediaTek header + a 32-bit ARM image (0xB8810 bytes, ARM-mode vectors at the start). The text lives in the image, not in the `logo` partition: "Orange State", "Your device has been unlocked and can't be trusted", "Your device will boot in 5 seconds" (also yellow/red/green variants and "will reboot in 5 seconds").
- The messages are plain C strings at image offsets 0x8DAE0 (title), 0x8DAF0 (line 1, prefixed "Your device "), 0x8DB24 (line 2). A data table at image offset ~0xB7AE0-0xB7B60 holds pointers to the pieces for each state (load address base is about 0x48002300, derived from the pointer table). The pieces are selected by boot state, so the strings are data driven; the 5 s delay is not yet located (either a field in that table or a delay call in the function that prints them).
- LK profiling in the stock expdb log: `boot_time` was 2.9-7.6 s including the 5 s warning wait, so the wait is at most 5 s of the pre-Android delay (the rest of the owner's ~30 s Qin-logo stage is the preloader/kernel/early Android before the display is re-initialised; the boot animation now starts at 18 s).

## Options
1. Blank the strings (same length, spaces): smallest patch, hides the text, keeps the 5 s wait. Low risk, but the screen stays on the logo for 5 s.
2. Also remove the wait: find the delay call/constant for the orange state and set it to 0 (needs code analysis; may be a `mdelay(5000)` or a countdown loop with a key poll).
3. Use the standard "no warning" path: some MediaTek LK builds skip the screen when a flag is set; not seen here.

## Risk and procedure
- Writing `lk_a`/`lk_b` is the riskiest operation on this phone (a bad LK = no boot; recovery only by mtkclient in BROM mode, which works here because secure boot is off: `fastboot getvar secure` = no). docs/recovery-guide.md lists lk as "never write" by default, so this needs the owner's explicit go-ahead and the restore kit at hand.
- **There is NO fallback slot: `lk_b.bin` in the 2026-11-10 backup is entirely zeros** (nothing in it), so the phone boots from lk_a alone. A bad lk_a = no boot until mtkclient (BROM mode) writes the original back. The unit's mtkclient state shows BROM access works (`sbc`/`sla`/`daa` all false, i.e. no secure boot, flashmode XFLASH), and the original lk_a.bin is backed up at qin-j35-20261110/partitions/lk_a.bin (sha256 to be recorded before flashing).
- Procedure: back up the phone's CURRENT lk_a (compare with the backup), flash the patched lk_a with mtkclient in BROM mode (the phone off, USB connected, owner present), verify by reading back, reboot; keep the restore command ready.
- Prepared patch (nothing flashed): `build/tools/patch_lk_warning.py lk_a.bin OUT.bin` blanks the three orange-state strings (81 bytes, same size). It does not remove the 5 s wait.
- The logo replacement (separate, `logo` partition) is independent and does not touch LK.

## Result (2026-11-14)
- v1 (spaces) drew black boxes on the logo; v2 (NUL bytes, 96 bytes at 0x8dce0-0x8dd45, `lk_a-empty-warning.bin`, sha256 7ce32e92a32758b15d5d12e40b9b7aa60008cfd37baef53e13c7b745b51cd952) is on the phone's lk_a (verified from Android with dd): no text over the logo, then the boot animation. Written with mtkclient in emergency (BROM/preloader) mode with no key combination: phone off, plug USB. The original is backed up (sha256 549f3b07...), restore with `mtk w lk_a lk_a-original.bin`.
- Still open: the 5 s wait (not located), and the charge-mode (powered off + USB) screen first shows a battery symbol with 'no battery' before the real level (LK/kpoc charging pictures; low priority).
