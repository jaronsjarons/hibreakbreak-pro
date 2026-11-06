# Third-party data in Fast Keys

The Fast Keys source code is licensed under the Apache License 2.0. The word lists in `assets/` are derived from the
data sets below and stay under those data sets' own licences. A copy of this file is packed into the release APK as
`assets/THIRD-PARTY.md`.

## assets/words.txt — word frequency list

- Source: FrequencyWords by Hermit Dave, file `content/2018/en/en_50k.txt`
  (https://github.com/hermitdave/FrequencyWords), which is built from the OpenSubtitles 2018 corpus
  (distributed by OPUS, https://opus.nlpl.eu/).
- Licence of the list: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0),
  https://creativecommons.org/licenses/by-sa/4.0/
- Changes: filtered and edited by `tools/make_dict.py` — limited to a-z words, contraction fragments and about 2,600
  likely subtitle typos and run-together words removed, a hand-written table of contractions and texting words added,
  trimmed to the 45,000 most frequent entries. (macOS `/usr/share/dict/words` is used only as a build-time filter;
  nothing from it is included.)
- `assets/words.txt` is therefore an adaptation and is distributed under CC BY-SA 4.0.

## assets/fixes.txt — common misspellings

- Source: Wikipedia, "Wikipedia:Lists of common misspellings/For machines", by Wikipedia contributors
  (https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines).
- Licence: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0),
  https://creativecommons.org/licenses/by-sa/4.0/
- Changes: built by `tools/make_fixes.py` — only fixes whose target is in `assets/words.txt` and whose typo is not
  itself a common word are kept; multi-word fixes are dropped; a list of phone-keyboard slips written for Fast Keys
  (in `tools/make_fixes.py`) is added.
- `assets/fixes.txt` is therefore an adaptation and is distributed under CC BY-SA 4.0.

## assets/next.txt — next-word predictions

- Source: Tatoeba English sentences, by Tatoeba contributors
  (https://tatoeba.org, export https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2).
- Licence: Creative Commons Attribution 2.0 France (CC BY 2.0 FR), https://creativecommons.org/licenses/by/2.0/fr/
- Changes: built by `tools/make_next.py` — word-pair counts only (no sentences are included): for each word, the
  8 words that most often follow it, limited to words in `assets/words.txt`, with Tatoeba's stock names removed.

## assets/emoji_keywords.txt

Written for Fast Keys; same licence as the source code.
