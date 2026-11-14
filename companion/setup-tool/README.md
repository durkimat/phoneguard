# Phone Guard family setup: how to run it

Status: prototype, tested on the owner's Cloudflare account and the J36 test phone (2026-11-14). No flashing in v1: the phone already runs Phone Guard 0.3.17 or newer.

## The parts
| Part | Where | Job |
|---|---|---|
| Setup tool | parent's PC (`server.mjs`, local page) | creates the family's Worker + database in the parent's own Cloudflare account, makes the codes |
| Worker + parent page | parent's Cloudflare (`../worker`) | go-between, holds settings; serves the parent page at `https://<address>/` |
| Child's phone | Phone Guard app | pairs, checks in about every 15 minutes, applies settings |
| Parent's smartphone | browser | opens the private parent link, approves phones, edits timetable, connects Spotify |

## Parent walk-through (the wizard)
Run `npm run bootstrap` once, then `npm start`. A wizard opens in the browser with a progress bar (7 steps). Optional steps can be skipped and done later from the parent page.
1. **Cloudflare:** *Open Cloudflare* (pre-filled token page), create the key, paste it, *Install my companion*. Or pick *Continue an earlier setup* if the companion is already installed.
2. **Install:** automatic checklist (account, address, install, wait for it to come online, make the code).
3. **Child's phone:** QR + typed address/code (10 minutes, *Make a new code*). On the phone: Phone Guard, *Connect to the family companion*. The wizard notices the waiting phone; type the 4 digits the phone shows, *Approve*, write down the recovery key (shown once), tick the box. The wizard shows when the phone has checked in.
4. **Your phone:** QR for the private parent link (skippable).
5. **Spotify (optional, skippable):** Spotify has no API to create developer apps or add users, so this is a hand-off: link to the dashboard, copy buttons for app name, description and redirect URI, instructions for Web API and user management, paste the Client ID (checked as 32 hex characters), *Save and connect Spotify* opens the parent page (deep link) for the Spotify sign-in, and the wizard ticks when the phone's sealed key is stored.
6. **WhatsApp (optional, skippable):** a guide with a QR code that opens the same steps on the parent's smartphone (WhatsApp cannot be opened onto its Linked devices screen): on the child's phone enter the WhatsApp number and get a code; on the smartphone with that account, Linked devices, Link a device, Link with phone number, type the code. The wizard also saves who the child may message and ticks when the phone reports it is linked.
7. **Finish:** summary of what is done and what was skipped, with how to do each later; timetable is set on the parent page (*Timetable and settings*).
Later: *Add a phone* on the parent page; *Disconnect from the family companion* on the phone to pair again.

## Commands
* `node server.mjs`: the guided local page (use `PORT=8790 NO_OPEN=1` to fix the port and not open a browser).
* `CF_TOKEN=... node cli.mjs`: same job on the command line; `CF_TOKEN=... node cli.mjs delete <worker-name>` removes a Worker.
* `npm run bundle`: rebuild `dist/worker.bundle.js` from `../worker/src` (run `node scripts/build-page.mjs` in `../worker` first if the parent page changed).
* Tests: `npm test` here (engine and server, offline); `npm run test:wizard` (wizard API, needs a LOCAL Worker on a fresh state: `cd ../worker && rm -rf .wrangler/state && npm run dev`); `cd ../worker && npm test` (needs the same dev server on a fresh state).

## Not built yet
Cloudflare "sign in" button (today: key from a link), single download per operating system, forms for editing modes (JSON only), email setup, recovery-key use (re-pair after losing the parent link), flashing from the tool.

## More detail
`../worker/PROTOCOL-DRAFT.md` (pairing, sync, QR and typed code formats, sealed secrets), `../../plan-phone-guard-two-editions.md` sections 4, 14 and 15.
