# Day to day: what your child sees, and what to do when

This page is for after setup. It shows the home screen on the Qin J36 (in the default green look) and what to do when something goes wrong. Everything in the pictures is real, taken from the phone's own software.

## The home screen

![The home screen: data left, weather and rain, the clock](img/home-rain-due.png)

From the top:

1. **The status line:** signal, mobile data arrows, Wi-Fi, Bluetooth, battery.
2. **Mobile data left:** "4.8 GB left" with a bar for how much of the plan is used (see below).
3. **The weather:** today's weather and temperature on the left. On the right, **when rain is due**: the time it is expected and the chance ("21:00, 80% chance"), or **"Now"** if it is raining. On a dry day it says **"Dry"** with the day's high and low.
4. **The clock and the date.**
5. **The symbol row:** unread messages and missed calls, with counts. A music note appears while music plays.
6. **The soft keys:** **Menu** on the left, **Music** on the right.

While music or radio plays, a line with a play triangle and a scrolling "Artist - Title" appears under the date, with a progress bar. When nothing is playing that space shows the next calendar event, if there is one.

### Going straight to things with the Up key

Press **Up** on the keypad and a symbol on the home screen is outlined. **Up** and **Left** move to the previous symbol, **Down** and **Right** to the next, and **OK** opens what is behind it: the forecast, the data page, the Wi-Fi list, the Bluetooth list, or the messages. **End** or the right soft key goes back.

![A symbol outlined with the Up key](img/home-up-highlight.png)

## Spotify playlists

Your playlists are listed with the ones you pinned first, then the ones you played in the last two weeks, then the rest in Spotify's order. Press `*` on a playlist to pin it (a star appears next to its name); press `*` again to unpin. Pins and play times are kept on the phone only. All of your playlists are listed, not just the first 50.

## Speaking instead of typing

On the text-entry screens (a message, a Spotify or radio search) your child can speak instead of typing. Press **Down** from the text to reach the small microphone at the bottom, **hold OK** and speak, then let go: the words appear at the cursor, ready to correct with the keypad. **Back** or **End** while holding cancels. A quick tap does nothing but remind you to hold.

The recording goes to your family's own Cloudflare Worker, which has Cloudflare's speech service turn it into text and then forgets it. Nothing is stored or logged, and the Worker only counts how many times each phone used it (up to 200 a day). It needs Wi-Fi or mobile data, and there is no microphone on password boxes.

You choose where it is available on the parent page: **Timetable**, then **Mode settings**, then **Voice typing** (and **Voice commands**, which is for a later feature). It is off in School/work and on in the other modes. If it is off, the microphone is not shown.

If the phone says **Voice isn't working right now**, Cloudflare's service was busy or its free daily allowance is used up; try again later.

## The side button

The button on the side of the phone (while the screen is on, locked or not):

- **One press** opens the camera (after a moment, to see whether a second press follows). It does not open if the front of the phone is covered, so a knock in a pocket does nothing. It also does nothing during a call, while the phone is locked, or in a mode with no camera.
- **Two quick presses** switch the torch on or off.
- **Holding it** is for speaking (coming in a later update); until then it does nothing.
- Inside the camera, one press takes the photo at once.

With the screen off the button does nothing yet.

## Mobile data

The phone counts how much of the SIM's mobile data has been used this month, so you do not have to look it up. It is the phone's own count, so it can differ from your provider's. There is a correction setting for that.

Set it up once, on the phone: **Menu, Settings, Connections, Mobile data, Change plan** (on the keypad: **Menu, 9, 3, 3**, then the last row). Enter the allowance in GB and the day of the month the plan renews. If you set a **Correction** (in MB) the phone adds it to its own count, so the figure matches your provider.

![The Mobile data page](img/mobile-data.png)

When 10% or less is left (you can change this), the strip on the home screen turns into a dark banner:

![The data low banner](img/home-data-low.png)

You can turn the data strip off for any mode (for example School/work) on the parent page: **Timetable**, then **Mode settings**, then untick **Mobile data left on the home screen**.

## Screen lock (PIN)

Your child can set their own PIN on the phone, without going into Android's settings: **Menu, Settings, This phone, Screen lock** (on the keypad: **Menu, 9, 4, 1**). Enter the PIN (4 to 8 digits) twice and press **Save**.

![The Screen lock page](img/screen-lock.png)

You can also set, change or remove the PIN from the parent page: **The phone** tab, **Screen lock (PIN)**. A PIN you set replaces your child's, so you can always get back in. This works on **Family** phones. On an **Open** phone the same row opens Android's own screen, and a forgotten PIN means erasing the phone.

## When something goes wrong

**My child forgot their PIN.** Open the parent page, go to **The phone**, **Screen lock (PIN)**, and set a new one, or remove it. The phone picks it up at its next check-in (a few minutes). On an Open phone there is no way to recover it other than erasing the phone.

**The phone needs updating.** Open the parent page, go to **The phone**, **Updates and removals**, and press **Update this phone** if it is offered. The phone installs it at its next check-in and needs to be on Wi-Fi. "Allow updating" must be ticked on the same page. You can untick it again afterwards if you want to approve each update.

**The companion needs updating.** The parent page shows "A newer companion is available". On the computer you set up with, run the setup tool and choose **Update my companion**. Nothing is lost.

**I cannot get into the parent page.**
- Use **Sign in with a passkey** if this device has one.
- Otherwise open the private link you saved when you set up (it ends in `#k=` and a long key). That always works. See [signing in](sign-in.md).
- If you have lost both, the setup tool on your computer holds a copy in your home folder (`.phoneguard`).

**I lost my child's phone.** Open the parent page and look at the **Today** tab for where it was last seen. The phone also keeps working under your rules while it is lost.

**New phone.** Open the parent page and press **Add a phone**, then follow [getting started](getting-started.md) to install on the new J36.

**The home screen shows no data strip or no weather.**
- The data strip needs a plan set on the phone (above), the SIM inserted, and the mode must have the setting ticked.
- The weather needs a place set on the parent page (**Services** tab, **Weather place**). Without one the weather and rain indicator are hidden.
