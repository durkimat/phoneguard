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
- The main-menu grid, lists, and Home come through unchanged. Held spectrum peaks use the theme's `accent` (red in the
  Mac themes, the ink colour in the Nokia ones). The Mac themes prefer Pixelify until the user picks a font.

Slice 4b finished the look: list rows carry pixel icons (main menu) and a shortcut digit at the right, long lists get a window scroll
bar, Now playing is its own Mac layout (ruled display, slider, analyser inset, three push buttons), notices are an alert box, and the
soft-key buttons lost their Nokia arrows. The grid, lists, Home and Settings are the same screens as before, framed.

## Slice 5: the look as data, and the skins apart

- **`Look`** (`Look.kt`) is the phone's look as plain data: theme, font, main-menu style and an optional accent. The theme decides the
  skin (`Theme.skin`). The phone keeps its own choices (Settings); a parent can fix any of them from Home Assistant, and a fixed
  setting wins for that field only (`Look.resolve`). Picking something the parent holds saves the phone's own choice but the screen
  stays as the parent set it, with the notice "Set by a parent". Unknown ids from HA are ignored.
- **The path from HA:** the card's new Look section (v0.30.0) calls `set_child` with `look: {theme, font, menu_style, accent}`;
  `hub.update_child` checks it (`schedule.clean_look`), `build_config` sends it as `look` (`menuStyle` on the wire), and `Config.parse`
  reads it into `ParentLook`. The launcher, call screen, volume bar, lock-screen cover and camera all read `Look.current`.
- **Skins:** `PaintedSkin` draws each screen's content; a `Chrome` draws what surrounds it and may take over the title, soft keys,
  list rows, scroll bar, player, notices and message boxes. `NokiaSkin` is `PaintedSkin(NokiaChrome)` (every hook left at its plain
  default) and `MacSkin` is `PaintedSkin(MacChrome())` in `MacSkin.kt`. The launcher swaps the skin live when the look needs another.
- A new look therefore means one new `Chrome`, with no change to the navigation or to any screen.

## Slice 6: Appearance, and the walkers that test navigation

**Appearance** (Settings > Appearance, the fourth row). Everything in the owner's theme-lab mock-up is a setting now:

| Setting | Choices | Where it lives |
|---|---|---|
| Presets | six looks that set everything below in one go | `Presets` in `Style.kt` |
| Windows (Mac look) | Stripes (System 7), Platinum (OS 8), Flat | `Style.windows` |
| Colour (Mac look) | Mono, Red, Colourful | `Style.colour` |
| Wallpaper (Mac look) | Dither, Plain, Blue, Green | `Style.wall` |
| Shadow (Mac look) | Hard, None | `Style.shadow` |
| Spectrum | Red peaks, Same colour, Gradient, No peaks | `Style.bars` |
| Clock, Text, Title fonts | any of 19 typefaces, or the one chosen under Font | `Style.fontDisplay/Text/Label` |
| Main menu (Settings > Menu style) | List, Grid, List without icons | `MenuStyle` |

`Style.kt` holds the data (`Style`, `Choices`, `Presets`, `Fonts`); `Look` carries a `Style`; a parent can hold any field from HA (config `look`: `windows`, `colour`, `wall`, `shadow`, `bars`, `fontDisplay`, `fontText`, `fontLabel`). The Mac-only rows are hidden when the theme is not a Mac one. The painted skin picks a typeface per role (`DISPLAY` the clock, `TEXT` the default, `LABEL` titles and soft keys); `Chrome` gained `selection` and `iconFill` so a chrome can colour the highlight and the icons. The 17 new typefaces are SIL OFL (`assets/fonts/OFL-NOTICE.txt`), converted from the mock-up's WOFF2 to TrueType.

The ids exist in four places (the phone, the integration's `schedule.py`, the card's `PG_LOOK`, the Settings screens). `ha/tests/test_hub.py` reads `Style.kt` and `LauncherModel.kt` and fails if the integration or the card drifts from the phone.

**Navigation tests, in three layers**
1. `NavigationBackTest`, `NavigationDataTest`: hand-picked paths pinned to a golden list of where Back goes.
2. `JourneyTest`: breadth first over every screen reachable by key presses, in seven setups (list and grid main menus, WhatsApp on, off and unlinked, three themes). Checks no key crashes, the highlight is always on the list, Back and End always reach Home, Back undoes a step in on the same row, a number key equals Down + OK, and every screen is reachable. A new screen with no route in, or a Back that strands you, fails here.
3. `rig/journey.py walk`: the same idea on the real app. Debug builds log what is on screen to logcat (`PGState`: screen, row, depth, layout, row count, look; never any row text) and the script presses real keys on the emulator or a test phone, visiting every screen it can reach without side effects. It catches what the model tests cannot: key mapping, the activity, other activities that take over the screen, the home request the red key sends. `rig/journey.py looks OUT` cycles every preset and Appearance choice and screenshots Home, Menu and Settings for each.
