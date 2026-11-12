#!/usr/bin/env python3
"""List files in an ext4 image (read-only, via debugfs) whose path matches the MediaTek telephony/IMS patterns.
Usage: inventory.py IMAGE [OUT.tsv]   -> path, type, size. Never mounts or writes the image. Run with python3 -I."""
import re, subprocess, sys

PAT = re.compile(r"(ims|volte|vilte|wfo|wfc|epdg|iwlan|mtk|mediatek|gba|telephony|telecom|carrier|rcs|ril|mal|ccci|radio|tele|sim|ussd|emergency|apn)", re.I)
SKIP_DIRS = re.compile(r"^/(proc|sys|dev|mnt|data|storage|sdcard|d|config|cache|acct|bugreports|lost\+found)(/|$)")

def ls(img, d):
    out = subprocess.run(["debugfs", "-R", "ls -l -p %s" % d, img], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.strip("/").split("/")
        if len(p) < 5: continue
        try: mode = int(p[1], 8)
        except ValueError: continue
        yield p[4], mode, (p[5] if len(p) > 5 and p[5] else "0")

def walk(img, d="/system", depth=0):
    for name, mode, size in ls(img, d):
        if name in (".", ".."): continue
        path = d.rstrip("/") + "/" + name
        if SKIP_DIRS.match(path): continue
        if (mode & 0o170000) == 0o040000:
            if depth < 7: yield from walk(img, path, depth + 1)
        elif (mode & 0o170000) == 0o100000:
            yield path, size

if __name__ == "__main__":
    img = sys.argv[1]
    out = open(sys.argv[2], "w") if len(sys.argv) > 2 else sys.stdout
    for path, size in walk(img):
        if PAT.search(path.split("/")[-1]) or re.search(r"/(ImsService|MtkGbaService|MtkTelephonyAssist|MtkCarrierConfig|CarrierConfig|mediatek-res)(/|$)", path):
            out.write("%s\t%s\n" % (path, size))
