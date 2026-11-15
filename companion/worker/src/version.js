// The companion's version, shown on the parent page and at /api/version, and compared with the newest release to say "update available".
// Bump COMPANION_VERSION on every release that changes the Worker (docs/releasing.md). The schema number comes from migrations.js.
import { MIGRATIONS } from "./migrations.js";
export const COMPANION_VERSION = "0.7.0";
export const SCHEMA = MIGRATIONS.length;
export const PROTOCOL = 1;   // the phone protocol: unchanged means every older phone app still works
