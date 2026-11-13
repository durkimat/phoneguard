#!/usr/bin/env python3
"""Build configurator.html (one self-contained file) from configurator.tpl.html + rig.js."""
import os, re
here = os.path.dirname(os.path.abspath(__file__))
tpl = open(os.path.join(here, "configurator.tpl.html")).read()
rig = open(os.path.join(here, "rig.js")).read()
assert "</script>" not in rig
out = tpl.replace("/*RIG*/", rig)
dst = os.path.join(here, "mockup-tweaker.html")
open(dst, "w").write(out)
print("wrote", dst, len(out), "bytes")
# syntax check of both inline scripts when a JS engine is available
try:
    import quickjs
    ctx = quickjs.Context()
    for i, m in enumerate(re.findall(r"<script>(.*?)</script>", out, re.S)):
        ctx.eval("(function(){" + m + "\n})"); print("script", i, "parses")
except ImportError:
    print("(quickjs not installed: syntax not checked)")
