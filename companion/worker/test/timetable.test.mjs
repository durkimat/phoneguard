import assert from "node:assert/strict";
import { zonedToUtc, resolveSegments, checkPattern } from "../src/page/timetable.js";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const iso = (t) => new Date(t).toISOString();
assert.equal(iso(zonedToUtc(2026, 11, 16, 510, "Europe/London")), "2026-11-16T08:30:00.000Z"); ok("winter: London 08:30 = 08:30Z");
assert.equal(iso(zonedToUtc(2026, 7, 16, 510, "Europe/London")), "2026-07-16T07:30:00.000Z"); ok("summer: London 08:30 = 07:30Z");
assert.equal(iso(zonedToUtc(2026, 10, 25, 150, "Europe/London")), "2026-10-25T02:30:00.000Z"); ok("clocks-back day: 02:30 resolves to a real instant (after the change)");
const pattern = { tz: "Europe/London", days: { 1: [[0, 450, "night"], [510, 900, "school"], [1260, 1440, "night"]], 7: [[0, 1440, "day"]] } };
const now = Date.UTC(2026, 10, 14, 12, 0); // Sat 14 Nov 2026
const seg = resolveSegments(pattern, now, 14);
assert.equal(seg.length, 3 * 2 + 2); ok("14 days from a Saturday: Mon x2 (3 blocks) and Sun x2 (1 block) = 8 segments");
assert.equal(iso(seg[0].from), "2026-11-15T00:00:00.000Z"); assert.equal(seg[0].mode, "day"); assert.equal(iso(seg[0].to), "2026-11-16T00:00:00.000Z"); ok("Sunday full-day block ends at next midnight");
assert.ok(seg.every((s, i) => s.to > s.from && (!i || s.from >= seg[i - 1].from))); ok("segments are ordered and non-empty");
const octNow = Date.UTC(2026, 9, 22, 12); const across = resolveSegments({ tz: "Europe/London", days: { 7: [[0, 1440, "day"]] } }, octNow, 7);
assert.equal((across[0].to - across[0].from) / 3600000, 25); ok("the 25-hour Sunday when clocks go back");
assert.deepEqual(checkPattern({ days: { 1: [[100, 200, "a"], [150, 300, "a"]] } }, { a: {} }), ["Day 1: two blocks overlap"]); ok("overlap reported");
assert.ok(checkPattern({ days: { 1: [[300, 200, "x"]] } }, {}).length === 2); ok("backwards block and unknown mode reported");
console.log(`\n${n} checks passed`);
