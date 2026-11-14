const S = document.querySelector("main").dataset.session, v = document.getElementById("v");
const h = (t, a = {}, ...k) => { const e = document.createElement(t); for (const [x, y] of Object.entries(a)) { if (x === "on") e.addEventListener("click", y); else if (x === "html") e.innerHTML = y; else e.setAttribute(x, y); } e.append(...k.flat().filter((c) => c != null)); return e; };
const call = (p, o = {}) => fetch(p, { ...o, headers: { "x-session": S, "content-type": "application/json" } }).then((r) => r.json());
const TOKEN_LINK = "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=phoneguard-setup";

function welcome(msg) {
  const key = h("input", { type: "password", placeholder: "Paste the key from Cloudflare", autocomplete: "off", "aria-label": "Cloudflare key" }), err = h("p", { class: "bad" }, msg ?? "");
  v.replaceChildren(
    h("p", {}, "This sets up your family's private companion in your own Cloudflare account. It takes a few minutes and you only do it once."),
    h("div", { class: "card" }, h("strong", {}, "1. Get a key from Cloudflare"), h("p", { class: "mut" }, "Cloudflare is the service that will hold your family's settings. The free plan is enough. Create a free account first if you do not have one."),
      h("a", { class: "btn", href: TOKEN_LINK, target: "_blank", rel: "noopener noreferrer" }, "Open Cloudflare"), h("p", { class: "mut" }, "On that page press Continue to summary, then Create token, and copy the key it shows.")),
    h("div", { class: "card" }, h("strong", {}, "2. Paste it here"), h("p", { class: "mut" }, "The key stays on this computer. It is only used to talk to Cloudflare and is forgotten when you close this page."), key, err,
      h("p", {}, h("button", { on: () => go({ token: key.value.trim() }) }, "Start setting up"))));
}
async function go(body) { const r = await call("/run", { method: "POST", body: JSON.stringify(body) }); if (r.error) return welcome(r.error); poll(body); }
async function poll(body) {
  const j = await call("/status");
  if (j.needAccount) return v.replaceChildren(h("p", {}, "Your key can see more than one Cloudflare account. Which one should hold the family's companion?"), ...j.needAccount.map((a) => h("p", {}, h("button", { on: () => go({ ...body, account: a.id }) }, a.name))));
  v.replaceChildren(h("h2", {}, "Setting up"), h("ul", {}, j.steps.map((s) => h("li", { class: s.state }, s.text))), j.error ? h("div", {}, h("p", { class: "bad" }, j.error), h("button", { on: () => welcome() }, "Try again")) : null);
  if (j.result) return done(j.result);
  if (!j.error) setTimeout(() => poll(body), 1500);
}
function done(r) {
  const mins = Math.max(0, Math.round((r.expires - Date.now()) / 60000));
  v.replaceChildren(h("h2", { class: "ok" }, "Your companion is ready"),
    h("div", { class: "card" }, h("strong", {}, "A. On the child's phone"), h("p", { class: "mut" }, `Open Phone Guard on the child's phone and scan this, or type the address and code instead. The code works for about ${mins} minutes; if it runs out, press Add a phone on your parent page for a new one.`),
      h("div", { class: "grid" }, h("div", { class: "qr", html: r.phoneQr }), h("div", {}, h("div", { class: "mut" }, "Address"), h("div", { class: "code" }, r.address), h("div", { class: "mut" }, "Code"), h("div", { class: "code" }, r.code)))),
    h("div", { class: "card" }, h("strong", {}, "B. On your own phone"), h("p", { class: "mut" }, "Scan this with your phone's camera. It opens your private parent page, where you approve the child's phone by typing the 4 digits it shows. Anyone with this link can manage the phone, so keep it private."),
      h("div", { class: "qr", html: r.parentQr }), h("p", { class: "link" }, r.link)),
    h("p", {}, h("a", { href: `https://${r.host}/guide`, target: "_blank", rel: "noopener noreferrer" }, "How it works and what to do next (opens your companion)")),
    h("p", { class: "mut" }, `A copy of your parent key is saved at ${r.saved}. Keep it somewhere safe.`));
}
welcome();
