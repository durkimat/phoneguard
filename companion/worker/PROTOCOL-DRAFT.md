# Phone Guard companion protocol, draft v1 (2026-11-14)

Backend-neutral wire format for pairing and sync. Implemented by `src/` (Cloudflare Worker + one SQLite Durable Object per family) and checked by `test/protocol.test.mjs` (23 checks, run both locally and against a real Cloudflare account). The Home Assistant backend would implement the same messages later (SPEC 4d/4f carry the original design).

## Keys and encodings (what Android produces and accepts)
* Phone key: ECDSA P-256 in the Android Keystore; public key sent as X.509 SubjectPublicKeyInfo, base64.
* Signatures: DER-encoded ECDSA over SHA-256, base64 (Android `SHA256withECDSA`). The Worker converts DER to/from WebCrypto's raw r||s.
* Server key: ECDSA P-256, generated inside the family's Durable Object on first use; public key published at `GET /api/info` and carried in the enrolment payload so the phone can pin it.

## Pairing (physical possession + two-sided confirmation)
1. **Parent** `POST /api/enrol/new` (bearer): returns `{token (10 chars from an unambiguous alphabet), expires (10 min), serverKey}`. The token or a QR containing {v, workerUrl, token, serverKey} is shown to the phone.
2. **Phone** `POST /api/pair {token, pub, model, app, nonce}`: the token is single-use and allows 3 tries; the server stores only its SHA-256. Returns `{pairingId, match}`, where `match` is a 4-digit number derived from SHA-256(token ":" pub).
3. Phone shows `match`; the parent page lists pending pairings (`GET /api/pairings`) with the same number and confirms only if they are equal: `POST /api/pairings/{id}/confirm {match}`. The server returns `{did, recoveryKey}` **once** to the parent; only a hash of the recovery key is kept.
4. **Phone** polls `GET /api/pair/{id}`; when confirmed it receives a **signed reply** `{v, did, n, t, p, s}` with `n` = the nonce it sent, signed by the server over `v|did|n|t|p`. `p` (JSON) carries the device id, the recovery key's hash, and the first config. The phone verifies with the pinned server key.

## Sync (signed both ways)
`POST /api/sync {v, did, ctr, n, p, s}`: `s` = signature over `v|did|ctr|n|p`. The server rejects unknown devices, bad signatures and any `ctr` not greater than the last seen (replay). The reply is a signed envelope as above (nonce echoed), whose `p` carries `{configVersion, config}`. The phone's counter is persisted before sending and may skip ahead.

## Parent API (bearer token for now; passkeys / Cloudflare Access later)
`GET /api/pairings`, `POST /api/pairings/{id}/confirm`, `GET /api/devices` (last seen + reported state), `POST /api/devices/{did}/config` (bumps the version).

## Not yet in this draft
Override-code seed, schedule window and modes schema (the profile document), events (`event` messages), location, update manifest, sealed credentials (connector secrets encrypted to the phone's key), recovery/re-pair with the recovery key, rate limits beyond the token tries, multi-parent login, QR rendering, the parent web page.

## Findings from running it
* Works unchanged on `wrangler dev --local` and on real Cloudflare; ~75-95 ms per call; a new `workers.dev` hostname under an existing account subdomain answered immediately.
* The first-ever `workers.dev` subdomain for an account needed ~80 s for TLS (see plan 13).

## Profile document (added 2026-11-14)
`schema/profile.schema.json` (JSON Schema 2020-12) formalises the config JSON the phone already parses in `core/Config.kt`, so the existing Home Assistant hub output validates unchanged. The Worker validates every profile the parent saves (a precompiled standalone validator, `src/profile-validator.js`, because Workers forbid runtime code generation; rebuild with `npm run build`) and rejects bad ones with readable `problems`; **the server owns the `version` number** (it sets it on every accepted change). Unknown extra keys are allowed so a newer companion never breaks an older phone. Examples: `schema/examples/`. Checks: `test/schema.test.mjs` (9) + the config part of `test/protocol.test.mjs`.
**Secrets are never in the profile**: the legacy `spotifyRefresh` field (plain text in today's HA hub) is replaced by `secrets["spotify.refresh"]`, a sealed object `{alg: "ECDH-P256+HKDF-SHA256+AES-256-GCM", epk, salt, iv, ct}` encrypted in the parent's browser to the phone's public key (to be specified with test vectors next).
`npm test` runs: schema (9), vectors (10), protocol (25).

## Sealed credentials (added 2026-11-14)
Connector secrets (Spotify refresh token, email password, calendar URLs with keys...) are encrypted **in the parent's browser** for the phone, so Cloudflare, the Worker and the Durable Object only ever hold ciphertext.
* **Phone encryption key:** at pairing the phone may send `encPub` (an X.509 SPKI P-256 key meant for key agreement, kept in the Android Keystore with PURPOSE_AGREE_KEY, API 31+); if omitted it defaults to the signing key `pub`. A separate key is preferred. The parent page fetches it with `GET /api/devices/{did}/enckey`.
* **Algorithm** `ECDH-P256+HKDF-SHA256+AES-256-GCM`: shared = ECDH(ephemeral private, phone encryption public), 32 raw bytes; key = HKDF-SHA256(ikm = shared, salt = 16 random bytes, info = UTF-8 `phoneguard-sealed-v1|<did>|<name>`) to 32 bytes; ct = AES-256-GCM(key, iv = 12 random bytes, aad = UTF-8 `<did>|<name>`), ciphertext followed by the 16-byte tag; fields base64: `{alg, epk (ephemeral SPKI), salt, iv, ct}`.
* **Binding:** the device id and the slot name (`spotify.refresh`, `email.password`, ...) are inside both the key derivation and the AAD, so a sealed value cannot be replayed onto another device or moved to another slot; any tampering fails the GCM tag.
* **Where it lives:** `profile.secrets[<slot>]` (validated by the schema). `src/seal.js` seals in a browser/Worker/Node; `test/helpers/open-sealed.mjs` is the phone side written with node:crypto. `vectors.json` has a deterministic example (fixed keys, salt, iv) for the Kotlin implementation.
* **Open point for the phone side:** confirm the Keystore ECDH key can be created with `PURPOSE_AGREE_KEY` on the J36 (Android 14), and that JCA `KeyAgreement("ECDH")` + HKDF (hand-written HMAC-SHA256 HKDF) + `AES/GCM/NoPadding` reproduce the vector.
`npm test` now runs schema (9), vectors (13) and protocol (32) checks.

## Changes after the phone-side review (2026-11-14)
* **Weekdays are ISO 1 = Monday ... 7 = Sunday** in `pattern.days` (matches Engine.kt `DayOfWeek.value`); the schema rejects 0.
* **Enums tightened** from the phone's code: `wa` off|open|30|60, `waApp` auto|simple|full, `location` off|network|gps, `notes` show|off, `radio` off|presets|any.
* **`x-lockedByParent`** per top-level profile field (true: modes, protectExtra, unprotect, restrictions, location, notesHide, keepRunning, pinned, radioStations, whatsapp, appCommands; false: look). Child-local, never in the profile: radio on/off within the allowed intent (wifi|bt|data), ringtone, volume.
* **Sync sends the profile only when needed:** the phone reports `cfgVersion` in its sync payload; if it is current the reply carries `{configVersion, unchanged: true}` and no profile.
Checks: schema 11, vectors 13, protocol 34.

## Enrolment payload: QR and typed code (v1, decided 2026-11-14)

Shown by the setup tool (or the parent page) after `POST /api/enrol/new`. The phone needs three things: where the Worker is, the single-use token, and a way to pin the server key.

**QR text (UTF-8, one line):**
`phoneguard://enrol?v=1&h=<host>&t=<token>&f=<fp>`
* `h` Worker host only, no scheme or path (e.g. `phoneguard-x7k2.jane.workers.dev`). The phone always uses `https://<h>/api/...`; it refuses anything else.
* `t` the 10-char token, unambiguous alphabet, no dashes in the QR.
* `f` base64url of the first 16 bytes of SHA-256(server public key SPKI DER). The phone calls `GET /api/info`, hashes the returned `serverKey` and refuses to pair if it differs. It then pins the full key.
* Unknown `v` or missing fields: the phone shows "Unsupported or damaged code" and nothing is sent. Roughly 150 characters, so a normal QR (version 8-9) scans easily.

**Typed fallback (two boxes, no camera):**
* Address: the part before `.workers.dev`, e.g. `phoneguard-x7k2.jane` (the phone appends `.workers.dev`; a full host with a dot-suffix is accepted too).
* Code: the token shown in groups, `ABCDE-FGHJK`; the phone strips non-alphanumerics and upper-cases (the Worker does the same).
* No fingerprint is typed, so the key is pinned on first contact (TLS to the host only). The two-sided 4-digit match, which the parent confirms on their own device, is what protects this route.

**Common rules:** token is single-use, 10 minutes, 3 tries (server enforced). After a good `/pair` the phone stores `{host, serverKey}` and discards the token. Test vectors for the QR string to be added to `test/vectors.json`.


## Hub model and parent API (parity with the Home Assistant integration)

The Worker holds, per phone, the **child** (what the parent sets: modes, weekly timetable, holiday, override, per-mode options, app lists, restrictions, WhatsApp, radio, look, weather place, jobs) and the **runtime** (what the phone reported: apps, state, usage, power diary, power-saver log, position, labels; and short-lived state: boost, release flag). `src/hub/schedule.js` turns a child into the phone's config; it is a port of the Home Assistant `schedule.py` and is checked against golden vectors (`test/golden-schedule.json`, made by the Python code). A new config version (a counter) is stored only when the built config changes; the 14-day window moves on every day, so this also runs at each check-in.

**Phone sync payload** (inside the signed `p`): either the old lean state, or the full report `{"kind":"sync","cfgh":"<last applied>","state":{...},"apps":[{pkg,label,hidden}],"power":[...],"reaped":[...],"appResults":[[id,"done"|"failed",detail,time]],"loc":[[time,lat,lon,acc,provider,mode]],"usage":{day:{pkg:secs}},"labels":{pkg:label}}` (identical to the Home Assistant payload).

**Reply payload**: `{"configVersion":N,"cfgh":"<12 hex>","boost":{mode,until}|null,"cmds":[{"type":"release"}]|[], "unchanged":true | "config":{...},"cfg":{...}}`. `cfgh` is opaque; the phone echoes it. A phone is "unchanged" when its `cfgh` equals the Worker's (or, for the old client, `state.cfgVersion >= configVersion`).

**Parent endpoints** (Bearer parent key): `GET /api/devices`, `GET /api/devices/{did}/view`, `GET /api/devices/{did}/config`, `GET /api/devices/{did}/enckey`, and `POST /api/devices/{did}/` + `child {patch}`, `override {mode|"auto"}`, `boost {mode,minutes,extend}`, `boost/cancel`, `holiday {from,until}`, `apps/mode {pkg,mode,allowed}`, `apps/mode-all {mode,allowed}`, `apps/approve {pkg,modes}`, `apps/dismiss {pkg}`, `apps/command {kind:"remove"|"update",pkg,name,...}`, `apps/command/cancel {id}`, `update {override?}`, `copy {from,parts}`, `rename {name}`, `secret {name,sealed|null}`, `release`, `forget`; family level `GET/POST /api/options` (`spotify_client_id`, `updates_enabled`) and `POST /api/check-updates` (newest GitHub release). Settings edits are validated exactly as the Home Assistant hub validates them and a refused change is never kept.
