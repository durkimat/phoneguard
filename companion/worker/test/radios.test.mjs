import assert from "node:assert/strict";
import * as R from "../src/hub/radios.js";

const H = 3600000, now = 100 * H;
const line = (t, locked, wifi, why, bt = 0, data = -1) => [t, locked, wifi, bt, data, why];

// only well-formed, newer lines are kept, and old ones go
let log = R.merge([], [line(now - 100 * H, 1, 0, "resting"), line(now - 3 * H, 0, 1, "in use"), [1, 2], line(now - 4 * H, 1, 0, "out of order"), line(now - 2 * H, 1, 1, "woken for\ncompanion poll")], now);
assert.deepEqual(log.map((e) => e[5]), ["in use", "woken for companion poll"]);
log = R.merge(log, [line(now - 2 * H, 1, 1, "sent again")], now);
assert.equal(log.length, 2);

// time locked, time with everything off, and what kept a radio on
const day = [line(now - 5 * H, 0, 1, "in use"), line(now - 4 * H, 1, 1, "waiting to rest"), line(now - 4 * H + 60000, 1, 0, "resting"),
  line(now - 2 * H, 1, 1, "kept on: charging"), line(now - 1 * H, 1, 0, "resting")];
const s = R.summarize(day, now, now);
assert.equal(s.locked_h, 4);
assert.equal(s.off_h, 2.98);
assert.deepEqual(s.on_why, [["kept on: charging", 1], ["waiting to rest", 0.02]]);
assert.equal(s.faults, 0);

// a radio on when it should be off is a fault, whichever radio it is
const bad = R.summarize([line(now - H, 1, 0, "resting", 1), line(now - H / 2, 1, 0, "resting", 0, 1)], now, now);
assert.equal(bad.faults, 2);
assert.equal(bad.off_h, 0);

// the last line lasts only as long as the phone was heard from; a long silence is cut
assert.equal(R.summarize([line(now - 10 * H, 1, 0, "resting")], now - 9 * H, now).off_h, 1);
assert.equal(R.summarize([line(now - 20 * H, 1, 0, "resting")], now, now).off_h, 6);
assert.equal(R.summarize([], null, now).lines, 0);
console.log("radios: ok");
