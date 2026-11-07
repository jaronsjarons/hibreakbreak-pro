package local.claude.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * The key area. Keys are drawn once per page switch (KeysView); the shift and enter keys are separate small views
 * on top so a shift-state or enter-label change only redraws that key. No press highlight, no pop-ups, no animation.
 * Letters act on touch-down; long-press swaps the letter for its symbol.
 */
final class KeyboardView extends ViewGroup {
    static final int SHIFT = -1, DEL = -2, SYM = -3, ABC = -4, SYM2 = -5, ENTER = -6, DEL_WORD = -7, SPACE = 32;
    static final int EMOJI_KEY = -8, EMOJI_PREV = -9, EMOJI_NEXT = -10, EMOJI = -11, EMOJI_SEARCH = -12;
    static final int ARROW_LEFT = -13, ARROW_RIGHT = -14, NUM_DOT = -15;   // NUM_DOT: the number pad's plain '.'   // EMOJI keys type their label
    static final int PAGE_ABC = 0, PAGE_SYM = 1, PAGE_SYM2 = 2, PAGE_EMOJI = 3, PAGE_NUM = 4, PAGE_PHONE = 5;
    static final int SHIFT_OFF = 0, SHIFT_ONE = 1, SHIFT_LOCK = 2;

    interface Listener {
        void onKey(int code);              // a key acted (letters already shifted by the IME)
        void onLongPress(Key key);         // letters: symbol replaces letter; ',' / '.' have their own menus
        void onGeometry(float[] keyX, float[] keyY);   // letter key centres a..z, in key widths
        void onText(String text);          // an emoji key
        void onCursorStart();              // a finger on the space bar started sliding: undo that space
        void onCursor(int step);           // move the cursor one character (-1 left, +1 right)
        void onCursorEnd();
    }

    static final class Key {
        final int code; final String label, upper; final String hint; final float x, w; final int row;
        float l, t, r, b;
        Key(int code, String label, String hint, float x, float w, int row) {
            this.code = code; this.label = label; this.upper = label.toUpperCase(); this.hint = hint; this.x = x; this.w = w; this.row = row;
        }
        boolean isFunction() { return code < 0 && code != EMOJI; }
        /** Keys that wait for finger-up because a long-press does something other than a symbol swap. */
        boolean actsOnUp() { return code == ',' || code == '.' || code == EMOJI_KEY; }   // NUM_DOT types at once
    }

    private static final boolean DEBUG_TOUCH = false;   // true: log every touch to compare with the kernel's (getevent)
    private static final int LONG_PRESS_MS = 320;
    private static final int REPEAT_START_MS = 380, REPEAT_MS = 220;   // holding delete erases a word per step
    private static final int ARROW_REPEAT_MS = 60;

    private final float dp;
    private final Key[][] pages = new Key[6][];
    private final int[] pageRows = new int[6];
    // Settings (Fast Keys setup screen): row height, a number row above the letters, arrow keys beside space.
    private int rowDp = 54;
    private boolean numberRow, arrows;
    private int page = PAGE_ABC;
    private int shiftState = SHIFT_OFF;
    private String enterLabel = null;   // null = return arrow
    private boolean incognito;           // private field: the space bar says so
    private Listener listener;

    private final KeysView keysView;
    private final ShiftView shiftView;
    private final EnterView enterView;
    private final PreviewView preview;
    final Paint keyPaint = new Paint(), fnPaint = new Paint(), labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG), smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG), bgPaint = new Paint();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Key> down = new SparseArray<>();
    private Key longKey, repeatKey;
    private final Runnable longRunnable = new Runnable() {
        public void run() {
            Key k = longKey;
            longKey = null;
            if (k == null || listener == null) return;
            lastLong = k;
            if (k.hint != null && k.code != '.') preview.setText(k.hint); else hidePreview();
            listener.onLongPress(k);
        }
    };
    private static final int HIDE_DELAY_MS = 90;
    private final Runnable hideRunnable = new Runnable() {
        public void run() { if (down.size() == 0) hidePreview(); }
    };
    private final Runnable repeatRunnable = new Runnable() {
        public void run() {
            if (repeatKey == null || listener == null) return;
            boolean del = repeatKey.code == DEL;
            listener.onKey(del ? DEL_WORD : repeatKey.code);   // delete: a word per step; arrows: a character
            handler.postDelayed(this, del ? REPEAT_MS : ARROW_REPEAT_MS);
        }
    };

    KeyboardView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
        bgPaint.setColor(0xFFD4D4D4);
        keyPaint.setColor(0xFFFFFFFF);
        fnPaint.setColor(0xFFB8B8B8);
        labelPaint.setColor(0xFF000000);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(23 * sp);
        labelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        hintPaint.setColor(0xFF000000);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        hintPaint.setTextSize(12.5f * sp);
        hintPaint.setTypeface(Typeface.DEFAULT_BOLD);
        smallPaint.setColor(0xFF000000);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        smallPaint.setTextSize(16 * sp);
        smallPaint.setTypeface(Typeface.DEFAULT_BOLD);
        iconPaint.setColor(0xFF000000);
        iconPaint.setStrokeWidth(2.2f * dp);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        buildPages();
        keysView = new KeysView(ctx);
        shiftView = new ShiftView(ctx);
        enterView = new EnterView(ctx);
        // The keys are rendered once into a GPU layer; a keypress frame only composites that one texture.
        keysView.setLayerType(LAYER_TYPE_HARDWARE, null);
        addView(keysView);
        addView(shiftView);
        addView(enterView);
        preview = new PreviewView(ctx);
        preview.setVisibility(INVISIBLE);
        addView(preview);
        setClipChildren(false);   // the top row's pop-up draws over the suggestion strip
        setBackgroundColor(0xFFD4D4D4);
        setMotionEventSplittingEnabled(false);
    }

    void setListener(Listener l) { listener = l; }
    int page() { return page; }

    void setPage(int p) {
        if (p == page) return;
        page = p;
        layoutKeys();
        keysView.invalidate();
        requestLayout();
        shiftView.invalidate();
        enterView.invalidate();
    }

    void setShiftState(int s) {
        if (s == shiftState) return;
        boolean capsChanged = (s == SHIFT_OFF) != (shiftState == SHIFT_OFF);
        shiftState = s;
        shiftView.invalidate();
        // Letters show capitals while shift is on (sentence start), lower case after.
        if (capsChanged && page == PAGE_ABC) keysView.invalidate();
    }

    void setEnterLabel(String label) {
        if (label == null ? enterLabel == null : label.equals(enterLabel)) return;
        enterLabel = label;
        enterView.invalidate();
    }

    void setIncognito(boolean on) {
        if (on == incognito) return;
        incognito = on;
        keysView.invalidate();
    }

    void cancelTouches() {
        handler.removeCallbacks(hideRunnable);
        hidePreview();
        handler.removeCallbacks(longRunnable);
        handler.removeCallbacks(repeatRunnable);
        down.clear();
        longKey = null; repeatKey = null;
    }

    // ---- layout ----

    private void buildPages() {
        String r1 = "qwertyuiop", h1 = "1234567890";
        String r2 = "asdfghjkl", h2 = "@#&*-+=()";
        String r3 = "zxcvbnm", h3 = "_$\"':;/";
        int o = numberRow ? 1 : 0;
        java.util.ArrayList<Key> abc = new java.util.ArrayList<>();
        if (numberRow) for (int i = 0; i < 10; i++) abc.add(ch(h1.charAt(i), (char) 0, i, 0));
        for (int i = 0; i < 10; i++) abc.add(ch(r1.charAt(i), numberRow ? (char) 0 : h1.charAt(i), i, o));
        for (int i = 0; i < 9; i++) abc.add(ch(r2.charAt(i), h2.charAt(i), 0.5f + i, 1 + o));
        abc.add(new Key(SHIFT, "", null, 0, 1.5f, 2 + o));
        for (int i = 0; i < 7; i++) abc.add(ch(r3.charAt(i), h3.charAt(i), 1.5f + i, 2 + o));
        abc.add(new Key(DEL, "", null, 8.5f, 1.5f, 2 + o));
        int b = 3 + o;
        abc.add(new Key(SYM, "123", null, 0, 1.5f, b));
        abc.add(new Key(EMOJI_KEY, "\uD83D\uDE42", null, 1.5f, 1, b));   // emoji where the comma was (comma = period slide left)
        if (arrows) {
            abc.add(new Key(ARROW_LEFT, "\u2190", null, 2.5f, 1, b));
            abc.add(new Key(SPACE, "", null, 3.5f, 3, b));
            abc.add(new Key(ARROW_RIGHT, "\u2192", null, 6.5f, 1, b));
        } else {
            abc.add(new Key(SPACE, "", null, 2.5f, 5, b));
        }
        abc.add(new Key('.', ".", ",!?", 7.5f, 1, b));
        abc.add(new Key(ENTER, "", null, 8.5f, 1.5f, b));
        pages[PAGE_ABC] = abc.toArray(new Key[0]);
        pageRows[PAGE_ABC] = 4 + o;
        if (pages[PAGE_EMOJI] == null) setEmojiPage(new String[0]);

        pages[PAGE_SYM] = symPage("1234567890", "@#$_&-+()/", "*\"':;!?", new Key(SYM2, "{&=", null, 0, 1.5f, 2));
        pages[PAGE_SYM2] = symPage("~`|•√π÷×§∆", "£€¥^°={}\\%", "©™✓[]<>", new Key(SYM, "123", null, 0, 1.5f, 2));
        pages[PAGE_NUM] = numPage("-,.");
        pages[PAGE_PHONE] = numPage("+*#");
        pageRows[PAGE_SYM] = pageRows[PAGE_SYM2] = pageRows[PAGE_EMOJI] = pageRows[PAGE_NUM] = pageRows[PAGE_PHONE] = 4;
    }

    /** Number pad for number and phone fields: 1-9 in a 3x3 block, extras down the right, 0 at the bottom. */
    private Key[] numPage(String extras) {
        java.util.ArrayList<Key> k = new java.util.ArrayList<>();
        String digits = "123456789";
        for (int i = 0; i < 9; i++) k.add(new Key(digits.charAt(i), String.valueOf(digits.charAt(i)), null, (i % 3) * 2.6f, 2.6f, i / 3));
        k.add(new Key(extras.charAt(0), String.valueOf(extras.charAt(0)), null, 7.8f, 2.2f, 0));
        k.add(new Key(extras.charAt(1), String.valueOf(extras.charAt(1)), null, 7.8f, 2.2f, 1));
        k.add(new Key(DEL, "", null, 7.8f, 2.2f, 2));
        k.add(new Key(extras.charAt(2) == '.' ? NUM_DOT : extras.charAt(2), String.valueOf(extras.charAt(2)), null, 0, 2.6f, 3));
        k.add(new Key('0', "0", null, 2.6f, 2.6f, 3));
        k.add(new Key(ABC, "abc", null, 5.2f, 2.6f, 3));
        k.add(new Key(ENTER, "", null, 7.8f, 2.2f, 3));
        return k.toArray(new Key[0]);
    }

    /** Apply the setup-screen settings; rebuilds the keys only when something changed. */
    void configure(int rowDp, boolean numberRow, boolean arrows) {
        if (rowDp == this.rowDp && numberRow == this.numberRow && arrows == this.arrows) return;
        this.rowDp = rowDp; this.numberRow = numberRow; this.arrows = arrows;
        buildPages();
        geometryUnit = 0;
        requestLayout();
        keysView.invalidate();
        shiftView.invalidate();
        enterView.invalidate();
    }

    private Key[] symPage(String r1, String r2, String r3, Key toggle) {
        Key[] k = new Key[10 + 10 + 9 + 5];
        int n = 0;
        for (int i = 0; i < 10; i++) k[n++] = ch(r1.charAt(i), (char) 0, i, 0);
        for (int i = 0; i < 10; i++) k[n++] = ch(r2.charAt(i), (char) 0, i, 1);
        k[n++] = toggle;
        for (int i = 0; i < 7; i++) k[n++] = ch(r3.charAt(i), (char) 0, 1.5f + i, 2);
        k[n++] = new Key(DEL, "", null, 8.5f, 1.5f, 2);
        bottomRow(k, n, new Key(ABC, "abc", null, 0, 1.5f, 3));
        return k;
    }

    /** The emoji page: up to 30 emoji in three rows, then abc, previous/next page, space, delete, enter. */
    void setEmojiPage(String[] emoji) {
        Key[] k = new Key[30 + 7];
        int n = 0;
        for (int i = 0; i < 30; i++) k[n++] = new Key(EMOJI, i < emoji.length ? emoji[i] : "", null, i % 10, 1, i / 10);
        k[n++] = new Key(ABC, "abc", null, 0, 1.5f, 3);
        k[n++] = new Key(EMOJI_PREV, "\u2039", null, 1.5f, 1, 3);
        k[n++] = new Key(EMOJI_SEARCH, "\uD83D\uDD0D", null, 2.5f, 1, 3);
        k[n++] = new Key(SPACE, "", null, 3.5f, 3, 3);
        k[n++] = new Key(EMOJI_NEXT, "\u203A", null, 6.5f, 1, 3);
        k[n++] = new Key(DEL, "", null, 7.5f, 1, 3);
        k[n++] = new Key(ENTER, "", null, 8.5f, 1.5f, 3);
        pages[PAGE_EMOJI] = k;
        if (page == PAGE_EMOJI) {
            layoutKeys();
            keysView.invalidate();
            requestLayout();
        }
    }

    private int bottomRow(Key[] k, int n, Key modeKey) {
        k[n++] = modeKey;
        k[n++] = new Key(',', ",", null, 1.5f, 1, 3);
        k[n++] = new Key(SPACE, "", null, 2.5f, 5, 3);
        k[n++] = new Key('.', ".", ",!?", 7.5f, 1, 3);
        k[n++] = new Key(ENTER, "", null, 8.5f, 1.5f, 3);
        return n;
    }

    private static Key ch(char c, char hint, float x, int row) {
        return new Key(c, String.valueOf(c), hint == 0 ? null : String.valueOf(hint), x, 1, row);
    }

    private int baseRowPx() { return Math.round(rowDp * dp); }
    private int totalHeight() { return baseRowPx() * (numberRow ? 5 : 4); }
    /** Every page fills the same height (no jump when switching), so pages with fewer rows get taller keys. */
    private float pageRowPx(int p) { return totalHeight() / (float) pageRows[p]; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = totalHeight();
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutKeys();
        keysView.layout(0, 0, r - l, b - t);
        place(shiftView, find(SHIFT));
        place(enterView, find(ENTER));
        float unit = (r - l) / 10f;
        preview.layout(0, 0, Math.round(unit * 1.3f), baseRowPx());
    }

    private void layoutKeys() {
        float unit = getMeasuredWidth() / 10f, rh = pageRowPx(page), rhAbc = pageRowPx(PAGE_ABC);
        for (Key k : pages[page]) {
            k.l = k.x * unit; k.r = (k.x + k.w) * unit; k.t = k.row * rh; k.b = (k.row + 1) * rh;
        }
        if (unit > 0 && listener != null && unit != geometryUnit) {
            geometryUnit = unit;
            float[] cx = new float[26], cy = new float[26];
            for (Key k : pages[PAGE_ABC]) {
                if (k.code >= 'a' && k.code <= 'z') {
                    cx[k.code - 'a'] = k.x + k.w / 2;
                    cy[k.code - 'a'] = (k.row + 0.5f) * rhAbc / unit;
                }
            }
            listener.onGeometry(cx, cy);
        }
    }

    private float geometryUnit;
    float tapX, tapY;   // where the last key was touched, in key widths from the top-left
    long tapTime;       // when (uptime ms)
    boolean tapOverlap; // pressed while another key was still held

    private static void place(View v, Key k) {
        if (k == null) { v.layout(0, 0, 0, 0); return; }
        v.layout(Math.round(k.l), Math.round(k.t), Math.round(k.r), Math.round(k.b));
    }

    private Key find(int code) {
        for (Key k : pages[page]) if (k.code == code) return k;
        return null;
    }

    private Key hit(float x, float y) {
        float rh = pageRowPx(page);
        int row = Math.max(0, Math.min(pageRows[page] - 1, (int) (y / rh)));
        Key best = null; float bestD = Float.MAX_VALUE;
        for (Key k : pages[page]) {
            if (k.row != row) continue;
            if (x >= k.l && x < k.r) return k;
            float d = Math.min(Math.abs(x - k.l), Math.abs(x - k.r));
            if (d < bestD) { bestD = d; best = k; }
        }
        return best;
    }

    // ---- touch ----

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        int idx = ev.getActionIndex();
        if (DEBUG_TOUCH && a != MotionEvent.ACTION_MOVE) {
            android.util.Log.d("FastKeysTouch", MotionEvent.actionToString(a) + " id=" + ev.getPointerId(idx) + " x="
                    + (int) ev.getX(idx) + " y=" + (int) ev.getY(idx) + " t=" + ev.getEventTime() + " down=" + down.size());
        }
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                Timing.down(ev.getEventTime());
                // A new finger ends the previous finger's long-press/repeat (fast rolling typing).
                handler.removeCallbacks(longRunnable);
                handler.removeCallbacks(repeatRunnable);
                longKey = null; repeatKey = null;
                handler.removeCallbacks(hideRunnable);
                flushHeldKeys(ev);
                Key k = hit(ev.getX(idx), ev.getY(idx));
                if (k == null) break;
                tapX = ev.getX(idx) / (getWidth() / 10f);
                tapY = ev.getY(idx) / (getWidth() / 10f);
                tapTime = ev.getEventTime();
                tapOverlap = down.size() > 0;   // another finger still on a key: the two may land in either order
                down.put(ev.getPointerId(idx), k);
                if (k.code == '.') { periodId = ev.getPointerId(idx); periodX = ev.getX(idx); }
                if (k.code == SPACE) { spaceId = ev.getPointerId(idx); spaceX = ev.getX(idx); cursorMode = false; }
                lastLong = null;
                String pv = previewText(k);
                if (k.code == EMOJI) { if (listener != null && !k.label.isEmpty()) listener.onText(k.label); }
                else if (!k.actsOnUp() && listener != null) listener.onKey(k.code);
                showPreview(k, pv);
                if (k.code == DEL || k.code == ARROW_LEFT || k.code == ARROW_RIGHT) {
                    repeatKey = k;
                    handler.postDelayed(repeatRunnable, REPEAT_START_MS);
                } else if (k.hint != null || k.code == ',' || k.code == EMOJI_KEY || k.code == SPACE) {
                    longKey = k;
                    handler.postDelayed(longRunnable, LONG_PRESS_MS);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = ev.getPointerId(idx);
                Key k = down.get(id);
                down.remove(id);
                if (k == null) break;
                if (k == longKey) { handler.removeCallbacks(longRunnable); longKey = null; }
                if (k == repeatKey) { handler.removeCallbacks(repeatRunnable); repeatKey = null; }
                int slide = k.code == '.' && id == periodId ? periodSlide(ev.getX(idx)) : 0;
                if (k.code == '.') periodId = -1;
                if (slide != 0 && listener != null) listener.onKey(slideChar(slide));
                else if (k.actsOnUp() && lastLong != k && listener != null) listener.onKey(k.code);
                if (lastLong == k) lastLong = null;
                if (k.code == SPACE && id == spaceId) {
                    spaceId = -1;
                    if (cursorMode && listener != null) listener.onCursorEnd();
                    cursorMode = false;
                }
                // Hide a moment later: in fast typing the next key moves the pop-up instead (one repaint per key).
                if (down.size() == 0) handler.postDelayed(hideRunnable, HIDE_DELAY_MS);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (spaceId >= 0) {
                    // Slide along the space bar: the cursor follows, one character per 0.35 key widths.
                    int si = ev.findPointerIndex(spaceId);
                    if (si >= 0 && listener != null) {
                        float dx = ev.getX(si) - spaceX, step = 0.35f * getWidth() / 10f;
                        if (!cursorMode && Math.abs(dx) > 16 * dp) {
                            cursorMode = true;
                            if (longKey != null && longKey.code == SPACE) { handler.removeCallbacks(longRunnable); longKey = null; }
                            listener.onCursorStart();
                            spaceX += Math.signum(dx) * 16 * dp;
                            dx = ev.getX(si) - spaceX;
                        }
                        while (cursorMode && Math.abs(dx) >= step) {
                            int dir = dx > 0 ? 1 : -1;
                            listener.onCursor(dir);
                            spaceX += dir * step;
                            dx = ev.getX(si) - spaceX;
                        }
                    }
                }
                // Sliding off the period key is a comma/! gesture: don't let the hold open the punctuation strip.
                if (periodId >= 0 && longKey != null && longKey.code == '.') {
                    int pi = ev.findPointerIndex(periodId);
                    if (pi >= 0 && periodSlide(ev.getX(pi)) != 0) { handler.removeCallbacks(longRunnable); longKey = null; }
                }
                if (periodId >= 0) {
                    int pi = ev.findPointerIndex(periodId);
                    if (pi >= 0) { int sl = periodSlide(ev.getX(pi)); preview.setText(String.valueOf(slideChar(sl))); }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                periodId = -1;
                if (cursorMode && listener != null) listener.onCursorEnd();
                spaceId = -1;
                cursorMode = false;
                hidePreview();
                cancelTouches();
                break;
        }
        return true;
    }

    /**
     * Keys that type on finger-up (period, comma, emoji) type now if another key goes down first, so fast typing
     * keeps its order: "word" + period + space gives "word. ", never "word .".
     */
    private void flushHeldKeys(MotionEvent ev) {
        for (int i = down.size() - 1; i >= 0; i--) {
            Key h = down.valueAt(i);
            int pid = down.keyAt(i);
            if (!h.actsOnUp()) continue;
            down.removeAt(i);   // its finger-up then does nothing
            if (h == lastLong) { lastLong = null; continue; }   // its long-press already acted
            int pi = ev.findPointerIndex(pid);
            int slide = h.code == '.' && pid == periodId && pi >= 0 ? periodSlide(ev.getX(pi)) : 0;
            if (h.code == '.') periodId = -1;
            if (listener != null) listener.onKey(slide != 0 ? slideChar(slide) : h.code);
        }
    }

    private int spaceId = -1;
    private float spaceX;
    private boolean cursorMode;

    private int periodId = -1;
    private float periodX;

    /** Hold period and slide: -2 far left (!), -1 left (comma), 1 or 2 right (?), 0 no slide. */
    private int periodSlide(float x) {
        // The comma gets a wide zone (18-80 dp left, owner Nov 6); ! needs a long slide past it.
        float dx = x - periodX, min = 18 * dp, far = 50 * dp, farLeft = 80 * dp;
        return dx <= -farLeft ? -2 : dx <= -min ? -1 : dx >= far ? 2 : dx >= min ? 1 : 0;
    }

    /** Period slide result: left = comma, further left = !, right = ? (owner, Nov 6: ! only on the left). */
    private static char slideChar(int slide) {
        return slide == -2 ? '!' : slide < 0 ? ',' : slide > 0 ? '?' : '.';
    }

    private Key lastLong;

    // ---- key pop-up: one small view made once; a press only moves it and redraws its own letter ----

    private String previewText(Key k) {
        if (k.code <= 0 || k.code == SPACE) return null;
        return page == PAGE_ABC && shiftState != SHIFT_OFF ? k.upper : k.label;
    }

    private void showPreview(Key k, String text) {
        if (text == null) { hidePreview(); return; }
        float w = preview.getWidth(), h = preview.getHeight();
        float x = Math.max(0, Math.min(getWidth() - w, (k.l + k.r) / 2 - w / 2));
        float y = k.t - h + (k.b - k.t) * 0.3f;
        if (preview.getTranslationX() != x) preview.setTranslationX(x);
        if (preview.getTranslationY() != y) preview.setTranslationY(y);
        preview.setText(text);
        if (preview.getVisibility() != VISIBLE) preview.setVisibility(VISIBLE);
    }

    private void hidePreview() {
        if (preview.getVisibility() != INVISIBLE) preview.setVisibility(INVISIBLE);
    }

    final class PreviewView extends View {
        private String text = "";
        private final Paint face = new Paint(), big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        PreviewView(Context c) {
            super(c);
            face.setColor(0xFFFFFFFF);
            big.setColor(0xFF000000);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(labelPaint.getTextSize() * 1.45f);
        }
        void setText(String t) {
            if (t.equals(text)) return;
            text = t;
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float in = dp;
            box.set(in, in, getWidth() - in, getHeight() - in);
            c.drawRoundRect(box, 5 * dp, 5 * dp, face);
            c.drawText(text, getWidth() / 2f, getHeight() / 2f + big.getTextSize() * 0.36f, big);
        }
    }   // the key whose long-press fired, so its finger-up doesn't also type it

    // ---- drawing ----

    private final RectF rect = new RectF();

    void drawKeyFace(Canvas c, Key k, float ox, float oy) {
        float g = 2.5f * dp;
        rect.set(k.l - ox + g, k.t - oy + g, k.r - ox - g, k.b - oy - g);
        boolean fn = k.isFunction();
        c.drawRoundRect(rect, 4 * dp, 4 * dp, fn ? fnPaint : keyPaint);
    }

    final class KeysView extends View {
        KeysView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            for (Key k : pages[page]) {
                if (k.code == SHIFT || k.code == ENTER) continue;
                drawKeyFace(c, k, 0, 0);
                float cx = (k.l + k.r) / 2, cy = (k.t + k.b) / 2;
                if (k.code == DEL) { drawBackspace(c, cx, cy); continue; }
                if (k.code == EMOJI_KEY) { drawSmiley(c, cx, cy); continue; }   // line drawings, not colour emoji, on e-ink
                if (k.code == EMOJI_SEARCH) { drawMagnifier(c, cx, cy); continue; }
                if (k.code == SPACE) {   // plain key, no bar; in a private field it says "incognito"
                    if (incognito) c.drawText("incognito", cx, cy + smallPaint.getTextSize() * 0.36f, smallPaint);
                    continue;
                }
                if (k.code == EMOJI || k.code == EMOJI_KEY || k.code == EMOJI_PREV || k.code == EMOJI_NEXT || k.code == EMOJI_SEARCH) {
                    c.drawText(k.label, cx, cy + labelPaint.getTextSize() * 0.36f, labelPaint);
                    continue;
                }
                if (k.code < 0) { c.drawText(k.label, cx, cy + smallPaint.getTextSize() * 0.36f, smallPaint); continue; }
                String label = page == PAGE_ABC && shiftState != SHIFT_OFF ? k.upper : k.label;
                c.drawText(label, cx, cy + labelPaint.getTextSize() * 0.36f + (k.hint != null ? 3 * dp : 0), labelPaint);
                if (k.hint != null) c.drawText(k.hint, k.r - 6 * dp, k.t + 6 * dp + hintPaint.getTextSize(), hintPaint);
            }
        }
        private void drawSmiley(Canvas c, float cx, float cy) {
            float r = 10 * dp;
            iconPaint.setStyle(Paint.Style.STROKE);
            c.drawCircle(cx, cy, r, iconPaint);
            iconPaint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - r * 0.36f, cy - r * 0.25f, 1.4f * dp, iconPaint);
            c.drawCircle(cx + r * 0.36f, cy - r * 0.25f, 1.4f * dp, iconPaint);
            iconPaint.setStyle(Paint.Style.STROKE);
            c.drawArc(cx - r * 0.5f, cy - r * 0.35f, cx + r * 0.5f, cy + r * 0.55f, 25, 130, false, iconPaint);
        }
        private void drawMagnifier(Canvas c, float cx, float cy) {
            float r = 6.5f * dp, ox = cx - 2 * dp, oy = cy - 2 * dp;
            iconPaint.setStyle(Paint.Style.STROKE);
            c.drawCircle(ox, oy, r, iconPaint);
            float d = r * 0.7071f;
            c.drawLine(ox + d, oy + d, ox + d + 5.5f * dp, oy + d + 5.5f * dp, iconPaint);
        }
        private void drawBackspace(Canvas c, float cx, float cy) {
            float w = 13 * dp, h = 8.5f * dp;
            Path p = new Path();
            p.moveTo(cx - w, cy); p.lineTo(cx - w / 2.4f, cy - h); p.lineTo(cx + w, cy - h);
            p.lineTo(cx + w, cy + h); p.lineTo(cx - w / 2.4f, cy + h); p.close();
            iconPaint.setStyle(Paint.Style.STROKE);
            c.drawPath(p, iconPaint);
            float xc = cx + w * 0.28f, d = 3.6f * dp;
            c.drawLine(xc - d, cy - d, xc + d, cy + d, iconPaint);
            c.drawLine(xc - d, cy + d, xc + d, cy - d, iconPaint);
        }
    }

    final class ShiftView extends View {
        ShiftView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            Key k = find(SHIFT);
            if (k == null) return;
            drawKeyFace(c, k, k.l, k.t);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = 10 * dp;
            Path p = new Path();
            p.moveTo(cx, cy - s); p.lineTo(cx + s, cy); p.lineTo(cx + s * 0.45f, cy);
            p.lineTo(cx + s * 0.45f, cy + s * 0.8f); p.lineTo(cx - s * 0.45f, cy + s * 0.8f);
            p.lineTo(cx - s * 0.45f, cy); p.lineTo(cx - s, cy); p.close();
            iconPaint.setStyle(shiftState == SHIFT_OFF ? Paint.Style.STROKE : Paint.Style.FILL_AND_STROKE);
            c.drawPath(p, iconPaint);
            if (shiftState == SHIFT_LOCK) {
                iconPaint.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * 0.6f, cy + s * 1.35f, cx + s * 0.6f, cy + s * 1.35f, iconPaint);
            }
            iconPaint.setStyle(Paint.Style.STROKE);
        }
    }

    final class EnterView extends View {
        EnterView(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            Key k = find(ENTER);
            if (k == null) return;
            drawKeyFace(c, k, k.l, k.t);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (enterLabel != null) { c.drawText(enterLabel, cx, cy + smallPaint.getTextSize() * 0.36f, smallPaint); return; }
            float s = 9 * dp;
            iconPaint.setStyle(Paint.Style.STROKE);
            c.drawLine(cx + s, cy - s, cx + s, cy + s * 0.3f, iconPaint);
            c.drawLine(cx + s, cy + s * 0.3f, cx - s, cy + s * 0.3f, iconPaint);
            c.drawLine(cx - s, cy + s * 0.3f, cx - s * 0.45f, cy - s * 0.25f, iconPaint);
            c.drawLine(cx - s, cy + s * 0.3f, cx - s * 0.45f, cy + s * 0.85f, iconPaint);
        }
    }
}
