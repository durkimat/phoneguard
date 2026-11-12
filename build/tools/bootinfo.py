#!/usr/bin/env python3
"""Read-only summary of an Android boot image (header v0-v4): header fields, cmdline, kernel version, ramdisk and AVB footer.
Usage: bootinfo.py boot.img [...]   (run with python3 -I; never writes)"""
import gzip, hashlib, re, struct, sys, zlib

def pages(n, p): return (n + p - 1) // p * p

def kernel_version(blob):
    for name, dec in (("raw", lambda b: b), ("gzip", lambda b: zlib.decompressobj(31).decompress(b[b.find(b"\x1f\x8b\x08"):]) if b"\x1f\x8b\x08" in b[:4096] else b"")):
        try: d = dec(blob)
        except Exception: d = b""
        m = re.search(rb"Linux version [^\x00\n]{0,200}", d)
        if m: return name, m.group(0).decode(errors="replace")
    return None, None

def main(path):
    d = open(path, "rb").read()
    print("==", path, len(d), "bytes, sha256", hashlib.sha256(d).hexdigest()[:16])
    if d[:8] != b"ANDROID!":
        print("not a boot image (magic %r); first 16 bytes zero: %s" % (d[:8], d[:16] == bytes(16))); return
    (ksz, kaddr, rsz, raddr, s2sz, s2addr, tags, page, ver, osv) = struct.unpack_from("<10I", d, 8)
    print("header v%d  page %d  kernel %d B  ramdisk %d B  os_version 0x%x (A%d, patch %d-%02d)" % (ver, page, ksz, rsz, osv, (osv >> 25) & 0x7f, 2000 + ((osv >> 4) & 0x7f) % 100 + (0), (osv & 0xf)))
    cmd = d[0x40:0x40 + 512].split(b"\0")[0].decode(errors="replace")
    print("cmdline:", cmd)
    ko = page
    print("kernel at", ko, "magic", d[ko:ko + 4].hex())
    kind, ver_s = kernel_version(d[ko:ko + ksz])
    print("kernel version string (%s):" % kind, ver_s or "not found (compressed with something other than gzip)")
    ro = ko + pages(ksz, page)
    rd = d[ro:ro + rsz]
    print("ramdisk magic", rd[:4].hex(), "(1f8b=gzip, 02214c18=lz4 legacy)" if rd else "(empty)")
    if ver >= 1:
        rec_sz, rec_off = struct.unpack_from("<IQ", d, 1648 if ver < 3 else 0)[0:2] if False else (0, 0)
    i = d.rfind(b"AVBf")
    print("AVB footer:", "present at %d" % i if i > 0 else "none found")

for p in sys.argv[1:]: main(p)
