#!/usr/bin/env python3
"""Convert a raw image to Android sparse format (zero 4 KiB blocks become DONT_CARE) and verify it.

Usage: mksparse.py make   RAW SPARSE      write SPARSE, then verify it
       mksparse.py verify RAW SPARSE      expand SPARSE and compare sha256 with RAW (zero fill for DONT_CARE)
Read-only on RAW. Refuses to overwrite SPARSE.
"""
import hashlib, os, struct, sys

BLK = 4096
RAW, DONT_CARE = 0xCAC1, 0xCAC3
MAX_RAW_BLKS = 16384  # 64 MiB per RAW chunk
READ_BLKS = 256       # 1 MiB reads

def runs(f, total_blks):
    """Yield (is_data, start_blk, n_blks) over f, merging neighbours of the same kind."""
    zero = bytes(BLK)
    cur = None
    blk = 0
    while blk < total_blks:
        n = min(READ_BLKS, total_blks - blk)
        data = f.read(n * BLK)
        for i in range(n):
            d = data[i * BLK:(i + 1) * BLK]
            is_data = d != zero
            if cur and cur[0] == is_data and (not is_data or cur[2] < MAX_RAW_BLKS):
                cur[2] += 1
            else:
                if cur:
                    yield tuple(cur)
                cur = [is_data, blk + i, 1]
        blk += n
    if cur:
        yield tuple(cur)

def make(raw, sparse):
    size = os.path.getsize(raw)
    assert size % BLK == 0, "size not a multiple of 4096"
    total_blks = size // BLK
    chunks = []
    with open(raw, "rb") as f:
        for r in runs(f, total_blks):
            chunks.append(r)
    with open(sparse, "xb") as out, open(raw, "rb") as f:
        out.write(struct.pack("<IHHHHIIII", 0xED26FF3A, 1, 0, 28, 12, BLK, total_blks, len(chunks), 0))
        for is_data, start, n in chunks:
            if is_data:
                out.write(struct.pack("<HHII", RAW, 0, n, 12 + n * BLK))
                f.seek(start * BLK)
                left = n * BLK
                while left:
                    b = f.read(min(left, 1 << 20)); out.write(b); left -= len(b)
            else:
                out.write(struct.pack("<HHII", DONT_CARE, 0, n, 12))
    print("sparse size", os.path.getsize(sparse), "chunks", len(chunks))

def sha_raw(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b: break
            h.update(b)
    return h.hexdigest()

def sha_sparse(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        magic, major, minor, fh, ch, blk, total, nchunks, _ = struct.unpack("<IHHHHIIII", f.read(28))
        assert magic == 0xED26FF3A and blk == BLK and fh == 28 and ch == 12
        blks = 0
        for _ in range(nchunks):
            t, _r, n, tsz = struct.unpack("<HHII", f.read(12))
            if t == RAW:
                left = n * BLK
                while left:
                    b = f.read(min(left, 1 << 20)); h.update(b); left -= len(b)
            elif t == DONT_CARE:
                z = bytes(1 << 20); left = n * BLK
                while left:
                    k = min(left, len(z)); h.update(z[:k]); left -= k
            else:
                raise SystemExit("unexpected chunk type %x" % t)
            blks += n
        assert blks == total, "block count mismatch"
        assert f.read(1) == b"", "trailing data"
    return h.hexdigest()

def verify(raw, sparse):
    a, b = sha_raw(raw), sha_sparse(sparse)
    print("raw   ", a); print("sparse", b)
    if a != b: raise SystemExit("MISMATCH")
    print("OK: sparse expands to the identical image")

if __name__ == "__main__":
    cmd, raw, sparse = sys.argv[1:4]
    if cmd == "make": make(raw, sparse); verify(raw, sparse)
    elif cmd == "verify": verify(raw, sparse)
    else: raise SystemExit(__doc__)
