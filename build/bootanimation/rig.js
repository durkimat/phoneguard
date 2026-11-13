/* Boot animation character rig: JavaScript port of gen.py (same maths, same parameter names).
   RIG.defaults        the look settings
   RIG.pose(t)         timeline state
   RIG.ops(t, Q)       final drawing operations (after styling) for time t
   RIG.svg(t, Q)       the same as an SVG string
   Keep in sync with gen.py: tests/parity (quickjs vs python) compares RIG.ops with gen.build(t).final(). */
const RIG = (() => {
  const W = 480, H = 640, FLOOR = 520, T = 35.0;
  const C = { TEAL: "#35d6c0", TEAL_D: "#0e6e63", COAT: "#8a4a28", COAT_D: "#5c2f18", SCARF: "#f26a1b", SCARF_D: "#b84a0d",
    HAIR: "#a7a59e", BEARD: "#7a7266", GLASS: "#17171a", EARBUD: "#2b3f95", LEG: "#232a32", SKIN: "#dcae92", BRASS: "#6b4b2a",
    FEAT: "#8a5a3c", BUTTON: "#3a2212", SMILE: "#ffffff", LENS: "#fefefe", EYEDOT: "#14323a", BROW: "#a7a59e",
    TUBE: "#abcdef", TUBEA: "#abcdee", WHITE: "#ffffff", FLOORC: "#0d131a" };
  const defaults = {
    headW: 64, headH: 70, headN: 3.4, taper: 0.22,
    lensW: 1.0, lensH: 40, lensR: 8, frameW: 5, glassCol: "#1b1b1f",
    hairCol: "#a7a59e", tufts: 22, tuftH: 1.0, lean: -0.35, hairline: 1.0,
    skin: "#f0c9a6", beard: true, beardAmt: 0.25, beardCol: "#7a7266", beardOpen: 24, moustache: true, mouthW: 14, smileD: 7, noseSize: 1.0, eyeDot: 3.6,
    coatCol: "#8a4a28", coatLen: 138, flare: 34, collarH: 1.0, collarW: 0, buttons: 4, pockets: true, scarfCol: "#f26a1b",
    irisCol: "#6f8f45", ringCol: "#a9792f", magR: 46,
    lineCol: "#3a2a20", legCol: "#2a3340", bg: "#070a0f", spot: true, spotAmt: 1.0, fadeSec: 1.0 };

  const lerp = (a, b, u) => a + (b - a) * u;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const sm = u => { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); };
  const rad = d => d * Math.PI / 180;
  const copysign = (m, s) => (s < 0 || Object.is(s, -0)) ? -Math.abs(m) : Math.abs(m);

  // ---- timeline -------------------------------------------------------------
  const GAZE = [[0, 0, 0], [0.7, -1, 0], [1.5, 1, 0], [2.3, 0, -1], [3.1, 0, 1], [3.9, -1, .6], [4.7, 1, -.6], [5.4, 0, 0], [6.0, 0, 0]];
  function gazeAt(g) {
    for (let i = 0; i < GAZE.length - 1; i++) {
      const [t0, x0, y0] = GAZE[i], [t1, x1, y1] = GAZE[i + 1];
      if (t0 <= g && g <= t1) { const u = sm((g - t0) / (t1 - t0)); return [lerp(x0, x1, u), lerp(y0, y1, u)]; }
    }
    return [0, 0];
  }
  function pose(t) {
    t = ((t % T) + T) % T;
    const P = { x: -400, f: 0, dir: -1, walk: 0, mode: "gap", du: null, mag: 0, carry: false, gaze: [0, 0], blink: 0, spot: 0, cap: "" };
    const up = o => Object.assign(P, o);
    if (t < 16) {
      P.dir = -1; P.spot = Math.min(1, t / 0.8, (15.6 - t) / 0.6);
      if (t < 4) up({ x: lerp(590, 240, t / 4), f: -1, walk: 1, mode: "walk", cap: "Sequence 1: walks on from the right" });
      else if (t < 4.8) up({ x: 240, f: -1 + sm((t - 4) / 0.8), mode: "turn", cap: "Turns to face the camera" });
      else if (t < 11.2) up({ x: 240, f: 0, mode: "dance", du: t - 4.8, cap: "Dances (Y M C A)" });
      else if (t < 12) up({ x: 240, f: -sm((t - 11.2) / 0.8), mode: "turn", cap: "Turns to leave" });
      else up({ x: lerp(240, -110, (t - 12) / 4), f: -1, walk: 1, mode: "walk", cap: "Walks off to the left" });
    } else if (t >= 17 && t < 34) {
      const r = t - 17;
      up({ dir: 1, carry: true, spot: Math.min(1, r / 0.8, (16.6 - r) / 0.6) });
      if (r < 4) up({ x: lerp(-110, 240, r / 4), f: 1, walk: 1, mode: "walk", cap: "Sequence 2: walks on from the left, magnifying glass in hand" });
      else if (r < 4.8) up({ x: 240, f: 1 - sm((r - 4) / 0.8), mode: "turn", cap: "Turns to face the camera" });
      else if (r < 5.6) up({ x: 240, f: 0, mode: "look", mag: sm((r - 4.8) / 0.8), cap: "Raises the magnifying glass" });
      else if (r < 11.6) {
        const g = r - 5.6; let bl = 0;
        for (const b of [2.0, 5.0]) if (b <= g && g <= b + 0.26) bl = Math.sin(Math.PI * (g - b) / 0.26);
        up({ x: 240, f: 0, mode: "look", mag: 1, gaze: gazeAt(g), blink: bl, cap: "Magnified eye looks around" });
      } else if (r < 12.2) up({ x: 240, f: 0, mode: "look", mag: 1 - sm((r - 11.6) / 0.6), cap: "Lowers the magnifying glass" });
      else if (r < 13) up({ x: 240, f: sm((r - 12.2) / 0.8), mode: "turn", cap: "Turns to leave" });
      else up({ x: lerp(240, 590, (r - 13) / 4), f: 1, walk: 1, mode: "walk", cap: "Walks off to the right" });
    }
    return P;
  }

  // ---- drawing helpers ------------------------------------------------------
  const HANDS = { rest: [-8, 100, 8, 100], Y: [-64, -100, 64, -100], M: [30, -112, -30, -112], C: [-30, 70, -76, -104], A: [38, -112, -38, -112] };
  const CYCLE = ["Y", "M", "C", "A"];
  function danceHands(du) {
    const seg = (du % 3.2) / 0.8, i = Math.floor(seg) % 4, u = seg - Math.floor(seg);
    const prev = CYCLE[(i + 3) % 4];
    return HANDS[prev].map((p, k) => lerp(p, HANDS[CYCLE[i]][k], sm(u / 0.35)));
  }
  function ik(sx, sy, hx, hy, L, side) {
    let dx = hx - sx, dy = hy - sy, d = Math.hypot(dx, dy) || 1e-6;
    const reach = 2 * L - 0.5;
    if (d > reach) { hx = sx + dx * reach / d; hy = sy + dy * reach / d; d = reach; dx = hx - sx; dy = hy - sy; }
    const a = Math.sqrt(Math.max(L * L - (d / 2) ** 2, 0)), mx = (sx + hx) / 2, my = (sy + hy) / 2, nx = -dy / d, ny = dx / d;
    const c1 = [mx + nx * a, my + ny * a], c2 = [mx - nx * a, my - ny * a];
    return [side * c1[0] >= side * c2[0] ? c1 : c2, [hx, hy]];
  }
  function arc(cx, cy, rx, ry, a0, a1, n = 14) {
    const o = [];
    for (let i = 0; i <= n; i++) { const a = rad(a0 + (a1 - a0) * i / n); o.push([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]); }
    return o;
  }

  // ---- scene ----------------------------------------------------------------
  function mkScene() {
    const ops = [];
    const add = (kind, pts, fill, stroke, w, a) => ops.push([kind, pts, fill, stroke, w, a]);
    return { ops,
      line: (x1, y1, x2, y2, w, col, a = 1) => add("line", [x1, y1, x2, y2], null, col, w, a),
      circle: (x, y, r, o = {}) => add("ellipse", [x, y, r, r], o.fill || null, o.stroke || null, o.w || 0, o.a === undefined ? 1 : o.a),
      ellipse: (x, y, rx, ry, o = {}) => add("ellipse", [x, y, rx, ry], o.fill || null, o.stroke || null, o.w || 0, o.a === undefined ? 1 : o.a),
      poly: (pts, o = {}) => add("poly", pts, o.fill || null, o.stroke || null, o.w || 0, o.a === undefined ? 1 : o.a),
      pline: (pts, w, col, a = 1) => add("pline", pts, null, col, w, a),
      rrect: (x, y, w_, h_, r, o = {}) => add("rrect", [x, y, w_, h_, r], o.fill || null, o.stroke || null, o.w || 0, o.a === undefined ? 1 : o.a) };
  }

  // ---- styling (port of restyle/param_style) ----------------------------------
  function paramStyle(Q) {
    const GREEN = "#3ddc84";
    const st = { minw: 3, tubea: Q.coatCol, tubec: Q.legCol, dot: GREEN, opaque: true, dropShine: true,
      fill: new Map([[C.TEAL, Q.skin], [C.LEG, Q.legCol], ["#10161c", Q.legCol], ["#2bbfaa", Q.skin], [C.BRASS, null], [C.LENS, ["#ffffff", 0.45]], [C.EYEDOT, "#22342b"]]),
      stroke: new Map([[C.TEAL_D, Q.lineCol], [C.GLASS, Q.glassCol], [C.HAIR, "#8d8a83"], [C.BRASS, Q.lineCol], [C.COAT_D, Q.lineCol], [C.SCARF_D, Q.lineCol], [C.LEG, Q.lineCol], [C.COAT, Q.lineCol],
        ["#c98a5a", "#c98a5a"], ["#e2b27c", "#e2b27c"], [C.FEAT, C.FEAT], ["#8d6a58", Q.lineCol], ["#4a3b32", "#4a3b32"], ["#3d5d4e", "#3d5d4e"], ["#1d2a2d", C.FEAT], ["#3f4f22", "#3f4f22"]]),
      fillstroke: new Map([[C.TEAL, Q.lineCol], [C.LEG, Q.lineCol], ["#10161c", Q.lineCol], ["#2bbfaa", Q.lineCol], [C.EARBUD, Q.lineCol], [C.COAT, Q.lineCol], [C.SCARF, Q.lineCol], [C.HAIR, Q.lineCol]]),
      recolor: new Map([[C.COAT, Q.coatCol], [C.SCARF, Q.scarfCol], [C.HAIR, Q.hairCol], [C.BEARD, Q.beardCol]]) };
    return st;
  }
  function restyle(op, st, Q) {
    let [kind, p, fill, stroke, w, a] = op;
    if (stroke === C.TUBEA) return [kind, p, null, st.tubea, w, 1.0];
    if (stroke === C.TUBE) return [kind, p, null, st.tubec, w, 1.0];
    if (fill === "#fff3d0" || fill === "#000000" || fill === "#cfeff0") return op;
    if (fill === C.WHITE && a < 0.3) return st.dropShine ? null : op;
    const tiny = kind === "ellipse" && p[2] < 3 && fill && !stroke;
    if (tiny) {
      let c = st.fill.has(fill) ? st.fill.get(fill) : fill; if (!c) c = st.dot;
      if (st.recolor.has(c)) c = st.recolor.get(c);
      return [kind, p, fill !== C.WHITE ? c : C.WHITE, null, 0, 1.0];
    }
    let nf = fill, ns = stroke, nw = w, na = a;
    if (fill) {
      nf = st.fill.has(fill) ? st.fill.get(fill) : fill;
      if (Array.isArray(nf)) { na = nf[1]; nf = nf[0]; }
      else if (nf === null) nf = Q.bg;
      if (!stroke && st.fillstroke.has(fill)) { ns = st.fillstroke.get(fill); nw = st.minw; }
    }
    if (stroke) { ns = st.stroke.has(stroke) ? st.stroke.get(stroke) : stroke; nw = Math.max(w, st.minw); }
    if (st.opaque && fill && st.fill.has(fill) && na < 1) na = 1.0;   // (as gen.py: also makes the lens fill opaque)
    if (nf && st.recolor.has(nf)) nf = st.recolor.get(nf);
    return [kind, p, nf, ns, nw, na];
  }

  // ---- character ----------------------------------------------------------------
  function contour(Q, cx, cy, rx, ry, th) {
    const n = Q.headN, taper = Q.taper, c = Math.cos(rad(th)), s = Math.sin(rad(th));
    const x = rx * copysign(Math.pow(Math.abs(c), 2 / n), c) * (1 - taper * Math.pow(Math.max(0, s), 2));
    const y = ry * copysign(Math.pow(Math.abs(s), 2 / n), s);
    return [cx + x, cy + y];
  }
  function headGeo(Q, P) {
    const f = P.f, k2 = Math.sqrt(Math.max(0, 1 - f * f)), k = 0.15 + 0.85 * k2;
    return { f, k2, k, fx: f * 30, sgn: f > 0 ? 1 : -1, rx: Q.headW * (0.56 + 0.44 * k2), ry: Q.headH };
  }
  function drawSpot(sc, Q, P, t) {
    if (P.spot <= 0 || !Q.spot) return;
    const sx = clamp(pose(t - 0.15).x, -60, 540), k = P.spot * Q.spotAmt;
    for (let i = 0; i < 5; i++) { const a = 8 + i * 3, b = 66 + i * 15;
      sc.poly([[sx - a, -10], [sx + a, -10], [sx + b, FLOOR + 4], [sx - b, FLOOR + 4]], { fill: "#fff3d0", a: 0.05 * k }); }
    for (let i = 0; i < 5; i++) sc.ellipse(sx, FLOOR + 4, 150 - i * 24, 27 - i * 4, { fill: "#fff3d0", a: 0.08 * k });
  }
  function resample(pts, n) {
    const d = [0];
    for (let i = 1; i < pts.length; i++) d.push(d[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
    const tot = d[d.length - 1] || 1, out = []; let j = 0;
    for (let i = 0; i < n; i++) {
      const tg = tot * i / (n - 1);
      while (j < pts.length - 2 && d[j + 1] < tg) j++;
      const sg = (d[j + 1] - d[j]) || 1, u = (tg - d[j]) / sg;
      out.push([lerp(pts[j][0], pts[j + 1][0], u), lerp(pts[j][1], pts[j + 1][1], u)]);
    }
    return out;
  }
  const mix = (A, B, u) => A.map((a, i) => [lerp(a[0], B[i][0], u), lerp(a[1], B[i][1], u)]);
  function drawHead(sc, Q, P, hx, hy) {
    const f = P.f, p = Math.abs(f), sgn = f >= 0 ? 1 : -1, th = p * Math.PI / 2, S = Math.sin(th), Cc = Math.cos(th);
    const rx = Q.headW, ry = Q.headH, D = rx * 0.98, N = Math.floor(Q.tufts), hl = Q.hairline;
    const pL = (u, v) => [hx + u * D, hy + v * ry], mir = q => [hx + sgn * (q[0] - hx), q[1]];
    const rng = (a, b, s) => { const o = []; for (let t = a; t < b; t += s) o.push(t); return o; };
    const f1 = rng(270, 451, 6).map(t => contour(Q, hx, hy, rx, ry, t)), f2 = rng(90, 271, 6).map(t => contour(Q, hx, hy, rx, ry, t));
    const face = [pL(0, -1), pL(.3, -.97), pL(.62, -.80), pL(.78, -.58), pL(.76, -.22), pL(1.12, .23), pL(.88, .28), pL(.92, .39), pL(.87, .50), pL(.90, .60), pL(.86, .84), pL(.62, 1.0)];
    const backs = [pL(.62, 1.0)].concat(rng(100, 271, 8).map(t => contour(Q, hx, hy, D, ry, t)));
    let p1, p2;
    if (sgn > 0) { p1 = face; p2 = backs; } else { p1 = backs.map(mir).reverse(); p2 = face.map(mir).reverse(); }
    const K = 60, sil = mix(resample(f1, K).concat(resample(f2, K)), resample(p1, K).concat(resample(p2, K)), p);
    sc.poly(sil, { fill: C.TEAL, stroke: C.TEAL_D, w: 3 });
    const Re = rx + 3;
    for (const phi of [-95, 95]) {
      const ang = th + rad(phi);
      if (Math.cos(ang) < -0.1) continue;
      const ex = hx + sgn * Re * Math.sin(ang);
      sc.ellipse(ex, hy + 6, 8, 12, { fill: C.TEAL, stroke: C.TEAL_D, w: 2 });
      sc.rrect(ex - 3, hy + 3, 6, 10, 3, { fill: C.EARBUD });
    }
    if (Q.beard) {
      const run = []; sil.forEach((q, i) => { if (q[1] > hy + 34) run.push(i); });
      if (run.length > 3) {
        const outer = sil.slice(run[0], run[run.length - 1] + 1), off = Math.max(2.0, 36 - 0.9 * Q.beardOpen), inner = [];
        for (const q of outer) { const dx = hx - q[0], dy = hy - q[1], d_ = Math.hypot(dx, dy) || 1; inner.push([q[0] + dx / d_ * off, q[1] + dy / d_ * off]); }
        const amt = Q.beardAmt;
        sc.poly(outer.concat(inner.slice().reverse()), { fill: C.BEARD, a: 0.10 + 0.6 * amt });
        const n_ = Math.floor(6 + 14 * amt);
        for (let j = 0; j < n_; j++) {
          const r = (outer.length - 1) * j / Math.max(n_ - 1, 1), i0 = Math.min(Math.floor(r), outer.length - 2), fr = r - i0, w_ = j % 2 ? 0.3 : 0.65;
          const ox = lerp(outer[i0][0], outer[i0 + 1][0], fr), oy = lerp(outer[i0][1], outer[i0 + 1][1], fr);
          const ix = lerp(inner[i0][0], inner[i0 + 1][0], fr), iy = lerp(inner[i0][1], inner[i0 + 1][1], fr);
          sc.circle(lerp(ox, ix, w_), lerp(oy, iy, w_), 1.5, { fill: C.BEARD, a: 0.8 });
        }
      }
    }
    if (Q.moustache) sc.ellipse(hx + sgn * 0.85 * D * S, hy + 27, 28 * Cc + 11 * S, 5.5, { fill: C.BEARD, a: 0.25 + 0.6 * Q.beardAmt });
    if (p < 0.5) {
      const nx = hx + sgn * 0.9 * D * S, ns_ = Q.noseSize;
      sc.pline([[nx - 2 * ns_, hy - 4], [nx - (5 + 2 * p * 4) * ns_, hy + 13 * ns_ - (ns_ - 1) * 4], [nx + (5 + 9 * p) * ns_, hy + 16 * ns_ - (ns_ - 1) * 4]], 3, C.FEAT);
    }
    const mw = (Q.mouthW + 3) * Cc + 7 * S, mx = hx + sgn * 0.86 * D * S, md = Q.smileD * Cc + 2.5 * S, top = [], bot = [];
    for (let i = 0; i < 11; i++) { top.push([mx - mw + 2 * mw * i / 10, hy + 36]); bot.push([mx - mw + 2 * mw * i / 10, hy + 36 + md * (1 - Math.pow((i - 5) / 5, 2))]); }
    sc.poly(top.concat(bot.reverse()), { fill: C.SMILE, stroke: C.FEAT, w: 2.5 });
    const lh = Q.lensH, ey = hy - 12, lw0 = 54 * Q.lensW, offs0 = lw0 / 2 + 4, Rl = Math.max(rx * 1.0, offs0 + 6), phl = Math.asin(Math.min(offs0 / Rl, 0.99)), lw = Math.max(lw0 * Cc, 6);
    const centres = {};
    for (const s_ of [-1, 1]) {
      const ang = th + s_ * phl;
      if (Math.cos(ang) < 0.1) continue;
      const cx = hx + sgn * Rl * Math.sin(ang); centres[s_] = cx;
      sc.rrect(cx - lw / 2, ey - lh / 2, lw, lh, Math.min(Q.lensR, lw / 2, lh / 2), { fill: C.LENS, stroke: C.GLASS, w: Q.frameW });
      const [gx, gy] = P.mode === "look" ? P.gaze : [0, 0];
      if (lw > 14 || p > 0.5) sc.circle(cx + gx * 3 * Cc + sgn * S * 2, ey + gy * 2, Q.eyeDot, { fill: C.EYEDOT });
      const wb = Math.max(lw, 16);
      sc.line(cx - wb / 2 + 1, ey - lh / 2 - 9, cx + wb / 2 - 1, ey - lh / 2 - 12 - (s_ === 1 ? 2 : 0), 4.5, C.BROW);
    }
    const nc = Object.keys(centres).length;
    if (Cc > 0.35 && nc === 2) { const bxm = hx + sgn * Rl * S; sc.line(bxm - 5 * Cc, ey - 8, bxm + 5 * Cc, ey - 8, Q.frameW, C.GLASS, clamp((Cc - .35) * 3, 0, 1)); }
    if (p > 0.35) {
      for (const key of Object.keys(centres)) {
        const s_ = Number(key), cx = centres[key];
        if (s_ === -1 || nc === 1) {
          const earX = hx + sgn * Re * Math.sin(th - rad(95));
          sc.line(cx - sgn * lw / 2, ey - 4, earX, hy - 8, Math.max(Q.frameW * 0.8, 3), C.GLASS, clamp((p - .35) * 2, 0, 1));
        }
      }
    }
    const tuft = (front, i) => {
      const c0 = front ? 188 : 160, c1 = front ? 352 : 320, t_ = c0 + (c1 - c0) * i / N, bp = contour(Q, hx, hy, (front ? rx : D) + 1, ry + 1, t_);
      const a = rad(t_), c = Math.cos(a), s_ = Math.sin(a), cl = front ? (0.22 <= i / N && i / N <= 0.56) : (0.55 <= i / N && i / N <= 0.9);
      const ht = ((i % 2 === 0 ? 6 : 20 + 12 * Math.abs(Math.sin(i * 1.9))) + (cl ? 8 : 0)) * Q.tuftH, lean = Q.lean;
      return i % 2 ? [bp[0] + (c + lean * (-s_)) * ht * 0.9, bp[1] + (s_ + lean * c) * ht] : [bp[0] + c * ht * 0.4, bp[1] + s_ * ht * 0.4];
    };
    const ft = []; for (let i = 0; i <= N; i++) ft.push(tuft(true, i));
    const w_ = rx * 0.98;
    const fh = [[hx + w_ * 0.97, hy - 2], [hx + w_ * 0.86, hy - 30 * hl], [hx + w_ * 0.55, hy - 46 * hl], [hx + w_ * 0.2, hy - 50 * hl],
      [hx - w_ * 0.2, hy - 52 * hl], [hx - w_ * 0.55, hy - 47 * hl], [hx - w_ * 0.86, hy - 30 * hl], [hx - w_ * 0.97, hy - 2]];
    let pt_ = []; for (let i = 0; i <= N; i++) pt_.push(tuft(false, i));
    let ph_ = [pL(.74, -.62 * hl), pL(.55, -.66 * hl), pL(.30, -.60 * hl), pL(.05, -.46 * hl), pL(-.22, -.28), pL(-.42, -.08), pL(-.52, .08), pL(-.56, .20)];
    if (sgn < 0) { pt_ = pt_.map(mir).reverse(); ph_ = ph_.map(mir).reverse(); }
    sc.poly(mix(ft.concat(fh), pt_.concat(ph_), p), { fill: C.HAIR, stroke: C.TEAL_D, w: 3 });
  }
  function coatGeo(P, bob) {
    const f = P.f, k2 = Math.sqrt(Math.max(0, 1 - f * f)), x = P.x;
    return { f, k2, x, bw: lerp(52, 102, k2), top: 328 + bob, sway: P.walk ? P.dir * Math.sin(x / 13.0) * 12 : 0 };
  }
  function drawCoat(sc, Q, P, bob) {
    const { f, k2, x, bw, top, sway } = coatGeo(P, bob);
    sc.ellipse(x, FLOOR + 3, 52, 8, { fill: "#000000", a: 0.45 });
    const L = Q.coatLen, hemw = bw / 2 + Q.flare * (0.35 + 0.65 * k2), xl = x - hemw - sway * 0.6, xr = x + hemw - sway * 0.6;
    const left = [[x - bw / 2 + 16, top], [x - bw / 2, top + 20], [x - bw / 2 - 3, top + 68], [xl, top + L]];
    const right = [[xr, top + L], [x + bw / 2 + 3, top + 68], [x + bw / 2, top + 20], [x + bw / 2 - 16, top]];
    const hem = []; for (let i = 1; i < 10; i++) hem.push([lerp(xl, xr, i / 10), top + L + 9 * Math.sin(Math.PI * i / 10) + sway * 0.35 * (i / 10 - 0.5) * 2]);
    sc.poly(left.concat(hem, right), { fill: C.COAT, stroke: C.COAT_D, w: 3 });
    if (k2 > 0.4) {
      const cxx = x + f * 22;
      sc.line(cxx, top + 16, cxx + sway * 0.1, top + L + 2, 2.5, "#c98a5a", k2);
      const nb = Math.floor(Q.buttons);
      for (let i = 0; i < nb; i++) { const by = top + 34 + i * ((L - 57) / Math.max(nb - 1, 1));
        sc.circle(cxx, by, 6.5, { fill: C.BUTTON, stroke: "#e2b27c", w: 2, a: k2 }); sc.circle(cxx, by, 1.6, { fill: "#e2b27c", a: k2 }); }
      for (const s_ of [-1, 1]) if (Q.pockets) sc.rrect(x + s_ * bw * 0.3 - 11, top + 84, 22, 20, 5, { stroke: "#c98a5a", w: 2, a: k2 });
    }
  }
  function drawCollar(sc, Q, P, bob, hx, hy, rx) {
    const { k2, x, bw, top } = coatGeo(P, bob);
    for (const s_ of [-1, 1]) {
      if (k2 < 0.3 && s_ === -(P.f > 0 ? 1 : -1)) continue;
      const up = 60 * (Q.collarH - 1), cw = Q.collarW;
      const wing = [[x + s_ * 12, top - 16], [x + s_ * (bw / 2 + 8 + cw * 0.5), top + 8], [x + s_ * (rx + 18 + cw), hy + 8 - up], [x + s_ * (rx + 8 + cw), hy - 16 - up], [x + s_ * (rx - 12), hy - 2 - up * 0.6]];
      sc.poly(wing, { fill: C.COAT, stroke: C.COAT_D, w: 3 });
      const bx_ = x + s_ * (bw / 2 - 2), by_ = top + 4, tx_ = x + s_ * (rx + 8 + cw), ty_ = hy - 16 - up;
      sc.pline([[bx_, by_], [bx_ + (tx_ - bx_) * 0.85, by_ + (ty_ - by_) * 0.85]], 2.5, "#c98a5a", 0.8);
    }
  }
  function drawScarf(sc, Q, P, bob) {
    const { f, k2, x, bw, top } = coatGeo(P, bob);
    sc.ellipse(x, top + 4, bw / 2 + 5, 15, { fill: C.SCARF, stroke: C.SCARF_D, w: 2 });
    sc.poly([[x - 10 * f, top + 6], [x + 22 + 8 * f, top + 12], [x + 26 + 8 * f, top + 70], [x + 6, top + 64]], { fill: C.SCARF, stroke: C.SCARF_D, w: 2, a: 0.55 + 0.45 * k2 });
  }
  function drawLegs(sc, Q, P, bob) {
    const f = P.f, k2 = Math.sqrt(Math.max(0, 1 - f * f)), x = P.x, d = P.dir;
    for (const [s_, off] of [[-1, 0.0], [1, Math.PI]]) {
      const hx = x + s_ * (6 + 9 * k2), hy = 436 + bob; let fx_, fy;
      if (P.walk) { const ph = x / 13.0 + off; fx_ = hx + d * Math.sin(ph) * 30; fy = FLOOR - Math.max(0, Math.cos(ph)) * 11; }
      else if (P.mode === "dance") { fx_ = hx + s_ * 5; fy = FLOOR - Math.max(0, Math.sin(P.du * Math.PI / 0.4 + (s_ < 0 ? 0 : Math.PI))) * 7; }
      else { fx_ = hx + s_ * 4 * k2; fy = FLOOR; }
      const kx = (hx + fx_) / 2 + d * 3, ky = (hy + fy) / 2 - 2, pts = [[hx, hy], [kx, ky], [fx_, fy - 6]];
      sc.pline(pts, 13, C.LEG); sc.pline(pts, 7, C.TUBE);
      sc.ellipse(fx_ + (Math.abs(f) > 0.5 ? d * 7 : 0), fy - 3, Math.abs(f) > .5 ? 15 : 12, 7, { fill: "#10161c", stroke: C.LEG, w: 2 });
    }
  }
  function drawArmsAndLens(sc, Q, P, hx, hy, bob) {
    const f = P.f, k2 = Math.sqrt(Math.max(0, 1 - f * f)), x = P.x, d = P.dir, sw = lerp(14, 44, k2), sy = 348 + bob;
    const sh = { "-1": [x - sw, sy], "1": [x + sw, sy] }, tgt = {};
    if (P.mode === "dance") { const a = danceHands(P.du); tgt["-1"] = [sh["-1"][0] + a[0], sh["-1"][1] + a[1]]; tgt["1"] = [sh["1"][0] + a[2], sh["1"][1] + a[3]]; }
    else if (P.walk) { const sw_ = Math.sin(x / 13.0), amp = 34 * Math.abs(f);
      tgt["-1"] = [sh["-1"][0] + d * sw_ * amp, sh["-1"][1] + 98]; tgt["1"] = [sh["1"][0] - d * sw_ * amp, sh["1"][1] + 98]; }
    else { const r = HANDS.rest; tgt["-1"] = [sh["-1"][0] + r[0], sh["-1"][1] + r[1]]; tgt["1"] = [sh["1"][0] + r[2], sh["1"][1] + r[3]]; }
    let lens = null;
    if (P.carry) {
      const mag = P.mag, R = lerp(Q.magR * 30 / 46, Q.magR, mag), ex = hx + 32, ey = hy - 12;
      const rc = [ex, ey], rh = [ex + 30, ey + 56], wh = tgt["1"], cc = [wh[0] + d * 6, wh[1] - 58];
      const hnd = [lerp(wh[0], rh[0], mag), lerp(wh[1], rh[1], mag)], cen = [lerp(cc[0], rc[0], mag), lerp(cc[1], rc[1], mag)];
      tgt["1"] = hnd; lens = [cen, R, hnd];
    }
    for (const s_ of [-1, 1]) {
      const [e, h] = ik(sh[s_][0], sh[s_][1], tgt[s_][0], tgt[s_][1], 64, s_);
      sc.pline([sh[s_], e, h], 13, C.COAT, 1.0); sc.pline([sh[s_], e, h], 7, C.TUBEA);
      sc.circle(h[0], h[1], 8, { fill: C.TEAL, stroke: C.TEAL_D, w: 2 });
    }
    if (lens) {
      const [[cx, cy], R, [hx_, hy_]] = lens;
      let ux = hx_ - cx, uy = hy_ - cy; const n = Math.hypot(ux, uy) || 1; ux /= n; uy /= n;
      sc.line(cx + ux * R, cy + uy * R, hx_, hy_, 11, C.BRASS);
      sc.circle(hx_, hy_, 8, { fill: C.TEAL, stroke: C.TEAL_D, w: 2 });
      const mag = P.mag;
      if (mag > 0.5) {
        sc.circle(cx, cy, R - 2, { fill: C.SKIN });
        sc.pline([[cx - 27, cy - 34], [cx, cy - 38], [cx + 27, cy - 34]], 9, C.GLASS);
        const ry = 19 * (1 - P.blink), [gx, gy] = P.gaze;
        sc.ellipse(cx, cy + 2, 31, Math.max(ry, 0.8), { fill: "#f4f1ea", stroke: "#4a3b32", w: 2 });
        if (P.blink < 0.75) {
          const ix = cx + gx * 12, iy = cy + 2 + gy * 6;
          sc.circle(ix, iy, Math.min(13, ry + 2), { fill: Q.irisCol, stroke: "#3f4f22", w: 2 });
          sc.circle(ix, iy, Math.min(8.5, ry), { fill: Q.ringCol, a: 0.85 });
          sc.circle(ix, iy, 5.2, { fill: "#0d1a17" }); sc.circle(ix - 4, iy - 4, 2.2, { fill: "#ffffff" });
        }
        sc.pline(arc(cx, cy + 2, 33, 20 * (1 - P.blink) + 1, 200, 340, 10), 4, "#4a3b32");
        for (let i = 0; i < 3; i++) sc.line(cx + 33 + i * 1.5, cy + 4 + i * 6, cx + 40, cy - 4 + i * 8, 2, "#8d6a58", 0.7);
      }
      sc.circle(cx, cy, R, { fill: "#cfeff0", a: 0.10 + 0.05 * (1 - mag) });
      sc.circle(cx, cy, R, { stroke: C.BRASS, w: 6 });
      sc.pline(arc(cx, cy, R - 9, R - 9, 205, 250, 6), 3, "#ffffff", 0.55);
    }
  }

  function build(t, Q) {
    const P0 = pose(t), sc = mkScene();
    sc.rrect(0, FLOOR - 4, W, H - FLOOR + 4, 0, { fill: C.FLOORC });
    sc.line(0, FLOOR - 4, W, FLOOR - 4, 2, "#1a232d");
    drawSpot(sc, Q, P0, t);
    if (P0.mode === "gap" || P0.x < -180 || P0.x > 660) return { sc, P: P0 };
    let bob;
    if (P0.walk) bob = -Math.abs(Math.sin(P0.x / 13.0)) * 5;
    else if (P0.mode === "dance") bob = -Math.abs(Math.sin(P0.du * Math.PI / 0.4)) * 5;
    else bob = 0;
    const sway = P0.mode === "dance" ? Math.sin(P0.du * Math.PI / 0.8) * 3 : 0;
    const P = Object.assign({}, P0); P.x += sway;
    const hx = P.x + P.f * 8, hy = 320 - Q.headH + bob, g = headGeo(Q, P);
    drawLegs(sc, Q, P, bob); drawCoat(sc, Q, P, bob); drawCollar(sc, Q, P, bob, hx, hy, g.rx); drawScarf(sc, Q, P, bob);
    drawHead(sc, Q, P, hx, hy); drawArmsAndLens(sc, Q, P, hx, hy, bob);
    return { sc, P: P0 };
  }
  function ops(t, Qin) {
    const Q = Object.assign({}, defaults, Qin || {}), { sc } = build(t, Q), st = paramStyle(Q);
    return sc.ops.map(o => restyle(o, st, Q)).filter(Boolean);
  }
  const fx = v => { let s = v.toFixed(1); if (s.indexOf(".") >= 0) s = s.replace(/0+$/, "").replace(/\.$/, ""); return s; };
  function svg(t, Qin) {
    const Q = Object.assign({}, defaults, Qin || {}), o = ops(t, Q);
    const out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + W + " " + H + '" width="100%" height="100%">', '<rect width="' + W + '" height="' + H + '" fill="' + Q.bg + '"/>'];
    for (const [kind, p, fill, stroke, w, a] of o) {
      let st = fill ? ' fill="' + fill + '"' : ' fill="none"';
      if (stroke) st += ' stroke="' + stroke + '" stroke-width="' + fx(w) + '" stroke-linecap="round" stroke-linejoin="round"';
      if (a < 1) st += ' opacity="' + a.toFixed(2) + '"';
      if (kind === "line") out.push('<line x1="' + fx(p[0]) + '" y1="' + fx(p[1]) + '" x2="' + fx(p[2]) + '" y2="' + fx(p[3]) + '"' + st + "/>");
      else if (kind === "ellipse") out.push('<ellipse cx="' + fx(p[0]) + '" cy="' + fx(p[1]) + '" rx="' + fx(p[2]) + '" ry="' + fx(p[3]) + '"' + st + "/>");
      else if (kind === "poly" || kind === "pline") out.push("<" + (kind === "poly" ? "polygon" : "polyline") + ' points="' + p.map(q => fx(q[0]) + "," + fx(q[1])).join(" ") + '"' + st + "/>");
      else if (kind === "rrect") out.push('<rect x="' + fx(p[0]) + '" y="' + fx(p[1]) + '" width="' + fx(p[2]) + '" height="' + fx(p[3]) + '" rx="' + fx(p[4]) + '"' + st + "/>");
    }
    out.push("</svg>");
    return out.join("");
  }
  return { defaults, pose, ops, svg, T };
})();
if (typeof module !== "undefined") module.exports = RIG;
