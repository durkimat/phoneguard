// The parent actions and sync merging (src/hub), mirroring the Home Assistant hub tests.
import assert from "node:assert/strict";
import * as H from "../src/hub/hub.js";
import * as S from "../src/hub/schedule.js";
import { mergeSync, newRuntime } from "../src/hub/hub.js";
const T0 = 1_794_000_000_000;
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const throwsMsg = (fn, re) => assert.throws(fn, re);
const fresh = () => ({ ch: S.defaultChild("Her"), rt: newRuntime() });

{ const { ch, rt } = fresh();
  const b = H.boost(rt, "day", 15, T0); assert.equal(b.until, T0 + 15 * 60_000);
  const b2 = H.boost(rt, "day", 15, T0); assert.equal(b2.until, b.until + 15 * 60_000); ok("boost presses add time, like the heating boost");
  H.boost(rt, "day", 500, T0 + 1); assert.ok(rt.boost.until <= T0 + 1 + H.BOOST_CAP_MIN * 60_000); ok("boost is capped at 3 hours");
  assert.equal(H.activeBoost(rt, T0 + 4 * 3600_000), null); assert.equal(rt.boost, null); ok("an expired boost disappears");
  H.boost(rt, "night", 5, T0); H.cancelBoost(rt); assert.equal(H.activeBoost(rt, T0), null); ok("cancel boost"); }

{ const { ch } = fresh();
  H.setOverride(ch, "night", T0); assert.equal(ch.override, "night"); assert.equal(S.buildConfig(ch, T0).override, "night");
  H.setOverride(ch, "auto", T0); assert.equal(ch.override, null); assert.ok(!("override" in S.buildConfig(ch, T0))); ok("override forces a mode and 'auto' returns to the timetable");
  throwsMsg(() => H.setOverride(ch, "nope", T0), /does not exist/); assert.equal(ch.override, null); ok("an unknown override is refused and not kept");
  H.setHoliday(ch, "2026-11-09", "2026-11-13"); assert.deepEqual(ch.holiday, { from: "2026-11-09", until: "2026-11-13" });
  const cfg = S.buildConfig(ch, T0); assert.ok(cfg.segments.some((s) => s.mode === "day") && !cfg.segments.some((s) => s.mode === "school" && s.from > T0 && s.from < T0 + 6 * 86400_000)); ok("holiday turns school into day for its dates");
  H.setHoliday(ch, null, null); assert.equal(ch.holiday, null); ok("holiday cleared"); }

{ const { ch, rt } = fresh(); const a = [{ pkg: "a.one", label: "One" }, { pkg: "a.two", label: "Two" }];
  mergeSync(ch, rt, { state: { battery: 80 }, apps: a, cfgh: "x" }, T0); assert.deepEqual(H.view(ch, rt, null, null, T0).pending_apps, []); ok("apps present at the first report are not new");
  mergeSync(ch, rt, { apps: [...a, { pkg: "a.new", label: "New" }] }, T0 + 1); assert.deepEqual(H.view(ch, rt, null, null, T0).pending_apps.map((x) => x.pkg), ["a.new"]); ok("a later app is pending");
  H.approveApp(ch, "a.new", ["day", "night"]); const v = H.view(ch, rt, null, null, T0);
  assert.deepEqual(v.pending_apps, []); assert.ok(v.modes.night.allow.includes("a.new")); ok("approving ticks it in the chosen modes and clears it");
  H.setAppMode(ch, "a.two", "school", true); assert.ok(ch.modes.school.allow.includes("a.two")); H.setAppMode(ch, "a.two", "school", false); assert.ok(!ch.modes.school.allow.includes("a.two")); ok("per-app tick on and off");
  H.setModeAll(ch, rt, "school", true); assert.ok(["a.one", "a.two", "a.new"].every((p) => ch.modes.school.allow.includes(p))); H.setModeAll(ch, rt, "school", false); assert.ok(!ch.modes.school.allow.includes("a.one")); ok("select-all for a mode");
  assert.equal(ch.modes.day.allowAll, false); assert.ok(ch.modes.day.allow.includes("a.one")); ok("an old allow-everything mode becomes an explicit list once");
  ch.modes.day.auto_new = true; mergeSync(ch, rt, { apps: [...a, { pkg: "a.new", label: "New" }, { pkg: "b.auto", label: "Auto" }] }, T0 + 2); assert.ok(ch.modes.day.allow.includes("b.auto")); ok("a mode that allows new apps ticks them on first sight"); }

{ const { ch } = fresh();
  H.updateChild(ch, { restrictions: ["no_factory_reset", "no_config_vpn"] }, T0); assert.deepEqual(ch.restrictions, ["no_factory_reset", "no_config_vpn"]); ok("restrictions patch");
  const snap = S.canon(ch);
  throwsMsg(() => H.updateChild(ch, { week: { 1: [[0, 100, "ghost"]] } }, T0), /does not have/); assert.equal(S.canon(ch), snap); ok("a timetable naming an unknown mode is refused and nothing changes");
  throwsMsg(() => H.updateChild(ch, { tz: "Mars/Olympus" }, T0), /timezone/); assert.equal(S.canon(ch), snap); ok("a bad time zone is refused and nothing changes");
  throwsMsg(() => H.updateChild(ch, { modes: { ...ch.modes, day: { ...ch.modes.day, wa: "weird" } } }, T0), /WhatsApp/); assert.equal(S.canon(ch), snap); ok("an unknown WhatsApp setting is refused");
  throwsMsg(() => H.updateChild(ch, { whatsapp: { perMinute: 99 } }, T0), /send limits/); ok("WhatsApp limits validated");
  H.updateChild(ch, { whatsapp: { all: false, allowed: [" +44123 ", "g:Fam", "+44123", ""] } }, T0); assert.deepEqual(ch.whatsapp.allowed, ["+44123", "g:Fam"]); ok("WhatsApp list tidied");
  H.updateChild(ch, { location: { lat: 51.75123, lon: -0.33987 } }, T0); assert.deepEqual(ch.location, { lat: 51.751, lon: -0.34 }); throwsMsg(() => H.updateChild(ch, { location: { lat: 99, lon: 0 } }, T0), /out of range/); ok("location rounded and range-checked");
  throwsMsg(() => H.updateChild(ch, { radio_stations: [{ name: "x", url: "http://plain" }] }, T0), /https/); H.updateChild(ch, { radio_stations: [{ name: " BBC ", url: "https://a" }, { name: "dup", url: "https://a" }] }, T0); assert.deepEqual(ch.radio_stations, [{ name: "BBC", url: "https://a" }]); ok("radio stations must be https and de-duplicated");
  throwsMsg(() => H.updateChild(ch, { look: { theme: "neon" } }, T0), /not one of/); H.updateChild(ch, { look: { theme: "amber", font: "" } }, T0); assert.deepEqual(ch.look, { theme: "amber" }); assert.equal(S.buildConfig(ch, T0).look.theme, "amber"); ok("look checked and reaches the phone config");
  H.updateChild(ch, { pinned: ["b", "a", "b", ""] }, T0); assert.deepEqual(ch.pinned, ["b", "a"]); ok("pinned apps tidied, order kept"); }

{ const a = S.defaultChild("A"), b = S.defaultChild("B"); b.person = "Her";
  H.updateChild(a, { restrictions: ["no_config_vpn"], week: { ...a.week, 6: [[0, 600, "night"], [600, 1440, "day"]] } }, T0);
  H.setAppMode(a, "only.on.a", "school", true); a.modes.school.gray = false;
  const done = H.copySettings(a, b, ["timetable", "restrictions", "mode_options"], T0); assert.deepEqual(done, ["timetable", "restrictions", "mode_options"]);
  assert.deepEqual(b.week["6"], [[0, 600, "night"], [600, 1440, "day"]]); assert.equal(b.modes.school.gray, false); assert.ok(!b.modes.school.allow.includes("only.on.a")); ok("copy settings keeps each phone's own app lists");
  throwsMsg(() => H.copySettings(a, b, ["nonsense"], T0), /unknown part/); ok("copy refuses an unknown part"); }

{ const { ch } = fresh(); let i = 0; const id = () => "id" + ++i;
  const c1 = H.queueApp(ch, "remove", "com.bad.app", "Bad", T0, {}, id); assert.equal(ch.commands.length, 1); assert.equal(S.buildConfig(ch, T0).appCommands[0].id, c1.id); ok("a removal is queued and reaches the config");
  throwsMsg(() => H.queueApp(ch, "remove", "com.bad.app", "Bad", T0, {}, id), /already waiting/); throwsMsg(() => H.queueApp(ch, "remove", H.OWN_PACKAGE, "me", T0, {}, id), /cannot be removed/); throwsMsg(() => H.queueApp(ch, "remove", "bad pkg!", "x", T0, {}, id), /cannot be removed/); ok("duplicate, own-package and malformed removals refused");
  throwsMsg(() => H.queueApp(ch, "update", H.OWN_PACKAGE, "pg", T0, { url: "http://x", sha256: "a".repeat(64) }, id), /https/); throwsMsg(() => H.queueApp(ch, "update", H.OWN_PACKAGE, "pg", T0, { url: "https://x", sha256: "zz" }, id), /checksum/); ok("an update needs an https address and a checksum");
  H.mergeAppResults(ch, [[c1.id, "done", "removed", T0 + 5], ["unknown", "done", "", 1], ["bad"]]); assert.equal(ch.commands.length, 0); assert.equal(ch.app_log.length, 1); assert.equal(ch.app_log[0][3], "done"); ok("a reported result finishes the job and is logged");
  const c2 = H.queueApp(ch, "remove", "com.other.app", "Other", T0, {}, id); H.cancelApp(ch, c2.id); assert.equal(ch.commands.length, 0); ok("a queued job can be cancelled"); }

{ const { ch, rt } = fresh(); const opts = { updates_enabled: false }, latest = { version: "0.4.0", url: "https://u", sha256: "b".repeat(64), size: 1 };
  rt.state = { version: "0.3.17" }; assert.equal(H.updateFor(rt, latest, opts).available, true);
  throwsMsg(() => H.queueUpdate(ch, rt, latest, opts, T0), /switched off/); const c = H.queueUpdate(ch, rt, latest, { updates_enabled: true }, T0); assert.equal(c.kind, "update"); ok("updates are off until switched on, then queue the newest release");
  rt.state = { version: "0.4.0" }; assert.equal(H.updateFor(rt, latest, opts).available, false); ok("no update when already current"); }

{ const { ch, rt } = fresh();
  mergeSync(ch, rt, { usage: { "2026-11-01": { "a.b": 60.9 }, junk: 1, "2026-11-02": { x: "no" } } }, T0); assert.deepEqual(rt.usage, { "2026-11-01": { "a.b": 60 }, "2026-11-02": {} }); ok("usage is merged and cleaned");
  const u = {}; for (let d = 1; d <= 40; d++) u["2026-10-" + String(d % 28 + 1).padStart(2, "0") + "-" + d] = {}; const big = {}; for (let d = 0; d < 35; d++) big[new Date(Date.UTC(2026, 9, 1 + d)).toISOString().slice(0, 10)] = { p: 1 }; mergeSync(ch, rt, { usage: big }, T0); assert.ok(Object.keys(rt.usage).length <= H.USAGE_DAYS); ok("only the last 30 days of usage are kept");
  H.mergeFix(rt, [[T0, 51.7, -0.3, 20, "gps", "day"], [T0 - 5, 1, 1, 1, "x"], [T0 + 9, 99, 0, 5, "gps"], ["bad"]]); assert.equal(rt.fix[0], T0); ok("only valid positions are kept and the newest wins");
  mergeSync(ch, rt, { power: [[T0, 80, 2000000, 0, 0, 1, "day", 4, 1, 0, 300, 0, "x"]], reaped: [[T0, "p.q", "Pq", "idle", 0]], labels: { "p.q": "Pq" } }, T0 + 60_000); assert.equal(rt.power.length, 1); assert.equal(rt.reaped.length, 1); assert.equal(rt.labels["p.q"], "Pq"); ok("power diary, power-saver log and labels are kept"); }

{ const { ch, rt } = fresh(); mergeSync(ch, rt, { state: { battery: 55, version: "0.3.17", mode: "day" }, apps: [{ pkg: "a.b", label: "B" }] }, T0);
  const v = H.view(ch, rt, { spotify_client_id: "a".repeat(32) }, null, T0); assert.equal(v.name, "Her"); assert.equal(v.mode_now, "night" in v.modes ? v.mode_now : v.mode_now); assert.equal(v.device.state.battery, 55); assert.equal(v.options.spotify_client_id, "a".repeat(32)); assert.ok(v.power && v.reaped); ok("the page view has settings, mode now, device state, options, power"); }
console.log(`\n${n} checks passed`);
