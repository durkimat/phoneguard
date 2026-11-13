#!/usr/bin/env python3
"""Boot animation mock-up generator (480x640, 15 fps, 35 s loop).

One scene description, two outputs:
  * SVG strings  -> mockup.html (browser player)
  * MVG text     -> ImageMagick `convert` -> PNG (checking now; final bootanimation frames later)

Usage:
  gen.py html OUT.html          write the player
  gen.py png  OUTDIR T [T...]   render PNGs of the given times (seconds)
  gen.py frames OUTDIR          render every frame as PNG (final pipeline)
"""
import math, os, subprocess, sys, json

W, H, FLOOR, FPS, T = 480, 640, 520, 15, 35.0

# ---- palette ---------------------------------------------------------------
BG = "#070a0f"; FLOORC = "#0d131a"
TEAL = "#35d6c0"; TEAL_D = "#0e6e63"
COAT = "#8a4a28"; COAT_D = "#5c2f18"; SCARF = "#f26a1b"; SCARF_D = "#b84a0d"
HAIR = "#a7a59e"; BEARD = "#4a443d"; GLASS = "#17171a"; EARBUD = "#2b3f95"
LEG = "#232a32"; SKIN = "#dcae92"; BRASS = "#6b4b2a"


def lerp(a, b, u): return a + (b - a) * u
def sm(u):
    u = max(0.0, min(1.0, u)); return u * u * (3 - 2 * u)
def clamp(v, a, b): return max(a, min(b, v))


# ---- scene: ops collected once, emitted as SVG or MVG --------------------------
class Scene:
    def __init__(s): s.ops = []
    def _a(s, kind, pts, fill, stroke, w, a, cap="round"):
        s.ops.append((kind, pts, fill, stroke, w, a, cap))
    def line(s, x1, y1, x2, y2, w, col, a=1.0): s._a("line", (x1, y1, x2, y2), None, col, w, a)
    def circle(s, x, y, r, fill=None, stroke=None, w=0, a=1.0): s._a("ellipse", (x, y, r, r), fill, stroke, w, a)
    def ellipse(s, x, y, rx, ry, fill=None, stroke=None, w=0, a=1.0): s._a("ellipse", (x, y, rx, ry), fill, stroke, w, a)
    def poly(s, pts, fill=None, stroke=None, w=0, a=1.0): s._a("poly", pts, fill, stroke, w, a)
    def pline(s, pts, w, col, a=1.0): s._a("pline", pts, None, col, w, a)
    def rrect(s, x, y, w_, h_, r, fill=None, stroke=None, w=0, a=1.0): s._a("rrect", (x, y, w_, h_, r), fill, stroke, w, a)

    def final(s):
        return [r for r in (restyle(op) for op in s.ops) if r]

    def svg(s):
        f = lambda v: ("%.1f" % v).rstrip("0").rstrip(".")
        out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="100%%" height="100%%">' % (W, H),
               '<rect width="%d" height="%d" fill="%s"/>' % (W, H, BG)]
        for kind, p, fill, stroke, w, a, cap in s.final():
            st = ' fill="%s"' % fill if fill else ' fill="none"'
            if stroke: st += ' stroke="%s" stroke-width="%s" stroke-linecap="round" stroke-linejoin="round"' % (stroke, f(w))
            if a < 1: st += ' opacity="%s"' % f2(a)
            if kind == "line": out.append('<line x1="%s" y1="%s" x2="%s" y2="%s"%s/>' % (*map(f, p), st))
            elif kind == "ellipse": out.append('<ellipse cx="%s" cy="%s" rx="%s" ry="%s"%s/>' % (*map(f, p), st))
            elif kind in ("poly", "pline"):
                pts = " ".join("%s,%s" % (f(x), f(y)) for x, y in p)
                out.append('<%s points="%s"%s/>' % ("polygon" if kind == "poly" else "polyline", pts, st))
            elif kind == "rrect":
                out.append('<rect x="%s" y="%s" width="%s" height="%s" rx="%s"%s/>' % (*map(f, p), st))
        out.append("</svg>")
        return "".join(out)

    def mvg(s):
        o = ["viewbox 0 0 %d %d" % (W, H), "fill %s" % BG, "rectangle 0,0 %d,%d" % (W, H), "stroke-linecap round", "stroke-linejoin round"]
        for kind, p, fill, stroke, w, a, cap in s.final():
            o.append("fill %s" % (rgba(fill, a) if fill else "none"))
            o.append("stroke %s" % (rgba(stroke, a) if stroke else "none"))
            o.append("stroke-width %.2f" % w)
            if kind == "line": o.append("line %.1f,%.1f %.1f,%.1f" % p)
            elif kind == "ellipse": o.append("ellipse %.1f,%.1f %.1f,%.1f 0,360" % p)
            elif kind == "poly": o.append("polygon " + " ".join("%.1f,%.1f" % q for q in p))
            elif kind == "pline": o.append("polyline " + " ".join("%.1f,%.1f" % q for q in p))
            elif kind == "rrect":
                x, y, w_, h_, r = p; o.append("roundrectangle %.1f,%.1f %.1f,%.1f %.1f,%.1f" % (x, y, x + w_, y + h_, r, r))
        return "\n".join(o)


GREEN = "#3ddc84"; ORANGE = "#ff8a3d"; NAVY = "#073042"; WHITE = "#ffffff"
_BODY = [TEAL, COAT, SCARF, HAIR, LEG, SKIN, BEARD, EARBUD, BRASS, "#10161c", "#2bbfaa", "#f4f1ea", "#7fa793", WHITE, "#ffffff"]

# a style maps the base colours to a look; None selects the original (full colour) look
STYLES = {
    "outline": dict(minw=3, fill={c: None for c in _BODY}, stroke={c: GREEN for c in (TEAL_D, GLASS, HAIR, BRASS, COAT_D, SCARF_D, LEG, "#8d6a58", "#4a3b32", "#3d5d4e", "#1d2a2d", COAT)},
                    fillstroke={c: GREEN for c in _BODY}, dot=GREEN, opaque=True, drop_shine=True, tube=True),
    "accent": dict(minw=3, fill={c: None for c in _BODY}, stroke={TEAL_D: GREEN, GLASS: WHITE, HAIR: GREEN, BRASS: ORANGE, COAT_D: GREEN, SCARF_D: ORANGE, LEG: GREEN, "#8d6a58": GREEN, "#4a3b32": GREEN, "#3d5d4e": GREEN, "#1d2a2d": WHITE, COAT: GREEN},
                    fillstroke={**{c: GREEN for c in _BODY}, SCARF: ORANGE, EARBUD: "#7d92ff"}, dot=GREEN, opaque=True, drop_shine=True, tube=True),
    "flat": dict(minw=2, fill={TEAL: GREEN, COAT: "#27b86b", LEG: "#27b86b", HAIR: "#d9f5e5", SKIN: "#d9f5e5", BEARD: "#1b7f4a", "#2bbfaa": "#27b86b", BRASS: "#27b86b"},
                  stroke={TEAL_D: NAVY, GLASS: NAVY, COAT_D: NAVY, SCARF_D: "#c4520f", LEG: NAVY, BRASS: NAVY, "#1d2a2d": NAVY, "#4a3b32": NAVY},
                  fillstroke={}, dot="#d9f5e5", opaque=True, drop_shine=False),
    "tint": dict(minw=3, fill={c: GREEN for c in _BODY}, fillalpha=0.12, stroke={c: GREEN for c in (TEAL_D, GLASS, HAIR, BRASS, COAT_D, SCARF_D, LEG, "#8d6a58", "#4a3b32", "#3d5d4e", "#1d2a2d", COAT)},
                  fillstroke={c: GREEN for c in _BODY}, dot=GREEN, opaque=False, drop_shine=True, tube=True),
}
TUBE = "#abcdef"
STYLE = None   # set by the caller; None = the original full-colour look


def restyle(op):
    st = STYLES.get(STYLE) if STYLE else None
    kind, p, fill, stroke, w, a, cap = op
    if stroke == TUBE:                                                                   # inner core of an outlined limb
        return (kind, p, None, BG, w, 1.0, cap) if st and st.get("tube") else None
    if not st: return op
    if fill == "#fff3d0" or fill == "#000000" or fill == "#cfeff0": return op            # spotlight, shadow, glass tint
    if fill == WHITE and a < 0.3: return None if st["drop_shine"] else op                 # shine / lens tint
    tiny = kind == "ellipse" and p[2] < 3 and fill and not stroke
    if tiny:
        c = st["fill"].get(fill, fill) or st["dot"]
        return (kind, p, c if fill != WHITE else WHITE, None, 0, 1.0, cap)
    nf, ns, nw, na = fill, stroke, w, a
    if fill:
        nf = st["fill"][fill] if fill in st["fill"] else fill
        if nf is None: nf = BG
        elif "fillalpha" in st and fill in st["fill"]: na = st["fillalpha"]
        if not stroke and fill in st["fillstroke"]: ns, nw = st["fillstroke"][fill], st["minw"]
    if stroke:
        ns = st["stroke"].get(stroke, stroke); nw = max(w, st["minw"])
    if st["opaque"] and fill in st["fill"] and na < 1 and "fillalpha" not in st: na = 1.0
    if stroke and fill is None and kind in ("line", "pline"):
        na = a
    return (kind, p, nf, ns, nw, na, cap)


def f2(v): return ("%.2f" % v).rstrip("0").rstrip(".")
def rgba(h, a):
    return "rgba(%d,%d,%d,%.2f)" % (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), a)


# ---- timeline --------------------------------------------------------------------
GAZE = [(0, 0, 0), (0.7, -1, 0), (1.5, 1, 0), (2.3, 0, -1), (3.1, 0, 1), (3.9, -1, .6), (4.7, 1, -.6), (5.4, 0, 0), (6.0, 0, 0)]


def gaze_at(g):
    for (t0, x0, y0), (t1, x1, y1) in zip(GAZE, GAZE[1:]):
        if t0 <= g <= t1:
            u = sm((g - t0) / (t1 - t0)); return lerp(x0, x1, u), lerp(y0, y1, u)
    return 0, 0


def pose(t):
    t %= T
    P = dict(x=-400, f=0, dir=-1, walk=0, mode="gap", du=None, mag=0, carry=False, gaze=(0, 0), blink=0, spot=0, cap="")
    if t < 16:                                   # sequence 1: right -> left, dance
        P["dir"] = -1
        P["spot"] = min(1, t / 0.8, (15.6 - t) / 0.6)
        if t < 4: P.update(x=lerp(590, 240, t / 4), f=-1, walk=1, mode="walk", cap="Sequence 1: walks on from the right")
        elif t < 4.8: P.update(x=240, f=-1 + sm((t - 4) / 0.8), mode="turn", cap="Turns to face the camera")
        elif t < 11.2: P.update(x=240, f=0, mode="dance", du=t - 4.8, cap="Dances (Y M C A)")
        elif t < 12: P.update(x=240, f=-sm((t - 11.2) / 0.8), mode="turn", cap="Turns to leave")
        else: P.update(x=lerp(240, -110, (t - 12) / 4), f=-1, walk=1, mode="walk", cap="Walks off to the left")
    elif 17 <= t < 34:                           # sequence 2: left -> right, magnifier
        r = t - 17
        P.update(dir=1, carry=True, spot=min(1, r / 0.8, (16.6 - r) / 0.6))
        if r < 4: P.update(x=lerp(-110, 240, r / 4), f=1, walk=1, mode="walk", cap="Sequence 2: walks on from the left, magnifying glass in hand")
        elif r < 4.8: P.update(x=240, f=1 - sm((r - 4) / 0.8), mode="turn", cap="Turns to face the camera")
        elif r < 5.6: P.update(x=240, f=0, mode="look", mag=sm((r - 4.8) / 0.8), cap="Raises the magnifying glass")
        elif r < 11.6:
            g = r - 5.6; bl = 0
            for b in (2.0, 5.0):
                if b <= g <= b + 0.26: bl = math.sin(math.pi * (g - b) / 0.26)
            P.update(x=240, f=0, mode="look", mag=1, gaze=gaze_at(g), blink=bl, cap="Magnified eye looks around")
        elif r < 12.2: P.update(x=240, f=0, mode="look", mag=1 - sm((r - 11.6) / 0.6), cap="Lowers the magnifying glass")
        elif r < 13: P.update(x=240, f=sm((r - 12.2) / 0.8), mode="turn", cap="Turns to leave")
        else: P.update(x=lerp(240, 590, (r - 13) / 4), f=1, walk=1, mode="walk", cap="Walks off to the right")
    return P


# ---- drawing --------------------------------------------------------------------
HANDS = {  # hand target relative to its shoulder: (Lx, Ly, Rx, Ry)
    "rest": (-8, 100, 8, 100), "Y": (-64, -100, 64, -100), "M": (30, -112, -30, -112),
    "C": (-30, 70, -76, -104), "A": (38, -112, -38, -112)}
CYCLE = ["Y", "M", "C", "A"]


def dance_hands(du):
    seg = (du % 3.2) / 0.8; i = int(seg) % 4; u = seg - int(seg)
    prev = CYCLE[(i - 1) % 4]
    a = [lerp(p, q, sm(u / 0.35)) for p, q in zip(HANDS[prev], HANDS[CYCLE[i]])]
    return a


def ik(sx, sy, hx, hy, L, side):
    dx, dy = hx - sx, hy - sy; d = math.hypot(dx, dy) or 1e-6
    reach = 2 * L - 0.5
    if d > reach: hx, hy, d = sx + dx * reach / d, sy + dy * reach / d, reach; dx, dy = hx - sx, hy - sy
    a = math.sqrt(max(L * L - (d / 2) ** 2, 0)); mx, my = (sx + hx) / 2, (sy + hy) / 2; nx, ny = -dy / d, dx / d
    c1 = (mx + nx * a, my + ny * a); c2 = (mx - nx * a, my - ny * a)
    return (c1 if side * c1[0] >= side * c2[0] else c2), (hx, hy)


def arc(cx, cy, rx, ry, a0, a1, n=14):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def draw_spot(sc, P, t):
    if P["spot"] <= 0: return
    sx = clamp(pose(t - 0.15)["x"], -60, 540); k = P["spot"]
    for i in range(5):
        a, b = 8 + i * 3, 66 + i * 15
        sc.poly([(sx - a, -10), (sx + a, -10), (sx + b, FLOOR + 4), (sx - b, FLOOR + 4)], fill="#fff3d0", a=0.05 * k)
    for i in range(5):
        sc.ellipse(sx, FLOOR + 4, 150 - i * 24, 27 - i * 4, fill="#fff3d0", a=0.08 * k)


def draw_head(sc, P, hx, hy):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); k = 0.15 + 0.85 * k2; fx = f * 36
    sgn = 1 if f > 0 else -1
    sc.ellipse(hx, hy, 64, 56, fill=TEAL, stroke=TEAL_D, w=3)
    # beard: band between the jaw and an inner opening that shifts with the turn
    bx = hx + fx * 0.3
    outer = arc(bx, hy, 61, 53, 18, 162); inner = arc(hx + fx, hy + 8, 8 + 33 * k, 27, 162, 18)
    sc.poly(outer + inner, fill=BEARD, a=0.78)
    for i in range(9):                                                                  # stubble flecks
        a = math.radians(30 + i * 15); sc.circle(bx + 52 * math.cos(a), hy + 44 * math.sin(a), 1.6, fill=HAIR, a=0.6)
    # ears and earbuds (the far ear is hidden in profile)
    for s_ in (-1, 1):
        if k2 < 0.3 and s_ == -sgn: continue
        ex = hx + s_ * 64 * k2 + (0 if k2 > 0.3 else -sgn * 4)
        sc.circle(ex, hy + 6, 9, fill=TEAL, stroke=TEAL_D, w=2)
        sc.rrect(ex - 4 + s_ * 1, hy + 4, 8, 17, 4, fill=EARBUD)
    # hair: thin band at the sides, tufts on top
    hxc = hx + fx * 0.4
    band_o = arc(hxc, hy, 64 * (0.5 + 0.5 * k2), 57, 192, 348, 26)
    band_i = arc(hxc + fx * 0.15, hy - 8, 49 * (0.5 + 0.5 * k2), 27, 348, 192, 26)          # hairline across the forehead
    sc.poly(band_o + band_i, fill=HAIR)
    sc.pline(arc(hxc + fx * 0.15, hy - 8, 49 * (0.5 + 0.5 * k2), 27, 215, 325, 12), 2.5, GLASS if STYLE is None else TEAL_D, a=0.5)
    for i in range(13):
        a = math.radians(195 + 150 * i / 12); c, s_ = math.cos(a), math.sin(a)
        bxp = hx + fx * 0.4 + 62 * c * (0.5 + 0.5 * k2); byp = hy + 54 * s_
        ht = 10 + 15 * abs(math.sin(i * 2.3)) + (9 if 3 <= i <= 6 else 0)
        tx, ty = -s_, c
        tip = (bxp + c * ht * 0.8 + tx * 5 * math.sin(i * 1.7), byp + s_ * ht + ty * 5 * math.sin(i * 1.7))
        sc.poly([(bxp - tx * 6, byp - ty * 6), tip, (bxp + tx * 6, byp + ty * 6)], fill=HAIR)
    # glasses
    ey = hy - 2; lw = 14 + 34 * k
    for s_ in (-1, 1):
        ex = hx + fx + s_ * 26 * k
        far = (s_ == -sgn)
        al = 1.0 if not far else clamp((0.75 - abs(f)) / 0.3, 0, 1)
        if al <= 0: continue
        sc.rrect(ex - lw / 2, ey - 19, lw, 38, 7, fill="#ffffff", stroke=GLASS, w=5, a=al)
        sc.rrect(ex - lw / 2, ey - 19, lw, 38, 7, fill="#ffffff", a=0.0 + 0.08 * al)
        gx, gy = P["gaze"] if P["mode"] == "look" else (0.5 * sgn * abs(f) if abs(f) > 0.5 else 0, 0)
        sc.circle(ex + gx * 3 * k + sgn * abs(f) * 2, ey + gy * 2, 4.6, fill="#14323a", a=al)
        sc.line(ex - lw / 2 + 2, ey - 28, ex + lw / 2 - 2, ey - 31 - (2 if s_ == 1 else 0), 4, HAIR, a=al)   # brow
    if k > 0.35: sc.line(hx + fx - 9 * k, ey - 6, hx + fx + 9 * k, ey - 6, 5, GLASS, a=clamp((k - .35) * 3, 0, 1))
    if abs(f) > 0.35: sc.line(hx + fx - sgn * (lw / 2 + 26 * k), ey - 6, hx - sgn * 52 * (1 - abs(f) * .2), hy + 0, 4, GLASS, a=clamp((abs(f) - .35) * 2, 0, 1))
    # nose, moustache, smile
    nx = hx + fx * 1.08
    sc.ellipse(nx, hy + 14, 5 + 7 * abs(f), 8, fill="#2bbfaa", stroke=TEAL_D, w=2)
    sc.ellipse(hx + fx * 1.05, hy + 28, 26 * k + 6, 6, fill=BEARD, a=0.95)
    mw = 20 * k + 3; mx = hx + fx * 1.0
    pts = [(mx + x, hy + 36 + 13 * (1 - (x / mw) ** 2)) for x in [mw * (-1 + 2 * i / 10) for i in range(11)]]
    sc.poly([(mx - mw, hy + 36)] + pts[1:-1] + [(mx + mw, hy + 36)] + [(mx + mw * (1 - 2 * i / 10), hy + 36 - 1.5) for i in range(11)], fill="#ffffff", stroke="#1d2a2d", w=2)


def draw_body(sc, P, bob):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); x = P["x"]
    bw = lerp(46, 94, k2); top = 326 + bob
    sc.ellipse(x, FLOOR + 3, 52, 8, fill="#000000", a=0.45)
    sc.rrect(x - bw / 2, top, bw, 116, 22, fill=COAT, stroke=COAT_D, w=3)
    if k2 > 0.45:                                                                       # zip and toggles
        sc.line(x + f * 20, top + 24, x + f * 20, top + 112, 3, "#d8d3c8", a=k2)
        sc.circle(x + f * 20 + 12, top + 60, 4, fill="#8b6a3a", a=k2)
    sc.ellipse(x, top + 4, bw / 2 + 5, 15, fill=SCARF, stroke=SCARF_D, w=2)              # scarf
    sc.poly([(x - 10 * f, top + 6), (x + 22 + 8 * f, top + 12), (x + 26 + 8 * f, top + 60), (x + 6, top + 56)], fill=SCARF, stroke=SCARF_D, w=2, a=0.55 + 0.45 * k2)


def draw_legs(sc, P, bob):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); x = P["x"]; d = P["dir"]
    for s_, off in ((-1, 0.0), (1, math.pi)):
        hx = x + s_ * (6 + 9 * k2); hy = 436 + bob
        if P["walk"]:
            ph = x / 13.0 + off
            fx_ = hx + d * math.sin(ph) * 30; fy = FLOOR - max(0, math.cos(ph)) * 11
        elif P["mode"] == "dance":
            fx_ = hx + s_ * 5; fy = FLOOR - max(0, math.sin(P["du"] * math.pi / 0.4 + (0 if s_ < 0 else math.pi))) * 7
        else:
            fx_ = hx + s_ * 4 * k2; fy = FLOOR
        kx, ky = (hx + fx_) / 2 + d * 3, (hy + fy) / 2 - 2
        sc.pline([(hx, hy), (kx, ky), (fx_, fy - 6)], 13, LEG)
        sc.pline([(hx, hy), (kx, ky), (fx_, fy - 6)], 7, TUBE)
        sc.ellipse(fx_ + d * 6 * abs(f) * 0 + (d * 7 if abs(f) > 0.5 else 0), fy - 3, 15 if abs(f) > .5 else 12, 7, fill="#10161c", stroke=LEG, w=2)


def draw_arms_and_lens(sc, P, hx, hy, bob):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); x = P["x"]; d = P["dir"]
    sw = lerp(14, 44, k2); sy = 348 + bob
    sh = {-1: (x - sw, sy), 1: (x + sw, sy)}
    tgt = {}
    if P["mode"] == "dance":
        a = dance_hands(P["du"]); tgt[-1] = (sh[-1][0] + a[0], sh[-1][1] + a[1]); tgt[1] = (sh[1][0] + a[2], sh[1][1] + a[3])
    elif P["walk"]:
        sw_ = math.sin(x / 13.0); amp = 34 * abs(f)
        tgt[-1] = (sh[-1][0] + d * sw_ * amp, sh[-1][1] + 98); tgt[1] = (sh[1][0] - d * sw_ * amp, sh[1][1] + 98)
    else:
        r = HANDS["rest"]; tgt[-1] = (sh[-1][0] + r[0], sh[-1][1] + r[1]); tgt[1] = (sh[1][0] + r[2], sh[1][1] + r[3])
    lens = None
    if P["carry"]:
        mag = P["mag"]; R = lerp(30, 46, mag)
        ex, ey = hx + 26, hy - 2                                                         # the eye being magnified
        raised_c = (ex, ey); raised_h = (ex + 30, ey + 56)
        walk_h = tgt[1]; carry_c = (walk_h[0] + d * 6, walk_h[1] - 58)
        hnd = (lerp(walk_h[0], raised_h[0], mag), lerp(walk_h[1], raised_h[1], mag))
        cen = (lerp(carry_c[0], raised_c[0], mag), lerp(carry_c[1], raised_c[1], mag))
        tgt[1] = hnd; lens = (cen, R, hnd)
    for s_ in (-1, 1):
        e, h = ik(sh[s_][0], sh[s_][1], tgt[s_][0], tgt[s_][1], 64, s_)
        sc.pline([sh[s_], e, h], 13, COAT, a=1.0)
        sc.pline([sh[s_], e, h], 7, TUBE)
        sc.circle(h[0], h[1], 8, fill=TEAL, stroke=TEAL_D, w=2)
    if lens:
        (cx, cy), R, (hx_, hy_) = lens
        ux, uy = hx_ - cx, hy_ - cy; n = math.hypot(ux, uy) or 1; ux, uy = ux / n, uy / n
        sc.line(cx + ux * R, cy + uy * R, hx_, hy_, 11, BRASS)
        sc.circle(hx_, hy_, 8, fill=TEAL, stroke=TEAL_D, w=2)
        mag = P["mag"]
        if mag > 0.5:                                                                    # magnified eye
            g = clamp((mag - 0.5) * 2, 0, 1)
            sc.circle(cx, cy, R - 2, fill=SKIN)
            sc.pline([(cx - 27, cy - 34), (cx, cy - 38), (cx + 27, cy - 34)], 9, GLASS)
            ry = 19 * (1 - P["blink"]); gx, gy = P["gaze"]
            sc.ellipse(cx, cy + 2, 31, max(ry, 0.8), fill="#f4f1ea", stroke="#4a3b32", w=2)
            if P["blink"] < 0.75:
                ix, iy = cx + gx * 12, cy + 2 + gy * 6
                sc.circle(ix, iy, min(13, ry + 2), fill="#7fa793", stroke="#3d5d4e", w=2)
                sc.circle(ix, iy, 5.5, fill="#0d1a17"); sc.circle(ix - 4, iy - 4, 2.2, fill="#ffffff")
            sc.pline(arc(cx, cy + 2, 33, 20 * (1 - P["blink"]) + 1, 200, 340, 10), 4, "#4a3b32")
            for i in range(3): sc.line(cx + 33 + i * 1.5, cy + 4 + i * 6, cx + 40, cy - 4 + i * 8, 2, "#8d6a58", a=0.7)   # crow's feet
        sc.circle(cx, cy, R, fill="#cfeff0", a=0.10 + 0.05 * (1 - mag))
        sc.circle(cx, cy, R, stroke=BRASS, w=6)
        sc.pline(arc(cx, cy, R - 9, R - 9, 205, 250, 6), 3, "#ffffff", a=0.55)


def build(t):
    P = pose(t); sc = Scene()
    sc.rrect(0, FLOOR - 4, W, H - FLOOR + 4, 0, fill=FLOORC)
    sc.line(0, FLOOR - 4, W, FLOOR - 4, 2, "#1a232d")
    draw_spot(sc, P, t)
    if P["mode"] == "gap" or P["x"] < -180 or P["x"] > 660: return sc, P
    if P["walk"]: bob = -abs(math.sin(P["x"] / 13.0)) * 5
    elif P["mode"] == "dance": bob = -abs(math.sin(P["du"] * math.pi / 0.4)) * 5
    else: bob = 0
    sway = math.sin(P["du"] * math.pi / 0.8) * 3 if P["mode"] == "dance" else 0
    P = dict(P); P["x"] += sway
    hx, hy = P["x"] + P["f"] * 8, 262 + bob
    draw_legs(sc, P, bob); draw_body(sc, P, bob); draw_head(sc, P, hx, hy); draw_arms_and_lens(sc, P, hx, hy, bob)
    return sc, P


# ---- outputs --------------------------------------------------------------------------
def render_png(t, path):
    sc, _ = build(t); mvg = path + ".mvg"
    open(mvg, "w").write(sc.mvg())
    subprocess.run(["convert", "-size", "%dx%d" % (W, H), "xc:" + BG, "-draw", "@" + mvg, path], check=True)
    os.remove(mvg)


def write_html(out):
    frames, caps = [], []
    n = int(T * FPS)
    for i in range(n):
        sc, P = build(i / FPS); frames.append(sc.svg()); caps.append(P["cap"])
    page = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "player.tpl.html")).read()
    page = page.replace("/*FRAMES*/[]", json.dumps(frames, separators=(",", ":"))).replace("/*CAPS*/[]", json.dumps(caps)).replace("/*FPS*/15", str(FPS))
    open(out, "w").write(page)
    print("wrote", out, "frames", n, "bytes", os.path.getsize(out))


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "html": write_html(sys.argv[2])
    elif cmd == "png":
        os.makedirs(sys.argv[2], exist_ok=True)
        for t in sys.argv[3:]: render_png(float(t), os.path.join(sys.argv[2], "t%05.2f.png" % float(t)))
    elif cmd == "styles":
        os.makedirs(sys.argv[2], exist_ok=True)
        for st in ("none", "outline", "accent", "flat", "tint"):
            STYLE = None if st == "none" else st
            for t in (2.0, 5.4, 24.0): render_png(t, os.path.join(sys.argv[2], "%s_%04.1f.png" % (st, t)))
    elif cmd == "frames":
        os.makedirs(sys.argv[2], exist_ok=True)
        for i in range(int(T * FPS)): render_png(i / FPS, os.path.join(sys.argv[2], "f%04d.png" % i))
