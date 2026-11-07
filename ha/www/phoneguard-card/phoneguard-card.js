/* Phone Guard card v0.1.0. Parent view of one child's phone: status, boost, holiday, app approvals, pairing.
 * Config: type: custom:phoneguard-card, entity: sensor.phoneguard_<child>
 * Reads the JSON string in the sensor's `data` attribute; every action is a phoneguard.* service. */
const PG_VERSION = "0.1.0";

class PhoneGuardCard extends HTMLElement {
  setConfig(config) {
    if (!config.entity) throw new Error("entity is required (sensor.phoneguard_<child>)");
    this._config = config;
    this._last = null;
    this._ui = this._ui || { sas: "", from: "", until: "", boostMode: "day", pickModes: {} };
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

  _wire() {
    const r = this._root;
    const sas = r.querySelector("input.sas");
    if (sas) sas.addEventListener("input", () => { this._ui.sas = sas.value; });
    r.querySelectorAll("input[data-f]").forEach((i) => i.addEventListener("change", () => { this._ui[i.dataset.f] = i.value; }));
    r.querySelectorAll("input[data-app]").forEach((i) =>
      i.addEventListener("change", () => this._svc("set_app_mode", { package: i.dataset.app, mode: i.dataset.mode, allowed: i.checked })));
    r.querySelectorAll("button[data-act]").forEach((b) => b.addEventListener("click", () => this._act(b)));
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
