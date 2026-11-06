#!/usr/bin/env python3
"""Builds assets/words.txt ("word count" per line, most frequent first) for the keyboard.

Source: dict-source/en_50k_opensubtitles2018.txt from hermitdave/FrequencyWords
(content/2018/en/en_50k.txt, CC-BY-SA 4.0, derived from OpenSubtitles 2018 via OPUS).
That list splits contractions ("don" + "t"), so contractions come from the table below,
and the apostrophe-less forms that only exist as typos of them are dropped.
Subtitle typos ("helo", "tommorow") and run-together pairs ("ofthe") are dropped too: a rare word (< 3000) one edit away from a word
60x more common is removed unless macOS /usr/share/dict/words (Webster's 2nd, public domain) lists it.
That file is only a build-time filter; nothing from it is bundled.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "dict-source", "en_50k_opensubtitles2018.txt")
OUT = os.path.join(ROOT, "assets", "words.txt")
LIMIT = 45000

# Contraction -> approximate count (same scale as the source: "the" ~ 22.7M).
CONTRACTIONS = {
    "don't": 4100000, "i'm": 4000000, "it's": 3600000, "that's": 1900000, "can't": 1500000,
    "you're": 1400000, "i'll": 1000000, "didn't": 1000000, "what's": 900000, "i've": 800000,
    "let's": 700000, "he's": 700000, "there's": 650000, "doesn't": 500000, "isn't": 500000,
    "won't": 450000, "we're": 450000, "she's": 400000, "wasn't": 350000, "they're": 350000,
    "i'd": 350000, "you've": 250000, "couldn't": 220000, "haven't": 220000, "aren't": 200000,
    "you'll": 200000, "we'll": 180000, "wouldn't": 170000, "where's": 150000, "shouldn't": 100000,
    "we've": 100000, "who's": 100000, "you'd": 100000, "it'll": 80000, "here's": 80000,
    "they'll": 50000, "hasn't": 50000, "weren't": 50000, "they've": 50000, "how's": 45000,
    "ain't": 45000, "hadn't": 40000, "that'll": 30000, "he'll": 30000, "she'll": 25000,
    "he'd": 25000, "she'd": 20000, "they'd": 20000, "we'd": 20000, "y'all": 15000,
    "c'mon": 30000, "o'clock": 40000, "would've": 30000, "could've": 25000, "should've": 25000,
    "what'll": 5000, "there'll": 5000, "who'd": 5000, "mustn't": 5000, "needn't": 2000,
}
DROP = {
    "don", "didn", "doesn", "isn", "wasn", "couldn", "shouldn", "wouldn", "aren", "haven",
    "hasn", "hadn", "weren", "ain", "mustn", "needn", "ll", "ve", "re", "dont", "im", "cant",
    "thats", "didnt", "doesnt", "isnt", "wont", "youre", "ive", "theyre", "wasnt", "couldnt",
    "shouldnt", "wouldnt", "arent", "havent", "hasnt", "hadnt", "werent", "whats", "wheres",
    "theres", "heres", "youll", "youve", "theyll", "weve", "itll", "aint", "hes", "shes",
    "youd", "theyve", "cmon", "mr", "mrs", "dr", "ms", "st", "th", "nd", "rd",
}
# Old-fashioned words (owner, Nov 2026): never suggested or kept as typed. Removed after the typo filter, which still
# uses them (so "yeol" stays dropped as a slip of "ye"). Still-current formal words stay (hence, whilst,
# amongst, behold, ought, mine, art).
ARCHAIC = {
    "tis", "twas", "thence", "whence", "thou", "thee", "thy", "thine", "thyself", "ye", "hath", "doth", "dost", "shalt",
    "wilt", "hast", "hadst", "didst", "wast", "wert", "wouldst", "couldst", "shouldst", "canst", "knowest", "hither",
    "thither", "whither", "wherefore", "whereupon", "hitherto", "thereof", "betwixt", "twixt", "ere", "oft", "nay", "yea",
    "methinks", "methought", "forsooth", "prithee", "anon", "alas", "unto", "mayhap", "perchance", "verily", "naught",
    "nought", "nigh", "yon", "yonder", "lo", "ay", "saith", "begone", "hark", "fie", "quoth", "tarry", "morrow", "avast",
    "maketh", "giveth", "taketh", "cometh", "goeth", "doeth", "sayeth", "leadeth", "believeth", "runneth", "liveth",
    "shan",
}
KEEP_SINGLE = {"a", "i"}
# Texting words that subtitles barely have (forced in after the typo filter).
EXTRA = {
    "lol": 60000, "haha": 30000, "omg": 20000, "btw": 20000, "idk": 20000, "thx": 10000, "lmao": 10000,
    "wtf": 10000, "pls": 10000, "tbh": 5000, "hahaha": 5000, "imo": 3000, "brb": 3000, "plz": 3000,
    "np": 3000, "ttyl": 2000, "wifi": 5000, "app": 20000, "apps": 8000, "email": 30000, "emails": 8000,
    "online": 30000, "texted": 5000, "texting": 6000,
}

words = {}
with open(SRC, encoding="utf-8") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0], int(parts[1])
        if not re.fullmatch(r"[a-z]+", w) or w in DROP:
            continue
        if len(w) == 1 and w not in KEEP_SINGLE:
            continue
        words[w] = max(words.get(w, 0), c)
for w, c in CONTRACTIONS.items():
    words[w] = max(words.get(w, 0), c)

A = "abcdefghijklmnopqrstuvwxyz"
def edits(w):
    n = len(w)
    for i in range(n):
        yield w[:i] + w[i + 1:]
        if i + 1 < n:
            yield w[:i] + w[i + 1] + w[i] + w[i + 2:]
        for c in A:
            yield w[:i] + c + w[i + 1:]
    for i in range(n + 1):
        for c in A:
            yield w[:i] + c + w[i:]

try:
    with open("/usr/share/dict/words", encoding="utf-8") as f:
        real = {l.strip().lower() for l in f}
except OSError:
    real = None
if real:
    typos = [w for w, c in words.items() if c < 3000 and len(w) >= 3 and w not in real
             and any(e != w and words.get(e, 0) > c * 60 for e in edits(w))]
    for w in typos:
        del words[w]
    print(f"dropped {len(typos)} likely typos", file=sys.stderr)
    # Run-together subtitle junk ("ofthe", "thankyou"): much rarer than both of its halves.
    def part(p):
        return (len(p) > 1 or p in ("a", "i")) and words.get(p, 0) >= 2000
    joined = [w for w, c in words.items() if w not in real and w not in CONTRACTIONS
              and any(part(w[:i]) and part(w[i:]) and c * 20 < min(words[w[:i]], words[w[i:]]) for i in range(1, len(w)))]
    for w in joined:
        del words[w]
    print(f"dropped {len(joined)} run-together words", file=sys.stderr)

for w, c in EXTRA.items():
    words[w] = max(words.get(w, 0), c)

for w in ARCHAIC:
    words.pop(w, None)

ranked = sorted(words.items(), key=lambda kv: (-kv[1], kv[0]))[:LIMIT]
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    for w, c in ranked:
        f.write(f"{w} {c}\n")
print(f"{len(ranked)} words -> {OUT}", file=sys.stderr)
