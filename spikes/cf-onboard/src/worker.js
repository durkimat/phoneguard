// Spike: the smallest "family hub": a Worker that serves a page and keeps state in a SQLite-backed Durable Object.
import { DurableObject } from "cloudflare:workers";

export class Hub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS checkins (at INTEGER, note TEXT)");
  }
  async fetch(req) {
    const sql = this.ctx.storage.sql;
    if (req.method === "POST") {
      const note = (await req.text()).slice(0, 200);
      sql.exec("INSERT INTO checkins VALUES (?, ?)", Date.now(), note);
    }
    const rows = sql.exec("SELECT at, note FROM checkins ORDER BY at DESC LIMIT 5").toArray();
    const count = sql.exec("SELECT count(*) AS n FROM checkins").one().n;
    return Response.json({ count, latest: rows });
  }
}

export default {
  async fetch(req, env) {
    const hub = env.HUB.get(env.HUB.idFromName("family"));
    return hub.fetch(req);
  },
};
