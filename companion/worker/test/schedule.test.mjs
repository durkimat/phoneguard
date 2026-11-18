// The JS schedule builder must reproduce, exactly, what the Home Assistant Python code builds (golden-schedule.json), except `version`.
import assert from "node:assert/strict";
import fs from "node:fs";
import { buildConfig, canon } from "../src/hub/schedule.js";
const cases = JSON.parse(fs.readFileSync(new URL("./golden-schedule.json", import.meta.url), "utf8"));
let n = 0, bad = 0;
for (const c of cases) {
  const got = buildConfig(c.child, c.now, c.options); delete got.version;
  for (const [mid, m] of Object.entries(got.modes)) { if (m.whereTo && m.location === "gps" && c.child.modes?.[mid]?.location !== "gps") m.location = "off"; delete m.voiceTyping; delete m.voiceCmds; delete m.whereTo; }   // Worker-only additions: the Home Assistant code has no voice settings
  try { assert.equal(canon(got), canon(c.cfg)); n++; console.log("  ok  " + c.name); }
  catch (e) { bad++; console.log("  FAIL " + c.name); for (const k of new Set([...Object.keys(got), ...Object.keys(c.cfg)])) if (canon(got[k]) !== canon(c.cfg[k])) console.log("     differs:", k, canon(got[k])?.slice(0, 300), "\n     expected:", canon(c.cfg[k])?.slice(0, 300)); }
}
const a = buildConfig(cases[0].child, cases[0].now), b = buildConfig(cases[0].child, cases[0].now), o = buildConfig({ ...cases[0].child, override: "night" }, cases[0].now);
assert.equal(a.version, b.version); assert.notEqual(a.version, o.version); assert.ok(Number.isSafeInteger(a.version) && a.version < 2 ** 48); n++; console.log("  ok  version is stable, changes with the config, fits 48 bits");
console.log(bad ? `\n${bad} FAILED` : `\n${n} checks passed`); process.exit(bad ? 1 : 0);
