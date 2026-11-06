#!/usr/bin/env python3
"""Builds assets/next.txt: for each word, the 8 words most often typed right after it (3 are shown, all 8 rank completions) ("^" = start of a sentence).
Also two- and three-word contexts, joined with "_" ("at_the", "^_how_are"), kept when seen at least 20 times and
when they rank differently from the shorter context they fall back to.

Source: dict-source/tatoeba_eng_sentences.tsv.bz2 = Tatoeba English sentences
(https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2), CC BY 2.0 FR, Tatoeba contributors.
Only words in assets/words.txt are kept. Tatoeba's stock names (Tom, Mary...) are never predicted.
"""
import bz2, collections, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "dict-source", "tatoeba_eng_sentences.tsv.bz2")
WORDS = os.path.join(ROOT, "assets", "words.txt")
OUT = os.path.join(ROOT, "assets", "next.txt")
NAMES = {"tom", "mary", "john", "alice", "jim", "ken", "bob", "tony", "mike", "jane", "taro", "dan", "sami", "layla",
         "jack", "lucy", "bill", "betty", "judy", "linda", "emily", "fadil", "dania", "ziri", "yanni", "skura", "rima"}
MIXUP_WORDS = {"its", "it's", "your", "you're", "their", "there", "they're", "then", "than"}   # as Dictionary.MIXUPS
TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?")

known = set()
with open(WORDS, encoding="utf-8") as f:
    for line in f:
        known.add(line.split()[0])

pairs = collections.defaultdict(collections.Counter)
longer = collections.defaultdict(collections.Counter)   # "a_b" / "a_b_c" -> next word counts
with bz2.open(SRC, "rt", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        # Split the sentence at clause ends so ". " starts a new context too.
        for clause in re.split(r"[.!?;:]+", parts[2].lower().replace("’", "'")):
            toks = TOKEN.findall(clause)
            if not toks:
                continue
            prev = "^"
            ctx = ["^"]   # the words before t in this clause, known words only (an unknown word starts over)
            for t in toks:
                if t in known and (prev == "^" or prev in known) and t not in NAMES:
                    pairs[prev][t] += 1
                for n in (2, 3):
                    if len(ctx) >= n:
                        key = ctx[-n:]
                        if t in known and t not in NAMES and not NAMES.intersection(key):
                            longer["_".join(key)][t] += 1
                prev = t
                ctx = ctx + [t] if t in known else []

n = 0
with open(OUT, "w", encoding="utf-8") as f:
    for prev in sorted(pairs):
        top = [w for w, c in pairs[prev].most_common(8) if c >= 2]
        if top and sum(pairs[prev].values()) >= 5:
            f.write(prev + " " + " ".join(top) + "\n")
            n += 1
    tops = {}
    for prev in pairs:
        tops[prev] = [w for w, c in pairs[prev].most_common(8) if c >= 2]
    m = 0
    for key in sorted(longer, key=lambda k: (k.count("_"), k)):
        c = longer[key]
        if sum(c.values()) < 20:
            continue
        top = [w for w, k in c.most_common(8) if k >= 3]
        shorter = key.split("_", 1)[1]
        # Nothing the shorter context doesn't already say; kept anyway after a mix-up word (its/it's, your/you're...),
        # where the keyboard needs to know the pair is common.
        if not top or (top[:3] == tops.get(shorter, [])[:3] and key.split("_")[-1] not in MIXUP_WORDS):
            continue
        tops[key] = top
        f.write(key + " " + " ".join(top) + "\n")
        m += 1
print(f"{n} one-word + {m} longer contexts -> {OUT}", file=sys.stderr)
