# Phone Guard family setup: how to run it

Status: prototype, tested on the owner's Cloudflare account and the J36 test phone (2026-11-14). No flashing in v1: the phone already runs Phone Guard 0.3.17 or newer.

## The parts
| Part | Where | Job |
|---|---|---|
| Setup tool | parent's PC (`server.mjs`, local page) | creates the family's Worker + database in the parent's own Cloudflare account, makes the codes |
| Worker + parent page | parent's Cloudflare (`../worker`) | go-between, holds settings; serves the parent page at `https://<address>/` |
| Child's phone | Phone Guard app | pairs, checks in about every 15 minutes, applies settings |
| Parent's smartphone | browser | opens the private parent link, approves phones, edits timetable, connects Spotify |

## Parent walk-through
1. **Run the tool** on the PC: `cd companion-scratch/setup-tool && npm install && npm run bundle && node server.mjs` (opens a page on this computer only).
2. **Get a Cloudflare key**: press *Open Cloudflare* on the page (free account is enough), *Continue to summary*, *Create token*, copy the key, paste it into the page, press *Start setting up*. Takes about 2 minutes.
3. The page shows two codes:
   * **A (child's phone):** QR code, or the typed Address and Code (valid 10 minutes).
   * **B (your phone):** QR code for your private parent link. Anyone with it can manage the phone; keep it private. A copy of the parent key is saved in `~/.phoneguard/`.
4. **On the child's phone:** Phone Guard status page, *Connect to the family companion*, scan the QR or type Address + Code, press Connect (or the keyboard Done key). The phone shows a 4-digit number.
5. **On your phone:** open the private link, find *New phone waiting*, type the 4 digits the child's phone shows, press *Approve*. Write down the recovery key shown (only shown once).
6. Within about 10 seconds the child's phone status page says *Companion: paired*.
7. **Timetable:** on the parent page press *Timetable and settings*, add time blocks per day (use *Copy to Tue to Fri*), *Save*. The phone picks it up at its next check-in (or *Sync now* on the phone).
8. **Add another phone, or the code ran out:** *Add a phone* on the parent page gives a new code and QR.
9. **Spotify (optional):** in *Timetable and settings* open the Spotify card and follow its 4 steps (create your own app at developer.spotify.com/dashboard with the redirect URL shown, tick Web API, add your Spotify emails, paste the Client ID, *Connect Spotify*). The Spotify owner needs Premium. The key is locked so only the child's phone can read it.
10. **Start again with the same phone:** child's phone, enrol screen, *Disconnect from the family companion*.

## Commands
* `node server.mjs`: the guided local page (use `PORT=8790 NO_OPEN=1` to fix the port and not open a browser).
* `CF_TOKEN=... node cli.mjs`: same job on the command line; `CF_TOKEN=... node cli.mjs delete <worker-name>` removes a Worker.
* `npm run bundle`: rebuild `dist/worker.bundle.js` from `../worker/src` (run `node scripts/build-page.mjs` in `../worker` first if the parent page changed).
* Tests: `npm test` here (engine, offline), `cd ../worker && npm test` (needs `npm run dev` running in another terminal on a fresh state: `rm -rf .wrangler/state`).

## Not built yet
Cloudflare "sign in" button (today: key from a link), single download per operating system, forms for editing modes (JSON only), email setup, recovery-key use (re-pair after losing the parent link), flashing from the tool.

## More detail
`../worker/PROTOCOL-DRAFT.md` (pairing, sync, QR and typed code formats, sealed secrets), `../../plan-phone-guard-two-editions.md` sections 4, 14 and 15.
