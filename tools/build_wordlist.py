#!/usr/bin/env python3
"""Builds controller/app/src/main/assets/words/words-en-gb.txt, the predictive-text word list (most likely first).

Ranking: how often words occur in film and TV dialogue (hermitdave/FrequencyWords en_50k.txt, from OpenSubtitles 2018,
CC-BY-SA-4.0), because that is closest to how people text. Spelling filter and British forms: SCOWL en_GB-ise expanded with
`unmunch` (forms.txt). Words that dialogue lacks but the web-ranked list (Norvig count_1w.txt) has come after, in that order.
A few texting words and family names are placed by hand. Strong swear words are left out of the suggestions.

Usage: build_wordlist.py en_50k.txt forms.txt words-en-gb.txt [web-ranked-previous-list.txt]
"""
import re, sys

fw_path, forms_path, out_path = sys.argv[1:4]
prev_path = sys.argv[4] if len(sys.argv) > 4 else None

forms = set(l.rstrip("\n") for l in open(forms_path, encoding="utf-8"))
lower = {w for w in forms if re.fullmatch(r"[a-z]+", w)}
cap = {w for w in forms if re.fullmatch(r"[A-Z][a-z]+", w)}

# not suggested: it is a child's phone (they can still be typed with multi-tap)
BLOCK = set("""fuck fucking fucked fucker fuckin motherfucker fucks shit shitty bullshit shite bitch bitches bastard cunt cock cocks dick
dicks pussy whore slut wanker twat prick piss pissed arse arsehole asshole ass nigger nigga fag faggot retard rape raped
rapist sex sexy porn tits boobs horny cum""".split())

# words and contractions that the dialogue list lacks (it splits "don't" into "don" and "'t"), placed by hand near words of
# similar everyday weight: (word as shown, rank to put it at)
HAND = [("I'm", 12), ("it's", 18), ("don't", 25), ("that's", 40), ("you're", 55), ("I'll", 60), ("can't", 65), ("I've", 70), ("didn't", 80),
        ("what's", 85), ("let's", 90), ("he's", 95), ("she's", 100), ("we're", 105), ("there's", 110), ("they're", 115), ("ok", 120),
        ("won't", 125), ("doesn't", 135), ("isn't", 130), ("wasn't", 140), ("I'd", 150), ("mum", 150), ("couldn't", 170), ("wouldn't", 175),
        ("we'll", 180), ("you'll", 185), ("you've", 190), ("haven't", 195), ("aren't", 200), ("bye", 200), ("lol", 220), ("we've", 240),
        ("omg", 260), ("here's", 280), ("who's", 300), ("hiya", 300), ("yep", 310), ("yup", 320), ("shouldn't", 330), ("how's", 400),
        ("they'll", 430), ("where's", 420), ("mummy", 420), ("hasn't", 450), ("nan", 450), ("weren't", 460), ("gran", 470), ("granny", 520),
        ("he'll", 520), ("daddy", 540), ("she'll", 560), ("okay", 75), ("pls", 900), ("thx", 950), ("cos", 700), ("tho", 750), ("xx", 1000)]

# texting abbreviations and slang kids use (rank = where they sit among everyday words). Ones that stand for swear words
# (wtf, fml, stfu, lmfao, af, jfc, omfg, ffs) are deliberately not here.
HAND += [("btw", 280), ("idk", 285), ("tbh", 330), ("omw", 340), ("brb", 360), ("lmao", 380), ("rn", 400), ("np", 700), ("ty", 650),
         ("plz", 910), ("jk", 800), ("imo", 1200), ("imho", 2500), ("smh", 900), ("ngl", 950), ("irl", 1100), ("fyi", 1200), ("bff", 1000),
         ("ily", 1000), ("wyd", 1100), ("hbu", 1300), ("lmk", 1000), ("gtg", 1200), ("ttyl", 1500), ("bc", 1800), ("cuz", 1000),
         ("coz", 800), ("ikr", 1400), ("bruh", 1100), ("fam", 1200), ("hbd", 2500), ("gn", 2500), ("gm", 2500), ("luv", 1300),
         ("fwiw", 2500), ("tmi", 3000), ("nvm", 1000), ("idc", 1300), ("atm", 1400), ("fomo", 3500), ("yolo", 3000), ("ofc", 1500),
         ("obvs", 3500), ("probs", 1800), ("convo", 2500), ("sesh", 4000), ("soz", 1500), ("thnx", 1500), ("cba", 1800), ("ik", 3500),
         ("wbu", 2500), ("hmu", 1800), ("dm", 2500), ("bday", 1200), ("dw", 2500), ("kk", 1500), ("yh", 1800), ("yeh", 1500),
         ("heya", 700), ("cya", 800), ("laters", 3000), ("gl", 3000), ("gg", 2500), ("afk", 4000), ("bbs", 5000), ("omds", 4000),
         ("xo", 1800), ("xoxo", 1500), ("rofl", 2500), ("sis", 1500), ("bro", 900), ("gonna", 100), ("sorry", 138), ("pm", 4000),
         ("tmrw", 2500), ("tmr", 2500)]

CONTR = {"cant": "can't", "wont": "won't", "dont": "don't", "im": "I'm", "ive": "I've", "ill": "I'll", "id": "I'd"}
SUFFIX = re.compile(r"^([a-z]+)'(s|t|re|ve|ll|d|m)$")

def shape(w):
    """The word as it should be shown, or None if it is not a word we want."""
    if w == "i":
        return "I"
    if w in BLOCK:
        return None
    m = SUFFIX.match(w)
    if m:
        base, suf = m.groups()
        if suf == "t":  # don't, can't, isn't, shan't, won't
            root = base[:-1] if base.endswith("n") else None
            ok = root in lower or root in ("ca", "wo", "sha")
        else:
            ok = base in lower or base == "i"
        if not ok:
            return None
        return ("I" + w[1:]) if w.startswith("i'") else w
    if not re.fullmatch(r"[a-z]+", w):
        return None
    if w in lower and len(w) > 1 or w in ("a",):
        return w
    if w.capitalize() in cap and len(w) > 1:
        return w.capitalize()
    return None

out, seen = [], set()
def add(w):
    s = shape(w)
    if s and s.lower() not in seen and s.lower() not in BLOCK:
        seen.add(s.lower()); out.append(s)

for line in open(fw_path, encoding="utf-8"):
    add(line.split()[0].lower())
if prev_path:  # words dialogue lacks, in their old (web) order
    for line in open(prev_path, encoding="utf-8"):
        add(line.strip().lower())
out = out[:50000]
for w, pos in sorted(HAND, key=lambda x: x[1]):
    if w in out[:pos]:
        out = [x for i, x in enumerate(out) if i < pos or x.lower() != w.lower()]  # keep the good one, drop later twins
        continue  # already ranks at least as well
    out = [x for x in out if x.lower() != w.lower()]  # also drops capitalised twins like "Rn"
    out.insert(min(pos, len(out)), w)
open(out_path, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(len(out), "words")
