#!/usr/bin/env python3
"""Remove the "Orange State / device has been unlocked" text from a MediaTek LK image (same length).

  patch_lk_warning.py lk_X.bin OUT.bin [--spaces]

Default: the three strings are overwritten with NUL bytes, so every pointer into them reads an EMPTY string and nothing is drawn.
--spaces: overwrite with spaces instead (v1; LK draws each space as a black box on the logo, so this is NOT recommended).

Only the three orange-state strings are touched (see docs/lk-warning.md); every other byte is identical and the result is the same size.
It does NOT remove the 5 second wait (that delay is not located yet). Nothing is written to the phone by this script."""
import sys
STRINGS = [b"Orange State", b"Your device has been unlocked and can't be trusted", b"Your device will boot in 5 seconds"]
src, out = sys.argv[1:3]
SPACES = "--spaces" in sys.argv
d = bytearray(open(src, "rb").read()); orig = bytes(d)
for s in STRINGS:
    n = d.count(s); assert n == 1, (s, n)
    i = d.find(s)
    d[i:i + len(s)] = bytes((b if b in (10, 13) else 0x20) if SPACES else 0 for b in s)
changed = [i for i in range(len(d)) if d[i] != orig[i]]
assert len(d) == len(orig)
open(out, "wb").write(d)
print("patched %d bytes in %d places; size %d unchanged" % (len(changed), len(STRINGS), len(d)))
for s in STRINGS: print("  blanked:", s.decode())
