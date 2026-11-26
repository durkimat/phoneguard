# Updating Phone Guard

Three things can be updated, each without redoing the install. Nothing is lost in any of them.

| What | How | Time |
|---|---|---|
| **Your companion** (the part in your Cloudflare account) | Run the setup tool and choose **Update my companion** | about a minute |
| **The Phone Guard app** on a Family phone | Your parent page: open the phone, **Updates and removals**, **Update this phone** | a few minutes, the phone does it |
| **The phone's system image** | The installer's update command (keeps the phone's data) | about 10 minutes |

Your parent page tells you when a newer companion is available: a notice appears at the top.

## Update your companion

You need the same computer you set up with (or any computer: see "If this is a different computer"), and a Cloudflare key.

1. Open a terminal in the folder where you unpacked the Phone Guard files (inside your `phoneguard` folder: `phoneguard-main/companion/setup-tool`), then run `npm start`. The wizard opens in your browser. You do not need to download anything new: the tool fetches the newest companion from GitHub itself and checks it.
2. On the first step, find **Update my companion**. It lists your companion, the version it is running, and the newest version.
3. Create a Cloudflare key with the link on that page (the same one as for the install), paste it into the box, and press **Update**.
4. Wait for the list to finish (about a minute). It ends with a message like "Updated 0.5.0 to 0.5.1. 1 phone is still connected and your parent link still works."

What the update does and does not do:
- It replaces the companion's code. It keeps your phones, settings, history, secrets and your private parent link. You do not need to pair anything again.
- New database fields are added automatically the first time the new version starts. Existing data is never rewritten or removed.
- If the data in your companion is newer than the update (for example you updated with a newer tool first and then tried to go back), it refuses and changes nothing.
- Phones keep working while it happens; they pick up the new version at their next check-in.

If you prefer a command line, from the same folder: `CF_TOKEN=<your key> node cli.mjs update`. It lists what it did and checks that your parent key still works.

### If this is a different computer, or the files are gone
Download the newest Phone Guard ZIP again (the same steps as the install), run `npm run bootstrap`, then `CF_TOKEN=<your key> node cli.mjs update`. With no saved setup on that computer it finds your companion in your Cloudflare account by name (`phoneguard-` followed by six characters).

### If something goes wrong
- *"Cloudflare did not accept that key"*: create the key again with the link on the page.
- *"There is no companion called ... in this Cloudflare account"*: the key is from a different Cloudflare account than the one you installed into.
- *It says the new version was uploaded but still reports the old one*: Cloudflare can take a minute. Reload your parent page, and run the update again if it still shows the old version.
- To put the old version back, open your Worker in the Cloudflare dashboard (Workers, your companion, Deployments) and roll back. If a newer version had already added database fields, the old code refuses to start rather than risk your data; run the update again instead.

## Update the Phone Guard app on a Family phone

1. Open your parent page, then the phone.
2. Under **Updates and removals**, switch on **Allow updating Phone Guard from here** (it is off until you do, so it cannot be pressed by accident).
3. Press **Check for updates**. If there is a newer app, it shows the version and **Update this phone**.
4. Press it and confirm. The phone downloads the app from GitHub (about 34 MB), checks it against its published checksum, and installs it. **It waits until the phone is on Wi-Fi**, so it does not use the child's mobile data; until then the phone's entry in **Updates and removals** says it is waiting. If a phone is never on Wi-Fi, connect it to your Wi-Fi once (Settings, Connections, Wi-Fi).

**Watching it happen (from app 0.5.1).** On the phone, **Settings > This phone > About phone** shows the version, the last check-in and where an update has got to (waiting and why, downloading with a percentage, installing, failed and why, or done). **Check now** checks in at once, which also tries a waiting update. A short notice appears when an update starts, fails or finishes. The phone reports "done" only once the new version is really installed, and reports failures straight away. With companion 0.8.12 or later, the parent page also says what a waiting update is waiting for. An update **from** 0.5.0 or earlier is done by the older app, which shows none of this and says "done" before it has happened: check the version.

An Open phone has no companion, so it has no remote update. Update it by flashing the newest image over the old one (below), which keeps its data.

## Update the phone's system image

Follow the guide for your system from the Start page, but at the flash step use the keep-data option:

```
node pg-install.mjs flash --keep-data
```

(The installer downloads the newest image first if you add `--download`.) This replaces the system and keeps the phone's contacts, messages and settings. A Family phone stays a Family phone and an Open phone stays an Open phone.

## Which version am I on?

- The companion: shown at the bottom of the **Updates and removals** section on your parent page, and at `https://<your address>/api/version`.
- The app: shown on the phone's Phone Guard status page and in the same section.
