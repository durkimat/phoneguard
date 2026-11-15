// The live checks against fake mail servers (plain, TLS and STARTTLS) and a fake calendar server.
import assert from "node:assert/strict";
import net from "node:net";
import tls from "node:tls";
import http from "node:http";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { testImap, testSmtp, checkCalendarUrl } from "../checks.mjs";
import { PROVIDERS, providerFor, UNSUPPORTED } from "../providers.mjs";

let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };
const dir = mkdtempSync(join(tmpdir(), "pgchk-"));
execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", join(dir, "k.pem"), "-out", join(dir, "c.pem"), "-days", "2", "-subj", "/CN=localhost"], { stdio: "ignore" });
const cert = { key: readFileSync(join(dir, "k.pem")), cert: readFileSync(join(dir, "c.pem")) }, trust = { rejectUnauthorized: false };

// ---- a fake IMAP server: greeting, LOGIN (user "u@example.com" / "good pass" with a quote), LOGOUT, optional STARTTLS
const imapHandler = (sock, { starttls = false } = {}) => {
  sock.setEncoding("utf8"); sock.write("* OK IMAP ready\r\n"); let buf = "";
  sock.on("data", (d) => { buf += d; let i; while ((i = buf.indexOf("\r\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 2); const [tag, cmd, ...rest] = line.split(" ");
    if (cmd === "STARTTLS") { sock.write(`${tag} OK Begin TLS\r\n`); sock.removeAllListeners("data"); const secure = new tls.TLSSocket(sock, { isServer: true, ...cert }); imapHandler2(secure); return; }
    if (cmd === "LOGIN") sock.write(rest.join(" ") === '"u@example.com" "good \\"pass"' ? `${tag} OK Logged in\r\n` : `${tag} NO [AUTHENTICATIONFAILED] Invalid credentials\r\n`);
    if (cmd === "LOGOUT") { sock.write(`* BYE\r\n${tag} OK bye\r\n`); sock.end(); } } });
};
const imapHandler2 = (sock) => { sock.setEncoding("utf8"); let buf = ""; sock.on("data", (d) => { buf += d; let i; while ((i = buf.indexOf("\r\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 2); const [tag, cmd, ...rest] = line.split(" "); if (cmd === "LOGIN") sock.write(rest.join(" ") === '"u@example.com" "good \\"pass"' ? `${tag} OK Logged in\r\n` : `${tag} NO [AUTHENTICATIONFAILED] Invalid credentials\r\n`); if (cmd === "LOGOUT") { sock.write(`${tag} OK bye\r\n`); sock.end(); } } }); };
const smtpHandler = (sock, { greet = true } = {}) => {
  sock.setEncoding("utf8"); if (greet) sock.write("220 smtp ready\r\n"); let buf = "";
  sock.on("data", (d) => { buf += d; let i; while ((i = buf.indexOf("\r\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 2);
    if (line.startsWith("EHLO")) sock.write("250-smtp\r\n250 AUTH PLAIN\r\n");
    else if (line === "STARTTLS") { sock.write("220 go ahead\r\n"); sock.removeAllListeners("data"); const s2 = new tls.TLSSocket(sock, { isServer: true, ...cert }); smtpHandler(s2, { greet: false }); return; }
    else if (line.startsWith("AUTH PLAIN")) sock.write(Buffer.from(line.slice(11), "base64").toString() === "\0u@example.com\0good \"pass" ? "235 ok\r\n" : "535 5.7.8 bad credentials\r\n");
    else if (line === "QUIT") { sock.write("221 bye\r\n"); sock.end(); } } });
};
const listen = (server) => new Promise((res) => server.listen(0, "127.0.0.1", () => res(server.address().port)));
const imapPlain = net.createServer((s) => imapHandler(s)), imapTls = tls.createServer(cert, (s) => imapHandler2(s) || s.write("* OK ready\r\n")), imapStart = net.createServer((s) => imapHandler(s, { starttls: true })), smtpStart = net.createServer((s) => smtpHandler(s)), smtpTls = tls.createServer(cert, (s) => smtpHandler(s));
const ports = { imapPlain: await listen(imapPlain), imapTls: await listen(imapTls), imapStart: await listen(imapStart), smtpStart: await listen(smtpStart), smtpTls: await listen(smtpTls) };
const base = { host: "127.0.0.1", user: "u@example.com", password: 'good "pass', allowPlain: true, tlsOptions: trust, timeoutMs: 4000 };

assert.deepEqual(await testImap({ ...base, port: ports.imapPlain, security: "none" }), { ok: true, message: "Signed in." }); ok("a correct mail login is accepted (including a password with a quote in it)");
const bad = await testImap({ ...base, port: ports.imapPlain, security: "none", password: "wrong" }); assert.equal(bad.ok, false); assert.match(bad.message, /app password/); ok("a wrong password gets a plain explanation that points to app passwords");
assert.equal((await testImap({ ...base, port: ports.imapStart, security: "starttls" })).ok, true); ok("IMAP over STARTTLS works");
const refused = await testImap({ ...base, port: 1, security: "none" }); assert.equal(refused.ok, false); assert.match(refused.message, /Could not reach/); ok("a closed port is reported as 'could not reach the server'");
const nohost = await testImap({ ...base, host: "no-such-host.invalid", port: 993, security: "ssl", allowPlain: false }); assert.equal(nohost.ok, false); assert.match(nohost.message, /not found|Could not reach/); ok("a mistyped server name is reported clearly");
const strict = await testImap({ host: "127.0.0.1", port: ports.imapTls, security: "ssl", user: "u@example.com", password: "x", timeoutMs: 4000 }); assert.equal(strict.ok, false); assert.match(strict.message, /certificate/); ok("a server certificate that is not trusted is refused (checking is on by default)");
assert.equal((await testSmtp({ ...base, port: ports.smtpStart, security: "starttls" })).ok, true); const sbad = await testSmtp({ ...base, port: ports.smtpStart, security: "starttls", password: "nope" }); assert.equal(sbad.ok, false); assert.match(sbad.message, /app password/); ok("outgoing mail (SMTP over STARTTLS): correct login accepted, wrong one explained");

// ---- calendar links
const cal = http.createServer((q, s) => { if (q.url === "/good.ics") { s.setHeader("content-type", "text/calendar"); s.end("BEGIN:VCALENDAR\r\nX-WR-CALNAME:Family\r\nBEGIN:VEVENT\r\nSUMMARY:a\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nSUMMARY:b\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"); } else if (q.url === "/page") s.end("<html>not a calendar</html>"); else { s.statusCode = 404; s.end("no"); } });
const cp = await listen(cal); const local = (path) => async (u, o) => fetch(`http://127.0.0.1:${cp}${path}`, o);
const good = await checkCalendarUrl("webcal://calendar.example.com/good.ics", { fetchImpl: local("/good.ics") }); assert.ok(good.ok); assert.equal(good.events, 2); assert.equal(good.name, "Family"); assert.equal(good.url, "https://calendar.example.com/good.ics"); ok("a calendar link is read: its name and number of events are shown, webcal:// becomes https://");
assert.equal((await checkCalendarUrl("https://x.example/page", { fetchImpl: local("/page") })).ok, false); assert.match((await checkCalendarUrl("https://x.example/missing", { fetchImpl: local("/missing") })).message, /not available/); assert.match((await checkCalendarUrl("http://plain.example/x.ics")).message, /https/); assert.equal((await checkCalendarUrl("nonsense")).ok, false); ok("a web page, a missing link, a plain http link and nonsense are all refused with a reason");

// ---- providers
assert.equal(providerFor("me@gmail.com").id, "gmail"); assert.equal(providerFor("a@icloud.com").id, "icloud"); assert.equal(providerFor("a@fastmail.com").id, "fastmail"); assert.equal(providerFor("a@unknown.org"), null); assert.ok(PROVIDERS.every((p) => p.imap.port && p.smtp.port && p.passwordHelp.steps.length)); assert.ok(UNSUPPORTED[0].match.test("a@outlook.com") && UNSUPPORTED[0].match.test("a@hotmail.co.uk") && !UNSUPPORTED[0].match.test("a@gmail.com")); ok("provider presets are found by address; Microsoft addresses are recognised as not yet supported");
for (const s of [imapPlain, imapTls, imapStart, smtpStart, smtpTls, cal]) s.close();
console.log(`\n${n} checks passed`); process.exit(0);
