# Launcher UI architecture: navigation separate from presentation

Status: plan agreed with the owner 2026-11-12. Slice 1 (this branch, `feat/ui-nav-stack`) only adds a test that pins today's Back behaviour.

## Decisions

1. **Navigation is separate from the look.** The model decides what screen, rows, selection and soft keys exist; a skin only draws them. `LauncherSkin` (`id`, `create`, `render(model, info)`) already exists, and `NokiaSkin` is its only implementation.
2. **Back closes the top window.** One back stack of frames (screen + selection) in the model replaces the per-screen `*From` flags (`contactsFrom`, `callsFrom`, `dialFrom`, `notesFrom`, `settingsFrom`, `weatherFrom`, `homeEntry`, `musicFromHome`, `nowFromHome`, `resFromFind`) and the hard-coded `selected = N` restores. Nokia draws only the top frame; a Mac-style skin may draw the stack behind it.
3. **Always D-pad navigable.** Touch stays under parent-app control and is a separate later piece. Every screen must work with the D-pad, soft keys and digits alone.
4. **Layout kinds.** Each screen declares `List`, `Grid`, `Detail` or `Custom`, so key handling and skins agree. In a list, Left/Right keep acting as the soft keys (today's behaviour). In a grid, Left/Right/Up/Down move; OK, the left soft key select; the right soft key goes back; digits jump to a tile.
5. **Grid with more than 9 entries scrolls vertically** (the owner's preference), keeping icons the same size. Smaller icons in a 4x3 grid can be tried as an alternative. The selected item's name shows in a strip above the soft keys.
6. **Look settings are plain data** (palette, fonts, window style, icon style, wallpaper, spectrum style), stored on the phone and set from the parent app; a Look screen on the phone is optional. Today only `Theme` (colours) and `Font` exist.

## Findings from the review (code on main at 990df19)

- 55 `Screen` values; `NokiaSkin` draws 16 of them itself (home, weather, battery, note, music, thread, write, calc, game help, snake, pairs, shooter, seeds, WhatsApp pairing, dial, call); the rest go through one generic list routine fed by `LauncherModel.title`, `rows`, `selected` and `softKeys`.
- Navigation is a hand-written state machine: `route(screen)` calls one key function per screen. Back targets are hard-coded per screen.
- `Key.LEFT` is an alias of the left soft key and `Key.RIGHT` of the right one on almost every screen; text entry and the games use them for movement.
- The main menu can have more than 9 entries (`0` selects the tenth).

## Slices

1. Pin today's Back behaviour (`NavigationBackTest`). **Done in this branch.**
2. Replace the `*From` flags with a back stack, family by family, keeping the pinned behaviour. **Done:** Settings (Theme, Font, Menu style, Typing, Speaker, Sound test, Connections, WhatsApp settings and its name styles, Battery), Notes, Weather, Games, Apps, Calculator, Logs and Running apps, the dialler (dial pad, calls, contacts; one `dialler` flag), Music (Music menu, Change source) and Messages (Messages, Inbox, WhatsApp chats, WhatsApp pairing). The flags `settingsFrom`, `notesFrom`, `weatherFrom`, `callsFrom`, `contactsFrom`, `dialFrom`, `musicFromHome`, `nowFromHome`, `homeEntry` and `waPairBack` are gone, and so are the hard-coded rows restored on Back (one of them pointed at the wrong Settings row after WhatsApp settings). After sending a text the trail is unwound to the Messages screen with `leaveTo()`.
   **Left as they are on purpose:** hops inside a family that already return by identity or fixed row (Chat to Inbox by address, the game screens to the Games list, Wi-Fi/Bluetooth to Connections), `writeBack` (the write screen already remembers its caller and the row is unchanged while it is open) and `resFromFind` (the radio results page, entangled with the station search). They can move to the trail later if a skin needs to draw them as stacked windows.
3. Add layout kinds and grid movement; main menu as a grid behind a Look setting, list as the default. **Done:** `Layout` (LIST, GRID, CUSTOM) comes from the model; Settings > Menu style (List or Grid, saved as `menu_style`); on the grid the D-pad moves in four directions (Left and Right stop being soft-key aliases there), more than three rows scroll vertically, digits jump, OK or the left key opens, the right key goes home; the chosen entry's name is drawn above the soft keys; touch picks a tile. Sixteen 16 x 16 pixel icons live in `PixelIcons`. Still to do: grid for screens other than the main menu, icons for installed apps.
4. Move colour and font choices into one Look data object and feed it to `NokiaSkin`.
5. A second skin (classic-Mac style), starting with the generic list, home and Now playing; games and calculator keep shared drawing.

## Open points

- Which screens use Left/Right for something other than the soft-key alias (text entry, games, Now playing seek/volume?) needs a short list before grid work.
- Pixel fonts blur on Android at sizes off the font's own grid; needs a check on the real phone, plus font licences before bundling.
- Prototype: `theme-lab` HTML mock-ups (owner has them) show the intended look; they are not in the repo.

## Slice 4a: the classic-Mac look (first cut)

A theme now carries a `skin` as well as its colours. The two new themes, "Classic Mac" and "Classic Mac dark" (Settings > Theme), draw
every screen inside one window on a dithered desktop:

- A menu bar across the top carries the status symbols. The window has a pinstriped title bar, and a close box appears when Back has
  somewhere to go (it reads `depth`, the length of the back stack). The two soft keys become push buttons under the window, the left one
  (the default action) with the heavier ring.
- Screens do not know about any of this. They still call `titleBar(...)` and `softKeys(...)`; in the Mac look those only record the
  title and labels, the content is clipped to the window, and the frame is drawn around it. So every screen, including games and
  Messages, works in both looks from day one.
- The main-menu grid, lists, Home and Now playing all come through unchanged. Held spectrum peaks use the theme's `accent` (red in the
  Mac themes, the ink colour in the Nokia ones). The Mac themes prefer Pixelify until the user picks a font.

Still to do for this look: icons beside list rows, a proper scroll bar in the window, a Mac-style Now playing layout, the Look data
object (so the parent app can set window style, accent and fonts one by one), and a skin of its own rather than a branch in `NokiaSkin`.
