# Family Chat 1.0.0-rc7: test checklist (for the owner)

rc7: a sticker tray and theme screen that use the whole width, and `help` that shows the current settings. Install over rc6 (same key). Earlier checklists still apply.

**Layout**
1. Sticker tray, phone portrait: the stickers fill the whole width in square tiles (4 columns on a normal phone), the tab row scrolls sideways, and there is one vertical scroll.
2. Turn the phone sideways (or use a bigger screen or a tablet): the tray gets more columns (about 9 on an 800 dp wide screen) and the tiles still fill each row evenly, with no empty strip on the right.
3. The same on every tab: Recent, Animated, Happy ... Words, and on search results; "No sticker matches that search." and the empty Recent text are full width too.
4. Chat theme screen: the wallpaper tiles fill the width (3 columns on a phone, more when wide); bubble colours and tabs scroll sideways.
5. Admin > Add or remove groups: the group list still fills the card.

**help**
6. In the admin group send `help`: one message, the commands on the first line, then one line each: "<child> can use: ...", "Photos and videos: on/off", "Videos download: on Wi-Fi only / on any connection", "Pictures, GIFs and stickers: shown / hidden", "Tell WhatsApp when read: on/off", "Connection: up/down", "Version: 1.0.0-rc7".
7. Change a setting in Admin > Chat options (or `photos on`) and send `help` again: the line changes.
8. `groups` gives the same "<child> can use: ..." line as in `help`. `photos on` and `photos off` answer "Photos and videos: on/off".
9. No new "Updated to ..." note appears after installing rc7 (the command list did not change).
10. Phone Guard (if it has an admin group): `help` shows its commands, the groups it may use, the connection and the version only; `photos on` still answers "Not supported here".
