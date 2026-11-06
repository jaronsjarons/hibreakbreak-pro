package local.claude.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.List;

/**
 * Fast keyboard for the HiBreak e-ink phone. Text is committed directly (no composing span, so the app doesn't
 * redraw an underline on every key); the current word and the text just before the cursor are tracked locally
 * so a keypress needs no synchronous round trip to the app.
 */
public final class FastIME extends InputMethodService implements KeyboardView.Listener, StripView.Listener {
    private static final String SEPARATORS_AUTOCORRECT = " .,!?;:\n";
    private static final String PUNCT_MENU = ", ! ? ' \" - ; :";
    private static final float MIN_CORRECTION = 2000f;

    private final Dictionary dict = new Dictionary();
    private final IcQueue icq = new IcQueue();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private KeyboardView keyboard;
    private StripView strip;

    // Editor state, tracked locally.
    private final StringBuilder word = new StringBuilder();      // current word, as typed (with case)
    private final StringBuilder before = new StringBuilder();    // up to 64 chars before the cursor
    private int pos, selStart, selEnd;
    private final int[] expected = new int[32];
    private int expectedN;
    private boolean beforeKnown;

    private int shift = KeyboardView.SHIFT_OFF;
    private boolean shiftAuto;           // current SHIFT_ONE came from autocap
    private long lastShiftTap;
    private boolean wordManualCap;       // first letter of the word was shifted by the user, not autocap

    private boolean autoCap, suggestionsOn, autoCorrectOn, numericStart, urlField;
    // Incognito (Oct 30): the app marked this field private (IME_FLAG_NO_PERSONALIZED_LEARNING, set by Chrome and
    // Firefox in incognito/private tabs). Suggestions still work, but nothing typed here is learned or saved.
    private boolean incognito;
    // Soul browser doesn't flag its private tabs; instead private mode restarts Soul on a separate WebView folder
    // (app_webview_secret). Root can see which folder the Soul process has open: asked once per Soul process
    // (a mode switch always restarts it). Without root the check fails once and is never tried again.
    private static final String SOUL = "com.mycompany.app.soulbrowser";
    private final java.util.HashMap<Integer, Boolean> soulPrivate = new java.util.HashMap<>();
    private int soulChecking = -1;
    private boolean noRoot;
    // Long-press the space bar: incognito on/off by hand (overrides the automatic answer) until you switch app.
    private Boolean manualIncognito;
    private String inputPackage;
    private boolean fieldPrivate;
    private int enterAction = EditorInfo.IME_ACTION_NONE;
    private boolean rawKeys;             // TYPE_NULL editors (terminals): send key events

    private boolean lastWasSpace, autoSpace, punctMenu;
    private String revertOriginal, revertCommitted;   // undo for the last autocorrect
    private String revertMix;   // that autocorrect fixed this mix-up word (its, your...): undoing it stops those fixes
    private final HashSet<String> userWords = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        // Key handling runs on this thread: ask for display priority so other apps' work doesn't delay a keypress.
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (Exception ignored) { }
        SharedPreferences sp = getSharedPreferences("words", Context.MODE_PRIVATE);
        userWords.addAll(sp.getStringSet("user", new HashSet<String>()));
        dict.mixOff.addAll(sp.getStringSet("mixoff", new HashSet<String>()));
        clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        String recent = sp.getString("emoji", "");
        if (!recent.isEmpty()) recentEmoji.addAll(java.util.Arrays.asList(recent.split(" ")));
        new Thread(new Runnable() {
            public void run() {
                try {
                    dict.loadNext(getAssets().open("next.txt"));
                    dict.loadFixes(getAssets().open("fixes.txt"));
                    dict.load(getAssets().open("words.txt"));
                    java.io.File learned = new java.io.File(getFilesDir(), "learned.txt");
                    if (learned.exists()) dict.restoreLearned(new java.io.FileInputStream(learned));
                    for (String w : userWords) dict.addUserWord(w);
                } catch (Exception ignored) { }
                // A field that opened while the words were loading gets its predictions now, not at the first key.
                main.post(new Runnable() { public void run() { if (word.length() == 0) showSuggestions(); } });
            }
        }, "dict-load").start();
    }

    // ---- emoji search: 🔍 on the emoji page, then type a word; matches show in the strip ----

    private boolean searchMode;
    private final StringBuilder query = new StringBuilder();
    private java.util.ArrayList<String[]> emojiWords;   // {emoji, keywords...}

    private void startSearch() {
        if (emojiWords == null) {
            emojiWords = new java.util.ArrayList<>();
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("emoji_keywords.txt"), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) if (!line.trim().isEmpty()) emojiWords.add(line.trim().split(" "));
                br.close();
            } catch (Exception ignored) { }
        }
        searchMode = true;
        query.setLength(0);
        keyboard.setPage(KeyboardView.PAGE_ABC);
        keyboard.setShiftState(shift = KeyboardView.SHIFT_OFF);
        showSearch();
    }

    private void endSearch() {
        searchMode = false;
        query.setLength(0);
        updateAutoCap();
        showSuggestions();
    }

    /** Keys while searching edit the query instead of the text. Returns true when handled. */
    private boolean searchKey(int code) {
        if (code >= 'a' && code <= 'z') { query.append((char) code); showSearch(); return true; }
        switch (code) {
            case KeyboardView.SHIFT: return true;
            case KeyboardView.DEL:
            case KeyboardView.DEL_WORD:
                if (query.length() == 0) endSearch(); else { query.setLength(query.length() - 1); showSearch(); }
                return true;
            case KeyboardView.SPACE:
                if (query.length() > 0 && query.charAt(query.length() - 1) != ' ') query.append(' ');
                showSearch();
                return true;
            case KeyboardView.EMOJI_KEY:
                endSearch();
                return false;   // goes on to open the emoji page
            default:
                endSearch();
                return code == KeyboardView.ENTER;
        }
    }

    private void showSearch() {
        String[] terms = query.toString().trim().isEmpty() ? new String[0] : query.toString().trim().split(" ");
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        items.add("\uD83D\uDD0D " + query);
        for (String[] e : emojiWords) {
            if (items.size() >= StripView.MAX) break;
            boolean all = terms.length > 0;
            for (String t : terms) {
                boolean hit = false;
                for (int i = 1; i < e.length && !hit; i++) hit = e[i].startsWith(t);
                if (!hit) { all = false; break; }
            }
            if (all) items.add(e[0]);
        }
        while (items.size() < 3) items.add("");
        strip.show(items.toArray(new String[0]), -1);
    }

    // ---- clipboard: the last thing you copied, offered in the strip for two minutes ----

    private static final String CLIP_MARK = "\uD83D\uDCCB ";
    private static final long CLIP_FRESH_MS = 120000;
    private String clipText;
    private long clipTime;
    private boolean clipPasted;
    private android.content.ClipboardManager clipboard;

    private final android.content.ClipboardManager.OnPrimaryClipChangedListener clipListener =
            new android.content.ClipboardManager.OnPrimaryClipChangedListener() {
                public void onPrimaryClipChanged() { readClip(); }
            };

    private void readClip() {
        try {
            android.content.ClipData c = clipboard == null ? null : clipboard.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return;
            CharSequence t = c.getItemAt(0).coerceToText(this);
            if (t == null || t.length() == 0 || t.toString().equals(clipText)) return;
            clipText = t.toString();
            clipTime = android.os.SystemClock.uptimeMillis();
            clipPasted = false;
        } catch (Exception ignored) { }
    }

    /** The copied text to offer (shortened for the strip), or null. */
    private String freshClip() {
        if (clipText == null || clipPasted || android.os.SystemClock.uptimeMillis() - clipTime > CLIP_FRESH_MS) return null;
        String t = clipText.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        strip = new StripView(this);
        strip.setListener(this);
        keyboard = new KeyboardView(this);
        keyboard.setListener(this);
        root.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(keyboard, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.setClipChildren(false);   // lets the key pop-up rise over the strip
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() { return false; }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int type = info.inputType;
        int cls = type & InputType.TYPE_MASK_CLASS;
        int var = type & InputType.TYPE_MASK_VARIATION;
        rawKeys = type == InputType.TYPE_NULL;
        boolean password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
        boolean address = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_URI
                || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_FILTER);
        boolean text = cls == InputType.TYPE_CLASS_TEXT;
        boolean capFlags = (type & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)) != 0;
        boolean plainVar = var == InputType.TYPE_TEXT_VARIATION_NORMAL || var == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
                || var == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE || var == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT;
        autoCap = text && !password && !address && (capFlags || plainVar);
        // Web addresses (browser URL bars): suggestions and learning, but no autocorrect, capitals or auto-spaces.
        urlField = text && var == InputType.TYPE_TEXT_VARIATION_URI;
        suggestionsOn = text && !password && (!address || urlField);
        // Space fixes typos only if switched on in the setup screen (off by default: the typed word is usually the one).
        autoCorrectOn = suggestionsOn && !urlField && (type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
                && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("autocorrect_space", false);
        fieldPrivate = (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
        if (info.packageName == null || !info.packageName.equals(inputPackage)) manualIncognito = null;
        inputPackage = info.packageName;
        updateIncognito();
        numericStart = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;

        boolean multiLine = (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        enterAction = ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 || multiLine) ? EditorInfo.IME_ACTION_NONE : action;
        String label;
        switch (enterAction) {
            case EditorInfo.IME_ACTION_GO: label = "Go"; break;
            case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
            case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
            case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
            case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
            case EditorInfo.IME_ACTION_PREVIOUS: label = "Prev"; break;
            default: label = null; enterAction = EditorInfo.IME_ACTION_NONE;
        }
        if (keyboard == null) return;
        keyboard.cancelTouches();
        keyboard.setEnterLabel(label);
        updateIncognito();
        SharedPreferences settings = getSharedPreferences("settings", Context.MODE_PRIVATE);
        keyboard.configure(settings.getInt("row_dp", 54), settings.getBoolean("number_row", false), settings.getBoolean("arrows", false));
        // Number and phone fields get a number pad.
        keyboard.setPage(cls == InputType.TYPE_CLASS_PHONE ? KeyboardView.PAGE_PHONE
                : numericStart ? KeyboardView.PAGE_NUM : KeyboardView.PAGE_ABC);

        pos = selStart = info.initialSelStart < 0 ? 0 : info.initialSelStart;
        selEnd = info.initialSelEnd < 0 ? pos : info.initialSelEnd;
        expectedN = 0;
        resetWordState();
        shift = KeyboardView.SHIFT_OFF;
        readClip();   // something copied while the keyboard was closed
        resyncBefore();
        updateAutoCap();
        showSuggestions();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (keyboard != null) keyboard.cancelTouches();
    }

    @Override
    public void onUpdateSelection(int oss, int ose, int nss, int nse, int cs, int ce) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce);
        if (nss == nse && isExpected(nss)) return;
        // The cursor moved for some other reason (tap, app edit, field cleared): start over from the app's text.
        selStart = nss; selEnd = nse; pos = nss;
        expectedN = 0;
        resetWordState();
        if (nss == nse) {
            resyncBefore();
            InputConnection ic = getCurrentInputConnection();
            CharSequence after = ic == null ? null : icq.textAfter(ic, 1);
            if (after == null || after.length() == 0 || !isWordChar(after.charAt(0))) word.append(trailingWord(before));
        }
        if (shift != KeyboardView.SHIFT_LOCK) shift = KeyboardView.SHIFT_OFF;
        updateAutoCap();
        showSuggestions();
    }

    // ---- keys ----

    @Override
    public void onKey(int code) {
        long t0 = System.nanoTime();
        try { handleKey(code); } finally { timeKey(t0); }
    }

    // Per-key handling time, logged every 100 keys (adb logcat -s FastKeys) to see whether a slowdown is ours.
    private long keyNanos, keyMax;
    private int keyCount;

    private void timeKey(long t0) {
        long d = System.nanoTime() - t0;
        keyNanos += d;
        if (d > keyMax) keyMax = d;
        if (++keyCount == 100) {
            android.util.Log.i("FastKeys", "100 keys: avg " + keyNanos / 100000 + " us, max " + keyMax / 1000
                    + " us, suggestions avg " + sugNanos / 100000 + " us");
            keyNanos = keyMax = sugNanos = 0;
            keyCount = 0;
        }
    }

    private void handleKey(int code) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (searchMode && searchKey(code)) return;
        if (code == KeyboardView.EMOJI_SEARCH) { startSearch(); return; }
        if (code != KeyboardView.DEL) { revertOriginal = null; }
        spaceUndoable = false;
        boolean wasSpace = lastWasSpace;
        lastWasSpace = false;
        if (punctMenu && code != KeyboardView.SHIFT) punctMenu = false;
        if (accentMenu && code != KeyboardView.SHIFT) accentMenu = false;
        switch (code) {
            case KeyboardView.SHIFT: onShift(); return;
            case KeyboardView.SYM: keyboard.setPage(KeyboardView.PAGE_SYM); showSuggestions(); return;
            case KeyboardView.SYM2: keyboard.setPage(KeyboardView.PAGE_SYM2); showSuggestions(); return;
            case KeyboardView.ABC: keyboard.setPage(KeyboardView.PAGE_ABC); showSuggestions(); return;
            case KeyboardView.EMOJI_KEY:
                emojiPage = recentEmoji.isEmpty() ? 1 : 0;
                keyboard.setEmojiPage(emojiPageContent(emojiPage));
                keyboard.setPage(KeyboardView.PAGE_EMOJI);
                showSuggestions();
                return;
            case KeyboardView.EMOJI_PREV:
            case KeyboardView.EMOJI_NEXT: {
                int pages = 1 + (EMOJI.length + 29) / 30;
                emojiPage = (emojiPage + (code == KeyboardView.EMOJI_NEXT ? 1 : pages - 1)) % pages;
                if (emojiPage == 0 && recentEmoji.isEmpty()) emojiPage = code == KeyboardView.EMOJI_NEXT ? 1 : pages - 1;
                keyboard.setEmojiPage(emojiPageContent(emojiPage));
                return;
            }
            case KeyboardView.ARROW_LEFT:
            case KeyboardView.ARROW_RIGHT:
                sendKey(ic, code == KeyboardView.ARROW_LEFT ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
                return;   // the app reports the new cursor; onUpdateSelection re-reads around it
            case KeyboardView.NUM_DOT:
                word.setLength(0);
                commit(ic, ".");
                break;
            case KeyboardView.DEL: onDelete(ic); break;
            case KeyboardView.DEL_WORD: onDeleteWord(ic); break;
            case KeyboardView.ENTER: onEnter(ic); break;
            case KeyboardView.SPACE:
                // Record what the space changes, so a slide that starts on the space bar can take it back.
                spaceDeleted.setLength(0); spaceInserted.setLength(0);
                spaceAutoSpace = autoSpace;
                lastLearned = null;
                recording = true;
                try { onSpace(ic, wasSpace); } finally { recording = false; }
                spaceLearned = lastLearned; spaceLearnedPrev = lastLearnedPrev;
                spaceUndoable = true;
                break;
            default: onChar(ic, (char) code);
        }
        updateAutoCap();
        requestSuggestions();
    }

    // Suggestions are worked out just after the keypress (the letter and pop-up go first); several fast keys
    // share one update. Anything that acts on the strip (space, a pick) flushes a pending update first.
    private boolean suggestionsPending;
    private final Runnable suggestionsRunnable = new Runnable() {
        public void run() { if (suggestionsPending) { suggestionsPending = false; showSuggestions(); } }
    };

    private static final int STRIP_PAUSE_MS = 150;

    /**
     * The strip waits for a pause in typing: while you type fast it stays still, so each key is one panel update
     * (the letter and pop-up) instead of two. Every key restarts the wait.
     */
    private void requestSuggestions() {
        suggestionsPending = true;
        main.removeCallbacks(suggestionsRunnable);
        main.postDelayed(suggestionsRunnable, STRIP_PAUSE_MS);
    }

    private void flushSuggestions() {
        if (!suggestionsPending) return;
        suggestionsPending = false;
        main.removeCallbacks(suggestionsRunnable);
        showSuggestions();
    }

    @Override
    public void onGeometry(float[] keyX, float[] keyY) { dict.setKeyCenters(keyX, keyY); }

    // ---- emoji: page 0 = recently used, then the list below in pages of 30 ----

    private static final String[] EMOJI = (
            "😂 ❤️ 😍 🤣 😊 🙏 😭 😘 👍 😅 👏 😁 🔥 💔 💕 😢 🤔 😆 🙄 💪 😉 ☺️ 👌 🤗 💜 😔 😎 😇 🎉 ✨ "
            + "🤷 🤦 😱 😌 🙌 😋 😏 🙂 🤩 😄 😀 😃 💯 🙈 👀 😒 🤭 😜 💋 😪 😑 💥 🙋 😞 😩 😡 🤪 👊 🥰 😥 "
            + "🤤 👉 👈 👇 ☝️ 😳 ✋ 😚 😝 😴 😬 🙃 🥺 😈 🤘 😣 😕 😐 😤 😫 😲 🤫 😮 😷 🤧 🤓 🤯 🥳 🥲 🫠 "
            + "💙 💚 💛 🧡 🖤 🤍 💖 💗 💓 💞 💘 💝 ❣️ ⭐ 🌟 💫 🌈 ☀️ 🌙 ⚡ ❄️ 🌸 🌹 🌻 🌼 🍀 🌿 🐶 🐱 🦉 "
            + "✅ ❌ ❗ ❓ ✔️ ➡️ ⬅️ ⬆️ ⬇️ 🔴 🎂 🎁 🎈 🎊 🏆 ⚽ 🎮 🎵 🎶 ☕ 🍕 🍔 🍻 🍷 🍾 🚗 ✈️ 🏠 📱 💻").split(" ");
    private final java.util.ArrayList<String> recentEmoji = new java.util.ArrayList<>();
    private int emojiPage;

    private String[] emojiPageContent(int p) {
        if (p == 0) {   // your recent ones, then the most used, so the first page is always full
            java.util.ArrayList<String> out = new java.util.ArrayList<>(recentEmoji);
            for (int i = 0; i < EMOJI.length && out.size() < 30; i++) if (!out.contains(EMOJI[i])) out.add(EMOJI[i]);
            return out.toArray(new String[0]);
        }
        int from = (p - 1) * 30, to = Math.min(EMOJI.length, from + 30);
        String[] out = new String[Math.max(0, to - from)];
        System.arraycopy(EMOJI, from, out, 0, out.length);
        return out;
    }

    @Override
    public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        revertOriginal = null;
        lastWasSpace = false;
        word.setLength(0);
        clearTaps();
        commit(ic, text);
        autoSpace = false;
        if (!incognito) {
            recentEmoji.remove(text);
            recentEmoji.add(0, text);
            while (recentEmoji.size() > 30) recentEmoji.remove(recentEmoji.size() - 1);
            getSharedPreferences("words", Context.MODE_PRIVATE).edit().putString("emoji", android.text.TextUtils.join(" ", recentEmoji)).apply();
        }
        updateAutoCap();
        showSuggestions();
    }

    // Where each letter of the current word was tapped (key widths); NaN for letters read back from the text.
    private final float[] wordTapX = new float[48], wordTapY = new float[48];

    private static final long TOGETHER_MS = 80;
    private final long[] wordTapT = new long[48];
    private final boolean[] wordTogether = new boolean[48];   // letter i was pressed together with letter i - 1

    private void clearTaps() {
        java.util.Arrays.fill(wordTapT, 0);
        java.util.Arrays.fill(wordTogether, false);
        java.util.Arrays.fill(wordTapX, Float.NaN);
        java.util.Arrays.fill(wordTapY, Float.NaN);
    }

    @Override
    public void onLongPress(KeyboardView.Key key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || searchMode) return;
        if (key.code == ',' || key.code == KeyboardView.EMOJI_KEY) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
            return;
        }
        if (key.code == KeyboardView.SPACE) {   // long-press space: incognito on/off; the space it typed is taken back
            onCursorStart();
            manualIncognito = !incognito;
            updateIncognito();
            showSuggestions();
            return;
        }
        if (key.code == '.') {
            punctMenu = true;
            strip.show(PUNCT_MENU.split(" "), -1);
            return;
        }
        if (key.hint == null) return;
        // The letter was typed on touch-down: replace it with the key's symbol.
        boolean wasShifted = false;
        if (word.length() > 0) {
            wasShifted = Character.isUpperCase(word.charAt(word.length() - 1));
            deleteBefore(ic, 1);
            word.setLength(word.length() - 1);
            if (wasShifted && word.length() == 0 && shift == KeyboardView.SHIFT_OFF) { shift = KeyboardView.SHIFT_ONE; shiftAuto = !wordManualCap; }
        }
        String accents = ACCENTS.get(key.code);
        accentWord = word.toString();
        spaceDeleted.setLength(0); spaceInserted.setLength(0);
        lastLearned = null;
        recording = true;
        try { onChar(ic, key.hint.charAt(0)); } finally { recording = false; }
        updateAutoCap();
        if (accents != null && suggestionsOn) {
            // The strip offers the accented letters; a tap swaps the symbol for one of them.
            accentMenu = true;
            accentLearned = lastLearned; accentLearnedPrev = lastLearnedPrev;
            String[] items = accents.split(" ");
            if (wasShifted) for (int i = 0; i < items.length; i++) items[i] = items[i].toUpperCase();
            strip.show(items, -1);
            return;
        }
        showSuggestions();
    }

    private static final java.util.HashMap<Integer, String> ACCENTS = new java.util.HashMap<>();
    static {
        ACCENTS.put((int) 'a', "à á â ä ã å æ ā");
        ACCENTS.put((int) 'e', "è é ê ë ē");
        ACCENTS.put((int) 'i', "ì í î ï");
        ACCENTS.put((int) 'o', "ò ó ô ö õ ø œ");
        ACCENTS.put((int) 'u', "ù ú û ü");
        ACCENTS.put((int) 'n', "ñ");
        ACCENTS.put((int) 'c', "ç");
        ACCENTS.put((int) 's', "ß");
        ACCENTS.put((int) 'y', "ÿ");
    }
    private boolean accentMenu;
    private String accentWord = "", accentLearned, accentLearnedPrev;

    /** An accented letter picked from the strip: undo the symbol, put the word back, then type the letter. */
    private void pickAccent(InputConnection ic, String letter) {
        accentMenu = false;
        if (spaceInserted.length() > 0) deleteBefore(ic, spaceInserted.length());
        if (spaceDeleted.length() > 0) commit(ic, spaceDeleted.toString());
        if (accentLearned != null) dict.unlearn(accentLearned, accentLearnedPrev);
        revertOriginal = null;
        word.setLength(0);
        clearTaps();
        word.append(accentWord);
        autoSpace = false;
        commit(ic, letter);
        word.append(letter);
        if (shift == KeyboardView.SHIFT_ONE) shift = KeyboardView.SHIFT_OFF;
        updateAutoCap();
        showSuggestions();
    }

    /** "youtube.com/wat", "https:" — but not a search like "best pizza near me". */
    private static boolean looksLikeAddress(String s) {
        s = s.trim();
        return !s.isEmpty() && s.indexOf(' ') < 0 && (s.indexOf('.') >= 0 || s.indexOf('/') >= 0 || s.indexOf(':') >= 0);
    }

    @Override
    public void onPick(int index, String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (accentMenu) { pickAccent(ic, text); return; }
        if (urlField && text.equals(siteShown)) {
            // A site you've been to: it replaces everything typed so far.
            String typed = fieldText(ic);
            if (typed.length() > 0) deleteBefore(ic, typed.length());
            commit(ic, siteShown);
            siteShown = null;
            word.setLength(0);
            clearTaps();
            autoSpace = false;
            showSuggestions();
            return;
        }
        if (searchMode) {
            endSearch();
            if (index > 0) onText(text);   // slot 0 is the query itself: tapping it just closes search
            return;
        }
        if (text.startsWith(CLIP_MARK) && clipText != null) {
            // Paste exactly what was copied; no space after it.
            commit(ic, clipText);
            clipPasted = true;
            word.setLength(0);
            clearTaps();
            autoSpace = false;
            updateAutoCap();
            showSuggestions();
            return;
        }
        if (punctMenu) {
            punctMenu = false;
            onChar(ic, text.charAt(0));
            updateAutoCap();
            showSuggestions();
            return;
        }
        revertOriginal = null;
        String prevForLearning = context();
        if (index == 0) learn(text.toLowerCase());   // the verbatim slot: keep it, and stop correcting it
        // Record the pick so a long-press (remove) can take it back.
        spaceDeleted.setLength(0); spaceInserted.setLength(0);
        recording = true;
        icq.beginBatch(ic);   // one change: Chrome's address bar undid a lone delete ("best pizzapizza", Nov 6 2026)
        if (word.length() > 0) deleteBefore(ic, word.length());
        else if (before.length() > 0 && isWordChar(before.charAt(before.length() - 1))) text = " " + text;
        // An address bar is also a search box: a pick adds a space unless the bar holds an address so far.
        boolean noSpace = urlField && looksLikeAddress(fieldText(ic) + text);
        commit(ic, noSpace ? text : text + " ");
        icq.endBatch(ic);
        recording = false;
        pickWord = text.trim();
        pickPrev = prevForLearning;
        word.setLength(0);
        autoSpace = !noSpace;
        learnWords(text.trim(), prevForLearning, false);
        updateAutoCap();
        showSuggestions();
    }

    private void onShift() {
        long t = System.currentTimeMillis();
        if (shift == KeyboardView.SHIFT_OFF) {
            shift = KeyboardView.SHIFT_ONE;
            shiftAuto = false;
        } else if (shift == KeyboardView.SHIFT_ONE) {
            // Quick second tap on a shift the user turned on = caps lock; a tap on autocap just turns it off.
            shift = (t - lastShiftTap < 400 && !shiftAuto) ? KeyboardView.SHIFT_LOCK : KeyboardView.SHIFT_OFF;
            shiftAuto = false;
        } else {
            shift = KeyboardView.SHIFT_OFF;
        }
        lastShiftTap = t;
        keyboard.setShiftState(shift);
    }

    private void onChar(InputConnection ic, char c) {
        boolean letter = Character.isLetter(c);
        if (letter || (c == '\'' && word.length() > 0)) {
            if (letter && shift != KeyboardView.SHIFT_OFF) c = Character.toUpperCase(c);
            if (word.length() == 0) wordManualCap = Character.isUpperCase(c) && !shiftAuto;
            autoSpace = false;
            commit(ic, String.valueOf(c));
            int at = word.length();
            if (at < wordTapX.length) {
                wordTapX[at] = letter ? keyboard.tapX : Float.NaN;
                wordTapY[at] = letter ? keyboard.tapY : Float.NaN;
                // Near-simultaneous with the letter before (held together, or under 80 ms apart): order unsure.
                wordTogether[at] = letter && at > 0 && wordTapT[at - 1] > 0
                        && (keyboard.tapOverlap || keyboard.tapTime - wordTapT[at - 1] < TOGETHER_MS);
                wordTapT[at] = letter ? keyboard.tapTime : 0;
            }
            word.append(c);
            if (shift == KeyboardView.SHIFT_ONE) shift = KeyboardView.SHIFT_OFF;
            return;
        }
        // Separator / symbol.
        boolean sentencePunct = ".,!?;:".indexOf(c) >= 0;
        boolean ender = (c == '.' || c == '!' || c == '?') && spaceAfterPunct();
        if (word.length() > 0 && SEPARATORS_AUTOCORRECT.indexOf(c) >= 0) {
            autoCorrect(ic, ender ? c + " " : String.valueOf(c));
            if (ender) autoSpace = true;   // a space pressed next is swallowed, so it stays one space
            return;
        }
        if (urlField && autoSpace && endsWith(" ") && ".:/".indexOf(c) >= 0) {
            // A pick's space in an address bar: "youtube |" + "." -> "youtube.|"
            deleteBefore(ic, 1);
            commit(ic, String.valueOf(c));
            autoSpace = false;
            word.setLength(0);
            return;
        }
        if (sentencePunct && autoSpace && endsWith(" ")) {
            // "word |" + "." -> "word. |"
            deleteBefore(ic, 1);
            commit(ic, c + " ");
            autoSpace = true;
            word.setLength(0);
            return;
        }
        autoSpace = false;
        word.setLength(0);
        if (ender) {
            commit(ic, c + " ");
            autoSpace = true;
        } else {
            commit(ic, String.valueOf(c));
        }
    }

    /**
     * Sentence punctuation gets its space automatically in ordinary text, but not in addresses, passwords or
     * terminals (suggestionsOn is off there) and not after a digit, so 3.5 stays 3.5.
     */
    private boolean spaceAfterPunct() {
        if (!suggestionsOn || rawKeys || urlField) return false;
        if (word.length() > 0) return true;   // right after a word
        int n = before.length();
        return n == 0 || !Character.isDigit(before.charAt(n - 1));
    }

    private void onSpace(InputConnection ic, boolean wasSpace) {
        if (word.length() > 0) {
            autoCorrect(ic, " ");
            lastWasSpace = true;
            return;
        }
        if (autoSpace) { autoSpace = false; lastWasSpace = true; return; }   // a suggestion already added the space
        if (wasSpace && before.length() >= 2 && before.charAt(before.length() - 1) == ' ') {
            char p = before.charAt(before.length() - 2);
            if (p != ' ' && p != '.' && p != '!' && p != '?' && p != '\n') {
                deleteBefore(ic, 1);
                commit(ic, ". ");
                return;
            }
        }
        commit(ic, " ");
        lastWasSpace = true;
    }

    /** Ends the current word with sep, replacing an obvious typo first. */
    private void autoCorrect(InputConnection ic, String sep) {
        if (autoCorrectOn) flushSuggestions();   // space applies what the strip shows for this exact word
        String prevForLearning = context();
        String typed = word.toString();
        String lower = typed.toLowerCase();
        String fixed = null;
        if (lower.equals("i")) fixed = "I";
        else if (lower.startsWith("i'") && dict.contains(lower)) { if (typed.charAt(0) == 'i') fixed = "I" + typed.substring(1); }
        else if (autoCorrectOn && !wordManualCap && !userWords.contains(lower) && !dict.keepAsTyped(lower, context())
                && !hasUpperAfterFirst(typed) && isPlainLetters(lower)) {   // café, señor: leave accented words alone
            dict.setTaps(lower, wordTapX, wordTapY, wordTogether);
            String c = dict.correction(lower, context());
            // Only apply what the strip shows in bold: a typo-tolerant completion may outrank the correction
            // ("inxred" shows incredible, so space must not turn it into "inbred").
            if (c != null && !c.equalsIgnoreCase(shownBold)) c = null;
            if (c != null && (c.indexOf(' ') > 0 || dict.frequency(c) >= MIN_CORRECTION)) fixed = matchCase(c, typed);
        }
        boolean forwardMix = fixed != null && dict.mixUp(lower, prevForLearning) != null;
        // The word before, when this one shows it was a mix-up ("your going" -> "you're going").
        String finalWord = fixed != null ? fixed : typed, earlier = null, earlierText = null;
        if (autoCorrectOn && !wordManualCap && prevForLearning != null && isPlainLetters(finalWord.toLowerCase())) {
            String fixPrev = dict.mixUpBefore(prevForLearning, finalWord.toLowerCase());
            if (fixPrev != null) {
                String prevLower = prevForLearning.substring(prevForLearning.lastIndexOf('_') + 1);
                int end = before.length() - typed.length(), start = end - 1 - prevLower.length();
                if (start >= 0 && before.charAt(end - 1) == ' ' && before.substring(start, end - 1).equalsIgnoreCase(prevLower)
                        && (start == 0 ? before.length() == pos : !isWordChar(before.charAt(start - 1)))) {
                    earlierText = before.substring(start, end - 1);
                    earlier = matchCase(fixPrev, earlierText);
                    prevForLearning = prevForLearning.substring(0, prevForLearning.length() - prevLower.length()) + fixPrev;
                }
            }
        }
        word.setLength(0);
        if (earlier != null) {
            deleteBefore(ic, typed.length() + 1 + earlierText.length());
            commit(ic, earlier + " " + finalWord + sep);
            revertOriginal = earlierText + " " + typed;
            revertCommitted = earlier + " " + finalWord + sep;
            revertMix = earlierText.toLowerCase();
        } else if (fixed != null && !fixed.equals(typed)) {
            deleteBefore(ic, typed.length());
            commit(ic, fixed + sep);
            revertOriginal = typed;
            revertCommitted = fixed + sep;
            revertMix = forwardMix ? lower : null;
        } else {
            commit(ic, sep);
        }
        autoSpace = false;
        // Learn what you ended up with, but not an unknown word the keyboard could have fixed (a typo, not a new word).
        boolean typo = fixed == null && !dict.isWord(lower) && !userWords.contains(lower) && dict.correction(lower, prevForLearning) != null;
        if (!typo) learnWords(fixed != null ? fixed : typed, prevForLearning, wordManualCap);
    }

    // ---- removing a suggestion: long-press it in the strip ----

    private String pickWord, pickPrev;

    @Override
    public void onRemove(int index, String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || pickWord == null || !pickWord.equals(text.trim())) return;
        // The tap already typed it: take it back, then block the word.
        if (spaceInserted.length() > 0) deleteBefore(ic, spaceInserted.length());
        if (spaceDeleted.length() > 0) commit(ic, spaceDeleted.toString());
        if (!incognito) for (String part : pickWord.toLowerCase().split(" ")) dict.unlearn(part, pickPrev);   // nothing was learned in incognito
        if (pickWord.indexOf(' ') < 0) {
            String lower = pickWord.toLowerCase();
            dict.block(lower);
            if (userWords.remove(lower)) {
                getSharedPreferences("words", Context.MODE_PRIVATE).edit().putStringSet("user", new HashSet<>(userWords)).apply();
            }
        }
        pickWord = null;
        autoSpace = false;
        word.setLength(0);
        clearTaps();
        word.append(trailingWord(before));   // the word you were typing is back
        scheduleSave();
        updateAutoCap();
        showSuggestions();
    }

    // ---- cursor control: slide along the space bar ----

    private final StringBuilder spaceDeleted = new StringBuilder(), spaceInserted = new StringBuilder();
    private String spaceLearned, spaceLearnedPrev;
    private boolean recording, spaceUndoable, spaceAutoSpace;

    /** incognito = the field says private, or Soul's private mode, or the space-bar toggle. */
    private void updateIncognito() {
        boolean soul = false;
        if (SOUL.equals(inputPackage) && !noRoot) {
            android.view.inputmethod.InputBinding b = getCurrentInputBinding();
            final int pid = b == null ? 0 : b.getPid();
            if (pid > 0) {
                Boolean known = soulPrivate.get(pid);
                if (known != null) soul = known;
                else {
                    soul = true;   // unknown yet: learn nothing until the check answers
                    if (soulChecking != pid) checkSoul(pid);
                }
            }
        }
        incognito = manualIncognito != null ? manualIncognito : fieldPrivate || soul;
        if (keyboard != null) keyboard.setIncognito(incognito);
    }

    private void checkSoul(final int pid) {
        soulChecking = pid;
        new Thread(new Runnable() {
            public void run() {
                Boolean result = null;
                try {
                    Process su = Runtime.getRuntime().exec(new String[] { "su", "-c", "ls -l /proc/" + pid + "/fd" });
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(su.getInputStream()));
                    boolean secret = false, any = false;
                    for (String line; (line = r.readLine()) != null; ) {
                        any = true;
                        if (line.contains("/app_webview_secret/")) secret = true;
                    }
                    su.waitFor();
                    if (any) result = secret;   // no output: root refused or the process is gone
                } catch (Exception e) { }
                final Boolean answer = result;
                main.post(new Runnable() {
                    public void run() {
                        soulChecking = -1;
                        if (answer == null) noRoot = true; else soulPrivate.put(pid, answer);
                        updateIncognito();
                    }
                });
            }
        }, "soul-check").start();
    }

    @Override
    public void onCursorStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (spaceUndoable) {   // the finger came down as a space: take that space (and its autocorrect) back
            spaceUndoable = false;
            if (spaceInserted.length() > 0) deleteBefore(ic, spaceInserted.length());
            if (spaceDeleted.length() > 0) commit(ic, spaceDeleted.toString());
            if (spaceLearned != null) dict.unlearn(spaceLearned, spaceLearnedPrev);
            autoSpace = spaceAutoSpace;
        }
        revertOriginal = null;
        resetWordState();
    }

    @Override
    public void onCursor(int step) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        sendKey(ic, step < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
        // The app reports the new cursor; onUpdateSelection re-reads the text around it.
    }

    @Override
    public void onCursorEnd() {
        updateAutoCap();
        showSuggestions();
    }

    // ---- URL bar sites ----

    /** Shows the strip; in a URL bar the best matching site you've been to takes the middle slot. */
    private void showStrip(String[] items, int bold) {
        siteShown = null;
        if (urlField && before.length() == pos && items.length >= 2) {
            String t = before.toString().trim().toLowerCase();
            if (t.startsWith("https://")) t = t.substring(8); else if (t.startsWith("http://")) t = t.substring(7);
            if (t.indexOf(' ') < 0) {
                java.util.List<String> s = dict.sites(t, 1);
                if (!s.isEmpty() && !t.isEmpty()) { items[1] = s.get(0); siteShown = s.get(0); bold = -1; }
            }
        }
        strip.show(items, bold);
    }

    private String siteShown;   // the address offered in the strip right now

    /** The URL bar's text up to the cursor (it is short; read from the app if our copy may be cut off). */
    private String fieldText(InputConnection ic) {
        if (before.length() == pos) return before.toString();
        CharSequence cs = ic == null ? null : icq.textBefore(ic, 300);
        return cs == null ? "" : cs.toString();
    }

    /** "https://www.YouTube.com/" -> "www.youtube.com"; null if it isn't an address. */
    private static String siteOf(String text) {
        String t = text.trim().toLowerCase();
        if (t.startsWith("https://")) t = t.substring(8); else if (t.startsWith("http://")) t = t.substring(7);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() || t.indexOf(' ') >= 0 || t.indexOf('.') <= 0 ? null : t;
    }

    // ---- learning ----

    private String lastLearned, lastLearnedPrev;   // undone if backspace takes the word back

    /** Count the words you ended (each part of a split), with the word before, and how you capitalise names. */
    private void learnWords(String text, String prev, boolean manualCap) {
        lastLearned = null;
        if (!suggestionsOn || !dict.isReady() || incognito) return;
        for (String part : text.split(" ")) {
            if (part.isEmpty() || !Character.isLetter(part.charAt(0))) { prev = null; continue; }
            String lower = part.toLowerCase();
            boolean name = manualCap && Character.isUpperCase(part.charAt(0)) && !hasUpperAfterFirst(part) && !lower.equals("i");
            dict.learnUse(lower, name ? part : null, prev);
            lastLearned = lower;
            lastLearnedPrev = prev;
            prev = Dictionary.extend(prev, lower);
        }
        scheduleSave();
    }

    private final Runnable saveRunnable = new Runnable() {
        public void run() {
            final String data = dict.dumpLearned();
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.io.File tmp = new java.io.File(getFilesDir(), "learned.tmp");
                        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                        out.write(data.getBytes("UTF-8"));
                        out.close();
                        tmp.renameTo(new java.io.File(getFilesDir(), "learned.txt"));
                    } catch (Exception ignored) { }
                }
            }, "learn-save").start();
        }
    };

    private void scheduleSave() {
        main.removeCallbacks(saveRunnable);
        main.postDelayed(saveRunnable, 4000);
    }

    private void onDelete(InputConnection ic) {
        autoSpace = false;
        if (revertOriginal != null && endsWith(revertCommitted)) {
            // Backspace right after an autocorrect: put back what was typed, and stop correcting that word.
            String orig = revertOriginal;
            revertOriginal = null;
            if (lastLearned != null) { dict.unlearn(lastLearned, lastLearnedPrev); lastLearned = null; }
            deleteBefore(ic, revertCommitted.length());
            commit(ic, orig);
            word.setLength(0);
            clearTaps();
            String last = orig.substring(orig.lastIndexOf(' ') + 1);   // a fixed word before this one ("your going")
            word.append(last);
            learn(last.toLowerCase());
            if (revertMix != null && !incognito) {
                dict.mixOff.add(revertMix);
                getSharedPreferences("words", Context.MODE_PRIVATE).edit().putStringSet("mixoff", new HashSet<>(dict.mixOff)).apply();
            }
            return;
        }
        revertOriginal = null;
        if (selStart != selEnd) {
            sendKey(ic, KeyEvent.KEYCODE_DEL);
            pos = selStart = selEnd = Math.min(selStart, selEnd);
            expect(pos);
            word.setLength(0);
            beforeKnown = false;
            return;
        }
        if (word.length() > 0) {
            deleteBefore(ic, 1);
            word.setLength(word.length() - 1);
            return;
        }
        sendKey(ic, KeyEvent.KEYCODE_DEL);
        if (pos > 0) pos--;
        selStart = selEnd = pos;
        expect(pos);
        if (before.length() > 0) before.setLength(before.length() - 1);
        if (before.length() < 2 && pos > 0) beforeKnown = false;
        if (!beforeKnown) resyncBefore();
        clearTaps();
        word.append(trailingWord(before));
    }

    /** Held delete: erases the word before the cursor (with the spaces after it), one word per step. */
    private void onDeleteWord(InputConnection ic) {
        autoSpace = false;
        revertOriginal = null;
        if (selStart != selEnd) { onDelete(ic); return; }
        if (word.length() > 0) {
            deleteBefore(ic, word.length());
            word.setLength(0);
            return;
        }
        if (before.length() < 40 && pos > before.length()) resyncBefore();
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        if (i > 0 && isWordChar(before.charAt(i - 1))) {
            while (i > 0 && isWordChar(before.charAt(i - 1))) i--;
        } else if (i > 0) {
            i--;   // a symbol or punctuation mark on its own
        }
        int n = before.length() - i;
        if (n == 0) { if (pos > 0) onDelete(ic); return; }   // nothing tracked (e.g. emoji): one key delete
        deleteBefore(ic, n);
    }

    private void onEnter(InputConnection ic) {
        // Enter / Go ends the word too: learn it (no autocorrect here), and in a URL bar the whole address.
        if (word.length() > 0 && suggestionsOn) learnWords(word.toString(), context(), wordManualCap);
        if (urlField) {
            String site = siteOf(fieldText(ic));
            if (site != null && !incognito) { dict.learnSite(site); scheduleSave(); }
        }
        word.setLength(0);
        autoSpace = false;
        if (rawKeys) { sendKey(ic, KeyEvent.KEYCODE_ENTER); return; }
        if (enterAction != EditorInfo.IME_ACTION_NONE) { icq.editorAction(ic, enterAction); return; }
        commit(ic, "\n");
    }

    // ---- editor plumbing ----

    private void commit(InputConnection ic, String s) {
        if (rawKeys && s.length() == 1 && s.charAt(0) == '\n') { sendKey(ic, KeyEvent.KEYCODE_ENTER); return; }
        icq.commitText(ic, s);
        if (recording) spaceInserted.append(s);
        pos += s.length();
        selStart = selEnd = pos;
        expect(pos);
        before.append(s);
        if (before.length() > 64) before.delete(0, before.length() - 64);
    }

    private void deleteBefore(InputConnection ic, int n) {
        icq.deleteBefore(ic, n);
        if (recording) {
            int b = before.length();
            spaceDeleted.insert(0, before.substring(Math.max(0, b - n)));
        }
        pos = Math.max(0, pos - n);
        selStart = selEnd = pos;
        expect(pos);
        before.setLength(Math.max(0, before.length() - n));
    }

    private void sendKey(InputConnection ic, int code) {
        icq.key(ic, code);
    }

    private void expect(int p) {
        expected[expectedN % expected.length] = p;
        expectedN++;
    }

    private boolean isExpected(int p) {
        int n = Math.min(expectedN, expected.length);
        for (int i = 0; i < n; i++) if (expected[i] == p) return true;
        return false;
    }

    private void resyncBefore() {
        before.setLength(0);
        InputConnection ic = getCurrentInputConnection();
        CharSequence cs = ic == null ? null : icq.textBefore(ic, 64);
        if (cs != null) before.append(cs);
        beforeKnown = true;
    }

    private void resetWordState() {
        clearTaps();
        word.setLength(0);
        lastWasSpace = autoSpace = punctMenu = accentMenu = false;
        searchMode = false;
        revertOriginal = null;
        wordManualCap = false;
    }

    private boolean endsWith(String s) {
        int n = s.length(), b = before.length();
        return b >= n && before.substring(b - n).equals(s);
    }

    private void updateAutoCap() {
        if (keyboard == null) return;
        if (shift == KeyboardView.SHIFT_LOCK) { keyboard.setShiftState(shift); return; }
        if (word.length() == 0 && autoCap) {
            boolean cap = sentenceStart();
            if (cap) { shift = KeyboardView.SHIFT_ONE; shiftAuto = true; }
            else if (shiftAuto) { shift = KeyboardView.SHIFT_OFF; shiftAuto = false; }
        } else if (shiftAuto && shift == KeyboardView.SHIFT_ONE && word.length() > 0) {
            shift = KeyboardView.SHIFT_OFF; shiftAuto = false;
        }
        keyboard.setShiftState(shift);
    }

    private boolean sentenceStart() {
        int i = before.length() - 1;
        boolean space = false;
        while (i >= 0 && before.charAt(i) == ' ') { i--; space = true; }
        if (i < 0) return before.length() == pos;   // only spaces back to the start of the field
        char c = before.charAt(i);
        if (c == '\n') return true;
        return space && (c == '.' || c == '!' || c == '?');
    }

    private long sugNanos;

    private void showSuggestions() {
        long t0 = System.nanoTime();
        try { showSuggestionsNow(); } finally { sugNanos += System.nanoTime() - t0; }
    }

    private String shownBold = "";   // the word space will apply, exactly as the strip shows it

    private void showSuggestionsNow() {
        if (strip == null || punctMenu || accentMenu || searchMode) return;
        shownBold = "";
        String[] items;
        int bold = -1;
        if (!suggestionsOn || !dict.isReady()) {
            items = new String[]{"", "", ""};
        } else if (word.length() == 0) {
            // Nothing typed yet: predict the next word from the one before it (or a sentence start).
            String[] p = dict.predict(context());
            boolean cap = shift != KeyboardView.SHIFT_OFF;
            items = new String[]{p.length > 1 ? predicted(p[1], cap) : "", p.length > 0 ? predicted(p[0], cap) : "",
                    p.length > 2 ? predicted(p[2], cap) : ""};
            String clip = freshClip();
            if (clip != null) items[0] = CLIP_MARK + clip;   // something you just copied: one tap pastes it
        } else {
            String typed = word.toString(), lower = typed.toLowerCase();
            dict.setTaps(lower, wordTapX, wordTapY, wordTogether);
            List<String> s = dict.suggest(lower, 2, context(), passed);
            notePassed(typed, s);
            String a = s.size() > 0 ? matchCase(s.get(0), typed) : "";
            String b = s.size() > 1 ? matchCase(s.get(1), typed) : "";
            String mix = dict.mixUp(lower, context());
            if (mix != null && !mix.equalsIgnoreCase(a)) { b = a; a = matchCase(mix, typed); }   // "more then": than in the middle, not thence
            boolean listedFix = dict.fix(lower) != null;
            if (dict.keepAsTyped(lower, context()) || userWords.contains(lower)) {
                // A real word already: keep it in the middle ("Just", not "Justice"), completions either side.
                items = new String[]{a, capitalI(typed), b};
                showStrip(items, -1);
                return;
            }
            items = new String[]{capitalI(typed), a, b};
            boolean willFix = autoCorrectOn && !wordManualCap && !dict.keepAsTyped(lower, context()) && !userWords.contains(lower)
                    && a.length() > 0 && a.equalsIgnoreCase(matchCaseOrNull(dict.correction(lower, context()), typed));
            if (willFix) { bold = 1; shownBold = a; }
        }
        showStrip(items, bold);
    }

    /**
     * Up to three words before the one being typed, lower-case and joined with "_" ("at_the", "^_how_are"; "^" = a
     * sentence start). Alone: "^" at a sentence start, "^^" at the start of a message, null when there's no usable context
     * (after a symbol, or text we haven't read).
     */
    private String context() {
        int i = before.length() - word.length();
        java.util.ArrayList<String> words = new java.util.ArrayList<>();   // newest first
        String start = null;
        while (words.size() < 3) {
            while (i > 0 && before.charAt(i - 1) == ' ') i--;
            if (i > 0 && before.charAt(i - 1) == ',') { i--; while (i > 0 && before.charAt(i - 1) == ' ') i--; }
            // Nothing but spaces back to the start of the field: the start of a message ("^^"), told apart from a new sentence.
            if (i == 0) {
                if (before.length() == pos) start = words.isEmpty() ? (urlField ? "^url" : "^^") : (urlField ? null : "^");
                break;
            }
            char c = before.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n') { start = urlField ? null : "^"; break; }   // "youtube.com" is not a sentence
            if (!isWordChar(c)) break;
            int j = i;
            while (j > 0 && isWordChar(before.charAt(j - 1))) j--;
            if (j == 0 && before.length() < pos) break;   // the word may be cut off
            words.add(before.substring(j, i).toLowerCase());
            i = j;
        }
        if (words.isEmpty()) return start;
        StringBuilder key = new StringBuilder("^".equals(start) && words.size() < 3 ? "^" : "");
        for (int w = words.size() - 1; w >= 0; w--) { if (key.length() > 0) key.append('_'); key.append(words.get(w)); }
        return key.toString();
    }

    private String predicted(String w, boolean cap) {
        w = matchCase(w, "x");   // i -> I, i'm -> I'm
        return cap ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w;
    }

    // Completions shown for the current word and not picked (word -> times shown), so longer words take over.
    private final java.util.HashMap<String, Integer> passed = new java.util.HashMap<>();
    private String passedFor = "";

    private void notePassed(String typed, List<String> shown) {
        if (typed.length() <= passedFor.length() || !typed.startsWith(passedFor)) passed.clear();   // new word or backspace
        if (!typed.equals(passedFor)) {
            for (String w : shown) { Integer n = passed.get(w); passed.put(w, n == null ? 1 : n + 1); }
        }
        passedFor = typed;
    }

    private void learn(String lower) {
        if (incognito || lower.isEmpty() || userWords.contains(lower) || dict.contains(lower)) return;
        userWords.add(lower);
        dict.addUserWord(lower);
        getSharedPreferences("words", Context.MODE_PRIVATE).edit().putStringSet("user", new HashSet<>(userWords)).apply();
    }

    private String matchCaseOrNull(String w, String typed) { return w == null ? "" : matchCase(w, typed); }

    private String matchCase(String w, String typed) {
        if (w.indexOf(' ') < 0) {
            String f = dict.form(w);   // a name you capitalise ("Caron") stays capitalised
            if (!f.equals(w) && !(typed.length() > 1 && typed.equals(typed.toUpperCase()))) return f;
        }
        if (w.equals("i") || w.startsWith("i'") || w.startsWith("i ")) w = "I" + w.substring(1);
        w = w.replace(" i ", " I ");
        if (w.endsWith(" i")) w = w.substring(0, w.length() - 1) + "I";
        if (typed.length() > 1 && typed.equals(typed.toUpperCase()) && !typed.equals(typed.toLowerCase())) return w.toUpperCase();
        if (typed.length() > 0 && Character.isUpperCase(typed.charAt(0))) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        return w;
    }

    /** "i", "i'm", "i'll": the strip shows (and a tap types) the capital form. */
    private static String capitalI(String typed) {
        return typed.equals("i") || typed.startsWith("i'") ? "I" + typed.substring(1) : typed;
    }

    private static boolean isPlainLetters(String s) {
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c > 'z' || (c < 'a' && c != '\'')) return false; }
        return true;
    }

    private static boolean hasUpperAfterFirst(String s) {
        for (int i = 1; i < s.length(); i++) if (Character.isUpperCase(s.charAt(i))) return true;
        return false;
    }

    private static boolean isWordChar(char c) { return Character.isLetter(c) || c == '\''; }

    private static String trailingWord(CharSequence s) {
        int i = s.length();
        while (i > 0 && isWordChar(s.charAt(i - 1))) i--;
        // Words that start with an apostrophe are quotes, not contractions.
        while (i < s.length() && s.charAt(i) == '\'') i++;
        return s.subSequence(i, s.length()).toString();
    }
}
