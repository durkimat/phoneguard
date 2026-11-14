// One Durable Object per family: holds the server keys, the pairings, the devices and their config.
// All state is in SQLite inside the object, so the family's data lives only in the parent's own Cloudflare account.
import { DurableObject } from "cloudflare:workers";
import * as S from "./hub/schedule.js";
import * as H from "./hub/hub.js";
import { parseRelease, parseSha256Text } from "./hub/store.js";
import { b64, unb64, importPub, importPriv, newServerKeys, signDer, verifyDer, sha256, hex, matchNumber, randomCode } from "./crypto.js";

const V = 1;
const TOKEN_TTL_MS = 10 * 60 * 1000;
const MAX_PAIR_TRIES = 3;
const json = (o, status = 200) => Response.json(o, { status });

export class Family extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
      CREATE TABLE IF NOT EXISTS tokens (hash TEXT PRIMARY KEY, expires INTEGER, tries INTEGER DEFAULT 0, used INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS pairings (id TEXT PRIMARY KEY, token TEXT, pub TEXT, enc_pub TEXT, model TEXT, app TEXT, nonce TEXT, match TEXT, created INTEGER, status TEXT, did TEXT);
      CREATE TABLE IF NOT EXISTS devices (did TEXT PRIMARY KEY, pub TEXT, enc_pub TEXT, model TEXT, app TEXT, ctr INTEGER DEFAULT 0, last_seen INTEGER, state TEXT, recovery_hash TEXT, created INTEGER);
      CREATE TABLE IF NOT EXISTS config (did TEXT PRIMARY KEY, version INTEGER, body TEXT, hash TEXT);
      CREATE TABLE IF NOT EXISTS hub (did TEXT PRIMARY KEY, child TEXT, rt TEXT);
      CREATE TABLE IF NOT EXISTS fixes (did TEXT, t INTEGER, lat REAL, lon REAL, acc INTEGER, prov TEXT, mode TEXT, PRIMARY KEY (did, t))`);
    try { this.sql.exec("ALTER TABLE config ADD COLUMN hash TEXT"); } catch { /* column exists */ }
  }

  // ---- the guarded phone's settings (child), what it reported (rt) and the family options ----------------------------------------

  options() { const r = this.sql.exec("SELECT v FROM meta WHERE k='options'").toArray()[0]; return r ? JSON.parse(r.v) : {}; }
  setOptions(o) { this.sql.exec("INSERT OR REPLACE INTO meta VALUES ('options', ?)", JSON.stringify(o)); }
  latest() { const r = this.sql.exec("SELECT v FROM meta WHERE k='latest'").toArray()[0]; return r ? JSON.parse(r.v) : null; }
  /** Days of location history to keep (0 switches the history off; the newest position is still shown). */
  historyDays() { const d = this.options().history_days; return Number.isInteger(d) ? d : 7; }
  storeFixes(did, loc, now) {
    const days = this.historyDays(); if (days <= 0) return;
    for (const f of H.validFixes(loc)) if (f[0] >= now - days * 86400_000) this.sql.exec("INSERT OR IGNORE INTO fixes VALUES (?,?,?,?,?,?,?)", did, ...f);
    this.sql.exec("DELETE FROM fixes WHERE did=? AND t < ?", did, now - days * 86400_000);
  }
  load(did) { const r = this.sql.exec("SELECT child, rt FROM hub WHERE did=?", did).toArray()[0]; return r ? { ch: JSON.parse(r.child), rt: JSON.parse(r.rt) } : null; }
  save(did, h) { this.sql.exec("INSERT OR REPLACE INTO hub VALUES (?,?,?)", did, JSON.stringify(h.ch), JSON.stringify(h.rt)); }

  /** Builds the phone's config from the settings; a new version number is stored only when something changed (the window moves on each day, so this also runs at every check-in). */
  refreshConfig(did, h, now = Date.now()) {
    const cfg = S.buildConfig(h.ch, now, this.options()), hash = S.cfgHash(cfg);
    const row = this.sql.exec("SELECT version, body, hash FROM config WHERE did=?", did).toArray()[0];
    if (row && row.hash === hash) return { version: row.version, hash, cfg: JSON.parse(row.body) };
    const version = (row?.version ?? 0) + 1, body = { ...cfg, version };
    this.sql.exec("INSERT OR REPLACE INTO config (did, version, body, hash) VALUES (?,?,?,?)", did, version, JSON.stringify(body), hash);
    return { version, hash, cfg: body };
  }

  async keys() {
    let r = this.sql.exec("SELECT v FROM meta WHERE k='keys'").toArray()[0];
    if (!r) { const k = await newServerKeys(); this.sql.exec("INSERT INTO meta VALUES ('keys', ?)", JSON.stringify(k)); return { ...k, privKey: await importPriv(k.priv) }; }
    const k = JSON.parse(r.v); return { ...k, privKey: await importPriv(k.priv) };
  }

  /** Signed reply envelope: {v, did, n, t, p, s}; s signs `v|did|n|t|p` (nonce echoed so replays and forged servers fail). */
  async reply(did, nonce, payload) {
    const { privKey } = await this.keys();
    const t = Date.now(), p = JSON.stringify(payload);
    return { v: V, did, n: nonce, t, p, s: await signDer(privKey, `${V}|${did}|${nonce}|${t}|${p}`) };
  }

  async fetch(req) {
    const url = new URL(req.url), path = url.pathname, m = req.method;
    const body = m === "POST" ? await req.json().catch(() => ({})) : {};
    if (path === "/info") return json({ v: V, serverKey: (await this.keys()).pub });

    // parent: create a one-time enrolment token (the QR / typed code)
    if (path === "/enrol/new" && m === "POST") {
      const token = randomCode(10), expires = Date.now() + TOKEN_TTL_MS;
      this.sql.exec("INSERT INTO tokens (hash, expires) VALUES (?, ?)", hex(await sha256(token)), expires);
      return json({ token, expires, serverKey: (await this.keys()).pub });
    }

    // phone: asks to pair. The token is single-use and limited to a few tries.
    if (path === "/pair" && m === "POST") {
      const { token, pub, encPub = body.pub, model = "", app = "", nonce } = body;
      if (typeof token !== "string" || typeof pub !== "string" || typeof nonce !== "string") return json({ error: "bad request" }, 400);
      const th = hex(await sha256(token.toUpperCase().replace(/[^A-Z0-9]/g, "")));
      const t = this.sql.exec("SELECT expires, tries, used FROM tokens WHERE hash=?", th).toArray()[0];
      if (!t || t.used || t.expires < Date.now()) return json({ error: "token invalid or expired" }, 403);
      if (t.tries >= MAX_PAIR_TRIES) return json({ error: "too many tries" }, 429);
      this.sql.exec("UPDATE tokens SET tries = tries + 1 WHERE hash=?", th);
      try { await importPub(pub); await crypto.subtle.importKey("spki", unb64(encPub), { name: "ECDH", namedCurve: "P-256" }, false, []); } catch { return json({ error: "bad public key" }, 400); }
      const id = randomCode(12), match = await matchNumber(token.toUpperCase().replace(/[^A-Z0-9]/g, ""), pub);
      this.sql.exec("INSERT INTO pairings VALUES (?,?,?,?,?,?,?,?,?,?,NULL)", id, th, pub, encPub, model.slice(0, 60), app.slice(0, 20), nonce.slice(0, 64), match, Date.now(), "pending");
      return json({ pairingId: id, match });
    }

    // phone: polls for the parent's confirmation
    if (path.startsWith("/pair/") && m === "GET") {
      const id = path.slice(6);
      const r = this.sql.exec("SELECT status, did, nonce FROM pairings WHERE id=?", id).toArray()[0];
      if (!r) return json({ error: "unknown" }, 404);
      if (r.status !== "confirmed") return json({ status: r.status });
      const dev = this.sql.exec("SELECT recovery_hash FROM devices WHERE did=?", r.did).toArray()[0];
      const cfg = this.sql.exec("SELECT version, body FROM config WHERE did=?", r.did).toArray()[0];
      return json({ status: "confirmed", reply: await this.reply(r.did, r.nonce, { did: r.did, recoveryHash: dev.recovery_hash, configVersion: cfg.version, config: JSON.parse(cfg.body) }) });
    }

    // parent: list pending pairings and confirm one (the 4-digit numbers must match on both screens)
    if (path === "/pairings" && m === "GET") {
      return json(this.sql.exec("SELECT id, model, app, match, created FROM pairings WHERE status='pending' ORDER BY created DESC").toArray());
    }
    if (path.startsWith("/pairings/") && path.endsWith("/confirm") && m === "POST") {
      const id = path.split("/")[2];
      const r = this.sql.exec("SELECT * FROM pairings WHERE id=?", id).toArray()[0];
      if (!r || r.status !== "pending") return json({ error: "no such pending pairing" }, 404);
      if (body.match !== r.match) return json({ error: "numbers do not match" }, 409);
      const did = "d" + randomCode(10).toLowerCase(), recoveryKey = randomCode(24).match(/.{4}/g).join("-");
      this.sql.exec("INSERT INTO devices (did, pub, enc_pub, model, app, ctr, last_seen, state, recovery_hash, created) VALUES (?,?,?,?,?,0,NULL,'{}',?,?)", did, r.pub, r.enc_pub, r.model, r.app, hex(await sha256(recoveryKey)), Date.now());
      const child = S.defaultChild((r.model || "Phone").slice(0, 60)); child.person = child.name;
      const h = { ch: child, rt: H.newRuntime() }; this.save(did, h); this.refreshConfig(did, h);
      this.sql.exec("UPDATE pairings SET status='confirmed', did=? WHERE id=?", did, id);
      this.sql.exec("UPDATE tokens SET used=1 WHERE hash=?", r.token);
      return json({ did, recoveryKey }); // shown once to the parent; only its hash is kept
    }

    // phone: signed sync {v, did, ctr, n, p, s}: counter must increase, signature must verify.
    // The payload is either the older lean state ({cfgVersion, mode, battery, ...}) or the full Home Assistant style report ({state, apps, usage, power, reaped, appResults, loc, labels, cfgh}).
    if (path === "/sync" && m === "POST") {
      const { v, did, ctr, n, p, s } = body;
      const d = this.sql.exec("SELECT * FROM devices WHERE did=?", did ?? "").toArray()[0];
      if (!d || v !== V || !Number.isInteger(ctr) || typeof n !== "string" || typeof p !== "string") return json({ error: "rejected" }, 403);
      if (!(await verifyDer(await importPub(d.pub), s, `${v}|${did}|${ctr}|${n}|${p}`))) return json({ error: "bad signature" }, 403);
      if (ctr <= d.ctr) return json({ error: "replay" }, 409);
      let payload = {}; try { payload = JSON.parse(p); } catch { return json({ error: "bad payload" }, 400); }
      if (!payload || typeof payload !== "object") return json({ error: "bad payload" }, 400);
      const now = Date.now(), full = payload.state && typeof payload.state === "object", state = full ? payload.state : payload;
      this.sql.exec("UPDATE devices SET ctr=?, last_seen=?, state=? WHERE did=?", ctr, now, JSON.stringify(state).slice(0, 4000), did);
      const h = this.load(did) ?? { ch: S.defaultChild(d.model || "Phone"), rt: H.newRuntime() };
      H.mergeSync(h.ch, h.rt, full ? payload : { state: payload }, now);
      if (full) this.storeFixes(did, payload.loc, now);
      const row = this.refreshConfig(did, h, now), boost = H.activeBoost(h.rt, now), cmds = [];
      if (h.rt.release) { cmds.push({ type: "release" }); }
      const haveHash = typeof payload.cfgh === "string" ? payload.cfgh : null, haveVersion = Number.isInteger(state.cfgVersion) ? state.cfgVersion : null;
      const unchanged = haveHash != null ? haveHash === row.hash : haveVersion != null && haveVersion >= row.version;
      h.rt.cfgh = row.hash; this.save(did, h);
      const out = { configVersion: row.version, cfgh: row.hash, boost, cmds, ...(unchanged ? { unchanged: true } : { config: row.cfg, cfg: row.cfg }) };
      if (cmds.length) { for (const t of ["devices", "config", "hub", "fixes"]) this.sql.exec(`DELETE FROM ${t} WHERE did=?`, did); }   // released: the phone is no longer ours
      return json(await this.reply(did, n, out));
    }

    // parent: family options (the Spotify app's public Client ID, switches)
    if (path === "/options" && m === "GET") return json({ ...this.options(), latest: this.latest() });
    if (path === "/options" && m === "POST") {
      const o = this.options();
      for (const [k, val] of Object.entries(body)) {
        if (k === "spotify_client_id") { const c = String(val ?? "").trim().toLowerCase(); if (c && !/^[0-9a-f]{32}$/.test(c)) return json({ error: "a Spotify Client ID is 32 letters and digits (0 to 9 and a to f)" }, 400); c ? (o[k] = c) : delete o[k]; }
        else if (k === "history_days") { if (![0, 1, 3, 7, 14].includes(val)) return json({ error: "history_days must be 0, 1, 3, 7 or 14" }, 400); o[k] = val; if (val === 0) this.sql.exec("DELETE FROM fixes"); else this.sql.exec("DELETE FROM fixes WHERE t < ?", Date.now() - val * 86400_000); }
        else if (k === "updates_enabled") { if (typeof val !== "boolean") return json({ error: "updates_enabled must be true or false" }, 400); val ? (o[k] = true) : delete o[k]; }
        else return json({ error: `unknown option '${k}'` }, 400);
      }
      this.setOptions(o); return json(o);
    }

    // parent: look for the newest Phone Guard release on GitHub (the only thing the companion fetches from the web)
    if (path === "/check-updates" && m === "POST") {
      try {
        const r = await fetch("https://api.github.com/repos/durkimat/phoneguard/releases/latest", { headers: { accept: "application/vnd.github+json", "user-agent": "phoneguard-companion" } });
        if (!r.ok) return json({ error: `GitHub answered ${r.status}` }, 502);
        const rel = parseRelease(await r.json()); if (!rel) return json({ error: "no release with an .apk was found" }, 404);
        if (rel.sha256_url?.startsWith("https://")) { const t = await fetch(rel.sha256_url, { headers: { "user-agent": "phoneguard-companion" } }); rel.sha256 = t.ok ? parseSha256Text(await t.text()) : ""; }
        this.sql.exec("INSERT OR REPLACE INTO meta VALUES ('latest', ?)", JSON.stringify(rel)); return json(rel);
      } catch (e) { return json({ error: "could not reach GitHub: " + e.message }, 502); }
    }

    // parent: devices
    if (path === "/devices" && m === "GET") {
      const now = Date.now();
      return json(this.sql.exec("SELECT d.did, d.model, d.app, d.last_seen, d.state, c.version FROM devices d JOIN config c USING (did)").toArray().map((r) => {
        const h = this.load(r.did); const b = h ? H.activeBoost(h.rt, now) : null;
        return { ...r, state: JSON.parse(r.state), name: h?.ch.name ?? r.model, mode_now: h ? S.resolveMode(this.refreshConfig(r.did, h, now).cfg, b, now) : null, boost: b, override: h?.ch.override ?? null };
      }));
    }
    const dm = path.match(/^\/devices\/([a-z0-9]+)(?:\/(.+))?$/);
    if (dm) {
      const did = dm[1], sub = dm[2] ?? "";
      const dev = this.sql.exec("SELECT enc_pub FROM devices WHERE did=?", did).toArray()[0];
      if (!dev) return json({ error: "unknown device" }, 404);
      const now = Date.now(), h = this.load(did), opts = this.options();
      if (sub === "enckey" && m === "GET") return json({ encKey: dev.enc_pub });
      if (sub === "config" && m === "GET") { const r = this.refreshConfig(did, h, now); return json({ version: r.version, config: r.cfg }); }
      if (sub === "track" && m === "GET") {
        const from = Number(url.searchParams.get("from")) || 0, to = Number(url.searchParams.get("to")) || Date.now() + 1, max = Math.min(Number(url.searchParams.get("max")) || 4000, 8000);
        const rows = this.sql.exec("SELECT t, lat, lon, acc, prov, mode FROM fixes WHERE did=? AND t>=? AND t<=? ORDER BY t", did, from, to).toArray().map((r) => [r.t, r.lat, r.lon, r.acc, r.prov, r.mode]);
        const step = Math.max(1, Math.ceil(rows.length / max)), points = rows.filter((_, i) => i % step === 0 || i === rows.length - 1);
        const span = this.sql.exec("SELECT MIN(t) a, MAX(t) b, COUNT(*) n FROM fixes WHERE did=?", did).toArray()[0];
        return json({ points, thinned: step > 1, retention_days: this.historyDays(), stored: span.n, first: span.a, last: span.b });
      }
      if (sub === "view" && m === "GET") { const v = H.view(h.ch, h.rt, opts, this.latest(), now); this.save(did, h); return json(v); }
      if (m !== "POST") return json({ error: "not found" }, 404);
      const done = (extra = {}) => { this.save(did, h); const r = this.refreshConfig(did, h, now); return json({ ok: true, version: r.version, ...extra }); };
      try {
        if (sub === "child") { H.updateChild(h.ch, body.patch ?? {}, now, opts); return done(); }
        if (sub === "override") { H.setOverride(h.ch, body.mode, now, opts); return done(); }
        if (sub === "boost") { const b = H.boost(h.rt, String(body.mode), Number(body.minutes), now, body.extend !== false); if (!(b.mode in h.ch.modes)) { H.cancelBoost(h.rt); return json({ error: `mode '${body.mode}' does not exist` }, 400); } return done({ boost: b }); }
        if (sub === "boost/cancel") { H.cancelBoost(h.rt); return done(); }
        if (sub === "holiday") { H.setHoliday(h.ch, body.from, body.until); H.updateChild(h.ch, {}, now, opts); return done(); }
        if (sub === "apps/mode") { if (!(body.mode in h.ch.modes)) throw new Error("no such mode"); H.setAppMode(h.ch, String(body.pkg), body.mode, Boolean(body.allowed)); return done(); }
        if (sub === "apps/mode-all") { if (!(body.mode in h.ch.modes)) throw new Error("no such mode"); H.setModeAll(h.ch, h.rt, body.mode, Boolean(body.allowed)); return done(); }
        if (sub === "apps/approve") { H.approveApp(h.ch, String(body.pkg), (body.modes ?? []).filter((x) => x in h.ch.modes)); return done(); }
        if (sub === "apps/dismiss") { H.dismissApp(h.ch, String(body.pkg)); return done(); }
        if (sub === "apps/command") { const c = H.queueApp(h.ch, body.kind, String(body.pkg), String(body.name ?? ""), now, body); return done({ command: c }); }
        if (sub === "apps/command/cancel") { H.cancelApp(h.ch, String(body.id)); return done(); }
        if (sub === "update") { const c = H.queueUpdate(h.ch, h.rt, this.latest(), opts, now, body.override ?? null); return done({ command: c }); }
        if (sub === "copy") { const src = this.load(String(body.from)); if (!src || body.from === did) throw new Error("choose a different phone to copy from"); const parts = H.copySettings(src.ch, h.ch, body.parts ?? [], now, opts); return done({ copied: parts }); }
        if (sub === "rename") { const name = String(body.name ?? "").trim().slice(0, 60); if (!name) throw new Error("a name is needed"); H.updateChild(h.ch, { name, person: body.person ?? h.ch.person }, now, opts); return done(); }
        if (sub === "secret") { const name = String(body.name ?? ""); if (!/^[a-z][a-z0-9_.]{0,39}$/.test(name)) throw new Error("bad secret name"); if (body.sealed == null) { delete h.ch.secrets?.[name]; } else { const x = body.sealed; if (x?.alg !== "ECDH-P256+HKDF-SHA256+AES-256-GCM" || typeof x.epk !== "string" || typeof x.ct !== "string") throw new Error("not a sealed secret"); (h.ch.secrets ??= {})[name] = x; } return done(); }
        if (sub === "track/clear") { this.sql.exec("DELETE FROM fixes WHERE did=?", did); return json({ ok: true }); }
        if (sub === "release") { h.rt.release = true; this.save(did, h); return json({ ok: true }); }
        if (sub === "forget") { for (const t of ["devices", "config", "hub", "fixes"]) this.sql.exec(`DELETE FROM ${t} WHERE did=?`, did); return json({ ok: true }); }
      } catch (e) { return json({ error: e.message }, 400); }
    }
    return json({ error: "not found" }, 404);
  }
}
