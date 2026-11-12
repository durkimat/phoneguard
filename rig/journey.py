#!/usr/bin/env python3
"""Walk the real launcher on a phone or the emulator by key presses and check it behaves.

Debug builds of the launcher log what is on screen (tag PGState: screen, row, how many screens are behind it, layout, rows, look)
once per change, and never any row text. This script presses keys and reads that, so it tests the whole path a person takes:
the key mapping, the activity, the model and the draw.

  rig/journey.py walk [--max-depth N]   visit every screen it can reach without side effects; at each step check that
                                        Back returns to the same screen and row, that the red key goes home, and that nothing crashes
  rig/journey.py looks OUTDIR           cycle every Appearance setting and preset, screenshot Home, Menu and Settings for each,
                                        and put everything back as it was

Uses ANDROID_SERIAL (default emulator-5554). Run it on a test phone or the emulator, never on a child's phone: it presses keys.
"""
import os, re, subprocess, sys, time

SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
ADB = os.environ.get("ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
PKG = "io.github.durkimat.phoneguard"
EMU = SERIAL.startswith("emulator-")
KEYS = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "OK": 23, "MENU": 82, "BACK": 4, "CALL": 5, "END": 6}


def adb(*a, text=True, check=False):
    r = subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=text)
    return r.stdout if text else r.stdout


def parse(line):
    d = dict(re.findall(r"(\w+)=(\S+)", line))
    d["sel"] = int(d["sel"]); d["depth"] = int(d["depth"]); d["rows"] = int(d["rows"])
    return d


last = None


def press(*names, settle=0.45):
    """Press keys in order and return the state shown afterwards (the last one if nothing changed)."""
    global last
    adb("logcat", "-c")
    def one(n):
        if n == "END" and EMU:  # the emulator's system swallows the hang-up key; send what it sends on a phone: a home request
            return f"am start -a android.intent.action.MAIN -c android.intent.category.HOME -n {PKG}/.launcher.LauncherActivity >/dev/null"
        return f"input keyevent {KEYS[n] if n in KEYS else 7 + int(n)}"  # a digit is KEYCODE_0 (7) + n
    adb("shell", " ; ".join(one(n) for n in names))
    time.sleep(settle)
    lines = [l for l in adb("logcat", "-d", "-s", "PGState").splitlines() if "PGState" in l and "screen=" in l]
    if lines: last = parse(lines[-1])
    if os.environ.get("JOURNEY_VERBOSE"): print("  ", " ".join(names), "->", last and (last["screen"], last["sel"], last["depth"], last["rows"]))
    return last


def read_last():
    lines = [l for l in adb("logcat", "-d", "-s", "PGState").splitlines() if "screen=" in l]
    return parse(lines[-1]) if lines else None


def start():
    global last
    adb("shell", "input keyevent KEYCODE_WAKEUP")
    adb("shell", f"am start -n {PKG}/.launcher.LauncherActivity")
    time.sleep(1.5)
    last = read_last()
    if last is None: press("OK")  # nothing logged yet and nothing has changed: change something so the phone says where it is
    return press("END")


def ours():
    time.sleep(0.8)  # an app that was just opened takes a moment to take the focus
    return PKG + ".launcher.LauncherActivity" in adb("shell", "dumpsys window | grep -m1 mCurrentFocus")


def recover():
    """Something opened another app: come back and go home."""
    global last
    adb("shell", f"am start -n {PKG}/.launcher.LauncherActivity"); time.sleep(1.0)
    last = read_last()
    return press("END")


def fatals():
    out = adb("logcat", "-d", "-b", "crash")
    return [l for l in out.splitlines() if PKG in l and ("FATAL" in l or "ANR" in l)]


# Screens we press OK on every row of; everything else is visited and left (choosing there would change the phone).
DESCEND = {"HOME", "MENU", "SETTINGS", "APPEARANCE", "GAMES", "CALLLOG", "CONTACTS", "MESSAGES", "INBOX", "LOGSET"}


class Walk:
    def __init__(self, max_depth):
        self.max_depth = max_depth; self.seen = {}; self.problems = []; self.notes = []; self.edges = 0

    def problem(self, path, msg): self.problems.append(f"{' > '.join(f'{a}[{b}]' for a, b in path) or 'HOME'}: {msg}")

    def go_to(self, row):
        """Move the highlight to [row] and return the state."""
        s = last
        step = "RIGHT" if s["layout"] == "GRID" else "DOWN"
        n = (row - s["sel"]) % max(s["rows"], 1)
        return press(*([step] * n)) if n else s

    def replay(self, path):
        """Back to the state at the end of [path]: Home, then the rows that led there."""
        press("END")
        for _, row in path:
            self.go_to(row); press("OK")

    def visit(self, s, path):
        """Called with the phone showing [s] (reached by [path]); leaves it showing the same state."""
        key = (s["screen"], s["depth"])
        new = key not in self.seen
        self.seen.setdefault(key, path)
        if not new: return
        if s["depth"] > 0:  # the red key goes home from here, and the way back still works
            h = press("END")
            if h["screen"] != "HOME": self.problem(path, f"END left us on {h['screen']}")
            self.replay(path)
        if s["screen"] not in DESCEND or s["depth"] >= self.max_depth: return
        node = dict(last); rows = 1 if node["screen"] == "HOME" else node["rows"]  # Home has one way on: OK opens the menu
        for r in range(rows):
            self.go_to(r)
            t = press("OK"); self.edges += 1
            if t["screen"] == node["screen"] and t["depth"] == node["depth"]:
                if not ours():
                    self.notes.append(f"{node['screen']} row {r} opens another app (left alone)")
                    recover(); self.replay(path)
                continue
            self.visit(t, path + [(node["screen"], r)])
            b = press("BACK")
            if (b["screen"], b["depth"], b["sel"]) != (node["screen"], node["depth"], r):
                self.problem(path + [(node["screen"], r)], f"Back from {t['screen']} landed on {b['screen']}/{b['depth']} row {b['sel']}, expected {node['screen']}/{node['depth']} row {r}")
                self.replay(path)


def walk(max_depth):
    start()
    w = Walk(max_depth)
    w.visit(last, [])
    print(f"visited {len(w.seen)} screens in {w.edges} steps: {', '.join(sorted({k[0] for k in w.seen}))}")
    for n in w.notes: print("note:", n)
    bad = w.problems + [f"crash: {l}" for l in fatals()]
    for b in bad: print("PROBLEM", b)
    print("OK" if not bad else f"{len(bad)} problem(s)")
    return 1 if bad else 0


def shot(out, name):
    time.sleep(0.5)
    open(os.path.join(out, name + ".png"), "wb").write(subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def to_settings():
    press("END"); press("OK"); s = press("UP"); s = press("OK")
    if s["screen"] == "LOGSET":  # a phone whose menu has no Settings entry: it is the last row of the Logs list
        s = press(str(s["rows"]))
    assert s["screen"] == "SETTINGS", f"expected Settings, got {s['screen']} (is Settings the last menu entry?)"
    return s


def to_appearance():
    to_settings(); s = press("4")
    assert s["screen"] == "APPEARANCE", f"expected Appearance, got {s['screen']}"
    return s


def choose(key_row, option):
    """In Appearance: open the setting on [key_row], move to [option] and press OK."""
    w = Walk(0)
    w.go_to(key_row); s = press("OK")
    assert s["screen"] == "CHOICE", s
    w.go_to(option); return press("OK")


def looks(out):
    os.makedirs(out, exist_ok=True)
    adb("shell", "wm", "size", "480x712")  # the whole screen, no navigation bar
    try:
        start(); to_appearance()
        s = last; rows = s["rows"]  # Presets, Windows, Colour, Wallpaper, Shadow, Spectrum, 3 fonts (Mac look)
        press("END")
        def views(tag):
            press("END"); shot(out, tag + "-home")
            press("OK"); shot(out, tag + "-menu")
            press("UP"); press("OK"); shot(out, tag + "-settings"); press("END")
        for i in range(6):
            to_appearance(); choose(0, i); views(f"preset{i}")
        # each single setting, from the first preset's neighbour (the plain Mac look)
        for key_row, n, name in [(1, 3, "windows"), (2, 3, "colour"), (3, 4, "wall"), (4, 2, "shadow")]:
            for o in range(n):
                to_appearance(); choose(key_row, o); views(f"{name}{o}")
        print("screenshots in", out)
    finally:
        adb("shell", "wm", "size", "reset")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "walk": sys.exit(walk(int(sys.argv[sys.argv.index("--max-depth") + 1]) if "--max-depth" in sys.argv else 4))
    elif cmd == "looks": sys.exit(looks(sys.argv[2] if len(sys.argv) > 2 else "looks-out"))
    else: print(__doc__)
