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
2. Replace the `*From` flags with a back stack, family by family, keeping the pinned behaviour. **Done so far:** Settings (Theme, Font, Typing, Speaker, Battery), Notes, Weather, Games, Apps, Calculator, the dialler (dial pad, calls, contacts, one `dialler` flag) and Music (Music menu, "Change source"); the flags `settingsFrom`, `notesFrom`, `weatherFrom`, `callsFrom`, `contactsFrom`, `dialFrom`, `musicFromHome` and `nowFromHome` are gone. **Still on flags:** messages (`homeEntry`, `startWrite(back)`), WhatsApp pairing (`waPairBack`), radio results (`resFromFind`) and Connections/Wi-Fi/Bluetooth, which return to their parents with hard-coded rows.
3. Add layout kinds and grid movement; main menu as a grid behind a Look setting, list as the default. **Done:** `Layout` (LIST, GRID, CUSTOM) comes from the model; Settings > Menu style (List or Grid, saved as `menu_style`); on the grid the D-pad moves in four directions (Left and Right stop being soft-key aliases there), more than three rows scroll vertically, digits jump, OK or the left key opens, the right key goes home; the chosen entry's name is drawn above the soft keys; touch picks a tile. Sixteen 16 x 16 pixel icons live in `PixelIcons`. Still to do: grid for screens other than the main menu, icons for installed apps.
4. Move colour and font choices into one Look data object and feed it to `NokiaSkin`.
5. A second skin (classic-Mac style), starting with the generic list, home and Now playing; games and calculator keep shared drawing.

## Open points

- Which screens use Left/Right for something other than the soft-key alias (text entry, games, Now playing seek/volume?) needs a short list before grid work.
- Pixel fonts blur on Android at sizes off the font's own grid; needs a check on the real phone, plus font licences before bundling.
- Prototype: `theme-lab` HTML mock-ups (owner has them) show the intended look; they are not in the repo.
