# Navigation rules

## The rule (owner, 2026-11-26)
**Back retraces the way the person came**, however they got there: menus, shortcut keys, home-screen symbols, option menus, another app or screen in between. UI journeys are consistent by default. Anything different is a decision, listed below with its reason. **Add to this list; do not make quiet exceptions.**

**Home is asked for by name:** the Home / hang-up key goes to the Home screen from anywhere.

## For developers
- Move forward with `goTo(screen)`, go back with `leave()`. Do not assign `screen` directly (only phone-state overlays do: live call, ringing, power menu, first-run, a debug demo).
- A finished task may return further than one step with `leaveTo(screen)` (Saved, Deleted, Sent, a Wi-Fi action, a date picked).
- `JourneyTest.backRetracesTheWayInByEveryKeyAndShortcut` enforces the rule. Its `exceptions` list must match this document.
- The crawler does not reach every screen: in-call, ringing, power menu, first run, Navigate and Spotify search results rest on their own tests or on nothing.

## Exceptions
| # | Exception | Reason |
|---|---|---|
| E1 | Tabs and destinations are never on the way twice: Now playing, the dial pad, call log, contacts, Wi-Fi, Bluetooth. Arriving at one already on the way drops back to it. | Otherwise cycling the dialler or re-picking music piles up a loop for Back to walk round. |
| E2 | A finished search box is skipped: Back from Spotify or radio search results goes to the menu the search was started from. (Navigate differs: Back from its results reopens the box with your words, as before.) | The box deletes a letter per Back, so reopening it would make leaving slow. **Owner to confirm, including whether Navigate should match.** |
| E3 | Back from the Inbox opened by a text notification goes to the notification list, not the notification's page. | Opening it clears it, so the page no longer exists. |
| E4 | While typing, the right key deletes; it is Back only when the box is empty. | The key is labelled for what it does. |
| E5 | If the parental mode changed while the launcher was hidden, it returns to Home. | The screen left behind may not be allowed in the new mode. |
| E6 | The left key pressed inside another app opens our Menu over Home. | The key means "our menu"; it is not a Back. |
| E7 | A finished or cancelled action may jump further back (Saved, Deleted, Sent, Stop music goes Home). | The steps in between belong to a task that is over. |

## Check on the phone: changes where the old behaviour looked deliberate
The old code or its tests said these on purpose. All now follow the rule; say which to restore (they would become exceptions here).

| # | Before (deliberate) | Now | What to try |
|---|---|---|---|
| T1 | "Now playing is a destination: Back is always home" | Back goes the way you came | Music > Spotify > play something > Back, Back. Also from the Home music shortcut |
| T2 | "Change source" cleared the way, so Back went Home | Back returns | Now playing > # > Back |
| T3 | Dialler: "Back leaves them together" | Back steps through pad, options, calls, contacts | Green key, left key > Call log, left (Contacts), Back x4. Is it too many presses? |
| T4 | After unlock: "our home screen, like a Nokia waking up". After a call, alarm or any other app, Home | The launcher comes to the front on the screen you left | Lock and unlock on Messages. Take a call and an alarm from Settings. **Privacy:** are you happy a woken phone shows the last screen? Should a long sleep return to Home? |
| T5 | Text notification: Inbox "goes home first" | Back returns to Notifications | E3 above |
| T6 | Option menus (Calendar, Event, Email, Phone) were replaced by what you chose | Back returns to the option menu | Calendar > Options > New event > Back |
| T7 | Game help was skipped on the way back | Back from a game shows its help page, then the list. A running game pauses if the launcher is hidden | Play each game, Back; take a call during Snake and Blaster |
| T8 | Right key on Playlists opened the Spotify menu; on the radio presets it opened the radio menu | Right key is Back. **Left key is now "Options"** on both (OK still plays; on the radio presets D-pad Left no longer plays) | Playlists > left key > Download. Radio presets > left key > Find station |
| T9 | Leaving another app always showed Home | Back out of an Android app returns to the list it came from | Android Apps > an app > Back. Android settings. Spotify sign-in. The playlist download job (it brings Spotify forward by itself) |

Also check: the left soft key and side button on the J36 with the camera and gallery; the green key pressed inside another app (the dial pad now opens over the screen the launcher was on).
