// The phone's battery diary and the power saver's log: keep them and boil them down. Port of ha/.../power.py.
export const KEEP_MS = 14 * 24 * 3600 * 1000, MAX_SAMPLES = 8000, MAX_GAP_MS = 3 * 3600 * 1000, MIN_GAP_MS = 30 * 1000, REAPED_KEEP = 300;
const [T, LEVEL, UAH, , PLUG, SCREEN, MODE, , WIFI, LOC, TEMP] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const num = (x) => typeof x === "number" && Number.isFinite(x);
const valid = (s) => Array.isArray(s) && s.length >= 12 && s.slice(0, 6).every(num) && typeof s[MODE] === "string";

export function merge(store, added, nowMs) {
  let last = store.length ? store[store.length - 1][T] : 0;
  if (Array.isArray(added)) for (const s of added) if (valid(s) && s[T] > last) { store.push(s); last = s[T]; }
  const cut = nowMs - KEEP_MS;
  return store.filter((s) => s[T] >= cut).slice(-MAX_SAMPLES);
}

const bucket = (d, key, hours, mah) => { const b = (d[key] ??= { hours: 0, mah: 0 }); b.hours += hours; b.mah += mah; };
const round = (x, n) => Math.round(x * 10 ** n) / 10 ** n;
const finish = (b) => ({ hours: round(b.hours, 2), mah_per_h: b.hours > 0 ? round(b.mah / b.hours, 1) : null });

export function summarize(samples, nowMs, hours = 24) {
  const recent = samples.filter((s) => s[T] >= nowMs - hours * 3600 * 1000);
  const total = { hours: 0, mah: 0 }, screen = {}, mode = {}, loc = {}, wifi = {};
  for (let i = 0; i + 1 < recent.length; i++) {
    const a = recent[i], b = recent[i + 1], dt = b[T] - a[T];
    if (dt < MIN_GAP_MS || dt > MAX_GAP_MS || a[PLUG] || b[PLUG]) continue;
    let used = (a[UAH] - b[UAH]) / 1000; if (used < -1) continue; used = Math.max(used, 0);
    const h = dt / 3600000; total.hours += h; total.mah += used;
    bucket(screen, a[SCREEN] ? "on" : "off", h, used); bucket(mode, String(a[MODE]), h, used); bucket(loc, a[LOC] ? "on" : "off", h, used); bucket(wifi, a[WIFI] ? "on" : "off", h, used);
  }
  const f = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, finish(v)]));
  const out = { window_hours: hours, on_battery: finish(total), by_screen: f(screen), by_mode: f(mode), by_location: f(loc), by_wifi: f(wifi) };
  if (samples.length) {
    const last = samples[samples.length - 1];
    out.level = last[LEVEL]; out.plugged = Boolean(last[PLUG]); out.temp_c = round(last[TEMP] / 10, 1);
    if (last[LEVEL] > 0 && last[UAH] > 0) { out.full_mah = Math.round(last[UAH] / 1000 / (last[LEVEL] / 100)); const rate = out.on_battery.mah_per_h; if (rate && !last[PLUG]) out.hours_left = round(last[UAH] / 1000 / rate, 1); }
    out.seen_ms = last[T];
  }
  const step = Math.max(1, Math.floor(recent.length / 240));
  out.chart = recent.filter((_, i) => i % step === 0).map((s) => [s[T], s[LEVEL], s[PLUG], s[SCREEN], s[MODE]]);
  return out;
}

export function mergeReaped(old, added) {
  const seen = new Set(old.map((e) => e[0] + "|" + e[1])), out = [...old];
  if (Array.isArray(added)) for (const e of added) {
    if (Array.isArray(e) && e.length >= 5 && Number.isInteger(e[0]) && typeof e[1] === "string" && typeof e[2] === "string" && typeof e[3] === "string" && !seen.has(e[0] + "|" + e[1])) { out.push([e[0], e[1].slice(0, 80), e[2].slice(0, 60), e[3].slice(0, 20), e[4] ? 1 : 0]); seen.add(e[0] + "|" + e[1]); }
  }
  out.sort((a, b) => a[0] - b[0]);
  return out.slice(-REAPED_KEEP);
}

export function summarizeReaped(entries, nowMs, hours = 24) {
  const recent = entries.filter((e) => e[0] >= nowMs - hours * 3_600_000), real = recent.filter((e) => !e[4]), would = recent.filter((e) => e[4]);
  const byReason = {}; for (const e of real) byReason[e[3]] = (byReason[e[3]] ?? 0) + 1;
  const names = {}; for (const e of real.length ? real : would) names[e[2]] = (names[e[2]] ?? 0) + 1;
  return { closed: real.length, would: would.length, by_reason: byReason, top: Object.entries(names).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, 6), latest: recent.slice(-8).map((e) => [e[0], e[2], e[3], e[4]]).reverse() };
}
