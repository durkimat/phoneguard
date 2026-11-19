#!/usr/bin/env python3
"""The boot chime: a short rising four-note phrase, 16-bit mono PCM at 44.1 kHz (the format Android's boot animation plays from part0/audio.wav).
   python3 gen_chime.py boot-chime.wav"""
import math, struct, sys, wave
RATE = 44100
NOTES = [(523.25, 0.16), (659.25, 0.16), (783.99, 0.16), (1046.50, 0.60)]  # C5 E5 G5 C6
out = []
for f, dur in NOTES:
    n = int(RATE * dur)
    for i in range(n):
        t = i / RATE
        env = min(1.0, i / (0.01 * RATE)) * math.exp(-4.0 * t / dur)          # quick attack, soft decay
        s = math.sin(2 * math.pi * f * t) + 0.25 * math.sin(2 * math.pi * 2 * f * t) # a little brightness
        out.append(0.45 * env * s / 1.25)
out += [0.0] * int(RATE * 0.15)
with wave.open(sys.argv[1] if len(sys.argv) > 1 else "boot-chime.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in out))
