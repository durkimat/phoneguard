// Location history maths: pure functions shared by the map page and the tests.
// Playback runs on a "display clock" that is not real time: quiet stretches (school, home) are skipped quickly and journeys are played slowly enough to follow.
export const R = 6371000;
export const dist = (a, b) => { const r = Math.PI / 180, dLat = (b[1] - a[1]) * r, dLon = (b[2] - a[2]) * r, x = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * r) * Math.cos(b[1] * r) * Math.sin(dLon / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(x)); };

// Seconds of display time per real minute: moving 0.5 s per min (a 20 minute walk takes 10 s), still 1 s per 45 min (six hours at school take 8 s).
export const MOVE_SEC_PER_MIN = 0.5, STILL_SEC_PER_MIN = 1 / 45;

// A segment counts as movement only when the phone moved further than GPS wobble could explain.
export function isMoving(a, b) { const d = dist(a, b), dt = Math.max(1, b[0] - a[0]) / 1000; return d > Math.max(40, (a[3] + b[3]) / 2) && d / dt > 0.3; }

export function buildFrames(points, speed = 1) {
  const segs = []; let clock = 0;
  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i], b = points[i + 1], dt = b[0] - a[0], moving = isMoving(a, b);
    const secs = Math.min(Math.max((dt / 60000) * (moving ? MOVE_SEC_PER_MIN : STILL_SEC_PER_MIN), moving ? 0.2 : 0.03), moving ? 10 : 3) / speed;
    segs.push({ i, t0: a[0], t1: b[0], a, b, moving, d0: clock, d1: clock + secs }); clock += secs;
  }
  return { segs, total: clock };
}

// Where is the dot, and what real time does it stand for, at display time `d` seconds?
export function positionAt(frames, points, d) {
  const { segs, total } = frames;
  if (!points.length) return null;
  if (!segs.length) return { lat: points[0][1], lon: points[0][2], t: points[0][0], seg: -1 };
  d = Math.max(0, Math.min(d, total));
  let lo = 0, hi = segs.length - 1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (segs[mid].d1 < d) lo = mid + 1; else hi = mid; }
  const s = segs[lo], f = s.d1 === s.d0 ? 1 : (d - s.d0) / (s.d1 - s.d0);
  return { lat: s.a[1] + (s.b[1] - s.a[1]) * f, lon: s.a[2] + (s.b[2] - s.a[2]) * f, t: Math.round(s.t0 + (s.t1 - s.t0) * f), seg: lo, moving: s.moving };
}
export const displayTimeOf = (frames, realT) => { for (const s of frames.segs) if (realT <= s.t1) return s.d0 + (s.d1 - s.d0) * ((realT - s.t0) / Math.max(1, s.t1 - s.t0)); return frames.total; };

// Places the phone stayed: consecutive fixes within `radiusM` of where the stay began, for at least `minMs`.
export function stops(points, { minMs = 10 * 60000, radiusM = 120 } = {}) {
  const out = []; let i = 0;
  while (i < points.length) {
    let j = i; while (j + 1 < points.length && dist(points[i], points[j + 1]) <= radiusM) j++;
    if (points[j][0] - points[i][0] >= minMs) { const seg = points.slice(i, j + 1); out.push({ from: points[i][0], to: points[j][0], lat: seg.reduce((s, p) => s + p[1], 0) / seg.length, lon: seg.reduce((s, p) => s + p[2], 0) / seg.length, n: seg.length }); i = j + 1; } else i++;
  }
  return out;
}
export const totalMetres = (points) => points.reduce((s, p, i) => (i && isMoving(points[i - 1], p) ? s + dist(points[i - 1], p) : s), 0);

// The start and end (ms) of a calendar day "YYYY-MM-DD" in a time zone, and the day a time falls on.
export function dayKey(t, tz) { const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date(t)).map((x) => [x.type, x.value])); return `${p.year}-${p.month}-${p.day}`; }
export function dayRange(key, tz, zonedToUtc) { const [y, m, d] = key.split("-").map(Number); return [zonedToUtc(y, m, d, 0, tz), zonedToUtc(y, m, d + 1, 0, tz) - 1]; }
