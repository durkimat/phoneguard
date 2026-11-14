// One Durable Object per family: holds the server keys, the pairings, the devices and their config.
// All state is in SQLite inside the object, so the family's data lives only in the parent's own Cloudflare account.
import { DurableObject } from "cloudflare:workers";
import validateProfile from "./profile-validator.js";
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
      CREATE TABLE IF NOT EXISTS config (did TEXT PRIMARY KEY, version INTEGER, body TEXT)`);
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
      this.sql.exec("INSERT INTO config VALUES (?, 1, ?)", did, JSON.stringify({ version: 1, base: "day", modes: { day: { name: "Day", allowAll: true } } }));
      this.sql.exec("UPDATE pairings SET status='confirmed', did=? WHERE id=?", did, id);
      this.sql.exec("UPDATE tokens SET used=1 WHERE hash=?", r.token);
      return json({ did, recoveryKey }); // shown once to the parent; only its hash is kept
    }

    // phone: signed sync {v, did, ctr, n, p, s}: counter must increase, signature must verify
    if (path === "/sync" && m === "POST") {
      const { v, did, ctr, n, p, s } = body;
      const d = this.sql.exec("SELECT * FROM devices WHERE did=?", did ?? "").toArray()[0];
      if (!d || v !== V || !Number.isInteger(ctr) || typeof n !== "string" || typeof p !== "string") return json({ error: "rejected" }, 403);
      if (!(await verifyDer(await importPub(d.pub), s, `${v}|${did}|${ctr}|${n}|${p}`))) return json({ error: "bad signature" }, 403);
      if (ctr <= d.ctr) return json({ error: "replay" }, 409);
      let state = {}; try { state = JSON.parse(p); } catch { return json({ error: "bad payload" }, 400); }
      this.sql.exec("UPDATE devices SET ctr=?, last_seen=?, state=? WHERE did=?", ctr, Date.now(), JSON.stringify(state).slice(0, 4000), did);
      const cfg = this.sql.exec("SELECT version, body FROM config WHERE did=?", did).toArray()[0];
      if (Number.isInteger(state.cfgVersion) && state.cfgVersion >= cfg.version) return json(await this.reply(did, n, { configVersion: cfg.version, unchanged: true })); // phone already has it: no big payload
      return json(await this.reply(did, n, { configVersion: cfg.version, config: JSON.parse(cfg.body) }));
    }

    // parent: devices and config
    if (path === "/devices" && m === "GET") {
      return json(this.sql.exec("SELECT d.did, d.model, d.app, d.last_seen, d.state, c.version FROM devices d JOIN config c USING (did)").toArray().map((r) => ({ ...r, state: JSON.parse(r.state) })));
    }
    if (path.startsWith("/devices/") && path.endsWith("/enckey") && m === "GET") {
      const r = this.sql.exec("SELECT enc_pub FROM devices WHERE did=?", path.split("/")[2]).toArray()[0];
      return r ? json({ encKey: r.enc_pub }) : json({ error: "unknown device" }, 404);
    }
    if (path.startsWith("/devices/") && path.endsWith("/config") && m === "GET") {
      const r = this.sql.exec("SELECT version, body FROM config WHERE did=?", path.split("/")[2]).toArray()[0];
      return r ? json({ version: r.version, config: JSON.parse(r.body) }) : json({ error: "unknown device" }, 404);
    }
    if (path.startsWith("/devices/") && path.endsWith("/config") && m === "POST") {
      const did = path.split("/")[2];
      if (!this.sql.exec("SELECT 1 FROM devices WHERE did=?", did).toArray()[0]) return json({ error: "unknown device" }, 404);
      const row = this.sql.exec("SELECT version FROM config WHERE did=?", did).toArray()[0];
      const profile = { ...body, version: row.version + 1 }; // the server owns the version number
      const text = JSON.stringify(profile);
      if (text.length > 60000) return json({ error: "profile too large" }, 413);
      if (!validateProfile(profile)) return json({ error: "invalid profile", problems: (validateProfile.errors ?? []).slice(0, 8).map((e) => `${e.instancePath || "/"} ${e.message}`) }, 400);
      this.sql.exec("UPDATE config SET version=?, body=? WHERE did=?", row.version + 1, text, did);
      return json({ version: row.version + 1 });
    }
    return json({ error: "not found" }, 404);
  }
}
