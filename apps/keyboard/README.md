# Fast Keys (local.claude.keyboard)

Own e-ink keyboard (Android input method) for the HiBreak Pro, to replace SwiftKey for everyday typing (Oct 23 2026).
Plain Java, no Gradle: `./build.sh` → `out/keyboard.apk` (own local key in `keys/`). SwiftKey stays installed as the fallback.

**Layout** (same as SwiftKey): QWERTY with long-press symbols drawn on every key (numbers on the top row, `@ # & * - + = ( )`,
`_ $ " ' : ; /`), shift (tap = next letter, quick double tap = caps lock), backspace (hold = one whole word per step, ~0.2 s), `123` page, `{&=` page,
comma, space, period, enter (shows Go/Search/Send/Next/Done when the field asks for it).
- Long-press a letter: types its symbol (the letter typed on touch-down is replaced).
- Period: hold and slide left = `,` (18 dp), further left = `!` (80 dp), slide right = `?` (18 dp); the pop-up shows which.
- Long-press period: the suggestion strip turns into `, ! ? ' " - ; :`.
- Emoji key (🙂, where the comma was on the letter page; comma = period slide left, still on the symbol pages): emoji page,
  30 per page, ‹ › to page; page 1 = recently used. Long-press it (or comma on symbol pages): system keyboard picker.

**Typing help**: suggestion strip, no divider lines. Typed text is a real word → [completion] [**as typed**, e.g. Just] [completion];
otherwise [as typed] [best guess, bold if space will apply it] [second]. Prefix completion + one-edit
corrections (QWERTY neighbours, swapped letters, missed/double letters, missing apostrophe: dont → don't, im → I'm).
Typos while you type: completions also follow the letters with one likely typo fixed (`inxr` → incredible), and longer
words get two-typo fixes when one isn't enough (`neccesary` → necessary). Space applies only the bold middle word.
Words in progress: letters that already begin common words (`gre`, `comp`) count as a word being typed, so completions lead and
typo guesses stay back; a completion shown and not picked loses weight with each further letter, so longer words take over
(`gr` great → `grea` great/greatest). Common misspellings: `assets/fixes.txt` (3,671, built by `tools/make_fixes.py` from
Wikipedia's "Lists of common misspellings/For machines", **CC BY-SA 4.0**, plus a Fast Keys list of phone slips: ts → its,
jsut → just, thier → their, alot → a lot…) are applied on space even when the subtitle list contains the typo.
Space applies exactly the bold word the strip shows.
Common words win: a rare word (under 3,000 in the list, e.g. `tat`) one slip from a core word (50,000+, at least 200x as common)
counts as a typo (tat → that, whit → what, anf → and); backspace after the fix keeps and learns it. Keys pressed together
(second key down while the first is held, or under 80 ms apart) make that pair's swap ×6 and a stray extra key of the pair
as likely as a real slip.
Missed spaces: two or three words run together (`justwant` → just want, `howareyou` → how are you, `idontknow` → I don't know,
also `ofbthe` where a bottom-row letter took the space), and a half-typed second word (`canyo` → can you, `justw` → just want,
from the words that usually follow the first; long words like `toge` → together are left alone). Applied on space only when
every part is a common word. Key positions: each letter's tap point is kept, so typo guesses follow where the finger actually
landed (Gaussian, σ 0.55 key widths) instead of a fixed neighbour list; e.g. `gine` → fine, or gone when the I tap leans to O.
Fast typing (Oct 24 test, `touch-test-2026-10-24/`): the touch driver reports at 100 Hz (protocol A); the keyboard got 181
of 186 contacts, and the other 5 were taps in the suggestion strip. Most errors are neighbour slips: 25% of taps land in the outer fifth of a
key, with no steady offset. So corrections weigh swapped letters (×2) over stray extra keys (×0.4), use the previous word,
let tap positions steer two-typo fixes too, and treat rare 2–3 letter subtitle entries ("fx") as typos.
Touch logging for a new test: `DEBUG_TOUCH` in KeyboardView + `adb shell getevent -lt /dev/input/event2`.
Mac check of the engine: `tools/dictcheck.sh "word,word:dx,dy;dx,dy..."`.
Autocorrect only for unknown words, never for words you capitalised yourself; backspace right after a correction restores what
you typed and remembers it (also when you tap the as-typed slot). Autocapitalised sentences, `i` → `I`, double space → `. `,
`.` `!` `?` always add their space (not after a digit, so 3.5 stays; not in URL/email/password fields), a space you press
next is swallowed; a key pressed while the period/comma/emoji finger is still down makes that key type first (fast typing kept
"word ." from happening); punctuation after a picked suggestion pulls the space back (`word .` → `word. `). No passwords/URLs/emails suggestions.

**Next word**: with nothing typed, the strip predicts the next word from the previous one (sentence start → I / The / He,
`thank` → you, `I'm` → not / going / sure); middle = most likely, a tap adds it with a space. While typing, words that usually
follow the previous word rank up (`I w` → was). Table `assets/next.txt` (21.5k words × 8 next words) is built by
`tools/make_next.py` from Tatoeba's English sentences (`dict-source/tatoeba_eng_sentences.tsv.bz2`,
https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2, **CC BY 2.0 FR**, Tatoeba contributors);
Tatoeba's stock names (Tom, Mary…) are never predicted. Nothing is learned from your typing.

**Oct 25 additions** (each built as a stage in `out/stages/`, timed with `tools/speedtest.sh`; results in
`speed-stages-2026-10-25.txt`: 0.08–0.22 ms per key on every stage, no trend):
- Learning (`learned.txt` in the app's files): words you end with, your word pairs (predicted first once used twice),
  names you capitalise (shown capitalised, e.g. Caron). An unknown word is learned only when there is nothing to correct it to, or when you
  keep it (backspace after a fix, tapping your own spelling); used twice = at least "fairly common".
- Slide along the space bar: the cursor follows (one character per 0.35 key widths); the space that started it is undone.
- Long-press a suggestion: it is taken back and never suggested again.
- Long-press a vowel/n/c/s/y: the symbol as before, plus accented letters in the strip (tap = swap the symbol for it).
  Accented words are never autocorrected.
- Copied text (last 2 minutes) shows in the strip with 📋; one tap pastes it.
- Emoji page 🔍: type a word (heart, laugh, fire…), matches show in the strip (`assets/emoji_keywords.txt`, own keywords).
- Setup screen: number row, arrow keys beside space (hold repeats), key height 44–66 dp. Number and phone fields open a
  number pad. Every page keeps the same height, so switching never jumps.
- Before typing: an empty field predicts chat openers (I, Hey, Yeah…; your own usual first words once used twice), kept apart
  from new sentences after a period. URL bars (TYPE_TEXT_VARIATION_URI) get suggestions + learning but no autocorrect,
  capitals or auto-spaces; an empty one offers your usual starts, else www. / https://; picks add no space.
  Predictions also appear when a field opens before the word list has loaded.
- Sites: pressing Go in a URL bar saves the whole address (learned.txt "s" lines, scheme and trailing slash dropped); an empty
  bar offers sites you went to twice or more, typing offers the best matching site in the middle slot, and a tap replaces what
  you typed with it. Enter/Go also counts as ending a word for learning.
- Neighbour-key slips that make another real word ("kind if" → of, "depends in" → on): swapped only when that letter's tap
  landed near the neighbouring key (likelihood ≥ 0.6), the typed word doesn't fit after the previous word, and the neighbour
  word is among the 3 likeliest there. A tap near a border also lets mid-word guesses follow the other letter.
- Space no longer autocorrects (Oct 31, owner): the typed word stays; "i" still becomes "I". Setup screen
  "Fix typos when I press space" turns the old behaviour back on. The strip waits for a 150 ms pause in typing before it
  updates, so a fast burst is one panel update per key (letter + pop-up) instead of two.
- Fix: a split shown while typing ("justw" → just want) no longer loses its rank once all its letters are typed.

**Speed / e-ink**: GPU drawing with the key area cached in a hardware layer (drawn once per page/shift change; a keypress frame
composites one texture). Calls to the app (commitText ~1.6 ms each) run on their own `ime-ic` thread (`IcQueue`, display priority, in order);
suggestions are computed just after the keypress and merged across fast keys (space/picks flush them first). The
process already sits in the top-app cpuset/schedtune group while shown; the controller's Fast keyboard mode keys off IME insets
(any keyboard). Do NOT switch to software drawing (`hardwareAccelerated=false`): the HiBreak's vendor HWC then skips every
keyboard frame ("No dirty region", dirty ratio 0) and the panel freezes while typing (Oct 23). The pop-up hides
90 ms after the last finger lifts, so fast typing moves it instead of hide+show. Real words skip the typo search. Per-key cost
(`adb logcat -s FastKeys`, every 100 keys): ~1.7 ms, of which ~1.6 ms is Android's commitText to the app, ~0.1 ms suggestions.
 text is committed directly (no composing underline for the app to redraw), the word and text before the cursor
are tracked locally (no synchronous app round trip per key), letters act on touch-down, no animations, no press highlight.
Key pop-up (owner ask): one view made once, moved with translation (no layout pass) and redrawn only when its letter changes;
shown after the text is sent, hidden when the last finger lifts; holding shows the symbol, period slide shows `,`/`!`. Keys are drawn once per page switch; shift, enter and each suggestion slot are separate small views, so a keypress only
redraws the slots whose text changed (and the shift key when its state changes). Letter labels turn capital while shift is on
(e.g. at a sentence start) and back to lower case after, so the key area redraws only on those two changes.

**Dictionary**: `assets/words.txt`, built by `tools/make_dict.py` from `dict-source/en_50k_opensubtitles2018.txt` =
hermitdave/FrequencyWords `content/2018/en/en_50k.txt` (https://github.com/hermitdave/FrequencyWords), **CC-BY-SA 4.0**, from
OpenSubtitles 2018 (OPUS). Script adds a hand-written contraction table and texting words (lol, idk, btw…), drops contraction
fragments and ~2600 subtitle typos (macOS `/usr/share/dict/words` used only as a build-time filter). 44k words, ~45 ms to load.

**Files**: `FastIME` (editor logic), `KeyboardView` (pages, touch, drawing), `StripView` (suggestions), `Dictionary`,
`SetupActivity` (launcher icon: turn on / switch keyboard / test box).

**Install**: `./install.sh` (installs, then re-applies max performance: ART `speed` compile — every install drops it —
battery-optimisation whitelist, standby bucket active, background allowed). The key thread also asks for display priority.
First time only: `adb install -r out/keyboard.apk`, `adb shell ime enable local.claude.keyboard/.FastIME`, pick it with the picker
(or `ime set local.claude.keyboard/.FastIME`). Back to SwiftKey: `adb shell ime set com.touchtype.swiftkey/com.touchtype.KeyboardService`.

## Public build

`./build.sh` is unchanged (local key in `keys/`, output in `out/`). For a public release:
`KS=/path/to/keyboard-release.p12 KS_PASS=… ./build.sh --release` → `out-release/keyboard.apk`, signed with alias `release`.
Release mode refuses to build with `DEBUG_TOUCH` on and packs `THIRD-PARTY.md` (data licences) into the APK as an asset.
The APK asks for no permissions; nothing typed is logged (logcat `FastKeys` only carries per-key timing every 100 keys);
learned words stay in the app's private files (`allowBackup=false`). `install.sh` and `tools/speedtest.sh` take the phone's
serial from `ANDROID_SERIAL` or a one-line `device.local` file. `.gitignore` lists what stays out of the public repo
(keys, build output, `device.local`, the dev logs and touch/typing tests). The word lists in `assets/` are CC BY-SA 4.0 /
CC BY 2.0 FR adaptations — see `THIRD-PARTY.md`.
