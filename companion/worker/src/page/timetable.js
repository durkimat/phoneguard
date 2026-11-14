// Turns the weekly pattern into the resolved timetable the phone follows: absolute instants for the next `days` days.
// The phone does no calendar or time-zone logic (see schema `segments`); this runs in the parent's browser and in tests.

// UTC instant of local wall-clock (y, m, d, minute-of-day) in an IANA zone. Handles DST by correcting the guess with the zone's real offset.
export function zonedToUtc(y, m, d, minute, tz) {
  const fmt = new Intl.DateTimeFormat("en-GB", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric", second: "numeric" });
  const offsetAt = (utc) => { const p = Object.fromEntries(fmt.formatToParts(new Date(utc)).map((x) => [x.type, +x.value])); return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - utc; };
  const wall = Date.UTC(y, m - 1, d, 0, minute);
  let utc = wall - offsetAt(wall);
  utc = wall - offsetAt(utc);
  return utc;
}

const todayIn = (tz, nowMs) => { const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: tz, year: "numeric", month: "numeric", day: "numeric" }).formatToParts(new Date(nowMs)).map((x) => [x.type, +x.value])); return { y: p.year, m: p.month, d: p.day }; };

export function resolveSegments(pattern, nowMs = Date.now(), days = 14) {
  const out = [], { y, m, d } = todayIn(pattern.tz, nowMs);
  for (let i = 0; i < days; i++) {
    const day = new Date(Date.UTC(y, m - 1, d + i));               // calendar arithmetic only; the zone is applied below
    const iso = day.getUTCDay() === 0 ? 7 : day.getUTCDay();
    const blocks = [...(pattern.days[String(iso)] ?? [])].sort((a, b) => a[0] - b[0]);
    for (const [start, end, mode] of blocks) {
      const from = zonedToUtc(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate(), start, pattern.tz);
      const to = end >= 1440 ? zonedToUtc(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate() + 1, 0, pattern.tz) : zonedToUtc(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate(), end, pattern.tz);
      if (to > nowMs && to > from) out.push({ from, to, mode });
    }
  }
  return out;
}

// Problems a parent should hear about before saving: overlaps, unknown modes, backwards times.
export function checkPattern(pattern, modes) {
  const problems = [];
  for (const [day, blocks] of Object.entries(pattern.days)) {
    const sorted = [...blocks].sort((a, b) => a[0] - b[0]);
    sorted.forEach(([s, e, mode], i) => {
      if (e <= s) problems.push(`Day ${day}: a block ends before it starts`);
      if (!(mode in modes)) problems.push(`Day ${day}: unknown mode "${mode}"`);
      if (i && s < sorted[i - 1][1]) problems.push(`Day ${day}: two blocks overlap`);
    });
  }
  return problems;
}
