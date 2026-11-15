# Installing Spotify on the child's phone

Phone Guard does not play music itself. It signs in to your Spotify account and tells the **Spotify app on the phone** what to play. Spotify only publishes its app on Google Play, so it is not part of the Phone Guard image and has to be put on the phone once.

> This is a workaround for now. A built-in player that needs no Spotify app, no app store and no Spotify developer account is being looked into.

You need: the phone on **Wi-Fi**, your **Spotify Premium** account, and a Family phone already connected to your companion (so you can approve apps on your parent page).

## Steps

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
