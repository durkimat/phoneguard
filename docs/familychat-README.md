# Family Chat

Family Chat lets a child use a few WhatsApp groups that a parent has chosen, on the child's own phone, through the parent's WhatsApp account (it links as a "linked device", like WhatsApp Web). The parent keeps control with a PIN and, from their own phone, with short messages in a private admin group called `FamilyChat-<child's name>`.

## The risks, in plain words
- It uses an unofficial connection. WhatsApp's terms do not allow that, so WhatsApp could limit or ban the account that is linked. It is the parent's own account: link it only if you accept that, and try it first with a spare account.
- Family Chat is not made by or connected to WhatsApp or Meta.
- The child sees everything anyone writes in the groups the parent allows, and can write in them.
- Messages are kept on the child's phone only. Licences: see NOTICE.txt.

## Install on Android 16
1. On the child's phone open the release page in the browser and download `familychat-<version>.apk` (and the `.sha256` if you want to check it: `sha256sum -c`).
2. Open the downloaded file. Android asks to allow the browser (or Files app) to install unknown apps: allow it for that app, then go back and install.
3. Open Family Chat and follow setup (six steps). Allow notifications when asked, and in Admin > Stay connected allow the battery exemption.
4. Early test builds are signed with a key whose backup is not finished yet; a later build may need the old one uninstalled first.

## Reporting problems
Open an issue on the repository (github.com/durkimat/phoneguard) or tell the owner. Say what you did, what you expected, what happened, the time, and the version shown in Admin > About. Do not paste phone numbers, PINs or message text.
