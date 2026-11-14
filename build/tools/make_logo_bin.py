#!/usr/bin/env python3
"""Rebuild the MediaTek `logo` partition image with our own boot logo.

  make_logo_bin.py ORIGINAL_logo.bin NEW_logo.png OUT_logo.bin [--index 0 --index 38]

The partition is a 512-byte MTK header (magic 0x58881688, payload size, name "logo") followed by a table of 60 zlib-compressed images
(count, total, offsets) in 32-bit BGRA. The full-screen images are 480x640 (1,228,800 bytes raw). On the Qin J35/J36 images 0 and 38 are the
white "Qin" boot logo (shown first by the bootloader); everything else (charging battery pictures, etc.) is copied unchanged.
Needs ImageMagick (convert) to turn the PNG into raw BGRA. The output keeps the original file size (zero padded)."""
import struct, subprocess, sys, zlib

args = [a for a in sys.argv[1:]]
idx = []
while "--index" in args:
    i = args.index("--index"); idx.append(int(args[i + 1])); del args[i:i + 2]
src, png, out = args
idx = idx or [0, 38]
d = open(src, "rb").read()
magic, size = struct.unpack_from("<II", d, 0)
assert magic == 0x58881688, "not an MTK partition image"
B = 512
n, tot = struct.unpack_from("<II", d, B)
offs = list(struct.unpack_from("<%dI" % n, d, B + 8)) + [tot]
blobs = [d[B + offs[i]:B + offs[i + 1]] for i in range(n)]
for i in idx:
    old = zlib.decompress(blobs[i]); assert len(old) == 480 * 640 * 4, "image %d is not a 480x640 BGRA picture" % i
raw = subprocess.run(["convert", png, "-resize", "480x640!", "-background", "black", "-alpha", "remove", "-alpha", "on", "-depth", "8", "bgra:-"],
                     check=True, capture_output=True).stdout
assert len(raw) == 480 * 640 * 4, len(raw)
new = zlib.compress(raw, 9)
for i in idx: blobs[i] = new
head = 8 + 4 * n; o = head; table = []
for b in blobs: table.append(o); o += len(b)
payload = struct.pack("<II", n, o) + struct.pack("<%dI" % n, *table) + b"".join(blobs)
header = bytearray(d[:B]); struct.pack_into("<I", header, 4, len(payload))
res = bytes(header) + payload
assert len(res) <= len(d), "new image is larger than the partition image"
res += b"\0" * (len(d) - len(res))
open(out, "wb").write(res)
print("wrote %s: %d bytes (original %d), images replaced: %s, new image %d bytes compressed" % (out, len(res), len(d), idx, len(new)))
# self-check: parse the result again
d2 = res; n2, t2 = struct.unpack_from("<II", d2, B); o2 = struct.unpack_from("<%dI" % n2, d2, B + 8) + (t2,)
assert n2 == n
for i in range(n2):
    got = zlib.decompress(d2[B + o2[i]:B + o2[i + 1]])
    want = zlib.decompress(blobs[i]); assert got == want, i
print("self-check ok: %d images decode" % n2)
