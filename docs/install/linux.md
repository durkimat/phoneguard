# Install Phone Guard on a Qin J36: Linux

Follow the steps in order. Allow about an hour, most of it waiting. You need the things listed on the [Start page](../getting-started.md#what-you-need).

### 1. Install Node.js
- Install it with your package manager:
  - Debian or Ubuntu: `sudo apt install nodejs npm`
  - Fedora: `sudo dnf install nodejs npm`
  - Arch: `sudo pacman -S nodejs npm`
- Then type `node --version`. It must show `v20` or higher. If it is older, install a newer one by following <https://nodejs.org/en/download>.

### 2. Open a terminal and make a folder
- Open a terminal.
- Type these two lines, pressing Enter after each:

```
mkdir -p ~/phoneguard
cd ~/phoneguard
```

- Leave this window open for the rest of the guide. Every command below is typed here.

### 3. Download the installer
- Type this and press Enter (it is one line):

```
curl -LO https://raw.githubusercontent.com/durkimat/phoneguard/main/installer/pg-install.mjs
```

- *You'll know it worked when:* `ls` lists `pg-install.mjs`.

### 4. Turn on USB debugging on the phone
- On the phone open **Settings**, then **About phone**.
- Tap **Build number** seven times.
- Go back, open **System**, then **Developer options**, and switch on **USB debugging**.
- Plug the phone into the computer with a USB-C data cable.
- Tap **Allow** when the phone asks about the computer.

### 5. Check the computer can see the phone
- Type `node pg-install.mjs check` and press Enter.
- If it says `adb` and `fastboot` were not found, type `yes`. It downloads them from Google into a `platform-tools` folder here.
- *You'll know it worked when:* every line says `OK`.
- If it cannot see the phone, try another USB cable (some only charge) and another port first.
- If it still sees nothing, your user may not be allowed to use USB phones. Install your distribution's Android udev rules (the package is often called `android-udev-rules` or `android-sdk-platform-tools-common`), unplug and replug the phone, and run the check again.

### 6. Allow unlocking on the phone
- On the phone open **Settings**, **System**, **Developer options**.
- Switch on **OEM unlocking**. If it is greyed out, connect the phone to Wi-Fi, wait a few minutes and look again.

### 7. Unlock the bootloader
- **This erases everything on the phone.**
- Type `node pg-install.mjs unlock` and press Enter.
- It restarts the phone into its bootloader, then asks you to type `yes`.
- On the phone, confirm the unlock when it asks (usually the volume keys choose and the power key confirms).
- The phone erases itself and restarts. This takes a few minutes; skip its welcome screens.
- A warning screen at every start is now normal.
- *You'll know it worked when:* the installer says the phone is erasing itself and restarting, and the phone starts up.

### 8. Check the phone is still visible
- Type `node pg-install.mjs check` and press Enter.
- If it lists your phone and every line says `OK`, go on to step 9.
- If it cannot see the phone, the erase switched USB debugging off. Repeat step 4, then run the check again.

### 9. Download the Phone Guard image
- Type `node pg-install.mjs download` and press Enter.
- It downloads about 1 GB, unpacks it to about 2.4 GB and checks it. You need about 5 GB free.
- *You'll know it worked when:* every line says `OK`.

### 10. Flash the phone
- Recommended on a phone you care about: first back up its IMEI and radio data. It is a separate job (about 30 minutes) that this installer does **not** do: [how to do it](../recovery-guide.md). If that data is lost, the phone cannot connect to the mobile network.
- Type `node pg-install.mjs all` and press Enter.
- It checks the phone, saves a list of the phone's settings and apps, checks the image, and asks you to type `yes` twice: once about the IMEI backup, once before it writes anything.
- **What you will see:**
  - Numbered stages in the terminal ("Step 1 of 6", and so on), with fastboot's own progress lines.
  - The phone restarts into a **text menu** (its flashing mode). **Do not touch the menu**: the installer controls it.
  - The whole flash takes about 3 to 5 minutes, and the phone then restarts by itself.
- Do not unplug the phone or let the computer sleep until it says `Done`.
- *You'll know it worked when:* it says `Done` and the phone restarts.

### 11. Wait for the first start
- Leave the phone alone for about 5 minutes: the first start is slow. Do not interrupt it.
- *You'll know it worked when:* the phone shows its first-run screen.

### 12. Choose how to use the phone
- On the phone, follow the first-run screen: language, time zone and Wi-Fi.
- Choose **Open phone** or **Family phone**. This is a one-time choice.
- **Open phone:** nothing is locked and you set everything up on the phone. You are finished with this guide: see [Using your Open phone](../open-phone.md).
- **Family phone:** continue with step 13.

### 13. Start the setup wizard (Family phone)
- You need a free [Cloudflare account](https://dash.cloudflare.com/sign-up) and your own smartphone.
- In the same terminal type these lines, pressing Enter after each:

```
curl -L -o phoneguard.zip https://github.com/durkimat/phoneguard/archive/refs/heads/main.zip
unzip phoneguard.zip
cd phoneguard-main/companion/setup-tool
npm run bootstrap
npm start
```

- *You'll know it worked when:* a page opens in your browser with a progress bar.

### 14. Follow the wizard
- Work through the wizard's steps. The optional ones (Spotify, email, calendars, WhatsApp) have a **Skip** button.
- If you use Spotify, the child's phone needs the Spotify app: [installing Spotify on the phone](spotify-app.md).
- The wizard guide shows each step: [family-setup.md](../family-setup.md).
- *You'll know it worked when:* the wizard says **All set**.

### 15. Set the timetable
- Open your private parent link on your own phone and add the school and night times.
- [Everyday controls](../family-setup.md#everyday-controls).

---

Stuck? [Troubleshooting](troubleshooting.md). Other ways to do these steps: [other ways](other-ways.md). For licensing see [licensing](../licensing.md).
