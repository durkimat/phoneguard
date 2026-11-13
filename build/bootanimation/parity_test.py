#!/usr/bin/env python3
"""Check rig.js (browser) draws exactly what gen.py (phone frames) draws. Needs: pip install quickjs."""
import json, math, sys, os
import quickjs
import gen

here = os.path.dirname(os.path.abspath(__file__))
ctx = quickjs.Context()
ctx.eval(open(os.path.join(here, "rig.js")).read().replace("if (typeof module", "if (false && typeof module"))
SETS = [{}, dict(lensR=18, lensW=1.2, lensH=46, tufts=28, tuftH=1.4, lean=0.4, hairline=0.8, beard=False, moustache=False, coatLen=160, flare=50,
                 collarH=1.6, collarW=22, buttons=3, pockets=False, headN=2.4, headW=70, headH=64, noseSize=1.4, mouthW=20, smileD=10, bg="#101820", skin="#c68e6b",
                 coatCol="#556b2f", scarfCol="#2255cc", hairCol="#5a3b22", irisCol="#3366aa", spot=False)]
TIMES = [0.5, 2.0, 4.5, 5.4, 6.2, 8.0, 10.6, 13.0, 16.5, 19.0, 21.5, 22.6, 24.0, 26.0, 28.0, 29.2, 30.5, 33.0]
bad = 0
for Qs in SETS:
    gen.STYLE = "param"; gen.set_params(Qs)
    for t in TIMES:
        js = json.loads(ctx.eval("JSON.stringify(RIG.ops(%r, %s))" % (t, json.dumps(Qs))))
        sc, _ = gen.build(t); py = sc.final()
        if len(js) != len(py): print("t=%.1f op count js=%d py=%d" % (t, len(js), len(py))); bad += 1; continue
        for i, (j, p) in enumerate(zip(js, py)):
            kind, pts, fill, stroke, w, a = j; pk, pp, pf, ps, pw, pa, _ = p
            flat = lambda v: [c for q in v for c in (q if isinstance(q, (list, tuple)) else [q])]
            ok = (kind == pk and fill == pf and stroke == ps and abs(w - pw) < 1e-6 and abs(a - pa) < 1e-6 and
                  len(flat(pts)) == len(flat(pp)) and all(abs(x - y) < 1e-6 for x, y in zip(flat(pts), flat(pp))))
            if not ok:
                print("t=%.1f op %d differs\n js=%s\n py=%s" % (t, i, j, p)); bad += 1; break
print("PARITY OK" if not bad else "FAILURES: %d" % bad)
sys.exit(1 if bad else 0)
