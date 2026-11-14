#!/usr/bin/env python3
"""Mock-ups for the "Daddy Bot" boot logo (replaces the Qin logo): the boot-animation character with DADDY / BOT in a retro font over its body.
  make_logo.py OUTDIR FONTDIR [--params ../bootanimation/settings-draft8.json]
Fonts are open-licence Google Fonts (OFL) downloaded into FONTDIR. Needs ImageMagick (convert/montage)."""
import json, os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bootanimation"))
import gen

out, fonts = sys.argv[1], sys.argv[2]
params = sys.argv[sys.argv.index("--params") + 1] if "--params" in sys.argv else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bootanimation", "settings-draft8.json")
gen.set_params(json.load(open(params))); gen.STYLE = "param"
os.makedirs(out, exist_ok=True)

# a calm front pose: arms at rest, no magnifier
P0 = dict(x=240, f=0, dir=-1, walk=0, mode="look", du=None, mag=0, carry=False, gaze=(0, 0), blink=0, spot=1, cap="")
gen.pose = lambda t: dict(P0)
sc, _ = gen.build(0)
S, TY = 1.35, -112                                             # scale the character up; feet near the bottom, head near the top
lines = sc.mvg().split("\n")
mvg = "\n".join(lines[:3] + ["translate %.1f,%.1f" % (240 - 240 * S, TY), "scale %.3f,%.3f" % (S, S)] + lines[3:])
open(os.path.join(out, "base.mvg"), "w").write(mvg)
base = os.path.join(out, "base.png")
subprocess.run(["convert", "-size", "480x640", "xc:" + gen.Q["bg"], "-draw", "@" + os.path.join(out, "base.mvg"), base], check=True)

def text_layer(path, font, size, fill, stroke, sw, y1, y2, dy=0):
    """DADDY and BOT as two centred lines on a transparent 480x640 canvas; each line is auto-fitted (caption:) into its own box so it never leaves the screen"""
    boxes = (("DADDY", 404, 104, 300 + dy), ("BOT", 330, 136, 418 + dy))
    cmd = ["convert", "-size", "480x640", "xc:none"]
    for word, w, h, y in boxes:
        cmd += ["(", "-size", "%dx%d" % (w - 2 * sw, h - 2 * sw), "-background", "none", "-font", os.path.join(fonts, font), "-fill", fill, "-stroke", stroke, "-strokewidth", str(sw),
                "-gravity", "center", "caption:" + word, "-bordercolor", "none", "-border", str(sw), ")", "-gravity", "North", "-geometry", "+%d+%d" % (0, y), "-composite"]
    cmd.append(path)
    subprocess.run(cmd, check=True)

V = [  # name, font, sizes (line1, line2), fill, stroke, strokewidth, y1, y2, extra: shadow colour
    ("A-arcade-white",   "PressStart2P-Regular.ttf", (60, 96),  "none", "#ffffff", 3, 300, 395, None),
    ("B-pixel-orange",   "Silkscreen-Bold.ttf",      (104, 150), "none", "#ff8a3d", 4, 285, 395, None),
    ("C-neon-green",     "Monoton-Regular.ttf",      (118, 168), "#3ddc84", "#3ddc84", 1, 285, 405, None),
    ("D-poster-shadow",  "Bungee-Regular.ttf",       (112, 150), "none", "#ffffff", 4, 290, 405, "#ff8a3d"),
    ("E-sci-fi-teal",    "Orbitron[wght].ttf",       (112, 160), "none", "#35e0c8", 4, 290, 405, None),
    ("F-retro-future",   "Audiowide-Regular.ttf",    (118, 170), "none", "#f4f1ea", 4, 285, 400, "#3ddc84"),
]
names = []
for name, font, size, fill, stroke, sw, y1, y2, shadow in V:
    lay = os.path.join(out, name + "-text.png"); text_layer(lay, font, size, fill, stroke, sw, y1, y2)
    comp = os.path.join(out, name + ".png")
    cmd = ["convert", base]
    if shadow:                                                  # hard offset shadow of the same lettering
        sh = os.path.join(out, name + "-shadow.png"); text_layer(sh, font, size, "none", shadow, sw, y1, y2, dy=7)
        cmd += [sh, "-composite"]
    cmd += [lay, "-composite", comp]
    subprocess.run(cmd, check=True); names.append(comp)
sheet = os.path.join(out, "daddybot-options.png")
subprocess.run(["montage"] + names + ["-tile", "3x2", "-geometry", "400x533+8+8", "-background", "#1a1a1a", "-label", "%t", sheet], check=True)
print("wrote", sheet)
