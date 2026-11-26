// The phone's radio diary (app 0.5.2 on): keep it and boil it down, so a parent can see that the radios really go off.
// A line is [time ms, locked 0/1, Wi-Fi 0/1, Bluetooth 0/1, mobile data 0/1 or -1 without a SIM, reason], written when any of these changes,
// with the radios as the phone read them back. So each line holds until the next one; the last holds until the phone was last heard from.
export const KEEP_MS = 3 * 24 * 3600 * 1000, MAX_LINES = 3000, MAX_SPAN_MS = 6 * 3600 * 1000, LATEST = 40;
export const RESTING = "resting"; // the phone's reason when the radios ought to be off (RadioLog.RESTING)

const flag = (x) => x === 0 || x === 1;
const valid = (e) => Array.isArray(e) && e.length >= 6 && Number.isInteger(e[0]) && flag(e[1]) && flag(e[2]) && flag(e[3]) && (e[4] === -1 || flag(e[4])) && typeof e[5] === "string";
const anyOn = (e) => e[2] === 1 || e[3] === 1 || e[4] === 1;

export function merge(store, added, nowMs) {
  let last = store.length ? store[store.length - 1][0] : 0;
  if (Array.isArray(added)) for (const e of added) if (valid(e) && e[0] > last) { store.push([e[0], e[1], e[2], e[3], e[4], e[5].replace(/[\u0000-\u001f\u007f]+/g, " ").slice(0, 80)]); last = e[0]; }
  return store.filter((e) => e[0] >= nowMs - KEEP_MS).slice(-MAX_LINES);
}

const hours = (ms) => Math.round(ms / 36000) / 100;

// seenMs: when the phone was last heard from. Gaps are cut at MAX_SPAN_MS (a phone switched off writes nothing).
export function summarize(entries, seenMs, nowMs, windowHours = 24) {
  const from = nowMs - windowHours * 3600 * 1000, why = {}, faults = [];
  let locked = 0, off = 0;
  entries.forEach((e, i) => {
    const end = i + 1 < entries.length ? entries[i + 1][0] : Math.max(e[0], seenMs ?? e[0]);
    const span = Math.max(0, Math.min(end, e[0] + MAX_SPAN_MS) - Math.max(e[0], from));
    if (e[0] >= from && e[1] && e[5] === RESTING && anyOn(e)) faults.push(e);
    if (!e[1] || !span) return;
    locked += span;
    if (anyOn(e)) why[e[5]] = (why[e[5]] ?? 0) + span; else off += span;
  });
  return { lines: entries.length, window_hours: windowHours, locked_h: hours(locked), off_h: hours(off),
    on_why: Object.entries(why).sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, ms]) => [k, hours(ms)]),
    faults: faults.length, last_fault: faults.length ? faults[faults.length - 1] : null, latest: entries.slice(-LATEST).reverse() };
}
