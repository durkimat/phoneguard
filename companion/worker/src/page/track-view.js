// The location history screen: pick a day, play it back on a map. Quiet stretches are skipped quickly and journeys slow down so movements are easy to follow.
import { h, status, select } from "/ui.js";
import L from "/map.js";
import { buildFrames, positionAt, stops, totalMetres, dayKey, dayRange, displayTimeOf } from "/track.js";
import { zonedToUtc } from "/timetable.js";

const fmtTime = (t, tz) => new Intl.DateTimeFormat("en-GB", { timeZone: tz, hour: "2-digit", minute: "2-digit" }).format(new Date(t));
const fmtDay = (key) => new Date(key + "T12:00:00Z").toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const fmtDur = (ms) => { const m = Math.round(ms / 60000); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; };
const fmtDist = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m / 10) * 10} m`);

export async function trackScreen({ api, app, back }, did, name, tz) {
  const all = await api(`/devices/${did}/track?max=8000`), pts = all.points;
  const days = [...pts.reduce((m, p) => m.set(dayKey(p[0], tz), (m.get(dayKey(p[0], tz)) ?? 0) + 1), new Map())].sort((a, b) => (a[0] < b[0] ? 1 : -1));
  let day = days[0]?.[0], speed = 1, playing = false, d = 0, raf = 0, last = 0, map = null, frames = null, dayPts = [];
  const stSet = status(), stopsBox = h("div", {}), chips = h("div", { class: "row" }), mapDiv = h("div", { id: "map", style: "height:55vh;min-height:300px;border-radius:12px;border:1px solid var(--line)", role: "img", "aria-label": "Map of where the phone has been" });
  const playBtn = h("button", { class: "pri", on: { click: () => toggle() } }, "Play"), slider = h("input", { type: "range", min: 0, max: 1000, value: 0, "aria-label": "Playback position", style: "flex:1;min-width:8rem", on: { input: (e) => { pause(); d = (e.target.value / 1000) * frames.total; draw(); } } });
  const timeLbl = h("div", { class: "big", style: "font-size:20px" }), sumLbl = h("p", { class: "mut" });
  const speedSel = select([[0.5, "Slower"], [1, "Normal"], [2, "Faster"], [4, "Much faster"]], 1, (x) => { const keep = realNow(); speed = Number(x); frames = buildFrames(dayPts, speed); d = displayTimeOf(frames, keep); draw(); });
  const retSel = select([[0, "Off"], [1, "1 day"], [3, "3 days"], [7, "7 days"], [14, "14 days"]], all.retention_days, async (x) => { try { await api("/options", "POST", { history_days: Number(x) }); stSet("ok", Number(x) ? `Keeping ${x} days.` : "History off and cleared."); } catch (e) { stSet("bad", e.message); } });
  let dot, trail, route, startM, endM;

  const realNow = () => positionAt(frames, dayPts, d)?.t ?? dayPts[0]?.[0] ?? 0;
  function draw() {
    const pos = positionAt(frames, dayPts, d); if (!pos || !map) return;
    dot.setLatLng([pos.lat, pos.lon]);
    const upto = dayPts.slice(0, (pos.seg < 0 ? 0 : pos.seg) + 1).map((p) => [p[1], p[2]]); upto.push([pos.lat, pos.lon]); trail.setLatLngs(upto);
    if (!map.getBounds().pad(-0.1).contains([pos.lat, pos.lon])) map.panTo([pos.lat, pos.lon], { animate: false });
    slider.value = frames.total ? Math.round((d / frames.total) * 1000) : 0;
    timeLbl.textContent = `${fmtTime(pos.t, tz)}${pos.moving ? "  (moving)" : ""}`; dot.setStyle({ fillColor: pos.moving ? "#d42a1e" : "#185fa5" });
  }
  function tick(ts) { if (!playing) return; d += Math.min(0.25, (ts - last) / 1000); last = ts; if (d >= frames.total) { d = frames.total; draw(); return pause(); } draw(); raf = requestAnimationFrame(tick); }
  function toggle() { if (playing) return pause(); if (d >= frames.total) d = 0; playing = true; playBtn.textContent = "Pause"; last = performance.now(); raf = requestAnimationFrame(tick); }
  function pause() { playing = false; cancelAnimationFrame(raf); playBtn.textContent = d >= (frames?.total ?? 0) ? "Replay" : "Play"; }

  function load(key) {
    pause(); day = key; const [a, b] = dayRange(key, tz, zonedToUtc); dayPts = pts.filter((p) => p[0] >= a && p[0] <= b); frames = buildFrames(dayPts, speed); d = 0;
    for (const c of chips.children) c.classList.toggle("pri", c.dataset.day === key);
    if (route) { [route, trail, startM, endM].forEach((x) => x.remove()); }
    const ll = dayPts.map((p) => [p[1], p[2]]);
    route = L.polyline(ll, { color: "#888", weight: 3, opacity: 0.7 }).addTo(map); trail = L.polyline([], { color: "#185fa5", weight: 5 }).addTo(map);
    startM = L.circleMarker(ll[0], { radius: 6, color: "#3b6d11", fillColor: "#3b6d11", fillOpacity: 1 }).addTo(map).bindTooltip("Start " + fmtTime(dayPts[0][0], tz));
    endM = L.circleMarker(ll[ll.length - 1], { radius: 6, color: "#a32d2d", fillColor: "#a32d2d", fillOpacity: 1 }).addTo(map).bindTooltip("End " + fmtTime(dayPts[dayPts.length - 1][0], tz));
    if (!dot) dot = L.circleMarker(ll[0], { radius: 9, color: "#fff", weight: 3, fillColor: "#185fa5", fillOpacity: 1 }).addTo(map); else dot.setLatLng(ll[0]);
    map.fitBounds(L.latLngBounds(ll).pad(0.2), { maxZoom: 17 });
    const st = stops(dayPts); sumLbl.textContent = `${dayPts.length} positions · about ${fmtDist(totalMetres(dayPts))} moved · ${st.length} stop${st.length === 1 ? "" : "s"}`;
    stopsBox.replaceChildren(...st.map((s) => h("button", { class: "small", style: "display:block;margin:4px 0;text-align:left", on: { click: () => { pause(); d = displayTimeOf(frames, s.from); draw(); map.setView([s.lat, s.lon], Math.max(map.getZoom(), 16)); } } }, `Stayed ${fmtTime(s.from, tz)} to ${fmtTime(s.to, tz)} (${fmtDur(s.to - s.from)})`)));
    pause(); playBtn.textContent = "Play"; draw();
  }

  const clear = h("button", { on: { click: async () => { if (confirm(`Delete all stored location history for ${name}?`)) { await api(`/devices/${did}/track/clear`, "POST", {}); trackScreen({ api, app, back }, did, name, tz); } } } }, "Clear history");
  const settings = h("div", { class: "card" }, h("strong", {}, "History"), h("p", { class: "mut" }, `Positions are kept in your Cloudflare account for the number of days below, then deleted. ${all.stored} stored now.`), h("div", { class: "row" }, "Keep", retSel, clear), stSet.el);
  const header = h("div", { class: "row" }, h("button", { on: { click: () => { pause(); back(); } } }, "Back"), h("h2", { style: "margin:0" }, `Where ${name} has been`));
  if (!days.length) { app.replaceChildren(header, h("p", {}, "No positions have been stored yet. In the phone's settings turn Location on for a mode (Mode settings, Location: Low accuracy or GPS), then wait for a check-in or two."), settings); return; }
  for (const [k, n] of days) chips.append(h("button", { "data-day": k, on: { click: () => load(k) } }, `${fmtDay(k)} (${n})`));
  app.replaceChildren(header, chips, mapDiv, h("div", { class: "row" }, playBtn, slider, speedSel), timeLbl, sumLbl, h("strong", {}, "Stops"), stopsBox, settings,
    h("p", { class: "mut" }, "Map tiles come from OpenStreetMap, so your browser asks them for the area you are looking at. Quiet stretches play fast and journeys play slowly."));
  map = L.map(mapDiv, { zoomControl: true }); L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap contributors" }).addTo(map);
  requestAnimationFrame(() => { map.invalidateSize(); load(day); });   // the map needs its final size before it can fit the route
}
