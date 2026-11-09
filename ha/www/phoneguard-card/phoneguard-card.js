/* Phone Guard card v0.22.0. Parent view of one child's phone: status, boost, holiday, app approvals, pairing.
 * Config: type: custom:phoneguard-card   (shows every guarded device, with tabs)
 *         optional  entity: sensor.phoneguard_<child>   to show just one
 * Reads the JSON string in the sensor's `data` attribute; every action is a phoneguard.* service. */
const PG_VERSION = "0.22.0";

class PhoneGuardCard extends HTMLElement {
  setConfig(config) {
    this._config = config || {};
    this._last = null;
    if (!this._ui) {
      let sel = null;
      try { sel = localStorage.getItem("phoneguard-card:selected"); } catch (e) { /* private mode */ }
      this._ui = { sel, sas: "", from: "", until: "", boostMode: "day", adding: false, addMode: "", newName: "", tts: {}, usageDay: null, newPerson: "", newPersonName: "", copyOpen: false, copyTo: "", copyParts: { timetable: true, holiday: false, restrictions: false, mode_options: false, whatsapp: false }, renaming: false, rnPerson: "", rnName: "" };
    }
    if (!this._root) this._root = this.attachShadow({ mode: "open" });
  }

  getCardSize() { return 8; }
  static getStubConfig() { return {}; }

  // HA people (person.*), with their picture and the trackers assigned to them
  _persons(hass) {
    return Object.keys(hass.states).filter((id) => id.startsWith("person.")).map((id) => {
      const a = hass.states[id].attributes;
      return { id, name: a.friendly_name || id.slice(7), picture: a.entity_picture || "", trackers: a.device_trackers || [] };
    }).sort((x, y) => x.name.localeCompare(y.name));
  }

  // The Home Assistant device behind a notify.mobile_app_<name> service: its name, tracker and owner
  _companion(hass, notify, persons) {
    const m = /^notify\.mobile_app_(.+)$/.exec(notify || "");
    if (!m) return null;
    const want = m[1].replace(/[^a-z0-9]/g, "");
    for (const id of Object.keys(hass.states)) {
      if (!id.startsWith("device_tracker.")) continue;
      const fn = String(hass.states[id].attributes.friendly_name || "");
      if (fn.toLowerCase().replace(/[^a-z0-9]/g, "") === want || id.slice(15).replace(/[^a-z0-9]/g, "") === want) {
        const owner = persons.find((x) => x.trackers.includes(id));
        return { tracker: id, name: fn || m[1], person: owner || null };
      }
    }
    return { tracker: null, name: m[1].replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase()), person: null };
  }

  // every phone running the Companion app, as a choice for "Add a device"
  _companionChoices(hass, persons) {
    const svc = (hass.services && hass.services.notify) || {};
    return Object.keys(svc).filter((k) => k.startsWith("mobile_app_")).map((k) => {
      const c = this._companion(hass, "notify." + k, persons);
      return { service: "notify." + k, name: c.name, person: c.person };
    }).sort((a, b) => a.name.localeCompare(b.name));
  }

  // the device's "person" is either a person entity id (linked to Home Assistant) or plain text
  _resolvePerson(hass, d, persons) {
    const link = d.person && d.person.startsWith("person.") ? d.person : null;
    if (link) {
      const p = persons.find((x) => x.id === link);
      if (p) return { key: p.id, name: p.name, picture: p.picture, linked: true };
    }
    if (!link) {
      // no link saved: the person that owns the Companion app's tracker for this phone
      const c = this._companion(hass, d.notify, persons);
      if (c && c.person) return { key: c.person.id, name: c.person.name, picture: c.person.picture, linked: true, auto: true };
    }
    const text = (d.person || d.name || "").replace(/^person\./, "");
    return { key: "text:" + text, name: text, picture: "", linked: false };
  }

  _entities(hass) {
    if (this._config.entity) return [this._config.entity];
    return Object.keys(hass.states).filter((id) => id.startsWith("sensor.phoneguard_") && hass.states[id].attributes.data).sort();
  }

  set hass(hass) {
    this._hass = hass;
    const ids = this._entities(hass);
    if (!ids.includes(this._ui.sel)) this._ui.sel = ids[0] || null;
    const st = this._ui.sel ? hass.states[this._ui.sel] : null;
    const raw = st ? st.attributes.data : null;
    const people = this._persons(hass);
    const psig = people.map((p) => p.id + p.name + p.picture + p.trackers.join("+")).join(";");
    const key = ids.map((i) => i + ":" + ((hass.states[i].attributes.data || "").length)).join(",") + "|" + psig + "|" + this._ui.sel + "|" + (raw || "") + (st ? st.state : "");
    // do not rebuild while a field inside the card has focus: that would eat what is being typed
    if (key === this._last) return;
    if (this._root.activeElement && this._root.activeElement.tagName === "INPUT") return;
    this._last = key;
    this._ids = ids;
    this._people = people;
    this._all = {};
    for (const id of ids) {
      try {
        const v = JSON.parse(hass.states[id].attributes.data);
        const who = this._resolvePerson(hass, v, people);
        const comp = this._companion(hass, v.notify, people);
        this._all[id] = { child: v.child, name: comp ? comp.name : v.name, followsHA: !!comp, person: who.name, personKey: who.key, picture: who.picture, linked: who.linked };
      } catch (e) { /* skip */ }
    }
    try { this._d = raw ? JSON.parse(raw) : null; } catch (e) { this._d = null; }
    const tt = this._tt();
    if (tt && !tt.dirty) tt.draft = null; // saved: show what Home Assistant now holds
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
    const css = `
      :host { display:block; }
      .hcard { background: var(--card-background-color, #fff); color: var(--primary-text-color, #222);
        border-radius: var(--ha-card-border-radius, 12px); box-shadow: var(--ha-card-box-shadow, none);
        border: var(--ha-card-border-width, 1px) solid var(--ha-card-border-color, var(--divider-color, #ddd));
        padding: 16px; font-family: var(--paper-font-body1_-_font-family, inherit); }
      h2 { margin: 0; font-size: 20px; font-weight: 500; }
      h3 { margin: 22px 0 8px; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; color: var(--secondary-text-color); }
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
      button.small { min-height: 32px; padding: 4px 10px; font-size: 13px; }
      button:disabled { opacity: .4; cursor: default; }
      input[type=text], input[type=date], select { font: inherit; padding: 8px; border-radius: 8px; min-height: 40px; box-sizing: border-box;
        border: 1px solid var(--divider-color, #ccc); background: var(--card-background-color, #fff); color: inherit; }
      input.sas { width: 7em; font-size: 20px; letter-spacing: .2em; text-align: center; }
      .split { display:flex; flex-wrap:wrap; gap: 0 32px; }
      .blk { flex: 1 1 280px; min-width: 0; }
      .seg { display:flex; flex-wrap:wrap; gap:6px; }
      .seg button.sel { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: transparent; }
      .tabs { display:flex; gap:6px; flex-wrap:wrap; margin-bottom: 12px; }
      .people { display:flex; align-items:center; justify-content:space-between; gap:10px 16px; flex-wrap:wrap; margin-bottom: 12px; }
      .who { display:flex; align-items:center; gap:14px; }
      .peoplebar { display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
      .pname { font-size: 24px; margin: 0; }
      .chips { display:flex; gap:6px; flex-wrap:wrap; margin-top:6px; }
      .av { border-radius: 50%; object-fit: cover; display:inline-block; flex: none; }
      .av.ph { background: var(--primary-color); color: var(--text-primary-color, #fff); text-align: center; font-weight: 600; font-size: 14px; }
      .person { display:flex; align-items:center; gap:6px; flex-wrap:wrap; padding-right:10px; margin-right:4px; border-right:1px solid var(--divider-color, #ddd); }
      .addform { display:flex; gap:6px; flex-wrap:wrap; align-items:center; width:100%; margin-top:6px; }
      .addform input[type=text], .addform select { flex:1; min-width: 9em; }
      .tabs button.sel { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: transparent; }
      table { width: 100%; border-collapse: collapse; }
      th, td { padding: 6px 4px; text-align: center; border-bottom: 1px solid var(--divider-color, #eee); }
      th:first-child, td:first-child { text-align: left; }
      td.name { word-break: break-word; }
      td.name small { display:block; color: var(--secondary-text-color); font-size: 11px; }
      tr.opt td { font-size: 12px; color: var(--secondary-text-color); }
      tr.opt td:first-child { font-size: 13px; }
      input[type=checkbox] { width: 22px; height: 22px; }
      details.sec { border-top: 1px solid var(--divider-color, #ddd); margin-top: 14px; }
      details.sec > summary { cursor: pointer; list-style: none; margin: 10px 0 6px; font-size: 13px; font-weight: 600; text-transform: uppercase;
        letter-spacing: .04em; color: var(--secondary-text-color); display: flex; align-items: center; gap: 6px; user-select: none; }
      details.sec > summary::-webkit-details-marker { display: none; }
      details.sec > summary::before { content: "\\25B8"; display: inline-block; width: 1em; transition: transform .15s; }
      details.sec[open] > summary::before { transform: rotate(90deg); }
      details.sec > summary .note { margin-left: auto; text-transform: none; letter-spacing: 0; font-weight: 600; background: var(--warning-color, #e67e22); color: #fff; border-radius: 999px; padding: 1px 9px; font-size: 12px; }
      .scroll { overflow-x: auto; }
      table.mx th, table.mx td { text-align: center; }
      table.mx th.name, table.mx td.name { text-align: left; }
      .mbtn { padding: 5px 12px; border-radius: 999px; border: 1px solid var(--divider-color, #ccc); background: transparent; color: var(--primary-text-color); font-weight: 600; cursor: pointer; }
      .mbtn.live { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: var(--primary-color); }
      .mbtn.held::after { content: " \\1F4CC"; font-size: 11px; }
      .newtag { border: 0; background: var(--warning-color, #e67e22); color: #fff; border-radius: 999px; font-size: 10px; font-weight: 700; padding: 1px 7px; margin-left: 6px; cursor: pointer; }
      .chips { display:flex; flex-wrap:wrap; gap:6px; }
      .pchip { display:inline-flex; align-items:center; gap:4px; background: var(--secondary-background-color, #eee); border-radius:999px; padding:3px 4px 3px 12px; font-size:13px; }
      .pchip button { border:0; background:transparent; cursor:pointer; color: var(--secondary-text-color); font-size:14px; line-height:1; padding:2px 7px; border-radius:50%; }
      .pchip button:hover { background: var(--divider-color, #ddd); }
      tr.pend td { background: color-mix(in srgb, var(--warning-color, #e67e22) 18%, transparent); }
      .new { background: color-mix(in srgb, var(--warning-color, #e67e22) 14%, transparent); border-radius: 8px; padding: 10px; margin-bottom: 8px; }
      .qr svg { width: 240px; max-width: 100%; height: auto; background: #fff; padding: 8px; border-radius: 8px; }
      .code { font-family: monospace; font-size: 12px; word-break: break-all; }
      .mut { opacity: .6; }
      .days { display:flex; gap:4px; }
      .day { flex:1; min-width:0; padding:4px 2px 6px; border-radius:8px; border:2px solid transparent; background:none; text-align:center; font-size:12px; min-height:0; }
      .day.sel { border-color: var(--primary-color); }
      .bar { display:flex; height:46px; width:70%; margin:4px auto 0; border-radius:6px; overflow:hidden; flex-direction:column; }
      .bar i { display:block; width:100%; }
      .legend { display:flex; gap:12px; flex-wrap:wrap; margin:8px 0; font-size:13px; }
      .legend b { display:inline-block; width:12px; height:12px; border-radius:3px; margin-right:4px; vertical-align:-1px; }
      .chg { display:flex; gap:8px; align-items:center; margin:6px 0; }
      .chg input[type=time] { width: 8em; font: inherit; padding: 8px; border-radius: 8px; min-height: 40px; box-sizing: border-box; border: 1px solid var(--divider-color, #ccc); background: var(--card-background-color, #fff); color: inherit; }
      .chg button { min-width: 40px; padding: 8px; }
      .copyrow { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin-top:8px; }
      .unsaved { color: var(--warning-color, #e67e22); font-weight: 600; font-size: 13px; }
      .u-days { display:flex; gap:4px; align-items:flex-end; margin: 6px 0 10px; }
      .u-day { flex:1; min-width:0; padding:4px 2px; border-radius:8px; border:2px solid transparent; background:none; font-size:11px; min-height:0; text-align:center; }
      .u-day.sel { border-color: var(--primary-color); }
      .u-day i { display:block; margin:2px auto 3px; width:60%; border-radius:4px 4px 0 0; background: var(--primary-color); min-height:2px; }
      .u-day .col { height: 44px; display:flex; align-items:flex-end; }
      .u-day .col i { width: 100%; }
      .u-app { margin: 7px 0; }
      .u-app .l { display:flex; justify-content:space-between; gap:8px; font-size:14px; }
      .u-app .t { height: 8px; border-radius: 4px; background: var(--divider-color, #eee); overflow:hidden; margin-top:3px; }
      .u-app .t i { display:block; height:100%; background: var(--primary-color); }
      label.rs { display:flex; gap:10px; align-items:flex-start; margin: 8px 0; }
      label.rs span small { display:block; color: var(--secondary-text-color); font-size: 12px; }
      .note { background: color-mix(in srgb, var(--warning-color, #e67e22) 14%, transparent); border-radius: 8px; padding: 8px 10px; font-size: 13px; margin: 8px 0; }
    `;
    const wrap = (inner) => { this._root.innerHTML = `<style>${css}</style><div class="hcard">${inner}</div>`; };
    const tabs = this._tabsHtml();
    if (!this._ids || !this._ids.length) {
      wrap(`${this._config.entity ? "" : tabs}<div class="sub">${this._config.entity ? "Waiting for " + this._esc(this._config.entity) + "…" : "No guarded devices yet. Add one to get started."}</div>`);
      this._wire(); return;
    }
    const d = this._d;
    if (!d) { wrap(`${tabs}<div class="sub">Waiting for ${this._esc(this._ui.sel || "")}…</div>`); this._wire(); return; }
    const dev = d.device;
    const stale = dev && (d.now - dev.last_seen) > 45 * 60000;
    const modeName = (d.modes[d.mode_now] || { name: d.mode_now }).name;
    let html = tabs;

    // header: name, mode, how recently heard from, and the refresh button
    const shownName = (this._all && this._all[this._ui.sel]) ? this._all[this._ui.sel].name : d.name;
    html += `<div class="row"><h2 style="font-size:18px">${this._esc(shownName)}</h2><span class="chip">${this._esc(modeName)}${d.override ? " (held)" : ""}${d.boost ? " + boost" : ""}</span>`;
    if (dev && !dev.pending) {
      html += `<span class="chip ${stale ? "warn" : "off"}">${stale ? "not heard " : "seen "}${this._ago(dev.last_seen)}</span>
        <button class="small" data-act="nudge" title="Ask the phone to check in now">⟳ Sync now</button>`;
    }
    html += `</div>`;
    if (dev && !dev.pending) {
      const s = dev.state || {};
      const bits = [];
      if (s.battery != null) bits.push("battery " + s.battery + "%");
      if (s.hidden != null) bits.push(s.hidden + " apps hidden");
      if (s.gray) bits.push("grayscale on");
      if (s.canGray === false) bits.push("grayscale not permitted on the phone");
      if (s.canSilence === false && Object.values(d.modes).some((m) => m.sound === "silent")) bits.push("Silent needs Do Not Disturb access on the phone (it vibrates instead)");
      if (!dev.in_sync) bits.push("changes not on the phone yet");
      if (stale) bits.push("check the phone's Wi-Fi");
      html += `<div class="sub">${this._esc(bits.join(" · "))}</div>`;
    }

    if (!dev) html += this._pairHtml(d);
    else if (dev.pending) {
      html += `<h3>Confirm the phone</h3>
        <div class="sub">The phone shows a 4-digit number. Type it here to confirm this is the right phone.</div>
        <div class="row" style="margin-top:8px"><input class="sas" type="text" inputmode="numeric" maxlength="4" placeholder="1234" value="${this._esc(this._ui.sas)}">
        <button class="primary" data-act="confirm">Confirm</button><button data-act="forget" class="danger">Cancel pairing</button></div>`;
    } else {
      html += this._matrixHtml(d) + this._modeSettingsHtml(d) + this._powerHtml(d) + this._holidayHtml(d);
    }
    html += this._timetableHtml(d);
    if (dev && !dev.pending) html += this._weatherHtml(d) + this._spotifyHtml(d) + this._whatsappHtml(d) + this._usageHtml(d) + this._batteryHtml(d);
    html += this._restrictionsHtml(d);
    html += `<h3>Device</h3>`;
    if (this._ui.renaming) {
      const people = this._people || [];
      const cur = this._ui.rnPerson;
      html += `<div class="row"><select data-f="rnPerson">${people.map((p) => `<option value="${this._esc(p.id)}" ${cur === p.id ? "selected" : ""}>${this._esc(p.name)}</option>`).join("")}
        <option value="${this._esc(cur && !cur.startsWith("person.") ? cur : "")}" ${cur && !cur.startsWith("person.") ? "selected" : ""}>${this._esc(cur && !cur.startsWith("person.") ? cur + " (not a Home Assistant person)" : "No Home Assistant person")}</option></select>
        ${(this._all[this._ui.sel] || {}).followsHA
          ? `<span class="sub">Device name follows Home Assistant: ${this._esc(this._all[this._ui.sel].name)}</span>`
          : `<input type="text" data-f="rnName" placeholder="Device name" value="${this._esc(this._ui.rnName)}">`}
        <button class="primary" data-act="rename_go">Save</button><button data-act="rename_toggle">Cancel</button></div>
        <div class="sub" style="margin-top:4px">The person is the Home Assistant person this phone belongs to. Their name and picture come from there.</div>`;
    } else html += `<div class="row"><button data-act="rename_toggle">Change person or device name…</button></div>`;
    html += `<div class="row" style="margin-top:8px">
      ${dev && !dev.pending ? `<button data-act="release" class="danger">Release phone…</button>` : ""}
      <button data-act="remove" class="danger">Remove this device…</button></div>
      ${this._copyHtml(d)}
      <div class="sub mut" style="margin-top:6px">${this._esc((dev && dev.model) || "")} · Phone Guard card ${PG_VERSION}</div>`;
    wrap(this._sections(html, d));
    this._wire();
  }

  // Every heading becomes a fold-away section, so the page is not all open at once. What is open is remembered in this browser.
  _sections(html, d) {
    const OPEN = ["Modes and apps", "Pair a phone", "Confirm the phone"];
    let saved = this._ui.sec;
    if (!saved) { try { saved = JSON.parse(localStorage.getItem("phoneguard-card:sections") || "{}"); } catch (e) { saved = {}; } this._ui.sec = saved; }
    const parts = html.split("<h3>");
    let out = parts[0];
    for (const part of parts.slice(1)) {
      const i = part.indexOf("</h3>");
      const title = part.slice(0, i), body = part.slice(i + 5);
      const open = title in saved ? saved[title] : OPEN.includes(title);
      const note = title === "Modes and apps" && d.pending_apps && d.pending_apps.length ? `<span class="note">${d.pending_apps.length} new</span>` : "";
      out += `<details class="sec" data-sec="${this._esc(title)}" ${open ? "open" : ""}><summary>${title}${note}</summary>${body}</details>`;
    }
    return out;
  }

  _avatar(pic, name, size) {
    const initial = this._esc((name || "?").trim().charAt(0).toUpperCase());
    if (pic) return `<img class="av" style="width:${size}px;height:${size}px" src="${this._esc(pic)}" alt="">`;
    return `<span class="av ph" style="width:${size}px;height:${size}px;line-height:${size}px">${initial}</span>`;
  }

  // the person, once, at the top: a big picture and the name; other people as plain name chips
  _personHtml() {
    const all = this._all || {}, me = all[this._ui.sel];
    if (!me) return "";
    const who = `<div class="who">${this._avatar(me.picture, me.person, 56)}<h2 class="pname">${this._esc(me.person)}</h2></div>`;
    if (this._config.entity) return `<div class="people">${who}</div>`;
    // the other people, and adding a person, sit level with this person, not under their name: they are not part of them
    const others = this._groups().filter((g) => g.key !== me.personKey);
    return `<div class="people">${who}<div class="peoplebar">${others.map((g) => `<button class="small" data-act="select_person" data-key="${this._esc(g.key)}">${this._esc(g.name)}</button>`).join("")}<button class="small" data-act="add_person" title="Add someone else, with their first device">＋ Add a person</button></div></div>`;
  }

  _groups() {
    const all = this._all || {}, groups = {};
    for (const id of this._ids || []) {
      if (!all[id]) continue;
      const g = (groups[all[id].personKey] = groups[all[id].personKey] || { key: all[id].personKey, name: all[id].person, ids: [] });
      g.ids.push(id);
    }
    return Object.values(groups).sort((x, y) => x.name.localeCompare(y.name));
  }

  _tabsHtml() {
    if (this._config.entity) return this._personHtml();
    const all = this._all || {}, me = all[this._ui.sel];
    let h = this._personHtml() + `<div class="tabs">`;
    const mine = this._groups().find((g) => me && g.key === me.personKey);
    for (const id of mine ? mine.ids : []) {
      h += `<button class="${id === this._ui.sel ? "sel" : ""}" data-act="select" data-id="${this._esc(id)}">${this._esc(all[id].name)}</button>`;
    }
    if (this._ui.adding) {
      const people = this._people || [];
      const phones = this._companionChoices(this._hass, people);
      const phone = this._ui.newPhone !== undefined && this._ui.newPhone !== "" ? this._ui.newPhone : (phones[0] ? phones[0].service : "__other");
      const chosen = phones.find((p) => p.service === phone);
      const personMode = this._ui.addMode === "person";
      // adding a person: offer the Home Assistant people who have no device here yet (or someone new); adding a device: anyone
      const taken = new Set(this._groups().map((g) => g.key));
      const choices = personMode ? people.filter((p) => !taken.has(p.id)) : people;
      const sel = this._ui.newPerson || (personMode ? (choices[0] ? choices[0].id : "__new") : (chosen && chosen.person ? chosen.person.id : (me && people.find((p) => p.id === me.personKey) ? me.personKey : (people[0] ? people[0].id : "__new"))));
      h += `<div class="addform"><b>${personMode ? "Add a person" : "Add a device"}</b>
        <select data-f="newPhone">${phones.map((p) => `<option value="${this._esc(p.service)}" ${phone === p.service ? "selected" : ""}>${this._esc(p.name)}</option>`).join("")}<option value="__other" ${phone === "__other" ? "selected" : ""}>Not in Home Assistant yet…</option></select>
        ${phone === "__other" ? `<input type="text" data-f="newName" placeholder="Device name, e.g. Tablet" value="${this._esc(this._ui.newName)}">` : ""}
        <select data-f="newPerson">${choices.map((p) => `<option value="${this._esc(p.id)}" ${sel === p.id ? "selected" : ""}>${this._esc(p.name)}</option>`).join("")}<option value="__new" ${sel === "__new" ? "selected" : ""}>Someone without a Home Assistant person…</option></select>
        ${sel === "__new" ? `<input type="text" data-f="newPersonName" placeholder="Name" value="${this._esc(this._ui.newPersonName)}">` : ""}
        <button class="primary" data-act="add_go">Create</button><button data-act="add_cancel">Cancel</button></div>
        <div class="sub" style="width:100%">${personMode ? "Choose the person and their first phone. Each person has their own devices, timetable and settings." : "Pick the phone running the Home Assistant app and its name and owner follow Home Assistant (Settings, Devices, and People)."}</div>`;
    } else h += `<button data-act="add">＋ Add a device</button>`;
    return h + `</div>`;
  }

  _pairHtml(d) {
    let h = `<h3>Pair a phone</h3>`;
    if (d.pairing && d.pairing.qr_svg) {
      h += `<div class="qr">${d.pairing.qr_svg}</div>
        <div class="sub">On the phone: open Phone Guard, press "Pair: scan the QR code". Valid for 10 minutes. Code ${this._esc(d.pairing.code || "")}.</div>`;
    } else if (d.pairing && d.pairing.qr_text) {
      h += `<div class="code">${this._esc(d.pairing.qr_text)}</div>`;
    }
    return h + `<div class="row" style="margin-top:8px"><button class="primary" data-act="pair">${d.pairing ? "New QR code" : "Start pairing"}</button></div>`;
  }

  // Boost (extra access for a while) and Mode (hold one mode) sit side by side
  _holidayHtml(d) {
    const h = d.holiday || {};
    return `<h3>Holiday (School becomes Day)</h3><div class="row">
      <input type="date" data-f="from" value="${this._esc(this._ui.from || h.from || "")}">
      <input type="date" data-f="until" value="${this._esc(this._ui.until || h.until || "")}">
      <button data-act="holiday" class="primary">Set</button><button data-act="holiday_clear">Clear</button></div>
      ${d.holiday ? `<div class="sub">On from ${this._esc(h.from)} to ${this._esc(h.until)}.</div>` : ""}`;
  }

  // The one place that decides what is visible: every mode is a column, every app a row. The mode buttons sit on top of the
  // columns (the lit one is the mode the phone is in now), Auto and Boost are on the same row.
  _matrixHtml(d) {
    const ids = Object.keys(d.modes), pinned = d.pinned || [], pend = d.pending_apps || [];
    let h = `<h3>Modes and apps</h3>`;
    if (!d.apps.length) return h + `<div class="sub">The phone has not reported its apps yet.</div>`;
    const bm = d.modes.day ? "day" : ids[0], bname = d.modes[bm].name;
    const left = d.boost ? Math.max(1, Math.ceil((d.boost.until - d.now) / 60000)) : 0;
    h += `<div class="row" style="justify-content:space-between;margin-bottom:8px">
      <span class="sub">${d.override ? `Held in <b>${this._esc((d.modes[d.override] || { name: d.override }).name)}</b> until you choose Auto.` : `Following the timetable: <b>${this._esc((d.modes[d.mode_now] || { name: d.mode_now }).name)}</b> now.`}</span>
      <span class="row"><button data-act="boost" data-mode="${bm}" data-min="10" title="Each press adds 10 minutes">${d.boost ? `Boost ${this._esc(bname)}: ${left} min left, +10` : `Boost ${this._esc(bname)} +10 min`}</button>${d.boost ? `<button data-act="cancel_boost" class="danger" title="End the boost now">✕</button>` : ""}</span></div>`;
    h += `<div class="scroll"><table class="mx"><tr class="modes"><th class="name"><button data-act="override" data-mode="auto" class="mbtn ${d.override ? "" : "live"}">Auto</button></th>${ids.map((m) =>
      `<th><button data-act="override" data-mode="${m}" class="mbtn ${d.mode_now === m ? "live" : ""} ${d.override === m ? "held" : ""}" title="Hold the phone in ${this._esc(d.modes[m].name)}">${this._esc(d.modes[m].name)}</button></th>`).join("")}<th>Main menu</th><th>Hide notes</th></tr>
      <tr class="opt"><td>Select all</td>${ids.map((m) => `<td><input type="checkbox" data-all="${m}" title="Allow every app in ${this._esc(d.modes[m].name)}"></td>`).join("")}<td></td><td></td></tr>
      <tr class="opt"><td>Allow new apps automatically</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="auto_new" data-mode="${m}" ${d.modes[m].auto_new ? "checked" : ""} title="Off: a newly installed app stays hidden here until you tick it"></td>`).join("")}<td></td><td></td></tr>`;
    for (const a of d.apps) {
      const isNew = pend.includes(a.pkg);
      h += `<tr class="${isNew ? "pend" : ""}"><td class="name">${this._esc(a.label)}${isNew ? ` <button class="newtag" data-act="dismiss" data-pkg="${this._esc(a.pkg)}" title="Mark as seen">NEW ✓</button>` : ""}<small>${this._esc(a.pkg)}</small></td>` +
        ids.map((m) => `<td><input type="checkbox" data-app="${this._esc(a.pkg)}" data-mode="${m}" ${(d.modes[m].allow || []).includes(a.pkg) ? "checked" : ""}></td>`).join("") +
        `<td><input type="checkbox" data-pin="${this._esc(a.pkg)}" ${pinned.includes(a.pkg) ? "checked" : ""}></td><td><input type="checkbox" data-nh="${this._esc(a.pkg)}" ${(d.notes_hide || []).includes(a.pkg) ? "checked" : ""}></td></tr>`;
    }
    return h + `</table></div><div class="sub" style="margin-top:6px">A tick means the app is visible in that mode. Phone, messages, contacts and Settings are always available. Main menu: ticked apps sit in the Nokia main menu, the others are under "Android Apps". Hide notes: clear that app's notifications as they arrive. The Clock hides its alarms, so leave it ticked in any mode where an alarm must ring.</div>`;
  }

  // everything else that differs between modes, in the same columns
  _modeSettingsHtml(d) {
    const ids = Object.keys(d.modes);
    if (!d.apps.length) return "";
    const sel = (opt, m, list, def) => `<td><select data-mode-opt="${opt}" data-mode="${m}">${list.map(([v, t]) => `<option value="${v}" ${(d.modes[m][opt] ?? def) === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>`;
    const tick = (opt, m, dflt = true) => `<td><input type="checkbox" data-mode-opt="${opt}" data-mode="${m}" ${(d.modes[m][opt] ?? dflt) !== false && (d.modes[m][opt] ?? dflt) ? "checked" : ""}></td>`;
    const row = (label, f) => `<tr class="opt"><td>${label}</td>${ids.map(f).join("")}</tr>`;
    let h = `<h3>Mode settings</h3><div class="scroll"><table class="mx"><tr><th class="name"></th>${ids.map((m) => `<th>${this._esc(d.modes[m].name)}</th>`).join("")}</tr>`;
    h += row("Grey screen", (m) => `<td><input type="checkbox" data-mode-opt="gray" data-mode="${m}" ${d.modes[m].gray ? "checked" : ""}></td>`);
    h += row("Home screen", (m) => `<td><select data-mode-opt="launcher" data-mode="${m}"><option value="default" ${d.modes[m].launcher !== "nokia" ? "selected" : ""}>Normal</option><option value="nokia" ${d.modes[m].launcher === "nokia" ? "selected" : ""}>Nokia</option></select></td>`);
    h += row("Touchscreen works", (m) => tick("touch", m)) + row("Pull-down shade works", (m) => tick("shade", m));
    h += row("Sound", (m) => sel("sound", m, [["normal", "Normal"], ["vibrate", "Vibrate"], ["silent", "Silent"]], "normal"));
    h += row("WhatsApp checks", (m) => sel("wa", m, [["off", "Off"], ["open", "When opened"], ["30", "Every 30 min"], ["60", "Hourly"]], "open"));
    h += row("WhatsApp app", (m) => sel("wa_app", m, [["auto", "Auto"], ["simple", "Our screens"], ["full", "Real app"]], "auto"));
    h += row("Incoming calls allowed", (m) => tick("inbound", m)) + row("Camera offered", (m) => tick("camera", m));
    h += row("Location", (m) => sel("location", m, [["off", "Off"], ["network", "Low accuracy"], ["gps", "GPS"]], "off"));
    h += row("Location every", (m) => `<td><select data-mode-opt="loc_every" data-mode="${m}" ${(d.modes[m].location || "off") === "off" ? "disabled" : ""}>${[[0, "Live"], [1, "1 min"], [5, "5 min"], [10, "10 min"], [15, "15 min"], [30, "30 min"], [60, "1 hour"]].map(([v, t]) => `<option value="${v}" ${(d.modes[m].loc_every ?? 30) === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>`);
    h += row("Notifications", (m) => sel("notes", m, [["show", "Listed"], ["off", "Cleared"]], "show"));
    h += row("Locked to allowed apps", (m) => `<td><input type="checkbox" data-mode-opt="kiosk" data-mode="${m}" ${d.modes[m].kiosk ? "checked" : ""}></td>`);
    return h + `</table></div><div class="sub" style="margin-top:6px">Grey screen turns the display black and white. Sound: Silent is total silence with no exceptions. Incoming calls: untick and the phone hangs up on every call in that mode. WhatsApp checks (our screens only): Off means no WhatsApp in that mode; When opened connects only while WhatsApp is open; the others check briefly in the background. WhatsApp app: Auto uses the real WhatsApp when it is installed and allowed in that mode, otherwise our screens. A Nokia home screen with touch off, no shade and locked apps gives the classic keypad-phone look.</div>`;
  }

  // the weather on the Nokia home screen comes from Open-Meteo for a place chosen here; the phone never asks for its own location
  _weatherHtml(d) {
    const loc = d.location;
    const home = this._hass && this._hass.config;
    return `<h3>Weather</h3><div class="row"><span>${loc ? `Showing the weather for ${loc.lat}, ${loc.lon}` : "No weather on the phone"}</span>
      <button data-act="wx_home" ${home && home.latitude != null ? "" : "disabled"}>Use this house's location</button>
      ${loc ? `<button data-act="wx_off">Turn weather off</button>` : ""}</div>
      <div class="sub">Shows a small weather symbol, the temperature and any rain coming in the next few hours on the home screen, and a Weather entry in the menu. Data by Open-Meteo.com. The place is rounded to about 100 metres.</div>`;
  }

  // Power and memory: automatic. The only thing to decide is which apps must never be closed, and how soon an idle one is.
  _powerHtml(d) {
    const ids = Object.keys(d.modes);
    if (!d.apps.length) return "";
    const keep = d.keep_running || [];
    const label = (pkg) => ((d.apps.find((a) => a.pkg === pkg) || {}).label) || (d.labels || {})[pkg] || pkg;
    const addable = d.apps.filter((a) => !keep.includes(a.pkg));
    let h = `<h3>Power and memory</h3><div class="sub" style="margin-bottom:8px">Apps nobody has used for a while are closed automatically, to save battery and memory. Android starts one again when something needs it.</div>
      <div class="scroll"><table class="mx"><tr><th class="name"></th>${ids.map((m) => `<th>${this._esc(d.modes[m].name)}</th>`).join("")}</tr>
      <tr class="opt"><td class="name">Close unused apps after</td>${ids.map((m) => `<td><select data-mode-opt="idle_min" data-mode="${m}">${[[0, "Never"], [10, "10 min"], [30, "30 min"], [60, "1 hour"]].map(([v, t]) => `<option value="${v}" ${(d.modes[m].idle_min ?? 10) === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>`).join("")}</tr>
      <tr class="opt"><td class="name">Close apps when the screen is off for</td>${ids.map((m) => `<td><select data-mode-opt="screen_off_min" data-mode="${m}">${[[0, "Never"], [2, "2 min"], [5, "5 min"], [10, "10 min"], [30, "30 min"]].map(([v, t]) => `<option value="${v}" ${(d.modes[m].screen_off_min ?? 5) === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>`).join("")}</tr></table></div>
      <div class="sub" style="margin:12px 0 6px"><b>Protected apps</b>: never closed, because they must keep running in the background.</div>
      <div class="chips">${keep.length ? keep.map((p) => `<span class="pchip">${this._esc(label(p))}<button data-act="keep_del" data-pkg="${this._esc(p)}" title="Remove from the protected list">✕</button></span>`).join("") : `<span class="sub">None</span>`}</div>
      <div class="row" style="margin-top:8px"><select data-keep-add ${addable.length ? "" : "disabled"}><option value="">Add an app…</option>${addable.map((a) => `<option value="${this._esc(a.pkg)}">${this._esc(a.label)}</option>`).join("")}</select>
      <button data-act="keep_add" ${addable.length ? "" : "disabled"}>Add</button></div>
      ${this._reapedHtml(d)}
      <div class="sub" style="margin-top:8px">Always protected without being listed: the phone, messages, contacts, settings, an app playing music, and an app with an ongoing notification such as a download or navigation. The phone's Logs &amp; Settings menu has a Running Apps screen to close one by hand.</div>`;
    return h;
  }

  // what the power saver has done in the last day, and the report-only switch
  _reapedHtml(d) {
    const r = d.reaped || { closed: 0, would: 0, by_reason: {}, top: [], latest: [] };
    const dry = !!d.idle_dry_run;
    const why = Object.entries(r.by_reason || {}).map(([k, v]) => `${v} ${k}`).join(", ");
    const n = dry ? r.would : r.closed;
    let h = `<div class="sub" style="margin:12px 0 4px"><b>Last 24 hours</b>: ${n ? `${dry ? "would have closed" : "closed"} ${n} app${n === 1 ? "" : "s"}${why ? " (" + this._esc(why) + ")" : ""}${r.top.length ? ": " + r.top.map(([nm, c]) => this._esc(nm) + (c > 1 ? " ×" + c : "")).join(", ") : ""}` : "nothing closed yet"}</div>`;
    if (r.latest && r.latest.length) h += `<details style="margin:4px 0"><summary class="sub" style="cursor:pointer">Recent</summary><div class="sub">${r.latest.map(([t, nm, why2, would]) => `${this._fmtTime(t)} ${this._esc(nm)} · ${this._esc(why2)}${would ? " (report only)" : ""}`).join("<br>")}</div></details>`;
    h += `<label class="sub" style="display:block;margin-top:6px"><input type="checkbox" data-dry ${dry ? "checked" : ""}> Report only: show what would be closed, but close nothing</label>`;
    return h;
  }

  // Spotify: the developer app's Client ID (public by design, entered once for every phone, never the client secret) and
  // this phone's sign-in, done here in the browser so nothing is typed on the keypad phone
  _spotifyHtml(d) {
    const id = (d.options || {}).spotify_client_id || "";
    const sp = d.spotify || {}, ui = this._ui, link = ui.spLink && ui.spLink.child === d.child ? ui.spLink : null;
    let h = `<h3>Spotify</h3><div class="row"><input data-sp-id type="text" size="36" maxlength="32" placeholder="Client ID (32 letters and digits)" value="${this._esc(id)}">
      <button data-act="sp_save">Save</button>${id ? `<button data-act="sp_clear">Remove</button>` : ""}</div>
      <div class="sub">${id ? "" : "Register an app at developer.spotify.com/dashboard (steps in docs/spotify-setup.md) and paste its Client ID here. Never paste the Client secret."}</div>`;
    if (!id) return h;
    h += `<div class="row" style="margin-top:8px"><span>${sp.signed_in ? `Signed in${sp.name ? " as " + this._esc(sp.name) : ""}` : "Not signed in"}</span>
      <button data-act="sp_signin">${sp.signed_in ? "Sign in again" : "Sign in with Spotify"}</button>${sp.signed_in ? `<button data-act="sp_signout">Sign out</button>` : ""}</div>`;
    if (link && link.error) h += `<div class="sub" style="color:var(--error-color,#c00)">${this._esc(link.error)}</div>`;
    else if (link) h += `<div class="sub"><a href="${this._esc(link.url)}" target="_blank" rel="noopener">Continue to Spotify</a> (opens in a new tab; sign in with the account the phone should use).
      The first time, add this address under Redirect URIs in the Spotify app's settings: <code>${this._esc(link.redirect)}</code></div>`;
    else h += `<div class="sub">The phone picks up the sign-in by itself a minute or two afterwards. Needs the https address of Home Assistant.</div>`;
    return h;
  }

  _copyHtml(d) {
    const ui = this._ui, all = this._all || {};
    const others = (this._ids || []).filter((id) => id !== ui.sel && all[id]);
    let h = `<div class="row" style="margin-top:12px"><button data-act="copy_toggle" ${others.length ? "" : "disabled"} title="${others.length ? "" : "Add another device first"}">Copy settings to another device…</button></div>`;
    if (!ui.copyOpen || !others.length) return h;
    const to = others.includes(ui.copyTo) ? ui.copyTo : others[0];
    const P = ui.copyParts;
    h += `<div class="new" style="margin-top:8px"><div class="row"><span>Copy to</span><select data-f="copyTo">${others.map((id) => `<option value="${this._esc(id)}" ${id === to ? "selected" : ""}>${this._esc(all[id].person)} – ${this._esc(all[id].name)}</option>`).join("")}</select></div>
      <label class="rs"><input type="checkbox" data-cp="timetable" ${P.timetable ? "checked" : ""}><span>Weekly timetable<small>Including the base mode and time zone.</small></span></label>
      <label class="rs"><input type="checkbox" data-cp="holiday" ${P.holiday ? "checked" : ""}><span>Holiday dates</span></label>
      <label class="rs"><input type="checkbox" data-cp="restrictions" ${P.restrictions ? "checked" : ""}><span>Phone restrictions</span></label>
      <label class="rs"><input type="checkbox" data-cp="whatsapp" ${P.whatsapp ? "checked" : ""}><span>WhatsApp settings<small>Who is allowed, and the send limits.</small></span></label>
      <label class="rs"><input type="checkbox" data-cp="mode_options" ${P.mode_options ? "checked" : ""}><span>Grey screen, sound, incoming calls, WhatsApp checks and "Everything allowed" for each mode<small>Only for modes both devices have.</small></span></label>
      <div class="sub">App lists are never copied: each device has its own apps, so choose those on that device.</div>
      <div class="row" style="margin-top:8px"><button class="primary" data-act="copy_go">Copy</button><button data-act="copy_toggle">Close</button></div></div>`;
    return h;
  }

  _label(d, pkg) {
    const a = (d.apps || []).find((x) => x.pkg === pkg);
    if (a) return a.label;
    if (d.labels && d.labels[pkg]) return d.labels[pkg];
    const t = pkg.split(".").slice(-1)[0];
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
  _dur(sec) { const m = Math.round(sec / 60); return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " min"; }

  // the phone's battery diary: a level line over the last day with the modes shaded behind it, and where the power went
  _batteryHtml(d) {
    const p = d.power;
    let h = `<h3>Battery</h3>`;
    if (!p || !p.chart || p.chart.length < 2) return h + `<div class="sub">No battery readings yet. They arrive with each sync once the phone has the newest version.</div>`;
    const c = p.chart, t0 = c[0][0], t1 = c[c.length - 1][0], W = 600, H = 110, span = Math.max(t1 - t0, 1);
    const x = (t) => ((t - t0) / span * W).toFixed(1), y = (lv) => (H - 6 - (H - 12) * lv / 100).toFixed(1);
    const modes = [...new Set(c.map((r) => r[4]))];
    const hue = (m) => (modes.indexOf(m) * 67 + 200) % 360;
    let bands = "", i = 0;
    while (i < c.length) {
      let j = i; while (j + 1 < c.length && c[j + 1][4] === c[i][4]) j++;
      const xe = j + 1 < c.length ? x(c[j + 1][0]) : W;
      bands += `<rect x="${x(c[i][0])}" y="0" width="${Math.max(0, xe - x(c[i][0])).toFixed(1)}" height="${H}" fill="hsl(${hue(c[i][4])},60%,55%)" opacity=".18"/>`;
      i = j + 1;
    }
    const line = c.map((r) => `${x(r[0])},${y(r[1])}`).join(" ");
    const dots = c.filter((r) => r[2]).map((r) => `<circle cx="${x(r[0])}" cy="${y(r[1])}" r="2" fill="#2e9e4f"/>`).join("");
    const screen = c.filter((r) => r[3]).map((r) => `<rect x="${x(r[0])}" y="${H - 5}" width="2" height="5" fill="currentColor"/>`).join("");
    h += `<svg viewBox="0 0 ${W} ${H}" style="width:100%;height:auto;color:var(--primary-text-color)" role="img" aria-label="Battery level over the last day">
      ${bands}<line x1="0" y1="${y(50)}" x2="${W}" y2="${y(50)}" stroke="currentColor" opacity=".15"/>
      <polyline points="${line}" fill="none" stroke="currentColor" stroke-width="2"/>${dots}${screen}</svg>
      <div class="sub">${modes.map((m) => `<span style="display:inline-block;width:10px;height:10px;background:hsl(${hue(m)},60%,55%);opacity:.5;margin:0 4px 0 8px"></span>${this._esc(m)}`).join("")}
      <span style="margin-left:8px">green dots: charging, ticks along the bottom: screen on</span></div>`;
    const f = (b) => (b && b.mah_per_h != null ? `${b.mah_per_h} mAh/h over ${b.hours} h` : "no data");
    const rows = [];
    rows.push(["Now", `${p.level}%${p.plugged ? " (charging)" : ""}${p.hours_left != null ? `, about ${p.hours_left} h left at this rate` : ""}${p.full_mah ? `, battery about ${p.full_mah} mAh` : ""}`]);
    rows.push(["On battery, last " + p.window_hours + " h", f(p.on_battery)]);
    for (const k of ["off", "on"]) if (p.by_screen && p.by_screen[k]) rows.push(["Screen " + k, f(p.by_screen[k])]);
    for (const m of Object.keys(p.by_mode || {})) rows.push(["Mode " + m, f(p.by_mode[m])]);
    for (const k of ["off", "on"]) if (p.by_location && p.by_location[k]) rows.push(["Location " + k, f(p.by_location[k])]);
    for (const k of ["off", "on"]) if (p.by_wifi && p.by_wifi[k]) rows.push(["Wi-Fi " + k, f(p.by_wifi[k])]);
    h += `<table>${rows.map((r) => `<tr><td>${this._esc(r[0])}</td><td>${this._esc(r[1])}</td></tr>`).join("")}</table>
      <div class="sub" style="margin-top:6px">Measured from the phone's own charge counter between check-ins, so it is finer than the percentage. It ignores time on charge and gaps of over three hours.</div>`;
    return h;
  }

  _usageHtml(d) {
    const days = Object.keys(d.usage || {}).sort().reverse().slice(0, 7);
    let h = `<h3>Screen time by app</h3>`;
    if (d.device && d.device.state && d.device.state.usageAllowed === false) {
      return h + `<div class="note">The phone has not been given usage access, so there is nothing to show. At setup run:<br><span class="code">adb shell appops set io.github.durkimat.phoneguard GET_USAGE_STATS allow</span></div>`;
    }
    if (!days.length) return h + `<div class="sub">No usage reported yet. It arrives with the next sync.</div>`;
    const total = (day) => Object.values(d.usage[day]).reduce((a, b) => a + b, 0);
    const maxTotal = Math.max(...days.map(total), 1);
    const sel = days.includes(this._ui.usageDay) ? this._ui.usageDay : days[0];
    const dn = (day, i) => (i === 0 ? "Today" : i === 1 ? "Yest." : new Date(day + "T12:00:00").toLocaleDateString([], { weekday: "short" }));
    h += `<div class="u-days">${days.map((day, i) => `<button class="u-day ${day === sel ? "sel" : ""}" data-act="usage_day" data-day="${day}">
      <div class="col"><i style="height:${Math.max(4, Math.round(44 * total(day) / maxTotal))}px"></i></div>${dn(day, i)}<br>${this._dur(total(day))}</button>`).join("")}</div>`;
    const apps = Object.entries(d.usage[sel]).sort((a, b) => b[1] - a[1]);
    const top = apps.length ? apps[0][1] : 1;
    h += `<div class="sub">${new Date(sel + "T12:00:00").toLocaleDateString([], { weekday: "long", day: "numeric", month: "short" })} · ${this._dur(total(sel))} in total</div>`;
    h += apps.length ? apps.map(([pkg, sec]) => `<div class="u-app"><div class="l"><span>${this._esc(this._label(d, pkg))}</span><span class="sub">${this._dur(sec)}</span></div>
      <div class="t"><i style="width:${Math.round(100 * sec / top)}%"></i></div></div>`).join("") : `<div class="sub">Nothing used.</div>`;
    return h + `<div class="sub mut" style="margin-top:6px">Time an app was on screen, as reported by the phone. The home screen is not counted.</div>`;
  }

  static get RESTRICTIONS() {
    return [
      ["no_factory_reset", "Block factory reset", "From Settings. A reset from recovery mode cannot be blocked.", true],
      ["no_safe_boot", "Block safe mode", "", true],
      ["no_config_date_time", "Lock date and time", "Keeps the clock on network time so schedules cannot be dodged.", true],
      ["no_add_user", "Block adding users", "", true],
      ["no_add_managed_profile", "Block work profiles", "", true],
      ["no_config_vpn", "Block VPN changes", "", false],
      ["disallow_config_private_dns", "Block private DNS changes", "", false],
      ["no_usb_file_transfer", "Block USB file transfer", "", false],
      ["no_modify_accounts", "Block adding or removing accounts", "", false],
      ["no_config_credentials", "Block certificate changes", "", false],
      ["no_config_mobile_networks", "Block mobile network settings", "", false],
      ["no_user_switch", "Block switching users", "", false],
      ["no_control_apps", "Block uninstall, force stop and clear data", "For every app, which also stops you doing it on the phone.", false],
      ["no_install_unknown_sources", "Block installing apps from unknown sources", "DumberOS also sets this itself; turning it off here may not undo that.", false],
    ];
  }

  // who WhatsApp on the phone may read and write to, and how fast it may send (the checks per mode are in the table above)
  _whatsappHtml(d) {
    const w = d.whatsapp || { all: false, allowed: [], perMinute: 5, perDay: 200 };
    return `<h3>WhatsApp</h3>
      <label class="rs"><input type="checkbox" data-wa="all" ${w.all ? "checked" : ""}><span>Allow everyone and every group<small>Without this, only the people and groups listed below can be read or written to.</small></span></label>
      <div class="sub" style="margin-top:6px">One per line: a phone number with country code and no plus (447700900111), or g: and a group's name (g:Family). Names are matched from the phone's contacts.</div>
      <textarea data-wa="allowed" rows="4" style="width:100%;box-sizing:border-box">${this._esc((w.allowed || []).join("\n"))}</textarea>
      <div class="row" style="margin-top:6px"><span>Send at most</span><input type="number" data-wa="perMinute" min="1" max="60" value="${Number(w.perMinute) || 5}" style="width:4em"><span>a minute and</span><input type="number" data-wa="perDay" min="1" max="2000" value="${Number(w.perDay) || 200}" style="width:5em"><span>a day</span></div>
      <div class="row" style="margin-top:8px"><button class="primary" data-act="wa_save">Save WhatsApp settings</button></div>
      <div class="sub">Messages stay on the phone. Home Assistant never sees them.</div>`;
  }

  _restrictionsHtml(d) {
    const R = PhoneGuardCard.RESTRICTIONS;
    const on = new Set(d.restrictions == null ? R.filter((r) => r[3]).map((r) => r[0]) : d.restrictions);
    let h = `<h3>Phone restrictions</h3>`;
    for (const [key, label, hint] of R) {
      h += `<label class="rs"><input type="checkbox" data-rs="${key}" ${on.has(key) ? "checked" : ""}><span>${this._esc(label)}${hint ? `<small>${this._esc(hint)}</small>` : ""}</span></label>`;
    }
    h += `<div class="row" style="margin-top:6px"><button class="small" data-act="rs_default" ${d.restrictions == null ? "disabled" : ""}>Back to the recommended set</button></div>
      <div class="sub" style="margin-top:6px">Blocking USB debugging is not offered: it also switches off developer options on the phone and cannot be undone from here. Force-stop protection for Phone Guard and the Home Assistant app is always on.</div>`;
    return h;
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

  _tt() { if (!this._ui.sel) return null; return (this._ui.tts[this._ui.sel] = this._ui.tts[this._ui.sel] || { sel: 1, draft: null, dirty: false }); }
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
    r.querySelectorAll("input[data-f], select[data-f]").forEach((i) => i.addEventListener("input", () => { this._ui[i.dataset.f] = i.value; }));
    const sas = r.querySelector("input.sas");
    if (sas) sas.addEventListener("input", () => { this._ui.sas = sas.value; });
    r.querySelectorAll("input[data-nh]").forEach((i) =>
      i.addEventListener("change", () => {
        const cur = (this._d.notes_hide || []).filter((p) => p !== i.dataset.nh);
        if (i.checked) cur.push(i.dataset.nh);
        return this._svc("set_child", { config: { notes_hide: cur } });
      }));
    r.querySelectorAll("input[data-pin]").forEach((i) =>
      i.addEventListener("change", () => {
        const cur = (this._d.pinned || []).filter((p) => p !== i.dataset.pin);
        if (i.checked) cur.push(i.dataset.pin);
        return this._svc("set_child", { config: { pinned: cur } });
      }));
    r.querySelectorAll("input[data-app]").forEach((i) =>
      i.addEventListener("change", () => this._svc("set_app_mode", { package: i.dataset.app, mode: i.dataset.mode, allowed: i.checked })));
    r.querySelectorAll("input[data-mode-opt], select[data-mode-opt]").forEach((i) => i.addEventListener("change", () => this._modeOpt(i)));
    r.querySelectorAll("details.sec").forEach((el) => el.addEventListener("toggle", () => {
      this._ui.sec[el.dataset.sec] = el.open;
      try { localStorage.setItem("phoneguard-card:sections", JSON.stringify(this._ui.sec)); } catch (e) { /* private window */ }
    }));
    r.querySelectorAll("input[data-dry]").forEach((i) => i.addEventListener("change", () => this._svc("set_child", { config: { idle_dry_run: i.checked } })));
    r.querySelectorAll("input[data-all]").forEach((i) => {
      const d = this._d, m = i.dataset.all, apps = d.apps || [];
      const n = apps.filter((a) => (d.modes[m].allow || []).includes(a.pkg)).length;
      i.checked = apps.length > 0 && n === apps.length; i.indeterminate = n > 0 && n < apps.length;
      i.addEventListener("change", () => this._svc("set_mode_all", { mode: m, allowed: i.checked }));
    });
    r.querySelectorAll("input[data-cp]").forEach((i) => i.addEventListener("change", () => { this._ui.copyParts[i.dataset.cp] = i.checked; }));
    r.querySelectorAll("select[data-f=newPhone]").forEach((i) => i.addEventListener("change", () => { this._ui.newPerson = ""; this._render(); }));
    r.querySelectorAll("select[data-f=newPerson], select[data-f=copyTo]").forEach((i) => i.addEventListener("change", () => this._render()));
    r.querySelectorAll("input[data-rs]").forEach((i) => i.addEventListener("change", () => this._restrict(i)));
    r.querySelectorAll("button[data-act]").forEach((b) => b.addEventListener("click", () => this._act(b)));
    r.querySelectorAll("[data-tt]").forEach((el) => {
      const ev = el.tagName === "BUTTON" ? "click" : "change";
      el.addEventListener(ev, () => this._ttEvent(el));
    });
  }

  _modeOpt(i) {
    const d = this._d, mode = i.dataset.mode, opt = i.dataset.modeOpt;
    const modes = JSON.parse(JSON.stringify(d.modes));
    if (opt === "gray") modes[mode].gray = i.checked;
    if (opt === "launcher") modes[mode].launcher = i.value;
    if (opt === "sound") modes[mode].sound = i.value;
    if (opt === "wa") modes[mode].wa = i.value;
    if (opt === "wa_app") modes[mode].wa_app = i.value;
    if (opt === "location") modes[mode].location = i.value;
    if (opt === "notes") modes[mode].notes = i.value;
    if (opt === "loc_every") modes[mode].loc_every = parseInt(i.value, 10);
    if (opt === "idle_min") modes[mode].idle_min = parseInt(i.value, 10);
    if (opt === "screen_off_min") modes[mode].screen_off_min = parseInt(i.value, 10);
    if (opt === "touch" || opt === "shade" || opt === "kiosk" || opt === "inbound" || opt === "camera") modes[mode][opt] = i.checked;
    if (opt === "auto_new") modes[mode].auto_new = i.checked;
    return this._svc("set_child", { config: { modes } });
  }

  _restrict(i) {
    const R = PhoneGuardCard.RESTRICTIONS;
    const cur = new Set(this._d.restrictions == null ? R.filter((r) => r[3]).map((r) => r[0]) : this._d.restrictions);
    (i.checked ? cur.add : cur.delete).call(cur, i.dataset.rs);
    return this._svc("set_child", { config: { restrictions: [...cur].sort() } });
  }

  _slug(name) { return name.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 30); }

  _act(b) {
    const a = b.dataset.act;
    const d = this._d;
    const ui = this._ui;
    ui.lastDev = ui.lastDev || {};
    if (a === "sp_save" || a === "sp_clear") {
      const v = a === "sp_clear" ? "" : (this.shadowRoot.querySelector("[data-sp-id]") || {}).value || "";
      return this._hass.callService("phoneguard", "set_options", { spotify_client_id: v.trim() });
    }
    if (a === "sp_signin") {
      ui.spLink = null;
      return this._hass.connection.sendMessagePromise({ type: "phoneguard/spotify_start", child: d.child, origin: window.location.origin })
        .then((r) => { ui.spLink = { child: d.child, url: r.url, redirect: r.redirect }; this._render(); })
        .catch((err) => { ui.spLink = { child: d.child, error: (err && err.message) || String(err) }; this._render(); });
    }
    if (a === "sp_signout") { ui.spLink = null; return this._svc("spotify_signout", {}); }
    if (a === "wx_home") { const h = this._hass.config; return this._svc("set_child", { config: { location: { lat: h.latitude, lon: h.longitude } } }); }
    if (a === "wx_off") return this._svc("set_child", { config: { location: null } });
    if (a === "select_person") {
      const g = this._groups().find((x) => x.key === b.dataset.key);
      if (!g) return;
      ui.sel = g.ids.includes(ui.lastDev[g.key]) ? ui.lastDev[g.key] : g.ids[0];
      try { localStorage.setItem("phoneguard-card:selected", ui.sel); } catch (e) { /* ignore */ }
      this._last = null; return (this.hass = this._hass);
    }
    if (a === "select") { ui.sel = b.dataset.id; if (this._all && this._all[ui.sel]) ui.lastDev[this._all[ui.sel].personKey] = ui.sel; try { localStorage.setItem("phoneguard-card:selected", ui.sel); } catch (e) { /* ignore */ } this._last = null; return (this.hass = this._hass); }
    if (a === "add") { ui.adding = true; ui.addMode = ""; ui.newPerson = ""; return this._render(); }
    if (a === "add_person") { ui.adding = true; ui.addMode = "person"; ui.newPerson = ""; ui.newPersonName = ""; return this._render(); }
    if (a === "add_cancel") { ui.adding = false; ui.addMode = ""; ui.newName = ""; return this._render(); }
    if (a === "add_go") {
      const known = this._people || [];
      const phones = this._companionChoices(this._hass, known);
      const phone = ui.newPhone !== undefined && ui.newPhone !== "" ? ui.newPhone : (phones[0] ? phones[0].service : "__other");
      const chosen = phones.find((p) => p.service === phone);
      const taken = new Set(this._groups().map((g) => g.key));
      const free = known.filter((p) => !taken.has(p.id));
      const choice = ui.newPerson || (ui.addMode === "person" ? (free[0] ? free[0].id : "__new") : (chosen && chosen.person ? chosen.person.id : (known[0] ? known[0].id : "__new")));
      const person = (choice === "__new" ? ui.newPersonName : choice).trim();
      const name = (chosen ? chosen.name : ui.newName).trim();
      const shown = choice === "__new" ? person : (known.find((p) => p.id === choice) || { name: person }).name;
      const slug = this._slug(shown + "_" + name);
      if (!person || !name || !slug) return alert("Choose the phone and the person, or give them names.");
      if ((this._ids || []).includes("sensor.phoneguard_" + slug)) return alert("That device already exists.");
      ui.adding = false; ui.addMode = ""; ui.newName = ""; ui.newPersonName = ""; ui.newPerson = ""; ui.newPhone = undefined;
      ui.sel = "sensor.phoneguard_" + slug;
      try { localStorage.setItem("phoneguard-card:selected", ui.sel); } catch (e) { /* ignore */ }
      const made = this._hass.callService("phoneguard", "create_pairing", { child: slug, name, person });
      return chosen ? made.then(() => this._hass.callService("phoneguard", "set_child", { child: slug, config: { notify: chosen.service } })) : made;
    }
    if (a === "rename_toggle") {
      ui.renaming = !ui.renaming;
      if (ui.renaming && d) { const me = (this._all || {})[ui.sel]; ui.rnPerson = me && me.linked ? me.personKey : (d.person || ""); ui.rnName = d.name; }
      return this._render();
    }
    if (a === "rename_go") { ui.renaming = false; return this._svc("set_child", { config: { person: (ui.rnPerson || "").trim() || d.name, name: ui.rnName.trim() || d.name } }); }
    if (a === "copy_toggle") { ui.copyOpen = !ui.copyOpen; return this._render(); }
    if (a === "copy_go") {
      const others = (this._ids || []).filter((id) => id !== ui.sel);
      const to = (others.includes(ui.copyTo) ? ui.copyTo : others[0] || "").replace("sensor.phoneguard_", "");
      const parts = Object.keys(ui.copyParts).filter((k) => ui.copyParts[k]);
      if (!to || !parts.length) return alert("Choose a device and at least one thing to copy.");
      const dest = (this._all[ "sensor.phoneguard_" + to ] || {}).name || to;
      if (!confirm("Copy " + parts.join(", ").replace("mode_options", "grey screen / everything allowed") + " to " + dest + "? It replaces what that device has now.")) return;
      ui.copyOpen = false;
      return this._svc("copy_settings", { to, parts }).catch((err) => alert("Not copied: " + (err.message || err)));
    }
    if (a === "usage_day") { ui.usageDay = b.dataset.day; return this._render(); }
    if (!d) return;
    if (a === "pair") return this._svc("create_pairing", { name: d.name });
    if (a === "confirm") return this._svc("confirm_pairing", { sas: ui.sas }).then(() => { ui.sas = ""; });
    if (a === "forget") return this._svc("forget");
    if (a === "keep_add" || a === "keep_del") {
      const cur = new Set(d.keep_running || []);
      if (a === "keep_del") cur.delete(b.dataset.pkg);
      else { const v = (this.shadowRoot.querySelector("[data-keep-add]") || {}).value; if (!v) return; cur.add(v); }
      return this._svc("set_child", { config: { keep_running: [...cur].sort() } });
    }
    if (a === "boost") return this._svc("boost", { mode: b.dataset.mode, minutes: Number(b.dataset.min) });
    if (a === "cancel_boost") return this._svc("cancel_boost");
    if (a === "override") return this._svc("set_override", { mode: b.dataset.mode });
    if (a === "nudge") return this._svc("nudge");
    if (a === "holiday") return this._svc("set_holiday", { from: ui.from || (d.holiday || {}).from, until: ui.until || (d.holiday || {}).until });
    if (a === "holiday_clear") { ui.from = ui.until = ""; return this._svc("set_holiday", {}); }
    if (a === "dismiss") return this._svc("dismiss_app", { package: b.dataset.pkg });
    if (a === "approve") {
      const modes = [...this._root.querySelectorAll(`input[data-pick="${CSS.escape(b.dataset.pkg)}"]:checked`)].map((i) => i.dataset.mode);
      return this._svc("approve_app", { package: b.dataset.pkg, modes });
    }
    if (a === "wa_save") {
      const get = (k) => this._root.querySelector(`[data-wa="${k}"]`);
      return this._svc("set_child", { config: { whatsapp: { all: get("all").checked, allowed: get("allowed").value.split("\n"), perMinute: Number(get("perMinute").value) || 5, perDay: Number(get("perDay").value) || 200 } } });
    }
    if (a === "rs_default") return this._svc("set_child", { config: { restrictions: null } });
    if (a === "release" && confirm("Release this phone? It stops being managed and everything is unhidden on its next sync.")) return this._svc("release");
    if (a === "remove" && confirm("Remove " + d.name + " completely? This deletes its timetable, app choices and usage history, and the phone stops being managed if it is paired.")) {
      if (d.device && !d.device.pending) this._svc("release");
      return this._svc("remove_child");
    }
  }
}

customElements.define("phoneguard-card", PhoneGuardCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "phoneguard-card", name: "Phone Guard", description: "Parent view of a supervised phone" });
