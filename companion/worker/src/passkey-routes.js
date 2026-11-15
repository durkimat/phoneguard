// The parent page's passkey and session endpoints (run inside the family's Durable Object; `sql` is its SQLite).
// The long parent key keeps working as the way back in: nothing here can remove it. A passkey sign-in gives a browser a session token that
// the page then sends exactly where it sent the key; only a hash of it is stored here.
import { b64u, unb64u, verifyRegistration, verifyAssertion } from "./passkey.js";
import { sha256, hex } from "./crypto.js";

const CHALLENGE_TTL = 5 * 60_000, INVITE_TTL = 10 * 60_000, SESSION_TTL = 30 * 86_400_000, DAY = 86_400_000;
const MAX_PASSKEYS = 20, MAX_SESSIONS = 50, MAX_CHALLENGES = 100;
const json = (o, status = 200) => Response.json(o, { status });
const rnd = (n) => b64u(crypto.getRandomValues(new Uint8Array(n)));
const hashOf = async (t) => hex(await sha256(String(t)));
const label = (v, fallback) => String(v ?? "").replace(/[\u0000-\u001f]/g, "").trim().slice(0, 60) || fallback;

function newChallenge(sql, kind, extra, ttl, now) {
  sql.exec("DELETE FROM challenges WHERE expires < ?", now);
  if (Number(sql.exec("SELECT COUNT(*) AS n FROM challenges WHERE kind != 'invite'").toArray()[0].n) >= MAX_CHALLENGES) sql.exec("DELETE FROM challenges WHERE id IN (SELECT id FROM challenges WHERE kind != 'invite' ORDER BY expires LIMIT 20)");
  const id = rnd(32); sql.exec("INSERT INTO challenges (id, kind, extra, expires) VALUES (?,?,?,?)", id, kind, extra ?? null, now + ttl); return id;
}
function takeChallenge(sql, id, kind, now) {
  const r = sql.exec("SELECT kind, extra, expires FROM challenges WHERE id=?", String(id)).toArray()[0];
  sql.exec("DELETE FROM challenges WHERE id=?", String(id));   // single use, even when the attempt then fails
  return r && r.kind === kind && r.expires >= now ? r : null;
}
const challengeOf = (clientDataJSON) => { try { return JSON.parse(new TextDecoder().decode(unb64u(clientDataJSON))).challenge; } catch { return null; } };

async function createSession(sql, name, now) {
  const token = "s." + rnd(24), h = await hashOf(token);
  sql.exec("DELETE FROM sessions WHERE expires < ?", now);
  sql.exec("INSERT INTO sessions (hash, name, created, last_used, expires) VALUES (?,?,?,?,?)", h, name, now, now, now + SESSION_TTL);
  sql.exec("DELETE FROM sessions WHERE hash IN (SELECT hash FROM sessions ORDER BY last_used DESC LIMIT -1 OFFSET ?)", MAX_SESSIONS);
  return { token, expires: now + SESSION_TTL };
}

/** True when `bearer` is a live session token. A browser in regular use stays signed in: the 30 days restart when it is used after a day. */
export async function sessionValid(sql, bearer, now) {
  if (typeof bearer !== "string" || !bearer.startsWith("s.") || bearer.length > 80) return false;
  const h = await hashOf(bearer), r = sql.exec("SELECT last_used, expires FROM sessions WHERE hash=?", h).toArray()[0];
  if (!r || r.expires < now) return false;
  if (now - r.last_used > DAY) sql.exec("UPDATE sessions SET last_used=?, expires=? WHERE hash=?", now, now + SESSION_TTL, h);
  return true;
}

function regOptions(rpId, challenge, name, existing) {
  return { publicKey: { challenge, rp: { id: rpId, name: "Phone Guard" }, user: { id: rnd(16), name, displayName: name }, pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }],
    authenticatorSelection: { residentKey: "required", requireResidentKey: true, userVerification: "required" }, attestation: "none", timeout: 120000, excludeCredentials: existing.map((id) => ({ type: "public-key", id })) } };
}
const existingIds = (sql) => sql.exec("SELECT id FROM passkeys").toArray().map((r) => r.id);

function storePasskey(sql, p, name, now) {
  if (Number(sql.exec("SELECT COUNT(*) AS n FROM passkeys").toArray()[0].n) >= MAX_PASSKEYS) throw new Error(`at most ${MAX_PASSKEYS} passkeys; remove one first`);
  sql.exec("INSERT OR REPLACE INTO passkeys (id, alg, jwk, name, created, last_used, counter) VALUES (?,?,?,?,?,NULL,?)", p.id, p.alg, JSON.stringify(p.jwk), name, now, p.counter);
}

/** Returns a Response for a passkey or session path, or null when the path is not one of ours. `bearer` is what the caller sent (never stored). */
export async function passkeyRoute({ sql, path, method, body, origin, bearer, now }) {
  if (!/^\/(passkeys?|session|auth)(\/|$)/.test(path)) return null;
  const rpId = new URL(origin).hostname, bad = (e, s = 400) => json({ error: e.message ?? String(e) }, s);

  if (path === "/auth/session" && method === "POST") return json({ ok: await sessionValid(sql, body.token, now) });

  // ---- sign in with a passkey (no login needed: this is how a browser gets one) ---------------------------------------------------
  if (path === "/passkey/login/options" && method === "POST") return json({ publicKey: { challenge: newChallenge(sql, "login", null, CHALLENGE_TTL, now), rpId, timeout: 120000, userVerification: "required" } });
  if (path === "/passkey/login" && method === "POST") {
    const ch = takeChallenge(sql, challengeOf(body.clientDataJSON), "login", now), pk = ch && sql.exec("SELECT id, alg, jwk, counter FROM passkeys WHERE id=?", String(body.id ?? "")).toArray()[0];
    if (!ch || !pk) return json({ error: "That passkey is not known here, or the request expired. Try again, or use your parent key." }, 401);
    try {
      const counter = await verifyAssertion(body, { challenge: challengeOf(body.clientDataJSON), origin, rpId }, { alg: pk.alg, jwk: JSON.parse(pk.jwk), counter: pk.counter ?? 0 });
      sql.exec("UPDATE passkeys SET last_used=?, counter=? WHERE id=?", now, counter, pk.id);
      return json({ ok: true, ...(await createSession(sql, label(body.label, "Browser"), now)) });
    } catch (e) { return bad(e, 401); }
  }

  // ---- add a device with a one-time link (no login: the link is the proof) --------------------------------------------------------
  if (path === "/passkey/invite/options" && method === "POST") {
    const ih = await hashOf(body.invite ?? ""), inv = sql.exec("SELECT expires FROM challenges WHERE id=? AND kind='invite'", ih).toArray()[0];
    if (!inv || inv.expires < now) return json({ error: "This link has expired or was already used. Ask for a new one." }, 403);
    return json(regOptions(rpId, newChallenge(sql, "register", ih, CHALLENGE_TTL, now), label(body.label, "Parent"), existingIds(sql)));
  }
  if (path === "/passkey/invite/register" && method === "POST") {
    const ih = await hashOf(body.invite ?? ""), ch = takeChallenge(sql, challengeOf(body.clientDataJSON), "register", now), inv = sql.exec("SELECT expires FROM challenges WHERE id=? AND kind='invite'", ih).toArray()[0];
    if (!ch || ch.extra !== ih || !inv || inv.expires < now) return json({ error: "This link has expired or was already used. Ask for a new one." }, 403);
    try {
      const p = await verifyRegistration(body, { challenge: challengeOf(body.clientDataJSON), origin, rpId }), name = label(body.label, "Parent");
      storePasskey(sql, p, name, now); sql.exec("DELETE FROM challenges WHERE id=?", ih);
      return json({ ok: true, id: p.id, ...(await createSession(sql, name, now)) });
    } catch (e) { return bad(e); }
  }

  // ---- from here on the caller is signed in (the front door checked) -------------------------------------------------------------
  if (path === "/passkey/register/options" && method === "POST") {
    if (Number(sql.exec("SELECT COUNT(*) AS n FROM passkeys").toArray()[0].n) >= MAX_PASSKEYS) return bad(new Error(`at most ${MAX_PASSKEYS} passkeys; remove one first`));
    return json(regOptions(rpId, newChallenge(sql, "register", null, CHALLENGE_TTL, now), label(body.label, "Parent"), existingIds(sql)));
  }
  if (path === "/passkey/register" && method === "POST") {
    const ch = takeChallenge(sql, challengeOf(body.clientDataJSON), "register", now);
    if (!ch || ch.extra) return json({ error: "That request expired. Try again." }, 400);
    try { const p = await verifyRegistration(body, { challenge: challengeOf(body.clientDataJSON), origin, rpId }); storePasskey(sql, p, label(body.label, "Parent"), now); return json({ ok: true, id: p.id }); } catch (e) { return bad(e); }
  }
  if (path === "/passkey/invite" && method === "POST") {
    const token = rnd(16); sql.exec("DELETE FROM challenges WHERE kind='invite' AND expires < ?", now);
    sql.exec("INSERT INTO challenges (id, kind, extra, expires) VALUES (?,?,NULL,?)", await hashOf(token), "invite", now + INVITE_TTL);
    return json({ token, expires: now + INVITE_TTL });
  }
  if (path === "/passkeys" && method === "GET") {
    const me = bearer ? await hashOf(bearer) : "";
    return json({ passkeys: sql.exec("SELECT id, name, created, last_used FROM passkeys ORDER BY created").toArray(), sessions: sql.exec("SELECT hash, name, created, last_used, expires FROM sessions WHERE expires >= ? ORDER BY last_used DESC", now).toArray().map((s) => ({ name: s.name, created: s.created, last_used: s.last_used, current: s.hash === me })) });
  }
  if (path === "/passkey/remove" && method === "POST") { sql.exec("DELETE FROM passkeys WHERE id=?", String(body.id ?? "")); return json({ ok: true }); }
  if (path === "/session/signout" && method === "POST") { if (bearer?.startsWith("s.")) sql.exec("DELETE FROM sessions WHERE hash=?", await hashOf(bearer)); return json({ ok: true }); }
  if (path === "/session/signout-all" && method === "POST") { sql.exec("DELETE FROM sessions"); return json({ ok: true }); }
  return null;
}
