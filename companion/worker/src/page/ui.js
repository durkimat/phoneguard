// Small shared helpers for the parent page. Everything from the server is put in with textContent (phone names and app labels are untrusted).
export const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "html") e.innerHTML = v;
    else if (k === "on") for (const [ev, fn] of Object.entries(v)) e.addEventListener(ev, fn);
    else if (k === "class") e.className = v;
    else if (k === "value") e.value = v;
    else if (k === "checked" || k === "selected" || k === "disabled") { if (v) e.setAttribute(k, ""); }
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c);
  return e;
};
export const ago = (t) => { if (!t) return "never"; const s = Math.max(0, (Date.now() - t) / 1000); return s < 90 ? "just now" : s < 5400 ? Math.round(s / 60) + " min ago" : s < 129600 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " days ago"; };
export const hm = (min) => String(Math.floor(min / 60)).padStart(2, "0") + ":" + String(min % 60).padStart(2, "0");
export const toMin = (v) => { const [a, b] = v.split(":").map(Number); return a * 60 + b; };
export const left = (until, now = Date.now()) => { const m = Math.max(0, Math.round((until - now) / 60000)); return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`; };
// A status line under a button: shows saving / saved / the problem.
export const status = () => { const p = h("p", { class: "mut", "aria-live": "polite" }); const f = (kind, text, problems = []) => { p.className = kind; p.replaceChildren(text, ...problems.map((x) => h("div", {}, x))); }; f.el = p; return f; };
export const section = (title, open, ...kids) => h("details", { class: "sec", open }, h("summary", {}, title), h("div", { class: "secbody" }, kids));
export const field = (label, control, hint) => h("label", { class: "fld" }, h("span", {}, label, hint ? h("small", {}, hint) : null), control);
export const select = (opts, current, onchange, attrs = {}) => h("select", { ...attrs, on: { change: (e) => onchange(e.target.value) } }, opts.map(([v, t]) => h("option", { value: v, selected: String(v) === String(current) }, t)));
export const check = (on, onchange, attrs = {}) => h("input", { type: "checkbox", checked: !!on, ...attrs, on: { change: (e) => onchange(e.target.checked) } });

/** Compares x.y.z version strings: negative if a is older than b, 0 if equal, positive if newer. */
export const cmpVer = (a, b) => { const p = (v) => String(v ?? "0").replace(/^v/, "").split(".").map((n) => parseInt(n, 10) || 0); const x = p(a), y = p(b); for (let i = 0; i < Math.max(x.length, y.length, 3); i++) { const d = (x[i] ?? 0) - (y[i] ?? 0); if (d) return d; } return 0; };
