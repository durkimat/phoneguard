# Family setup: your own Phone Guard companion

This guide takes you from nothing to a child's phone managed from your own phone, with no Home Assistant and no service run by the Phone Guard project. Allow 30 to 45 minutes the first time.

> **Status.** The setup tool described here is a working prototype, tested on a Qin J36 with a real Cloudflare account. It is run from source with Node.js today; a one-file download for Windows, Linux and macOS is planned. The tool does not flash the phone yet: the phone must already be running the Phone Guard image (see [Before you start](#before-you-start)). Items marked *not yet* are listed under [Known limits](#known-limits).

## What you will end up with

* A small private service (a Cloudflare Worker with its own database) in **your own** Cloudflare account. It holds your family's settings and passes them to the phone.
* The child's phone checking in about every 15 minutes and following your timetable. If it loses internet it keeps the last settings.
* A private page on **your own phone** where you approve phones, edit the timetable and connect Spotify.

You own the Cloudflare account, the data in it and the responsibilities that come with it. The Phone Guard project never sees your settings or your keys.

## How the pieces fit

| Part | Where it runs | What it does |
|---|---|---|
| Setup tool | your computer, once | creates the service in your Cloudflare account and makes the pairing codes |
| Companion (Worker + database) | your Cloudflare account | holds settings, relays them, serves your parent page |
| Child's phone | Phone Guard app | pairs, checks in, follows the timetable |
| Your phone | any browser | opens your private link to approve phones and change settings |

Passwords and tokens for connected services (Spotify, later email) are locked in your browser so that only the child's phone can unlock them. Your Cloudflare account only ever holds the locked copy.

## Before you start

You need:

1. **A Qin J36 running the Phone Guard image.** The image is not yet publicly downloadable (it contains vendor files whose redistribution is being checked, see [licensing](licensing.md)). Until that is settled you can (a) get the image from the project owner, or (b) build it yourself: the build recipe is in `build/` and [docs/own-build-viability.md](docs/own-build-viability.md), and flashing is described in [docs/recovery-guide.md](docs/recovery-guide.md). Building takes a Linux machine with about 300 GB of disk and several hours.
2. **A computer** (Windows, Linux or macOS) with [Node.js](https://nodejs.org) 20 or newer and `git`.
3. **A free Cloudflare account.** Sign up at <https://dash.cloudflare.com/sign-up>. You do not need a domain or a payment card.
4. **Your own smartphone** with a camera and a normal browser.
5. *(Optional)* **A Spotify Premium account** if you want Spotify on the child's phone. The owner of the Spotify developer app must have Premium.

## Step 1: get the tool

```bash
git clone https://github.com/durkimat/phoneguard.git
cd phoneguard/companion/setup-tool
npm run bootstrap
```

`bootstrap` installs what is needed and builds the Worker. It needs internet and takes about a minute.

## Step 2: run it and give it a Cloudflare key

```bash
npm start
```

A page opens in your browser (it only works on this computer). Then:

1. Press **Open Cloudflare**. A Cloudflare page opens with the permission already filled in (Workers Scripts: Edit).
2. Press **Continue to summary**, then **Create token**. Copy the key it shows.
3. Paste the key into the setup page and press **Start setting up**.

The key stays on your computer, is used only to talk to Cloudflare and is forgotten when you close the page.

The page shows a checklist: checking your account, preparing your address, installing the companion, waiting for it to come online (this can take two minutes while Cloudflare prepares the secure address), making the code for the child's phone.

## Step 3: two codes appear

* **A, for the child's phone:** a QR code, plus an address and a code to type instead. It works for about 10 minutes.
* **B, for your own phone:** a QR code for your **private parent link**. Anyone who has this link can manage your phones, so do not share or post it. A copy of the parent key is saved in `~/.phoneguard/` on your computer.

Scan **B** with your phone's camera now and keep the page it opens (add it to your home screen if you like).

## Step 4: connect the child's phone

1. On the child's phone open **Phone Guard**, then press **Connect to the family companion**.
2. Scan the QR code from **A**, or type the address and the code and press **Connect** (or the keyboard's Done key).
3. The phone shows a 4-digit number. Leave it on screen.

## Step 5: approve it on your phone

1. On your parent page you will see **New phone waiting**.
2. Type the 4 digits the child's phone is showing and press **Approve**. Typing them checks that you are approving the phone in your hand and not a stranger's.
3. A **recovery key** appears once. Write it down and keep it somewhere safe (not on the same phone).
4. Within about 10 seconds the child's phone says **Companion: paired**.

## Step 6: set a timetable

1. On your parent page press **Timetable and settings** under the phone.
2. For each day add blocks (start, end, mode). **Copy to Tue to Fri** saves typing. Gaps use the base mode.
3. Press **Save**. The phone picks it up at its next check-in, or immediately if you press **Sync now** on the phone.

Times follow the time zone shown on the page, including clock changes. The page works out the next 14 days; the phone does no calendar logic and keeps repeating your weekly pattern if it is offline for longer.

## Step 7 (optional): connect Spotify

Spotify only lets a few people use each developer app, so every family creates its own (about five minutes):

1. Go to <https://developer.spotify.com/dashboard> with your Premium account and create an app.
2. For the **redirect URI**, enter the address shown in the Spotify box on your parent page. It looks like `https://<your-address>/spotify/callback` and must match exactly.
3. Tick **Web API**.
4. Under **Users and Access**, add the email address of every Spotify account that will be used (yours and your child's). Spotify limits a developer app to five.
5. Copy the app's **Client ID** into the Spotify box on your parent page and press **Connect Spotify**. Sign in to Spotify when asked.

The sign-in key is locked in your browser for the child's phone. The phone collects it at its next check-in.

## Everyday use

* **Add another phone, or the code ran out:** press **Add a phone** on the parent page.
* **Change the timetable:** Step 6 again.
* **See the phones:** the list shows last seen, battery and current mode.
* **Start again with the same phone:** on the phone's enrol screen press **Disconnect from the family companion**, then connect again. Secrets sealed before a disconnect will not open afterwards, so reconnect Spotify.

## Privacy, cost and limits

* **Where data lives:** timetable, phone model, battery level, current mode and the app version are in your Cloudflare account. No location history or messages are stored by the companion.
* **Cost:** the free Cloudflare plan is enough. A phone checking in every 15 minutes is about 100 requests a day; Cloudflare's free allowance is far larger. Check Cloudflare's current limits if you add many phones.
* **Your responsibility:** you are the account owner and, in data protection terms, responsible for the data in it. Read Cloudflare's terms for your account.
* **Open source:** all code is in this repository under Apache-2.0.

## Troubleshooting

| Symptom | What to try |
|---|---|
| Cloudflare "did not accept that key" | Create the key again with the button on the setup page. Check you chose Workers Scripts: Edit. |
| Setup waits a long time on "online" | A new Cloudflare address can take up to two minutes. If it stops after that, run `npm start` again and retry. |
| Phone says "token invalid or expired" | Codes last 10 minutes and work once. Press **Add a phone** on your parent page for a new one. |
| Digits do not match | Check you typed what the **child's phone** shows. After three wrong tries ask the phone for a new code. |
| Phone is not paired after approval | Check the phone has internet. Press **Sync now** on its status page and read the Log line at the bottom. |
| Parent page asks for a key | Open your private link again. It is the QR code B or the saved file in `~/.phoneguard/`. |
| Spotify says "invalid redirect URI" | The address in the Spotify dashboard must match the one shown on your parent page exactly, including `https://` and the path. |
| Spotify signs in but nothing plays | Your Spotify account must be added under Users and Access, and the app owner needs Premium. |

## Removing everything

* On the phone: **Disconnect from the family companion**.
* In Cloudflare: delete the Worker named `phoneguard-…` (Workers and Pages in the dashboard), or run `CF_TOKEN=<key> node cli.mjs delete <name>` in the setup tool folder.
* On your computer: delete `~/.phoneguard/`.
* In Spotify: remove the developer app and revoke access in your account settings.

## Known limits

* The tool does not flash the phone (*not yet*).
* Sign-in to Cloudflare uses a key you create from a link rather than a "Sign in" button (*not yet*).
* If you lose your private link, there is no recovery flow yet (*not yet*): re-run setup to create a new companion and pair the phones again.
* Modes (what each mode allows) are edited as JSON on the parent page; forms are planned.
* Email, calendar and WhatsApp setup are planned; Spotify and the timetable work today.
* A packaged download for Windows, Linux and macOS is planned; today you run it with Node.js.
