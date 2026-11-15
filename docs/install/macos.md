# Install Phone Guard on a Qin J36: macOS

Follow the steps in order. Allow about an hour, most of it waiting. You need the things listed on the [Start page](../getting-started.md#what-you-need).

### 1. Install Node.js
- Go to <https://nodejs.org> and download the **LTS** installer for macOS (the `.pkg` file).
- Open it and follow the prompts, accepting the defaults.
- *You'll know it worked when:* `node --version` (step 2) shows `v20` or higher.

### 2. Open a terminal and make a folder
- Press Cmd+Space, type `Terminal` and press Enter.
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
- If it cannot see the phone, try another USB cable (some only charge) and another port, then run the check again.

### 6. Allow unlocking on the phone
- On the phone open **Settings**, **System**, **Developer options**.
- Switch on **OEM unlocking**. If it is greyed out, connect the phone to Wi-Fi, wait a few minutes and look again.

### 7. Unlock the bootloader
- **This erases everything on the phone.**
- Type these three commands, pressing Enter after each:

```
./platform-tools/adb reboot bootloader
./platform-tools/fastboot devices
./platform-tools/fastboot flashing unlock
```

- The second command should list your phone.
- On the phone, confirm the unlock (usually the volume keys choose and the power key confirms).
- The phone erases itself and restarts. Skip its welcome screens.
- A warning screen at every start is now normal.

### 8. Turn USB debugging on again
- The erase switched it off. Repeat step 4.

### 9. Download the Phone Guard image
- Type `node pg-install.mjs download` and press Enter.
- It downloads about 1 GB, unpacks it to about 2.4 GB and checks it. You need about 5 GB free.
- *You'll know it worked when:* every line says `OK`.

### 10. Flash the phone
- Type `node pg-install.mjs all` and press Enter.
- It checks the phone, saves a backup of the phone's details, checks the image, then asks you to type `yes`.
- Do not unplug the phone or let the computer sleep until it says `Done`.
- *You'll know it worked when:* it says `Done` and the phone restarts.

### 11. Wait for the first start
- The first start takes several minutes. Do not interrupt it.
- *You'll know it worked when:* the phone shows its first-run screen.

### 12. Choose how to use the phone
- On the phone, follow the first-run screen: language, time zone and Wi-Fi.
- Choose **Open phone** or **Family phone**. This is a one-time choice.
- **Open phone:** nothing is locked and you set everything up on the phone. You are finished with this guide: see [Using your Open phone](open-phone.md).
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
- The wizard guide shows each step: [family-setup.md](../family-setup.md).
- *You'll know it worked when:* the wizard says **All set**.

### 15. Set the timetable
- Open your private parent link on your own phone and add the school and night times.
- [Everyday controls](../family-setup.md#everyday-controls).

---

Stuck? [Troubleshooting](troubleshooting.md). Other ways to do these steps: [other ways](other-ways.md). For licensing see [licensing](../licensing.md).
