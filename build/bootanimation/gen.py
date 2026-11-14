#!/usr/bin/env python3
"""Boot animation mock-up generator (480x640, 15 fps, 35 s loop).

One scene description, two outputs:
  * SVG strings  -> mockup.html (browser player)
  * MVG text     -> ImageMagick `convert` -> PNG (checking now; final bootanimation frames later)

Usage:
  gen.py html OUT.html          write the player
  gen.py png  OUTDIR T [T...]   render PNGs of the given times (seconds)
  gen.py frames OUTDIR          render every frame as PNG (final pipeline)
Add `--params settings.json` (the text from the browser tweaker) to any command to use those look settings.
"""
import math, os, subprocess, sys, json

W, H, FLOOR, FPS, T = 480, 640, 520, 15, 35.0

# ---- palette ---------------------------------------------------------------
BG = "#070a0f"; FLOORC = "#0d131a"
TEAL = "#35d6c0"; TEAL_D = "#0e6e63"
COAT = "#8a4a28"; COAT_D = "#5c2f18"; SCARF = "#f26a1b"; SCARF_D = "#b84a0d"
HAIR = "#a7a59e"; BEARD = "#7a7266"; GLASS = "#17171a"; EARBUD = "#2b3f95"
LEG = "#232a32"; SKIN = "#dcae92"; BRASS = "#6b4b2a"
FEAT = "#8a5a3c"; BUTTON = "#3a2212"; SMILE = "#ffffff"; LENS = "#fefefe"; EYEDOT = "#14323a"; BROW = HAIR


# every look setting lives here; the browser configurator uses the same names and defaults
PARAMS = dict(
    headW=64, headH=70, headN=3.4, taper=0.22,
    lensW=1.0, lensH=40, lensR=8, frameW=5, glassCol="#1b1b1f",
    hairCol="#a7a59e", tufts=22, tuftH=1.0, lean=-0.35, hairline=1.0,
    skin="#f0c9a6", beard=True, beardAmt=0.25, beardCol="#7a7266", beardOpen=24, moustache=True, mouthW=14, smileD=7, noseSize=1.0, eyeDot=3.6,
    coatCol="#8a4a28", coatLen=138, flare=34, collarH=1.0, collarW=0, buttons=4, pockets=True, scarfCol="#f26a1b",
    irisCol="#6f8f45", ringCol="#a9792f", magR=46,
    lineCol="#3a2a20", legCol="#2a3340", bg="#070a0f", spot=True, spotAmt=1.0, fadeSec=1.0)
Q = dict(PARAMS)


def set_params(d):
    global Q
    Q = dict(PARAMS); Q.update({k: v for k, v in d.items() if k in PARAMS}); return Q


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
               '<rect width="%d" height="%d" fill="%s"/>' % (W, H, Q["bg"])]
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
        o = ["viewbox 0 0 %d %d" % (W, H), "fill %s" % Q["bg"], "rectangle 0,0 %d,%d" % (W, H), "stroke-linecap round", "stroke-linejoin round"]
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
TUBE = "#abcdef"; TUBEA = "#abcdee"
def final_style(line, face, glass, eye, lens=("#ffffff", 0.45), legfill=None, brow="#8d8a83", feat=FEAT, hair=None):
    st = dict(minw=3, tube=True, tubea=COAT, tubec=legfill or BG, dot=GREEN, opaque=True, drop_shine=True,
        fill={TEAL: face, LEG: legfill, "#10161c": legfill, "#2bbfaa": face, BRASS: None, LENS: lens, EYEDOT: eye},
        stroke={TEAL_D: line, GLASS: glass, HAIR: brow, BRASS: line, COAT_D: line, SCARF_D: line, LEG: line, COAT: line, "#c98a5a": "#c98a5a", "#e2b27c": "#e2b27c",
                FEAT: feat, "#8d6a58": line, "#4a3b32": "#4a3b32", "#3d5d4e": "#3d5d4e", "#1d2a2d": feat, "#3f4f22": "#3f4f22"},
        fillstroke={TEAL: line, LEG: line, "#10161c": line, "#2bbfaa": line, EARBUD: line, COAT: line, SCARF: line, HAIR: line})
    if hair: st["fill"][HAIR] = hair
    return st


STYLES["skin"] = final_style(GREEN, "#f0c9a6", "#1b1b1f", "#22342b")                                   # green line art, skin-tone face
STYLES["greenface"] = final_style("#1a7a49", GREEN, "#1b1b1f", "#073042", lens=("#ffffff", 0.9), feat="#0a4a2c")   # Android-green face
STYLES["flat"] = final_style("#3a2a20", "#f0c9a6", "#1b1b1f", "#22342b", legfill="#2a3340")                     # flat illustration, brown ink
STYLES["final"] = STYLES["skin"]
STYLE = "param"   # set by the caller; None = the original full-colour look


def param_style():
    st = final_style(Q["lineCol"], Q["skin"], Q["glassCol"], "#22342b", legfill=Q["legCol"])
    st["recolor"] = {COAT: Q["coatCol"], SCARF: Q["scarfCol"], HAIR: Q["hairCol"], BEARD: Q["beardCol"]}
    st["tubea"] = Q["coatCol"]
    return st


def restyle(op):
    st = (param_style() if STYLE == "param" else STYLES.get(STYLE)) if STYLE else None
    kind, p, fill, stroke, w, a, cap = op
    if stroke == TUBEA:                                                                  # sleeve core
        return (kind, p, None, st["tubea"] if st and st.get("tubea") else BG, w, 1.0, cap) if st and st.get("tube") else None
    if stroke == TUBE:                                                                   # inner core of an outlined limb
        return (kind, p, None, st.get("tubec", BG), w, 1.0, cap) if st and st.get("tube") else None
    if not st: return op
    if fill == "#fff3d0" or fill == "#000000" or fill == "#cfeff0": return op            # spotlight, shadow, glass tint
    if fill == WHITE and a < 0.3: return None if st["drop_shine"] else op                 # shine / lens tint
    tiny = kind == "ellipse" and p[2] < 3 and fill and not stroke
    if tiny:
        c = st["fill"].get(fill, fill) or st["dot"]
        c = st.get("recolor", {}).get(c, c)
        return (kind, p, c if fill != WHITE else WHITE, None, 0, 1.0, cap)
    nf, ns, nw, na = fill, stroke, w, a
    if fill:
        nf = st["fill"][fill] if fill in st["fill"] else fill
        if isinstance(nf, tuple): nf, na = nf
        elif nf is None: nf = Q["bg"]
        elif "fillalpha" in st and fill in st["fill"]: na = st["fillalpha"]
        if not stroke and fill in st["fillstroke"]: ns, nw = st["fillstroke"][fill], st["minw"]
    if stroke:
        ns = st["stroke"].get(stroke, stroke); nw = max(w, st["minw"])
    if st["opaque"] and fill in st["fill"] and na < 1 and "fillalpha" not in st: na = 1.0
    if stroke and fill is None and kind in ("line", "pline"):
        na = a
    nf = st.get("recolor", {}).get(nf, nf)
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
    if P["spot"] <= 0 or not Q["spot"]: return
    sx = clamp(pose(t - 0.15)["x"], -60, 540); k = P["spot"] * Q["spotAmt"]
    for i in range(5):
        a, b = 8 + i * 3, 66 + i * 15
        sc.poly([(sx - a, -10), (sx + a, -10), (sx + b, FLOOR + 4), (sx - b, FLOOR + 4)], fill="#fff3d0", a=0.05 * k)
    for i in range(5):
        sc.ellipse(sx, FLOOR + 4, 150 - i * 24, 27 - i * 4, fill="#fff3d0", a=0.08 * k)


def contour(cx, cy, rx, ry, th):
    n, taper = Q["headN"], Q["taper"]
    c, s_ = math.cos(math.radians(th)), math.sin(math.radians(th))
    x = rx * math.copysign(abs(c) ** (2 / n), c) * (1 - taper * max(0, s_) ** 2)
    y = ry * math.copysign(abs(s_) ** (2 / n), s_)
    return cx + x, cy + y


def head_geo(P, hx, hy):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); k = 0.15 + 0.85 * k2
    return dict(f=f, k2=k2, k=k, fx=f * 30, sgn=1 if f > 0 else -1, rx=Q["headW"] * (0.56 + 0.44 * k2), ry=Q["headH"])


def resample(pts, n):
    d = [0.0]
    for i in range(1, len(pts)): d.append(d[-1] + math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]))
    tot = d[-1] or 1.0; out = []; j = 0
    for i in range(n):
        tg = tot * i / (n - 1)
        while j < len(pts) - 2 and d[j + 1] < tg: j += 1
        sg = (d[j + 1] - d[j]) or 1.0; u = (tg - d[j]) / sg
        out.append((lerp(pts[j][0], pts[j + 1][0], u), lerp(pts[j][1], pts[j + 1][1], u)))
    return out


def mix(A, B, u): return [(lerp(a[0], b[0], u), lerp(a[1], b[1], u)) for a, b in zip(A, B)]


def draw_head(sc, P, hx, hy):
    """One head that turns continuously: outline, hair and stubble morph between the front and side views;
    glasses, ears, nose and mouth are placed by rotating them round the head (yaw)."""
    f = P["f"]; p = abs(f); sgn = 1 if f >= 0 else -1; th = p * math.pi / 2; S, Cc = math.sin(th), math.cos(th)
    rx, ry = Q["headW"], Q["headH"]; D = rx * 0.98; N = int(Q["tufts"]); hl = Q["hairline"]
    pL = lambda u, v: (hx + u * D, hy + v * ry)
    mir = lambda q: (hx + sgn * (q[0] - hx), q[1])
    # --- silhouette: front outline <-> profile outline, matched point by point
    f1 = [contour(hx, hy, rx, ry, t) for t in range(270, 451, 6)]
    f2 = [contour(hx, hy, rx, ry, t) for t in range(90, 271, 6)]
    face = [pL(0, -1), pL(.3, -.97), pL(.62, -.80), pL(.78, -.58), pL(.76, -.22), pL(1.12, .23), pL(.88, .28), pL(.92, .39), pL(.87, .50), pL(.90, .60), pL(.86, .84), pL(.62, 1.0)]
    backs = [pL(.62, 1.0)] + [contour(hx, hy, D, ry, t) for t in range(100, 271, 8)]
    if sgn > 0: p1, p2 = face, backs
    else: p1, p2 = [mir(q) for q in backs][::-1], [mir(q) for q in face][::-1]
    K = 60
    sil = mix(resample(f1, K) + resample(f2, K), resample(p1, K) + resample(p2, K), p)
    sc.poly(sil, fill=TEAL, stroke=TEAL_D, w=3)
    # --- ears with the earbud seated inside (the ear turning away goes behind the head)
    Re = rx + 3
    for phi in (-95, 95):
        ang = th + math.radians(phi)
        if math.cos(ang) < -0.1: continue
        ex = hx + sgn * Re * math.sin(ang)
        sc.ellipse(ex, hy + 6, 8, 12, fill=TEAL, stroke=TEAL_D, w=2)
        sc.rrect(ex - 3, hy + 3, 6, 10, 3, fill=EARBUD)
    # --- stubble: a band along the lower part of the outline
    if Q["beard"]:
        run = [i for i, q in enumerate(sil) if q[1] > hy + 34]
        if len(run) > 3:
            outer = [sil[i] for i in range(run[0], run[-1] + 1)]
            off = max(2.0, 36 - 0.9 * Q["beardOpen"]); inner = []
            for q in outer:
                dx, dy = hx - q[0], hy - q[1]; d_ = math.hypot(dx, dy) or 1.0
                inner.append((q[0] + dx / d_ * off, q[1] + dy / d_ * off))
            amt = Q["beardAmt"]
            sc.poly(outer + inner[::-1], fill=BEARD, a=0.10 + 0.6 * amt)
            n_ = int(6 + 14 * amt)
            for j in range(n_):
                r = (len(outer) - 1) * j / max(n_ - 1, 1); i0 = min(int(r), len(outer) - 2); fr = r - i0
                w_ = 0.3 if j % 2 else 0.65
                ox, oy = lerp(outer[i0][0], outer[i0 + 1][0], fr), lerp(outer[i0][1], outer[i0 + 1][1], fr)
                ix, iy = lerp(inner[i0][0], inner[i0 + 1][0], fr), lerp(inner[i0][1], inner[i0 + 1][1], fr)
                sc.circle(lerp(ox, ix, w_), lerp(oy, iy, w_), 1.5, fill=BEARD, a=0.8)
    # --- moustache, nose, mouth
    if Q["moustache"]:
        sc.ellipse(hx + sgn * 0.85 * D * S, hy + 27, 28 * Cc + 11 * S, 5.5, fill=BEARD, a=0.25 + 0.6 * Q["beardAmt"])
    if p < 0.5:
        nx = hx + sgn * 0.9 * D * S; ns_ = Q["noseSize"]
        sc.pline([(nx - 2 * ns_, hy - 4), (nx - (5 + 2 * p * 4) * ns_, hy + 13 * ns_ - (ns_ - 1) * 4), (nx + (5 + 9 * p) * ns_, hy + 16 * ns_ - (ns_ - 1) * 4)], 3, FEAT)
    mw = (Q["mouthW"] + 3) * Cc + 7 * S; mx = hx + sgn * 0.86 * D * S; md = Q["smileD"] * Cc + 2.5 * S
    top = [(mx - mw + 2 * mw * i / 10, hy + 36) for i in range(11)]
    bot = [(mx - mw + 2 * mw * i / 10, hy + 36 + md * (1 - ((i - 5) / 5) ** 2)) for i in range(11)]
    sc.poly(top + bot[::-1], fill=SMILE, stroke=FEAT, w=2.5)
    # --- glasses
    lh = Q["lensH"]; ey = hy - 12; lw0 = 54 * Q["lensW"]; offs0 = lw0 / 2 + 4
    Rl = max(rx * 1.0, offs0 + 6); phl = math.asin(min(offs0 / Rl, 0.99)); lw = max(lw0 * Cc, 6)
    centres = {}
    for s_ in (-1, 1):
        ang = th + s_ * phl
        if math.cos(ang) < 0.1: continue
        cx = hx + sgn * Rl * math.sin(ang); centres[s_] = cx
        sc.rrect(cx - lw / 2, ey - lh / 2, lw, lh, min(Q["lensR"], lw / 2, lh / 2), fill=LENS, stroke=GLASS, w=Q["frameW"])
        gx, gy = P["gaze"] if P["mode"] == "look" else (0, 0)
        if lw > 14 or p > 0.5: sc.circle(cx + gx * 3 * Cc + sgn * S * 2, ey + gy * 2, Q["eyeDot"], fill=EYEDOT)
        wb = max(lw, 16)
        sc.line(cx - wb / 2 + 1, ey - lh / 2 - 9, cx + wb / 2 - 1, ey - lh / 2 - 12 - (2 if s_ == 1 else 0), 4.5, BROW)
    if Cc > 0.35 and len(centres) == 2:
        bxm = hx + sgn * Rl * S
        sc.line(bxm - 5 * Cc, ey - 8, bxm + 5 * Cc, ey - 8, Q["frameW"], GLASS, a=clamp((Cc - .35) * 3, 0, 1))
    if p > 0.35:
        for s_, cx in centres.items():
            if s_ == -1 or len(centres) == 1:
                ear_x = hx + sgn * Re * math.sin(th - math.radians(95))
                sc.line(cx - sgn * lw / 2, ey - 4, ear_x, hy - 8, max(Q["frameW"] * 0.8, 3), GLASS, a=clamp((p - .35) * 2, 0, 1))
    # --- hair: tufts + hairline, front outline <-> side outline
    def tuft(front, i):
        c0, c1 = (188, 352) if front else (160, 320); t_ = c0 + (c1 - c0) * i / N
        bp = contour(hx, hy, (rx if front else D) + 1, ry + 1, t_)
        a = math.radians(t_); c, s_ = math.cos(a), math.sin(a)
        cl = (0.22 <= i / N <= 0.56) if front else (0.55 <= i / N <= 0.9)
        ht = ((6 if i % 2 == 0 else 20 + 12 * abs(math.sin(i * 1.9))) + (8 if cl else 0)) * Q["tuftH"]; lean = Q["lean"]
        return ((bp[0] + (c + lean * (-s_)) * ht * 0.9, bp[1] + (s_ + lean * c) * ht) if i % 2 else (bp[0] + c * ht * 0.4, bp[1] + s_ * ht * 0.4))
    ft = [tuft(True, i) for i in range(N + 1)]
    w_ = rx * 0.98
    fh = [(hx + w_ * 0.97, hy - 2), (hx + w_ * 0.86, hy - 30 * hl), (hx + w_ * 0.55, hy - 46 * hl), (hx + w_ * 0.2, hy - 50 * hl),
          (hx - w_ * 0.2, hy - 52 * hl), (hx - w_ * 0.55, hy - 47 * hl), (hx - w_ * 0.86, hy - 30 * hl), (hx - w_ * 0.97, hy - 2)]
    pt_ = [tuft(False, i) for i in range(N + 1)]
    ph_ = [pL(.74, -.62 * hl), pL(.55, -.66 * hl), pL(.30, -.60 * hl), pL(.05, -.46 * hl), pL(-.22, -.28), pL(-.42, -.08), pL(-.52, .08), pL(-.56, .20)]
    if sgn < 0: pt_ = [mir(q) for q in pt_][::-1]; ph_ = [mir(q) for q in ph_][::-1]
    sc.poly(mix(ft + fh, pt_ + ph_, p), fill=HAIR, stroke=TEAL_D, w=3)


def coat_geo(P, bob):
    f = P["f"]; k2 = math.sqrt(max(0, 1 - f * f)); x = P["x"]
    return dict(f=f, k2=k2, x=x, bw=lerp(52, 102, k2), top=328 + bob, sway=(P["dir"] * math.sin(x / 13.0) * 12 if P["walk"] else 0))


def draw_coat(sc, P, bob):
    g = coat_geo(P, bob); f, k2, x, bw, top, sway = g["f"], g["k2"], g["x"], g["bw"], g["top"], g["sway"]
    sc.ellipse(x, FLOOR + 3, 52, 8, fill="#000000", a=0.45)
    L_ = Q["coatLen"]; hemw = bw / 2 + Q["flare"] * (0.35 + 0.65 * k2)
    xl, xr = x - hemw - sway * 0.6, x + hemw - sway * 0.6                              # hem trails the walking direction
    left = [(x - bw / 2 + 16, top), (x - bw / 2, top + 20), (x - bw / 2 - 3, top + 68), (xl, top + L_)]
    right = [(xr, top + L_), (x + bw / 2 + 3, top + 68), (x + bw / 2, top + 20), (x + bw / 2 - 16, top)]
    hem = [(lerp(xl, xr, i / 10), top + L_ + 9 * math.sin(math.pi * i / 10) + sway * 0.35 * (i / 10 - 0.5) * 2) for i in range(1, 10)]
    sc.poly(left + hem + right, fill=COAT, stroke=COAT_D, w=3)
    if k2 > 0.4:                                                                         # front opening, buttons, pockets
        cxx = x + f * 22
        sc.line(cxx, top + 16, cxx + sway * 0.1, top + L_ + 2, 2.5, "#c98a5a", a=k2)
        nb = int(Q["buttons"])
        for i in range(nb):
            by = top + 34 + i * ((L_ - 57) / max(nb - 1, 1))
            sc.circle(cxx, by, 6.5, fill=BUTTON, stroke="#e2b27c", w=2, a=k2)
            sc.circle(cxx, by, 1.6, fill="#e2b27c", a=k2)
        for s_ in (-1, 1):
            if Q["pockets"]: sc.rrect(x + s_ * bw * 0.3 - 11, top + 84, 22, 20, 5, stroke="#c98a5a", w=2, a=k2)


def draw_collar(sc, P, bob, hx, hy, rx):
    g = coat_geo(P, bob); k2, x, bw, top = g["k2"], g["x"], g["bw"], g["top"]
    for s_ in (-1, 1):
        if k2 < 0.3 and s_ == -(1 if P["f"] > 0 else -1): continue
        up = 60 * (Q["collarH"] - 1); cw = Q["collarW"]
        wing = [(x + s_ * 12, top - 16), (x + s_ * (bw / 2 + 8 + cw * 0.5), top + 8), (x + s_ * (rx + 18 + cw), hy + 8 - up), (x + s_ * (rx + 8 + cw), hy - 16 - up), (x + s_ * (rx - 12), hy - 2 - up * 0.6)]
        sc.poly(wing, fill=COAT, stroke=COAT_D, w=3)
        bx_, by_ = x + s_ * (bw / 2 - 2), top + 4; tx_, ty_ = x + s_ * (rx + 8 + cw), hy - 16 - up
        sc.pline([(bx_, by_), (bx_ + (tx_ - bx_) * 0.85, by_ + (ty_ - by_) * 0.85)], 2.5, "#c98a5a", a=0.8)


def draw_scarf(sc, P, bob):
    g = coat_geo(P, bob); f, k2, x, bw, top = g["f"], g["k2"], g["x"], g["bw"], g["top"]
    sc.ellipse(x, top + 4, bw / 2 + 5, 15, fill=SCARF, stroke=SCARF_D, w=2)
    sc.poly([(x - 10 * f, top + 6), (x + 22 + 8 * f, top + 12), (x + 26 + 8 * f, top + 70), (x + 6, top + 64)], fill=SCARF, stroke=SCARF_D, w=2, a=0.55 + 0.45 * k2)


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
        mag = P["mag"]; R = lerp(Q["magR"] * 30 / 46, Q["magR"], mag)
        ex, ey = hx + 32, hy - 12                                                        # the eye being magnified
        raised_c = (ex, ey); raised_h = (ex + 30, ey + 56)
        walk_h = tgt[1]; carry_c = (walk_h[0] + d * 6, walk_h[1] - 58)
        hnd = (lerp(walk_h[0], raised_h[0], mag), lerp(walk_h[1], raised_h[1], mag))
        cen = (lerp(carry_c[0], raised_c[0], mag), lerp(carry_c[1], raised_c[1], mag))
        tgt[1] = hnd; lens = (cen, R, hnd)
    for s_ in (-1, 1):
        e, h = ik(sh[s_][0], sh[s_][1], tgt[s_][0], tgt[s_][1], 64, s_)
        sc.pline([sh[s_], e, h], 13, COAT, a=1.0)
        sc.pline([sh[s_], e, h], 7, TUBEA)
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
                sc.circle(ix, iy, min(13, ry + 2), fill=Q["irisCol"], stroke="#3f4f22", w=2)
                sc.circle(ix, iy, min(8.5, ry), fill=Q["ringCol"], a=0.85)
                sc.circle(ix, iy, 5.2, fill="#0d1a17"); sc.circle(ix - 4, iy - 4, 2.2, fill="#ffffff")
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
    hx, hy = P["x"] + P["f"] * 8, 320 - Q["headH"] + bob
    g_ = head_geo(P, hx, hy)
    draw_legs(sc, P, bob); draw_coat(sc, P, bob); draw_collar(sc, P, bob, hx, hy, g_['rx']); draw_scarf(sc, P, bob); draw_head(sc, P, hx, hy); draw_arms_and_lens(sc, P, hx, hy, bob)
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
    if "--params" in sys.argv:                                  # settings copied from the browser tweaker
        i = sys.argv.index("--params"); set_params(json.load(open(sys.argv[i + 1]))); del sys.argv[i:i + 2]
    STYLE = "param"
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
    elif cmd == "bootzip":
        import zipfile, tempfile, shutil
        out = sys.argv[2]; w_, h_ = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (W, H)
        tmp = tempfile.mkdtemp(prefix="bootanim-"); n = int(T * FPS)
        for i in range(n):
            png = os.path.join(tmp, "%04d.png" % i); render_png(i / FPS, png)
            subprocess.run(["convert", png, "-resize", "%dx%d!" % (w_, h_), "-colors", "128", "-dither", "None", "PNG8:" + png], check=True)
        fade = max(1, int(round(Q["fadeSec"] * FPS)))
        desc = "%d %d %d\nf 0 0 part0 %d %s\n" % (w_, h_, FPS, fade, Q["bg"])
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:                             # Android needs the entries stored, not compressed
            z.writestr("desc.txt", desc)
            for i in range(n): z.write(os.path.join(tmp, "%04d.png" % i), "part0/%04d.png" % i)
        shutil.rmtree(tmp); print("wrote", out, os.path.getsize(out), "bytes,", n, "frames; desc.txt:\n" + desc)
    elif cmd == "bootzip2":
        # Short boot animation for the real boot (docs/boot-animation.md): part0 plays once (~11.6 s: both sequences compressed), part1 loops the
        # magnifier look-around (6 s, starts and ends on the same pose) until Android is ready, then fades.
        import zipfile, tempfile, shutil
        out = sys.argv[2]; w_, h_ = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (W, H)
        SEG = [(0.0, 4.8, 2.0), (4.8, 11.2, 1.6), (11.2, 16.0, 2.0), (17.0, 22.6, 2.0)]   # original-time ranges and playback speed
        intro = []
        for a, b, sp in SEG:
            n_ = int(round((b - a) / sp * FPS))
            intro += [a + (b - a) * k / n_ for k in range(n_)]
        loop = [22.6 + 6.0 * k / (6 * FPS) for k in range(6 * FPS)]
        tmp = tempfile.mkdtemp(prefix="bootanim2-")
        def make(tlist, part):
            os.makedirs(os.path.join(tmp, part))
            for i, t in enumerate(tlist):
                png = os.path.join(tmp, part, "%04d.png" % i); render_png(t, png)
                subprocess.run(["convert", png, "-resize", "%dx%d!" % (w_, h_), "-colors", "128", "-dither", "None", "PNG8:" + png], check=True)
        make(intro, "part0"); make(loop, "part1")
        fade = max(1, int(round(Q["fadeSec"] * FPS)))
        desc = "%d %d %d\np 1 0 part0\nf 0 0 part1 %d %s\n" % (w_, h_, FPS, fade, Q["bg"])
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
            z.writestr("desc.txt", desc)
            for part, tl in (("part0", intro), ("part1", loop)):
                for i in range(len(tl)): z.write(os.path.join(tmp, part, "%04d.png" % i), "%s/%04d.png" % (part, i))
        shutil.rmtree(tmp); print("wrote", out, os.path.getsize(out), "bytes; intro frames", len(intro), "loop frames", len(loop), "\n" + desc)
    elif cmd == "frames":
        os.makedirs(sys.argv[2], exist_ok=True)
        for i in range(int(T * FPS)): render_png(i / FPS, os.path.join(sys.argv[2], "f%04d.png" % i))
