// Live checks run from the parent's computer before anything is sent to the phone: does this mail login work, is this calendar link readable.
import net from "node:net";
import tls from "node:tls";

const sni = (host) => (net.isIP(host) ? {} : { servername: host });   // TLS refuses an IP address as a server name
const quote = (s) => `"${String(s).replace(/[\\"]/g, "\\$&")}"`;

// A tiny line-based client: connect, send commands, read until a line that satisfies `until`.
function reader(sock, timeoutMs = 15000) {
  let buf = ""; const waiters = [];
  const pump = () => { while (waiters.length) { const w = waiters[0], lines = buf.split("\r\n"); const done = lines.findIndex((l, i) => i < lines.length - 1 && w.until(l)); if (done < 0) return; buf = lines.slice(done + 1).join("\r\n"); waiters.shift().resolve(lines.slice(0, done + 1)); } };
  sock.setEncoding("utf8"); sock.on("data", (d) => { buf += d; pump(); });
  const fail = (e) => { while (waiters.length) waiters.shift().reject(e); }; sock.on("error", fail); sock.on("close", () => fail(new Error("the server closed the connection")));
  return { sock, write: (s) => sock.write(s + "\r\n"), read: (until) => new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error("the server stopped answering")), timeoutMs); waiters.push({ until, resolve: (x) => { clearTimeout(timer); resolve(x); }, reject: (e) => { clearTimeout(timer); reject(e); } }); pump(); }) };
}
function open({ host, port, security, allowPlain = false, timeoutMs = 15000, tlsOptions = {} }) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${host} did not answer within ${timeoutMs / 1000} seconds`)), timeoutMs);
    const ok = (s) => { clearTimeout(t); resolve(reader(s, timeoutMs)); }, bad = (e) => { clearTimeout(t); reject(e); };
    if (security === "ssl") { const s = tls.connect({ host, port, ...sni(host), ...tlsOptions }, () => ok(s)); s.on("error", bad); }
    else if (security === "starttls" || (security === "none" && allowPlain)) { const s = net.connect({ host, port }, () => ok(s)); s.on("error", bad); }
    else bad(new Error("unsupported security setting"));
  });
}
const upgrade = (c, host, tlsOptions = {}, timeoutMs = 15000) => new Promise((resolve, reject) => { const s = tls.connect({ socket: c.sock, ...sni(host), ...tlsOptions }, () => resolve(reader(s, timeoutMs))); s.on("error", reject); });

const friendly = (e) => /ENOTFOUND|EAI_AGAIN/.test(e.message) ? "That server name was not found. Check it for typing mistakes." : /ECONNREFUSED|ETIMEDOUT|did not answer/.test(e.message) ? "Could not reach the server. Check the server name and port, and your internet connection." : /certificate|self.signed|hostname/i.test(e.message) ? "The server's security certificate was not accepted, so the connection was refused." : e.message;
const AUTHMSG = "The server did not accept that address and password. Most providers need an app password, not the normal password.";

export async function testImap({ host, port, security, user, password, ...opts }) {
  let c; try {
    c = await open({ host, port, security, ...opts });
    await c.read((l) => /^\* (OK|PREAUTH|BYE)/i.test(l));
    if (security === "starttls") { c.write("a0 STARTTLS"); const r = await c.read((l) => /^a0 /i.test(l)); if (!/^a0 OK/i.test(r.at(-1))) return { ok: false, message: "The server does not offer a secure connection on that port." }; c = await upgrade(c, host, opts.tlsOptions, opts.timeoutMs); }
    c.write(`a1 LOGIN ${quote(user)} ${quote(password)}`);
    const r = await c.read((l) => /^a1 /i.test(l)), last = r.at(-1);
    c.write("a2 LOGOUT");
    return /^a1 OK/i.test(last) ? { ok: true, message: "Signed in." } : { ok: false, message: /AUTHENTICATIONFAILED|invalid credentials|authentication failed|LOGIN failed/i.test(last) ? AUTHMSG : `The server said: ${last.replace(/^a1 (NO|BAD)\s*/i, "").slice(0, 120)}` };
  } catch (e) { return { ok: false, message: friendly(e) }; } finally { c?.sock?.destroy(); }
}

export async function testSmtp({ host, port, security, user, password, ...opts }) {
  let c; try {
    c = await open({ host, port, security, ...opts });
    await c.read((l) => /^220 /.test(l));
    const ehlo = () => { c.write("EHLO phoneguard.local"); return c.read((l) => /^250 /.test(l) || /^[45]\d\d /.test(l)); };
    await ehlo();
    if (security === "starttls") { c.write("STARTTLS"); const r = await c.read((l) => /^\d{3} /.test(l)); if (!/^220/.test(r.at(-1))) return { ok: false, message: "The server does not offer a secure connection on that port." }; c = await upgrade(c, host, opts.tlsOptions, opts.timeoutMs); await ehlo(); }
    c.write("AUTH PLAIN " + Buffer.from(`\0${user}\0${password}`).toString("base64"));
    const r = await c.read((l) => /^\d{3} /.test(l)), last = r.at(-1);
    c.write("QUIT");
    return /^235/.test(last) ? { ok: true, message: "Signed in." } : { ok: false, message: /^53[045]/.test(last) ? AUTHMSG : `The server said: ${last.slice(0, 120)}` };
  } catch (e) { return { ok: false, message: friendly(e) }; } finally { c?.sock?.destroy(); }
}

export async function checkCalendarUrl(input, { fetchImpl = fetch } = {}) {
  let url = String(input ?? "").trim().replace(/^webcal:\/\//i, "https://");
  if (!/^https:\/\//i.test(url)) return { ok: false, message: "The link must start with https:// or webcal://." };
  try {
    const r = await fetchImpl(url, { redirect: "follow", signal: AbortSignal.timeout(20000), headers: { "user-agent": "phoneguard-setup" } });
    if (!r.ok) return { ok: false, message: r.status === 404 || r.status === 403 ? "That link is not available (it may have been reset or copied wrongly)." : `The calendar server answered ${r.status}.` };
    const text = await r.text();
    if (text.length > 5_000_000) return { ok: false, message: "That calendar file is too large for the phone." };
    if (!/BEGIN:VCALENDAR/i.test(text)) return { ok: false, message: "That link did not give a calendar. Use the calendar's 'Secret address in iCal format' (a link ending .ics)." };
    const events = (text.match(/BEGIN:VEVENT/gi) ?? []).length, name = /^X-WR-CALNAME:(.+)$/im.exec(text)?.[1]?.trim() ?? "";
    return { ok: true, url, name, events, message: events ? `Found a calendar with ${events} event${events === 1 ? "" : "s"}.` : "Found a calendar with no events yet." };
  } catch (e) { return { ok: false, message: e.name === "TimeoutError" ? "The calendar server took too long to answer." : "Could not read that link. Check it and your internet connection." }; }
}
