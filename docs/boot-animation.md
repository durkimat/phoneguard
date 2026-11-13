# Boot animation (2026-11-13)

Character: a cartoon of the owner (grey spiky hair, glasses, light stubble, orange scarf, brown duffel coat). Sequence 1: walks on from the right in a follow spot, turns, dances Y-M-C-A, turns, walks off left. Sequence 2: walks on from the left with a magnifying glass, magnifies one eye (hazel green) which looks around, turns, walks off right. 35 s loop, 15 fps, 480 x 640.

## Files (build/bootanimation/)
- `gen.py`: the drawing code (Python). `rig.js`: the same drawing in JavaScript for the browser tweaker. `parity_test.py` checks both draw identical operations (needs `pip install quickjs`). Keep them in step: any change goes in both, then run the test.
- `configurator.tpl.html` + `make_configurator.py` build `mockup-tweaker.html` (sliders, live preview). "Settings" text from the page can be saved as JSON and passed to the generator with `--params`.
- `settings-draft8.json`: the owner's current look.
- Commands: `gen.py png DIR T... --params S.json` (stills), `gen.py bootzip OUT.zip [W H] --params S.json` (the package).

## Package
`bootanimation.zip`: `desc.txt` plus `part0/0000.png ... 0524.png`, stored (not compressed), 8-bit PNGs. About 12.6 MB.
```
480 640 15
f 0 0 part0 15 #070a0f
```
One part of type `f`, count 0 (loops until boot finishes), 15 fade frames, background `#070a0f`.

## Fade out when the phone is ready
Android's `f` part type: when boot completes, the part keeps playing for the given number of frames while it fades to the background colour, then the animation ends and the lock screen or launcher appears. The fade is done by Android at run time (it is not drawn into the frames). Length is the `fadeSec` setting (1.0 s = 15 frames). The tweaker's "Phone is ready" button previews it.

## Not yet verified
- Real screen size (assumed 480 x 640 from the LK logo images). Check `wm size` on the phone and rebuild at that size.
- That our Android 14 build honours the `f` type and fade (the spec says yes; test on the phone).
- Where the zip goes in the image (`/product/media/bootanimation.zip` or `/system/media/`) and the effect of 12.6 MB on partition size. Smaller options if needed: fewer colours, 10 fps, shorter loop.
- How long the real boot lasts, so how much of the 35 s loop is seen.
- LK still shows the Qin logo and the unlocked warning first; replacing the logo needs the `logo` partition (separate, low risk), the warning needs an LK patch (see boot notes).
