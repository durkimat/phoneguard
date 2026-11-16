# Installing Spotify on the child's phone

Phone Guard does not play music itself. It signs in to your Spotify account and tells the **Spotify app on the phone** what to play. Spotify only publishes its app on Google Play, so it is not part of the Phone Guard image and has to be put on the phone once.

> Spotify only lets its own app play its music dependably (the open-source players others use have recently stopped working for newer Spotify accounts), so the official app is what Phone Guard controls.

You need: the phone on **Wi-Fi**, your **Spotify Premium** account, and a Family phone already connected to your companion (so you can approve apps on your parent page).

## The easiest way: the phone fetches it itself

Once your companion and the phone's Phone Guard app are up to date, nothing needs a cable or a computer:

1. The phone gets **Aurora Store** automatically when it is added (it stays hidden from your child unless you allow it in a mode).
2. When you **connect Spotify** on your parent page (Services tab), the companion tells the phone to fetch the **Spotify app**. You can also press **Put the Spotify app on the phone** there, or untick "Put the Spotify app on the phone by itself" if you would rather not.
3. The phone must be on **Wi-Fi** and awake enough to check in. It opens Aurora's Spotify page and presses Install for you, then Android's own confirm step. It tries at most once every six hours; if it stops, the Spotify section says why.
4. The last step is yours: **sign in to Spotify on the phone** once with your Premium account (touchscreen).

The Spotify section of your parent page shows both halves: whether Spotify is connected (signed in) and whether the Spotify app is actually on the phone.

## The older way (cable): let the setup wizard do it

In the setup wizard's **Spotify** step (or any time you run the wizard again), press **Install it for me**.

You need: the child's phone **plugged into this computer** with USB debugging on, **on Wi-Fi**, and `adb` on this computer (running `node pg-install.mjs check` in the phoneguard folder downloads it). It takes about 5 minutes and you do not need to touch the phone.

What it does, so nothing is a surprise:
1. Downloads Aurora Store from F-Droid and checks it against a fixed checksum, then installs it on the phone.
2. Allows Aurora and Spotify on your companion for Day mode (you can see this on your parent page, Apps tab).
3. Opens Aurora on the phone, signs in anonymously (no Google account), and installs Spotify from it.
4. Removes Aurora Store again and takes its approval back, so the child has no app store.
5. For a minute or two the phone's animations are switched off (Aurora's screens need it) and then put back exactly as they were.

Then **sign in to Spotify on the phone** with your Premium account (use the touchscreen). The wizard shows a tick once the phone has reported the Spotify app.

If it stops, it says why in plain words. The usual causes are that the phone is not on Wi-Fi, the cable is not connected, or the phone has not synced yet (press **Sync now** on its status page and press the button again). The by-hand route below always works.

## By hand

1. **Put Aurora Store on the phone.** Aurora is a free, open-source client for Google Play. Download `com.aurora.store_76.apk` from <https://f-droid.org/packages/com.aurora.store/> on your computer, plug the phone in, and in a terminal in the folder where it saved type:

   ```
   adb install com.aurora.store_76.apk
   ```

   (Use `platform-tools/adb`, the copy the installer downloaded, if you do not have your own.)
2. **Approve it.** On the phone's next check-in, **Aurora Store** appears on your parent page in the **Apps** tab as a new app. Approve it for Day mode and press **Sync now** on the phone's status page.
3. **Open Aurora on the phone** and press **Next**, **Next**, **Finish**. On the sign-in screen choose **Anonymous** (no Google account is needed).
4. **Find Spotify** (search for "Spotify") and press **Install**. Android asks to confirm: press **Install**.
5. **Approve Spotify** the same way as in step 2 (Apps tab, Day mode), then switch **Aurora Store off again** for every mode, so the child cannot use an app store.
6. **Sign in to Spotify on the phone** with your Premium account (use the touchscreen).
7. Back in the setup wizard (or the parent page, Services tab) finish the Spotify step. The wizard shows a tick for "The Spotify app is on the phone" once the phone has reported it.

## If it does not play

- *"Spotify is not installed"*: step 4 did not finish; check the phone is on Wi-Fi.
- *The phone does not appear as a Spotify device*: sign in to Spotify on the phone (step 6) with the same account you connected in the wizard.
- *Nothing happens at all*: your Spotify account must be added under **User management** in the Spotify developer dashboard, and the owner of that app needs Premium.

## Keeping Spotify up to date

Spotify updates itself only if Aurora is allowed to run. When you want to update it, approve Aurora for Day mode for a few minutes, open it, press **Updates**, then switch it off again.
