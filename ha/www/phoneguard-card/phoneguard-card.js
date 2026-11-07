/* Phone Guard card v0.2.0. Parent view of one child's phone: status, boost, holiday, app approvals, pairing.
 * Config: type: custom:phoneguard-card, entity: sensor.phoneguard_<child>
 * Reads the JSON string in the sensor's `data` attribute; every action is a phoneguard.* service. */
const PG_VERSION = "0.2.0";

class PhoneGuardCard extends HTMLElement {
  setConfig(config) {
    if (!config.entity) throw new Error("entity is required (sensor.phoneguard_<child>)");
    this._config = config;
    this._last = null;
    this._ui = this._ui || { sas: "", from: "", until: "", boostMode: "day", pickModes: {}, tt: { sel: 1, draft: null, dirty: false } };
    if (!this._root) {
      this._root = this.attachShadow({ mode: "open" });
    }
  }

  getCardSize() { return 8; }
  static getStubConfig() { return { entity: "sensor.phoneguard_her" }; }

  set hass(hass) {
    this._hass = hass;
    const st = hass.states[this._config.entity];
    const raw = st ? st.attributes.data : null;
    const key = (raw || "") + (st ? st.state : "");
    // do not rebuild while a field inside the card has focus: that would eat what is being typed
    if (key === this._last) return;
    if (this._root.activeElement && this._root.activeElement.tagName === "INPUT") return;
    this._last = key;
    try { this._d = raw ? JSON.parse(raw) : null; } catch (e) { this._d = null; }
    if (!this._ui.tt.dirty) this._ui.tt.draft = null; // saved: show what Home Assistant now holds
    this._render();
  }

  _svc(service, data) {
    return this._hass.callService("phoneguard", service, Object.assign({ child: this._d.child }, data || {}));
  }

  _ago(ms) {
    const s = Math.max(0, Math.round((this._d.now - ms) / 1000));
    if (s < 90) return s + "s ago";
    if (s < 5400) return Math.round(s / 60) + " min ago";
    if (s < 172800) return Math.round(s / 3600) + " h ago";
    return Math.round(s / 86400) + " days ago";
  }

  _fmtTime(ms) { return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }); }
  _esc(s) { return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }

  _render() {
    const d = this._d;
    const css = `
      :host { display:block; }
      .hcard { background: var(--card-background-color, #fff); color: var(--primary-text-color, #222);
        border-radius: var(--ha-card-border-radius, 12px); box-shadow: var(--ha-card-box-shadow, none);
        border: var(--ha-card-border-width, 1px) solid var(--ha-card-border-color, var(--divider-color, #ddd));
        padding: 16px; font-family: var(--paper-font-body1_-_font-family, inherit); }
      h2 { margin: 0 0 4px; font-size: 20px; font-weight: 500; }
      h3 { margin: 18px 0 8px; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; color: var(--secondary-text-color); }
      .row { display:flex; flex-wrap: wrap; gap: 8px; align-items: center; }
      .sub { color: var(--secondary-text-color); font-size: 13px; }
      .chip { display:inline-block; padding: 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 600;
        background: var(--primary-color); color: var(--text-primary-color, #fff); }
      .chip.warn { background: var(--warning-color, #e67e22); }
      .chip.off { background: var(--disabled-color, #999); }
      button { font: inherit; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--divider-color, #ccc);
        background: var(--secondary-background-color, #f3f3f3); color: inherit; cursor: pointer; min-height: 40px; }
      button.primary { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: transparent; }
      button.danger { color: var(--error-color, #c0392b); }
      input[type=text], input[type=date] { font: inherit; padding: 8px; border-radius: 8px; min-height: 40px; box-sizing: border-box;
        border: 1px solid var(--divider-color, #ccc); background: var(--card-background-color, #fff); color: inherit; }
      input.sas { width: 7em; font-size: 20px; letter-spacing: .2em; text-align: center; }
      table { width: 100%; border-collapse: collapse; }
      th, td { padding: 6px 4px; text-align: center; border-bottom: 1px solid var(--divider-color, #eee); }
      th:first-child, td:first-child { text-align: left; }
      td.name { word-break: break-word; }
      td.name small { display:block; color: var(--secondary-text-color); font-size: 11px; }
      input[type=checkbox] { width: 22px; height: 22px; }
      .new { background: color-mix(in srgb, var(--warning-color, #e67e22) 14%, transparent); border-radius: 8px; padding: 10px; margin-bottom: 8px; }
      .qr svg { width: 240px; max-width: 100%; height: auto; background: #fff; padding: 8px; border-radius: 8px; }
      .code { font-family: monospace; font-size: 12px; word-break: break-all; }
      .bigsas { font-size: 34px; font-weight: 600; letter-spacing: .2em; }
      .mut { opacity: .6; }
      .days { display:flex; gap:4px; }
      .day { flex:1; min-width:0; padding:4px 2px 6px; border-radius:8px; border:2px solid transparent; background:none; text-align:center; font-size:12px; min-height:0; }
      .day.sel { border-color: var(--primary-color); }
      .bar { display:flex; height:46px; width:70%; margin:4px auto 0; border-radius:6px; overflow:hidden; flex-direction:column; }
      .bar i { display:block; width:100%; }
      .legend { display:flex; gap:12px; flex-wrap:wrap; margin:8px 0; font-size:13px; }
      .legend b { display:inline-block; width:12px; height:12px; border-radius:3px; margin-right:4px; vertical-align:-1px; }
      .chg { display:flex; gap:8px; align-items:center; margin:6px 0; }
      .chg input[type=time] { width: 8em; }
      .chg select { font: inherit; padding: 8px; border-radius: 8px; min-height: 40px; border: 1px solid var(--divider-color, #ccc); background: var(--card-background-color, #fff); color: inherit; }
      .chg button { min-width: 40px; padding: 8px; }
      .copyrow { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin-top:8px; }
      button:disabled { opacity: .4; cursor: default; }
      .unsaved { color: var(--warning-color, #e67e22); font-weight: 600; font-size: 13px; }
    `;
    if (!d) {
      this._root.innerHTML = `<style>${css}</style><div class="hcard">Waiting for ${this._esc(this._config.entity)}…</div>`;
      return;
    }
    const dev = d.device;
    const stale = dev && (d.now - dev.last_seen) > 45 * 60000;
    const modeName = (d.modes[d.mode_now] || { name: d.mode_now }).name;
    let html = `<style>${css}</style><div class="hcard">`;

    // header
    html += `<div class="row"><h2>${this._esc(d.name)}</h2><span class="chip">${this._esc(modeName)}${d.boost ? " + boost" : ""}</span>`;
    if (dev && !dev.pending) html += `<span class="chip ${stale ? "warn" : "off"}">${stale ? "not heard " : "seen "}${this._ago(dev.last_seen)}</span>`;
    html += `</div>`;
    if (dev && !dev.pending) {
      const s = dev.state || {};
      const bits = [];
      if (s.battery != null) bits.push("battery " + s.battery + "%");
      if (s.hidden != null) bits.push(s.hidden + " apps hidden");
      if (s.gray) bits.push("grayscale on");
      if (s.canGray === false) bits.push("grayscale not permitted");
      if (!dev.in_sync) bits.push("config pending");
      if (d.boost) bits.push("boost until " + this._fmtTime(d.boost.until));
      html += `<div class="sub">${this._esc(bits.join(" · "))}</div>`;
    }

    // pairing
    if (!dev) {
      html += `<h3>Pair a phone</h3>`;
      if (d.pairing && d.pairing.qr_svg) {
        html += `<div class="qr">${d.pairing.qr_svg}</div>
          <div class="sub">On the phone: open Phone Guard, press "Pair: scan the QR code". Valid for 10 minutes. Code ${this._esc(d.pairing.code || "")}.</div>`;
      } else if (d.pairing && d.pairing.qr_text) {
        html += `<div class="code">${this._esc(d.pairing.qr_text)}</div>`;
      }
      html += `<div class="row" style="margin-top:8px"><button class="primary" data-act="pair">${d.pairing ? "New QR code" : "Start pairing"}</button></div>`;
    } else if (dev.pending) {
      html += `<h3>Confirm the phone</h3>
        <div class="sub">The phone shows a 4-digit number. Type it here to confirm this is the right phone.</div>
        <div class="row" style="margin-top:8px"><input class="sas" type="text" inputmode="numeric" maxlength="4" placeholder="1234" value="${this._esc(this._ui.sas)}">
        <button class="primary" data-act="confirm">Confirm</button><button data-act="forget" class="danger">Cancel pairing</button></div>`;
    } else {
      // boost
      html += `<h3>Extra time</h3><div class="row">
        <button data-act="boost" data-min="15">+15 min</button><button data-act="boost" data-min="30">+30 min</button>
        <button data-act="boost" data-min="60">+1 h</button>${d.boost ? `<button data-act="cancel_boost" class="danger">Cancel</button>` : ""}
        <button data-act="nudge">Sync now</button></div>`;
      if (d.boost) html += `<div class="sub">Day mode until ${this._fmtTime(d.boost.until)}. Each press adds time.</div>`;

      // holiday
      const h = d.holiday || {};
      html += `<h3>Holiday (School becomes Day)</h3><div class="row">
        <input type="date" data-f="from" value="${this._esc(this._ui.from || h.from || "")}">
        <input type="date" data-f="until" value="${this._esc(this._ui.until || h.until || "")}">
        <button data-act="holiday" class="primary">Set</button><button data-act="holiday_clear">Clear</button></div>`;
      if (d.holiday) html += `<div class="sub">On from ${this._esc(h.from)} to ${this._esc(h.until)}.</div>`;

      // weekly timetable
      html += this._timetableHtml(d);

      // new apps
      if (d.pending_apps.length) {
        html += `<h3>New apps waiting</h3>`;
        const modes = Object.keys(d.modes).filter((m) => !d.modes[m].allowAll);
        const all = Object.keys(d.modes);
        for (const pkg of d.pending_apps) {
          const app = d.apps.find((a) => a.pkg === pkg) || { pkg, label: pkg };
          html += `<div class="new"><b>${this._esc(app.label)}</b><div class="sub">${this._esc(pkg)} · hidden until you decide</div>
            <div class="row" style="margin-top:6px">${all.map((m) => `<label><input type="checkbox" data-pick="${this._esc(pkg)}" data-mode="${m}"> ${this._esc(d.modes[m].name)}</label>`).join(" ")}
            <button class="primary" data-act="approve" data-pkg="${this._esc(pkg)}">Approve</button>
            <button data-act="dismiss" data-pkg="${this._esc(pkg)}">Keep hidden</button></div></div>`;
        }
      }

      // matrix
      const editable = Object.keys(d.modes).filter((m) => !d.modes[m].allowAll);
      html += `<h3>Apps allowed in each mode</h3>`;
      if (!d.apps.length) html += `<div class="sub">The phone has not reported its apps yet.</div>`;
      else {
        html += `<table><tr><th>App</th>${editable.map((m) => `<th>${this._esc(d.modes[m].name)}</th>`).join("")}</tr>`;
        for (const a of d.apps) {
          html += `<tr><td class="name">${this._esc(a.label)}<small>${this._esc(a.pkg)}</small></td>` +
            editable.map((m) => `<td><input type="checkbox" data-app="${this._esc(a.pkg)}" data-mode="${m}" ${(d.modes[m].allow || []).includes(a.pkg) ? "checked" : ""}></td>`).join("") + `</tr>`;
        }
        html += `</table><div class="sub" style="margin-top:6px">Day allows everything. Phone, messages, contacts and Settings are always available.</div>`;
      }

      html += `<h3>Phone</h3><div class="row"><button data-act="release" class="danger">Release phone…</button></div>`;
      html += `<div class="sub mut" style="margin-top:6px">${this._esc(dev.model || "")} · Phone Guard card ${PG_VERSION}</div>`;
    }
    html += `</div>`;
    this._root.innerHTML = html;
    this._wire();
  }

  // ---- weekly timetable: a day is a list of changes ("from 08:30 it is School") ----------------------

  static get DAYS() { return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]; }
  static get PALETTE() { return { day: "#7fb069", school: "#4a90d9", night: "#6c5ba7" }; }
  static get EXTRA() { return ["#e0a030", "#d9643a", "#3aa6a0", "#a0a0a0"]; }

  _modeColor(id) {
    const known = PhoneGuardCard.PALETTE[id];
    if (known) return known;
    const ids = Object.keys(this._d.modes).filter((m) => !PhoneGuardCard.PALETTE[m]);
    return PhoneGuardCard.EXTRA[Math.max(0, ids.indexOf(id)) % PhoneGuardCard.EXTRA.length];
  }

  _weekToEntries(week, base) {
    const out = {};
    for (let dow = 1; dow <= 7; dow++) {
      const segs = (week[String(dow)] || []).slice().sort((a, b) => a[0] - b[0]);
      let cur = 0; const e = [];
      for (const [st, en, mode] of segs) {
        if (st > cur) e.push([cur, base]);
        e.push([st, mode]); cur = Math.max(cur, en);
      }
      if (cur < 1440) e.push([cur, base]);
      if (!e.length) e.push([0, base]);
      out[dow] = this._tidy(e);
    }
    return out;
  }

  // sort, force a start at midnight, drop duplicates at one time and merge neighbours with the same mode
  _tidy(list) {
    const byTime = new Map();
    for (const [t, m] of list) byTime.set(Math.max(0, Math.min(1435, t)), m);
    const sorted = [...byTime.entries()].sort((a, b) => a[0] - b[0]);
    if (!sorted.length || sorted[0][0] !== 0) sorted.unshift([0, sorted.length ? sorted[0][1] : this._d.base]);
    const out = [];
    for (const [t, m] of sorted) if (!out.length || out[out.length - 1][1] !== m) out.push([t, m]);
    return out;
  }

  _entriesToWeek(entries) {
    const week = {};
    for (let dow = 1; dow <= 7; dow++) {
      const e = this._tidy(entries[dow]);
      week[String(dow)] = e.map(([t, m], i) => [t, i + 1 < e.length ? e[i + 1][0] : 1440, m]);
    }
    return week;
  }

  _tt() { return this._ui.tt; }
  _entries() { return this._tt().draft || this._weekToEntries(this._d.week, this._d.base); }
  _edit(fn) {
    const tt = this._tt();
    tt.draft = tt.draft || this._weekToEntries(this._d.week, this._d.base);
    fn(tt.draft); tt.dirty = true; this._render();
  }

  _hm(min) { return String(Math.floor(min / 60)).padStart(2, "0") + ":" + String(min % 60).padStart(2, "0"); }

  _bar(list) {
    return `<div class="bar">${list.map(([t, m], i) => {
      const end = i + 1 < list.length ? list[i + 1][0] : 1440;
      return `<i style="flex:${end - t};background:${this._modeColor(m)}" title="${this._hm(t)}"></i>`;
    }).join("")}</div>`;
  }

  _timetableHtml(d) {
    const tt = this._tt(), entries = this._entries(), sel = tt.sel, list = entries[sel];
    const modeIds = Object.keys(d.modes);
    let h = `<h3>Weekly timetable</h3><div class="legend">${modeIds.map((m) => `<span><b style="background:${this._modeColor(m)}"></b>${this._esc(d.modes[m].name)}</span>`).join("")}</div>`;
    h += `<div class="days">${PhoneGuardCard.DAYS.map((n, i) => `<button class="day ${sel === i + 1 ? "sel" : ""}" data-tt="day" data-d="${i + 1}">${n}${this._bar(entries[i + 1])}</button>`).join("")}</div>`;
    h += `<div class="sub" style="margin-top:6px">The bars run from midnight (top) to midnight (bottom).</div><div class="sub" style="margin-top:8px">${PhoneGuardCard.DAYS[sel - 1]}: each line says what the phone does from that time until the next line.</div>`;
    list.forEach(([t, m], i) => {
      h += `<div class="chg"><input type="time" step="300" value="${this._hm(t)}" data-tt="time" data-i="${i}" ${i === 0 ? "disabled" : ""}>
        <select data-tt="mode" data-i="${i}">${modeIds.map((id) => `<option value="${id}" ${id === m ? "selected" : ""}>${this._esc(d.modes[id].name)}</option>`).join("")}</select>
        <button data-tt="del" data-i="${i}" ${i === 0 ? "disabled" : ""} aria-label="Remove this change">✕</button></div>`;
    });
    h += `<div class="row"><button data-tt="add">+ Add a change</button></div>`;
    h += `<div class="copyrow"><span class="sub">Copy ${PhoneGuardCard.DAYS[sel - 1]} to</span>
      <button data-tt="copy" data-to="weekdays">Mon–Fri</button><button data-tt="copy" data-to="weekend">Sat–Sun</button><button data-tt="copy" data-to="all">every day</button></div>`;
    h += `<div class="row" style="margin-top:12px"><button class="primary" data-tt="save" ${tt.dirty ? "" : "disabled"}>Save timetable</button>
      <button data-tt="discard" ${tt.dirty ? "" : "disabled"}>Discard</button>${tt.dirty ? `<span class="unsaved">Unsaved changes</span>` : ""}</div>`;
    h += `<div class="sub" style="margin-top:6px">Holiday dates above turn School into Day. Saving sends the new timetable to the phone straight away.</div>`;
    return h;
  }

  _ttEvent(el) {
    const a = el.dataset.tt, tt = this._tt(), sel = tt.sel;
    if (a === "day") { tt.sel = Number(el.dataset.d); return this._render(); }
    if (a === "add") return this._edit((e) => {
      const day = e[sel], last = day[day.length - 1];
      let t = Math.min(1435, last[0] + 60);
      while (day.some((x) => x[0] === t) && t > 5) t -= 5; // keep it free of an existing change
      const other = Object.keys(this._d.modes).find((m) => m !== last[1]) || last[1];
      e[sel] = this._tidy(day.concat([[t, other]]));
    });
    if (a === "del") return this._edit((e) => { e[sel].splice(Number(el.dataset.i), 1); e[sel] = this._tidy(e[sel]); });
    if (a === "time" || a === "mode") {
      const i = Number(el.dataset.i);
      return this._edit((e) => {
        const row = e[sel][i];
        if (a === "time" && el.value) { const [hh, mm] = el.value.split(":").map(Number); row[0] = hh * 60 + mm; }
        if (a === "mode") row[1] = el.value;
        e[sel] = this._tidy(e[sel]);
      });
    }
    if (a === "copy") {
      const to = el.dataset.to === "weekdays" ? [1, 2, 3, 4, 5] : el.dataset.to === "weekend" ? [6, 7] : [1, 2, 3, 4, 5, 6, 7];
      return this._edit((e) => { for (const dow of to) if (dow !== sel) e[dow] = e[sel].map((x) => x.slice()); });
    }
    if (a === "discard") { tt.draft = null; tt.dirty = false; return this._render(); }
    if (a === "save") {
      const week = this._entriesToWeek(this._entries());
      tt.dirty = false;
      return this._svc("set_child", { config: { week } }).catch((err) => { tt.dirty = true; this._render(); alert("Not saved: " + (err.message || err)); });
    }
  }

  _wire() {
    const r = this._root;
    const sas = r.querySelector("input.sas");
    if (sas) sas.addEventListener("input", () => { this._ui.sas = sas.value; });
    r.querySelectorAll("input[data-f]").forEach((i) => i.addEventListener("change", () => { this._ui[i.dataset.f] = i.value; }));
    r.querySelectorAll("input[data-app]").forEach((i) =>
      i.addEventListener("change", () => this._svc("set_app_mode", { package: i.dataset.app, mode: i.dataset.mode, allowed: i.checked })));
    r.querySelectorAll("button[data-act]").forEach((b) => b.addEventListener("click", () => this._act(b)));
    r.querySelectorAll("[data-tt]").forEach((el) => {
      const ev = el.tagName === "BUTTON" ? "click" : "change";
      el.addEventListener(ev, () => this._ttEvent(el));
    });
  }

  _act(b) {
    const a = b.dataset.act;
    const d = this._d;
    if (a === "pair") return this._svc("create_pairing", { name: d.name });
    if (a === "confirm") return this._svc("confirm_pairing", { sas: this._ui.sas }).then(() => { this._ui.sas = ""; });
    if (a === "forget") return this._svc("forget");
    if (a === "boost") return this._svc("boost", { mode: "day", minutes: Number(b.dataset.min) });
    if (a === "cancel_boost") return this._svc("cancel_boost");
    if (a === "nudge") return this._svc("nudge");
    if (a === "holiday") return this._svc("set_holiday", { from: this._ui.from || (d.holiday || {}).from, until: this._ui.until || (d.holiday || {}).until });
    if (a === "holiday_clear") { this._ui.from = this._ui.until = ""; return this._svc("set_holiday", {}); }
    if (a === "dismiss") return this._svc("dismiss_app", { package: b.dataset.pkg });
    if (a === "approve") {
      const modes = [...this._root.querySelectorAll(`input[data-pick="${CSS.escape(b.dataset.pkg)}"]:checked`)].map((i) => i.dataset.mode);
      return this._svc("approve_app", { package: b.dataset.pkg, modes });
    }
    if (a === "release" && confirm("Release this phone? It stops being managed and everything is unhidden on its next sync.")) return this._svc("release");
  }
}

customElements.define("phoneguard-card", PhoneGuardCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "phoneguard-card", name: "Phone Guard", description: "Parent view of a supervised phone" });
