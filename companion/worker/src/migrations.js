// The companion's database schema, as an ordered list of steps. The rules (see docs/releasing.md):
//  * Steps only ever go on the END of the list. Never edit or reorder a step that has been released.
//  * A step is additive and safe to run twice (CREATE TABLE IF NOT EXISTS, ALTER TABLE ADD COLUMN). Never drop or rewrite data in a step.
//  * The schema number is the length of the list. It is stored in the database; code older than the data refuses to run (see family.js)
//    instead of guessing, so putting an old Worker back by mistake cannot damage a family's data.
export const MIGRATIONS = [
  // 1: the original tables
  (sql) => sql.exec(`CREATE TABLE IF NOT EXISTS tokens (hash TEXT PRIMARY KEY, expires INTEGER, tries INTEGER DEFAULT 0, used INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS pairings (id TEXT PRIMARY KEY, token TEXT, pub TEXT, enc_pub TEXT, model TEXT, app TEXT, nonce TEXT, match TEXT, created INTEGER, status TEXT, did TEXT);
      CREATE TABLE IF NOT EXISTS devices (did TEXT PRIMARY KEY, pub TEXT, enc_pub TEXT, model TEXT, app TEXT, ctr INTEGER DEFAULT 0, last_seen INTEGER, state TEXT, recovery_hash TEXT, created INTEGER);
      CREATE TABLE IF NOT EXISTS config (did TEXT PRIMARY KEY, version INTEGER, body TEXT);
      CREATE TABLE IF NOT EXISTS hub (did TEXT PRIMARY KEY, child TEXT, rt TEXT);`),
  // 2: the opaque config hash the phone echoes back
  (sql) => { try { sql.exec("ALTER TABLE config ADD COLUMN hash TEXT"); } catch { /* column exists (data made before schema numbers) */ } },
  // 3: location history
  (sql) => sql.exec("CREATE TABLE IF NOT EXISTS fixes (did TEXT, t INTEGER, lat REAL, lon REAL, acc INTEGER, prov TEXT, mode TEXT, PRIMARY KEY (did, t))"),
  // 4: parent passkeys, signed-in browsers (only a hash of each session token) and short-lived sign-in challenges / add-a-device links
  (sql) => sql.exec(`CREATE TABLE IF NOT EXISTS passkeys (id TEXT PRIMARY KEY, alg INTEGER, jwk TEXT, name TEXT, created INTEGER, last_used INTEGER, counter INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, name TEXT, created INTEGER, last_used INTEGER, expires INTEGER);
      CREATE TABLE IF NOT EXISTS challenges (id TEXT PRIMARY KEY, kind TEXT, extra TEXT, expires INTEGER);`),
];

/** Brings the database up to the newest schema. Returns {stored, schema, tooNew}. `stored` is what was there before (0 = a database made before schema numbers existed). */
export function migrate(sql, migrations = MIGRATIONS) {
  sql.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
  const row = sql.exec("SELECT v FROM meta WHERE k='schema'").toArray()[0], stored = row ? Number(row.v) : 0;
  if (stored > migrations.length) return { stored, schema: migrations.length, tooNew: true };
  for (let i = stored; i < migrations.length; i++) { migrations[i](sql); sql.exec("INSERT OR REPLACE INTO meta VALUES ('schema', ?)", String(i + 1)); }
  return { stored, schema: migrations.length, tooNew: false };
}
