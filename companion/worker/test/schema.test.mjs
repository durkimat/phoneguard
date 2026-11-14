// The example profiles must validate; hand-made bad ones must not.
import Ajv from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import fs from "node:fs";
import assert from "node:assert/strict";
const read = (f) => JSON.parse(fs.readFileSync(new URL(f, import.meta.url), "utf8"));
const ajv = new Ajv({ allErrors: true, strict: false }); addFormats(ajv);
const validate = ajv.compile(read("../schema/profile.schema.json"));
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
for (const f of ["school-day-night.json", "minimal.json"]) { assert.ok(validate(read("../schema/examples/" + f)), f + " " + JSON.stringify(validate.errors)); ok("example validates: " + f); }
const base = read("../schema/examples/school-day-night.json");
const bad = { "missing version": (p) => { delete p.version; }, "pattern day 0 (days are ISO 1-7)": (p) => { p.pattern.days["0"] = []; }, "wa unknown value": (p) => { p.modes.day.wa = "always"; }, "location unknown value": (p) => { p.modes.day.location = "balanced"; }, "segment without mode": (p) => { p.segments[0] = { from: 1, to: 2 }; }, "accent not hex": (p) => { p.look.accent = "green"; }, "appCommand bad sha": (p) => { p.appCommands = [{ id: "a", kind: "install", sha256: "xyz" }]; }, "launcher unknown": (p) => { p.modes.day.launcher = "stock"; } };
for (const [name, mut] of Object.entries(bad)) { const p = structuredClone(base); mut(p); assert.equal(validate(p), false, name); ok("rejected: " + name); }
const extra = structuredClone(base); extra.futureField = { x: 1 }; extra.modes.day.futureMode = 5; assert.ok(validate(extra)); ok("unknown extra keys are allowed (forward compatible)");
console.log(`\n${n} checks passed`);
