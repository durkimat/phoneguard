#!/usr/bin/env python3
"""Builds the sticker library the app ships (assets/stickers-lib/), from:
  - Microsoft Fluent Emoji 3D PNGs (MIT, Copyright (c) Microsoft Corporation) at the pinned commit in selection.json, one 512 px lossy WebP each;
  - Noto animated emoji (Google, CC BY 4.0) listed in animated.txt, re-encoded as small animated WebP (every second frame, 256 px).
Needs: python3 with Pillow (pip install pillow) and network (once; downloads are cached in ~/.cache/familychat-stickers).
The result is not committed (about 10 MB); release-familychat.sh runs this when it is missing."""
import io, json, os, sys, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', 'src', 'main', 'assets', 'stickers-lib'))
CACHE = os.path.expanduser('~/.cache/familychat-stickers')
sel = json.load(open(os.path.join(HERE, 'selection.json')))
COMMIT = sel['commit']
RAW = 'https://raw.githubusercontent.com/microsoft/fluentui-emoji/%s/assets/%s/3D/%s_3d.png'
NOTO = 'https://fonts.gstatic.com/s/e/notoemoji/latest/%s/512.webp'

def fetch(url, name):
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, name)
    if os.path.exists(p): return open(p, 'rb').read()
    req = urllib.request.Request(url, headers={'User-Agent': 'familychat-sticker-build'})
    try:
        data = urllib.request.urlopen(req, timeout=60).read()
    except Exception:
        return None
    open(p, 'wb').write(data)
    return data

def static(item):
    n = item['name']
    data = fetch(RAW % (COMMIT, urllib.parse.quote(n), n.lower().replace(' ', '_')), 'fl-' + COMMIT[:8] + '-' + item['id'] + '.png')
    if not data:  # people with skin tones keep their yellow default in a Default folder
        data = fetch('https://raw.githubusercontent.com/microsoft/fluentui-emoji/%s/assets/%s/Default/3D/%s_3d_default.png' % (COMMIT, urllib.parse.quote(n), n.lower().replace(' ', '_')), 'fl-' + COMMIT[:8] + '-' + item['id'] + '-d.png')
    if not data: return None
    im = Image.open(io.BytesIO(data)).convert('RGBA').resize((512, 512), Image.LANCZOS)
    buf = io.BytesIO(); im.save(buf, 'WEBP', quality=72, method=6, alpha_quality=80)
    return buf.getvalue()

def animated(cp):
    data = fetch(NOTO % urllib.parse.quote(cp.replace(' ', '_')), 'noto-' + cp.replace(' ', '_') + '.webp')
    if not data: return None
    im = Image.open(io.BytesIO(data))
    fr, du = [], []
    for i in range(im.n_frames):
        im.seek(i); fr.append(im.convert('RGBA')); du.append(im.info.get('duration', 40) or 40)
    keep = [fr[i].resize((256, 256), Image.LANCZOS) for i in range(0, len(fr), 2)]
    dur = [sum(du[i:i + 2]) for i in range(0, len(fr), 2)]
    buf = io.BytesIO()
    keep[0].save(buf, 'WEBP', save_all=True, append_images=keep[1:], duration=dur, loop=0, quality=35, method=6, alpha_quality=35)
    return buf.getvalue()

def main():
    os.makedirs(os.path.join(OUT, 'static'), exist_ok=True); os.makedirs(os.path.join(OUT, 'animated'), exist_ok=True)
    index, bad = [], []
    items = sel['items']
    with ThreadPoolExecutor(6) as ex:
        for it, data in zip(items, ex.map(static, items)):
            if data is None: bad.append(it['name']); continue
            open(os.path.join(OUT, 'static', it['id'] + '.webp'), 'wb').write(data)
            index.append({'id': it['id'], 'name': it['cldr'], 'k': ' '.join(it['keywords']), 'cat': it['cat'], 'anim': False, 'file': 'static/%s.webp' % it['id']})
    byuni = {i['unicode']: i for i in items}
    lines = [l.strip() for l in open(os.path.join(HERE, 'animated.txt')) if l.strip() and not l.startswith('#')]
    for l in lines:
        cp = l.split('|')[0].strip(); it = byuni.get(cp)
        if it is None: raise SystemExit('animated %s is not in selection.json' % cp)
        data = animated(cp)
        if data is None: bad.append('animated ' + cp); continue
        fid = 'a-' + it['id']
        open(os.path.join(OUT, 'animated', fid + '.webp'), 'wb').write(data)
        index.append({'id': fid, 'name': it['cldr'], 'k': ' '.join(it['keywords']), 'cat': 'Animated', 'anim': True, 'file': 'animated/%s.webp' % fid})
    json.dump({'items': index}, open(os.path.join(OUT, 'index.json'), 'w'), ensure_ascii=False, separators=(',', ':'))
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(OUT) for f in fs)
    print('static %d, animated %d, missing %s, total %.1f MB' % (sum(1 for i in index if not i['anim']), sum(1 for i in index if i['anim']), bad, total / 1048576))

main()
