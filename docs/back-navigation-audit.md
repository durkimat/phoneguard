# Back navigation audit (2026-11-26)

Rule (owner): Back retraces the way the person got there, including shortcut keys and direct links from the home screen.

Fixed in this pass: our own full screens (camera, gallery) now return to the screen and row they were opened from. Their End key still goes Home.

Method: a temporary crawler (an extension of `JourneyTest`: every key and home shortcut from Home with sample data, one Back after each step to a new screen whose right soft key says "Back"), a second throwaway test for the green key (handled by the activity), and reading the activity code. It reached 77 of 89 screens. NOT covered: in-call and call menu, ringing screen, power menu, first run, Spotify search results, the Navigate screens, per-app data. The existing `JourneyTest` only checks OK and number keys that go one level deeper, which is why these passed.

Nothing below has been changed. For discussion.

## A. Clear breaks of the rule
| # | Way in | Back goes to | Expected |
|---|---|---|---|
| A1 | Green call key on Menu, Extras, Contacts, Messages, WhatsApp, Settings and its sub-screens, Phone options, then Back from the dial pad | the screen BEFORE the one you were on (Settings -> Menu, Theme -> Look) | the screen you pressed it on. Cause: `Action.CallKey` -> `startDial` switches screen without remembering it. Voice "call" and Call on a contact use the same function |
| A2 | Playlists (or Devices) > right soft key, labelled "Back" | opens the Spotify menu, a screen never visited; Back again -> Now playing, again -> Home | the Music menu. If the menu is wanted there, it needs its own key and label |
| A3 | Music menu > Spotify / a playlist or device > Now playing | Home | the list or menu you chose from |
| A4 | Notifications > OK on a text message > Inbox | Home (`openInbox` goes home first, on purpose) | Notifications |
| A5 | Android Apps (or a pinned app, Android settings, the real WhatsApp, Spotify sign-in), then Back out of that app | Home (the launcher goes Home on return from any other app) | the list it was opened from |

## B. Right screen, wrong row
Back from a Wi-Fi network or Bluetooth device lands on the first row, not the one chosen. Spotify menu > Devices > Back does the same.

## C. Probably intended; please confirm
| # | Case | Today |
|---|---|---|
| C1 | Option menus (Calendar, Event, Email, Phone options): choose an entry, then Back | returns to the screen under the menu; the menu itself is not part of the path |
| C2 | Dialler: Left cycles dial pad, calls, contacts | Back leaves all three together. Also: green key on the dial pad -> calls, Back skips the dial pad |
| C3 | Game help > Play > Back | the Games list, skipping the help page |

## D. Interruptions (not navigation, but they lose your place; from reading the code, not tried)
After the screen sleeps while the launcher is in front, or after a call or an alarm screen, the launcher comes back on Home.
