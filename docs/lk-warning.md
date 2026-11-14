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
- Patch ONE slot first (lk_b is only used if the preloader selects it: check how the slot is chosen before relying on it as a fallback), keep both originals (in qin-j35-20261110/partitions), verify sha256 after writing, and keep the phone connected to the PC with mtkclient ready.
- The logo replacement (separate, `logo` partition) is independent and does not touch LK.
