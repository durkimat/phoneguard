import assert from "node:assert/strict";
import { buildFrames, positionAt, stops, totalMetres, isMoving, dist, dayKey, dayRange, displayTimeOf } from "../src/page/track.js";
import { zonedToUtc } from "../src/page/timetable.js";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const M = 60000, H = 3600000, T = Date.UTC(2026, 10, 12, 7, 0);   // Thu 12 Nov 2026 07:00Z
// ~111 m per 0.001 degree of latitude
const p = (min, dlat, dlon = 0, acc = 15) => [T + min * M, 51.75 + dlat, -0.34 + dlon, acc, "gps", "day"];
// home 07:00-08:00, walk to school 08:00-08:20 (about 1.6 km), at school 08:20-15:00, walk home 15:00-15:20
const track = [p(0, 0), p(30, 0.0001), p(60, 0), p(65, 0.004), p(70, 0.008), p(75, 0.012), p(80, 0.0145), p(120, 0.0145), p(240, 0.0146), p(360, 0.0145), p(480, 0.0145), p(485, 0.011), p(490, 0.007), p(495, 0.003), p(500, 0)];

assert.ok(Math.abs(dist(p(0, 0), p(0, 0.001)) - 111) < 2); ok("distance is metres (0.001 degree = about 111 m)");
assert.equal(isMoving(p(0, 0), p(30, 0.0001)), false); assert.equal(isMoving(p(0, 0, 0, 80), p(1, 0.0004, 0, 80)), false); assert.equal(isMoving(p(60, 0), p(65, 0.004)), true); ok("GPS wobble is not movement; a real walk is");
const f = buildFrames(track), still = f.segs.filter((s) => !s.moving), mov = f.segs.filter((s) => s.moving);
const secsPerMin = (s) => (s.d1 - s.d0) / ((s.t1 - s.t0) / M);
assert.ok(mov.length >= 6 && still.length >= 6); assert.ok(mov.every((s) => secsPerMin(s) > 0.25)); assert.ok(still.filter((s) => s.t1 - s.t0 > 30 * M).every((s) => secsPerMin(s) < 0.1)); ok("journeys play about 0.5 s per minute; long quiet stretches about 1 s per 45 minutes");
assert.ok(f.total < 90 && f.total > 10); const total8h = (track.at(-1)[0] - track[0][0]) / 1000; assert.ok(f.total < total8h / 200); ok(`a ${total8h / 3600 | 0}-hour day plays in about ${Math.round(f.total)} seconds`);
const fast = buildFrames(track, 4); assert.ok(Math.abs(fast.total * 4 - f.total) < f.total * 0.25); ok("the speed setting scales the whole timeline");
const a = positionAt(f, track, 0); assert.equal(a.t, track[0][0]); const z = positionAt(f, track, 1e9); assert.equal(z.t, track.at(-1)[0]); ok("playback starts at the first fix and ends at the last");
const mid = f.segs[3]; const q = positionAt(f, track, (mid.d0 + mid.d1) / 2); assert.ok(q.t > mid.t0 && q.t < mid.t1 && q.lat > mid.a[1] && q.lat < mid.b[1]); ok("the dot is interpolated along the path");
let prev = -1; for (let d = 0; d <= f.total; d += f.total / 200) { const t = positionAt(f, track, d).t; assert.ok(t >= prev); prev = t; } ok("real time never goes backwards during playback");
assert.ok(Math.abs(displayTimeOf(f, mid.t0) - mid.d0) < 1e-6); ok("a real time maps to its display time (used to jump to a stop)");
const st = stops(track); assert.equal(st.length, 2); assert.ok(st[0].from === track[0][0] && st[0].to === track[2][0]); assert.ok(st[1].to - st[1].from > 5 * H); ok("stops are found: home and school, each with start and end");
assert.deepEqual(stops([p(0, 0), p(5, 0.0001)]), []); ok("a stay shorter than 10 minutes is not a stop");
const km = totalMetres(track); assert.ok(km > 3000 && km < 3600); ok("distance moved ignores wobble (about 3.2 km here)");
assert.equal(dayKey(Date.UTC(2026, 10, 12, 23, 30), "Europe/London"), "2026-11-12"); assert.equal(dayKey(Date.UTC(2026, 6, 12, 23, 30), "Europe/London"), "2026-07-13"); ok("days follow the phone's time zone (including summer time)");
const [d0, d1] = dayRange("2026-10-25", "Europe/London", zonedToUtc); assert.equal((d1 + 1 - d0) / H, 25); ok("a day range copes with the 25-hour day when clocks go back");
assert.equal(buildFrames([]).total, 0); assert.equal(positionAt(buildFrames([p(0, 0)]), [p(0, 0)], 5).t, T); assert.equal(positionAt(buildFrames([]), [], 0), null); ok("empty and single-point tracks are safe");
console.log(`\n${n} checks passed`);
