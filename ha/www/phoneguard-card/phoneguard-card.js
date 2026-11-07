/* Phone Guard card v0.7.0. Parent view of one child's phone: status, boost, holiday, app approvals, pairing.
 * Config: type: custom:phoneguard-card   (shows every guarded device, with tabs)
 *         optional  entity: sensor.phoneguard_<child>   to show just one
 * Reads the JSON string in the sensor's `data` attribute; every action is a phoneguard.* service. */
const PG_VERSION = "0.7.0";

class PhoneGuardCard extends HTMLElement {
  setConfig(config) {
    this._config = config || {};
    this._last = null;
    if (!this._ui) {
      let sel = null;
      try { sel = localStorage.getItem("phoneguard-card:selected"); } catch (e) { /* private mode */ }
      this._ui = { sel, sas: "", from: "", until: "", boostMode: "day", adding: false, newName: "", tts: {}, usageDay: null, newPerson: "", newPersonName: "", copyOpen: false, copyTo: "", copyParts: { timetable: true, holiday: false, restrictions: false, mode_options: false }, renaming: false, rnPerson: "", rnName: "" };
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
      .who { display:flex; align-items:center; gap:14px; margin-bottom: 12px; }
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
      html += `<div class="sub">${this._esc(bits.join(" · "))}</div>`;
    }

    if (!dev) html += this._pairHtml(d);
    else if (dev.pending) {
      html += `<h3>Confirm the phone</h3>
        <div class="sub">The phone shows a 4-digit number. Type it here to confirm this is the right phone.</div>
        <div class="row" style="margin-top:8px"><input class="sas" type="text" inputmode="numeric" maxlength="4" placeholder="1234" value="${this._esc(this._ui.sas)}">
        <button class="primary" data-act="confirm">Confirm</button><button data-act="forget" class="danger">Cancel pairing</button></div>`;
    } else {
      html += this._boostHtml(d) + this._holidayHtml(d) + this._newAppsHtml(d);
    }
    html += this._timetableHtml(d) + this._copyHtml(d);
    if (dev && !dev.pending) html += this._matrixHtml(d) + this._usageHtml(d);
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
      <div class="sub mut" style="margin-top:6px">${this._esc((dev && dev.model) || "")} · Phone Guard card ${PG_VERSION}</div>`;
    wrap(html);
    this._wire();
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
    let h = `<div class="who">${this._avatar(me.picture, me.person, 56)}<div><h2 class="pname">${this._esc(me.person)}</h2>`;
    const others = this._groups().filter((g) => g.key !== me.personKey);
    if (others.length && !this._config.entity) {
      h += `<div class="chips">${others.map((g) => `<button class="small" data-act="select_person" data-key="${this._esc(g.key)}">${this._esc(g.name)}</button>`).join("")}</div>`;
    }
    return h + `</div></div>`;
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
      const sel = this._ui.newPerson || (chosen && chosen.person ? chosen.person.id : (me && people.find((p) => p.id === me.personKey) ? me.personKey : (people[0] ? people[0].id : "__new")));
      h += `<div class="addform"><b>Add a device</b>
        <select data-f="newPhone">${phones.map((p) => `<option value="${this._esc(p.service)}" ${phone === p.service ? "selected" : ""}>${this._esc(p.name)}</option>`).join("")}<option value="__other" ${phone === "__other" ? "selected" : ""}>Not in Home Assistant yet…</option></select>
        ${phone === "__other" ? `<input type="text" data-f="newName" placeholder="Device name, e.g. Tablet" value="${this._esc(this._ui.newName)}">` : ""}
        <select data-f="newPerson">${people.map((p) => `<option value="${this._esc(p.id)}" ${sel === p.id ? "selected" : ""}>${this._esc(p.name)}</option>`).join("")}<option value="__new" ${sel === "__new" ? "selected" : ""}>Someone without a Home Assistant person…</option></select>
        ${sel === "__new" ? `<input type="text" data-f="newPersonName" placeholder="Name" value="${this._esc(this._ui.newPersonName)}">` : ""}
        <button class="primary" data-act="add_go">Create</button><button data-act="add_cancel">Cancel</button></div>
        <div class="sub" style="width:100%">Pick the phone running the Home Assistant app and its name and owner follow Home Assistant (Settings, Devices, and People).</div>`;
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
  _boostHtml(d) {
    const modes = Object.keys(d.modes);
    const sel = this._ui.boostMode && d.modes[this._ui.boostMode] ? this._ui.boostMode : (d.modes.day ? "day" : modes[0]);
    const name = (m) => (d.modes[m] || { name: m }).name;
    const boost = `<h3>Boost</h3>
      <div class="row"><span>Give</span><select data-f="boostMode">${modes.map((m) => `<option value="${m}" ${m === sel ? "selected" : ""}>${this._esc(name(m))}</option>`).join("")}</select><span>access for</span></div>
      <div class="row" style="margin-top:8px"><button data-act="boost" data-min="15">+15 min</button><button data-act="boost" data-min="30">+30 min</button>
      <button data-act="boost" data-min="60">+1 h</button>${d.boost ? `<button data-act="cancel_boost" class="danger">Cancel boost</button>` : ""}</div>
      <div class="sub" style="margin-top:6px">${d.boost
        ? `${this._esc(name(d.boost.mode))} access until ${this._fmtTime(d.boost.until)}. Each press adds time.`
        : "Adds that mode's apps on top of what is allowed right now, then ends by itself."}</div>`;
    const mode = `<h3>Mode</h3>
      <div class="seg"><button class="${d.override ? "" : "sel"}" data-act="override" data-mode="auto">Auto</button>${modes.map((m) =>
        `<button class="${d.override === m ? "sel" : ""}" data-act="override" data-mode="${m}">${this._esc(name(m))}</button>`).join("")}</div>
      <div class="sub" style="margin-top:6px">${d.override
        ? `Held in ${this._esc(name(d.override))} whatever the timetable says, until you choose Auto.`
        : `Following the timetable (${this._esc(name(d.mode_now))} now). Pick a mode to hold it.`}</div>`;
    return `<div class="split"><div class="blk">${boost}</div><div class="blk">${mode}</div></div>`;
  }

  _holidayHtml(d) {
    const h = d.holiday || {};
    return `<h3>Holiday (School becomes Day)</h3><div class="row">
      <input type="date" data-f="from" value="${this._esc(this._ui.from || h.from || "")}">
      <input type="date" data-f="until" value="${this._esc(this._ui.until || h.until || "")}">
      <button data-act="holiday" class="primary">Set</button><button data-act="holiday_clear">Clear</button></div>
      ${d.holiday ? `<div class="sub">On from ${this._esc(h.from)} to ${this._esc(h.until)}.</div>` : ""}`;
  }

  _newAppsHtml(d) {
    if (!d.pending_apps.length) return "";
    const all = Object.keys(d.modes);
    let h = `<h3>New apps waiting</h3>`;
    for (const pkg of d.pending_apps) {
      const app = d.apps.find((a) => a.pkg === pkg) || { pkg, label: pkg };
      h += `<div class="new"><b>${this._esc(app.label)}</b><div class="sub">${this._esc(pkg)} · hidden until you decide</div>
        <div class="row" style="margin-top:6px">${all.map((m) => `<label><input type="checkbox" data-pick="${this._esc(pkg)}" data-mode="${m}"> ${this._esc(d.modes[m].name)}</label>`).join(" ")}
        <button class="primary" data-act="approve" data-pkg="${this._esc(pkg)}">Approve</button>
        <button data-act="dismiss" data-pkg="${this._esc(pkg)}">Keep hidden</button></div></div>`;
    }
    return h;
  }

  // every mode is a column: whether it allows everything, whether the screen is grey, then app by app
  _matrixHtml(d) {
    const ids = Object.keys(d.modes);
    let h = `<h3>Modes and apps</h3>`;
    if (!d.apps.length) return h + `<div class="sub">The phone has not reported its apps yet.</div>`;
    h += `<table><tr><th>App</th>${ids.map((m) => `<th>${this._esc(d.modes[m].name)}</th>`).join("")}</tr>
      <tr class="opt"><td>Everything allowed</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="allowAll" data-mode="${m}" ${d.modes[m].allowAll ? "checked" : ""}></td>`).join("")}</tr>
      <tr class="opt"><td>Grey screen</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="gray" data-mode="${m}" ${d.modes[m].gray ? "checked" : ""}></td>`).join("")}</tr>
      <tr class="opt"><td>Home screen</td>${ids.map((m) => `<td><select data-mode-opt="launcher" data-mode="${m}"><option value="default" ${d.modes[m].launcher !== "nokia" ? "selected" : ""}>Normal</option><option value="nokia" ${d.modes[m].launcher === "nokia" ? "selected" : ""}>Nokia</option></select></td>`).join("")}</tr>
      <tr class="opt"><td>Touchscreen works</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="touch" data-mode="${m}" ${d.modes[m].touch !== false ? "checked" : ""}></td>`).join("")}</tr>
      <tr class="opt"><td>Pull-down shade works</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="shade" data-mode="${m}" ${d.modes[m].shade !== false ? "checked" : ""}></td>`).join("")}</tr>
      <tr class="opt"><td>Sound</td>${ids.map((m) => `<td><select data-mode-opt="sound" data-mode="${m}">${[["normal", "Normal"], ["vibrate", "Vibrate"], ["silent", "Silent"]].map(([v, t]) => `<option value="${v}" ${(d.modes[m].sound || "normal") === v ? "selected" : ""}>${t}</option>`).join("")}</select></td>`).join("")}</tr>
      <tr class="opt"><td>Incoming calls allowed</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="inbound" data-mode="${m}" ${d.modes[m].inbound !== false ? "checked" : ""}></td>`).join("")}</tr>
      <tr class="opt"><td>Locked to allowed apps</td>${ids.map((m) => `<td><input type="checkbox" data-mode-opt="kiosk" data-mode="${m}" ${d.modes[m].kiosk ? "checked" : ""}></td>`).join("")}</tr>`;
    for (const a of d.apps) {
      h += `<tr><td class="name">${this._esc(a.label)}<small>${this._esc(a.pkg)}</small></td>` +
        ids.map((m) => {
          const on = d.modes[m].allowAll || (d.modes[m].allow || []).includes(a.pkg);
          return `<td><input type="checkbox" data-app="${this._esc(a.pkg)}" data-mode="${m}" ${on ? "checked" : ""} ${d.modes[m].allowAll ? "disabled" : ""}></td>`;
        }).join("") + `</tr>`;
    }
    return h + `</table><div class="sub" style="margin-top:6px">Untick "Everything allowed" to choose apps for that mode (it starts with every app ticked). Phone, messages, contacts and Settings are always available. Grey screen turns the whole display black and white. Sound: Silent is total silence with no exceptions. Incoming calls allowed: untick it and the phone hangs up on every call in that mode (parents ring the school office instead). A Nokia home screen with touch off, no shade and locked apps gives the classic keypad-phone look.</div>
      <div class="sub" style="margin-top:6px">The Clock hides its alarms: leave it ticked in any mode where she needs her alarm to ring. In School it is left out on purpose.</div>`;
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
      <label class="rs"><input type="checkbox" data-cp="mode_options" ${P.mode_options ? "checked" : ""}><span>Grey screen, sound, incoming calls and "Everything allowed" for each mode<small>Only for modes both devices have.</small></span></label>
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
    r.querySelectorAll("input[data-app]").forEach((i) =>
      i.addEventListener("change", () => this._svc("set_app_mode", { package: i.dataset.app, mode: i.dataset.mode, allowed: i.checked })));
    r.querySelectorAll("input[data-mode-opt], select[data-mode-opt]").forEach((i) => i.addEventListener("change", () => this._modeOpt(i)));
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
    if (opt === "touch" || opt === "shade" || opt === "kiosk" || opt === "inbound") modes[mode][opt] = i.checked;
    if (opt === "allowAll") {
      modes[mode].allowAll = i.checked;
      // leaving "everything allowed": start from every current app ticked, so nothing changes until you untick something
      if (!i.checked && !(modes[mode].allow || []).length) modes[mode].allow = d.apps.map((a) => a.pkg);
    }
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
    if (a === "select_person") {
      const g = this._groups().find((x) => x.key === b.dataset.key);
      if (!g) return;
      ui.sel = g.ids.includes(ui.lastDev[g.key]) ? ui.lastDev[g.key] : g.ids[0];
      try { localStorage.setItem("phoneguard-card:selected", ui.sel); } catch (e) { /* ignore */ }
      this._last = null; return (this.hass = this._hass);
    }
    if (a === "select") { ui.sel = b.dataset.id; if (this._all && this._all[ui.sel]) ui.lastDev[this._all[ui.sel].personKey] = ui.sel; try { localStorage.setItem("phoneguard-card:selected", ui.sel); } catch (e) { /* ignore */ } this._last = null; return (this.hass = this._hass); }
    if (a === "add") { ui.adding = true; return this._render(); }
    if (a === "add_cancel") { ui.adding = false; ui.newName = ""; return this._render(); }
    if (a === "add_go") {
      const known = this._people || [];
      const phones = this._companionChoices(this._hass, known);
      const phone = ui.newPhone !== undefined && ui.newPhone !== "" ? ui.newPhone : (phones[0] ? phones[0].service : "__other");
      const chosen = phones.find((p) => p.service === phone);
      const choice = ui.newPerson || (chosen && chosen.person ? chosen.person.id : (known[0] ? known[0].id : "__new"));
      const person = (choice === "__new" ? ui.newPersonName : choice).trim();
      const name = (chosen ? chosen.name : ui.newName).trim();
      const shown = choice === "__new" ? person : (known.find((p) => p.id === choice) || { name: person }).name;
      const slug = this._slug(shown + "_" + name);
      if (!person || !name || !slug) return alert("Choose the phone and the person, or give them names.");
      if ((this._ids || []).includes("sensor.phoneguard_" + slug)) return alert("That device already exists.");
      ui.adding = false; ui.newName = ""; ui.newPersonName = ""; ui.newPerson = ""; ui.newPhone = undefined;
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
    if (a === "boost") return this._svc("boost", { mode: ui.boostMode && d.modes[ui.boostMode] ? ui.boostMode : (d.modes.day ? "day" : Object.keys(d.modes)[0]), minutes: Number(b.dataset.min) });
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
