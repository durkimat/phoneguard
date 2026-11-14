# Unlock the bootloader (step 2)

The bootloader decides which system software the phone may start. A locked one refuses ours, so it has to be unlocked once.

> **This erases everything on the phone.** Back up anything you want first. These are the standard Android steps, confirmed by the project owner on the J36; they have not yet been written up with screenshots from a fresh, locked phone.

## Steps

1. Do [step 1](computer-setup.md) first (USB debugging on, `adb devices` shows the phone).
2. On the phone open **Settings, System, Developer options** and switch on **OEM unlocking**. If it is greyed out, connect the phone to Wi-Fi, wait a few minutes and look again.
3. On the computer:

```bash
adb reboot bootloader
fastboot devices                  # the phone should be listed
fastboot flashing unlock
```

4. The phone asks you to confirm (usually the volume keys choose and the power key confirms). Confirm. The phone erases itself and restarts.
5. The phone shows a warning screen each time it starts from now on. That is normal for an unlocked phone.
6. Turn USB debugging back on (the erase switched it off) and check:

```bash
adb reboot bootloader
fastboot getvar unlocked          # should say: unlocked: yes
fastboot reboot
```

## If it does not work

- *OEM unlocking greyed out:* Wi-Fi on, wait, check again; the phone must not be locked to a carrier.
- *`fastboot flashing unlock` refused:* OEM unlocking is not on yet; repeat step 2.
- *Nothing listed in fastboot mode:* [troubleshooting](troubleshooting.md).

Next: [get the image](get-the-image.md).
