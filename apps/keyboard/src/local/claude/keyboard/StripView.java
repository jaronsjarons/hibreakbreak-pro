package local.claude.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/** Suggestion strip: a row of slot views; a slot only redraws when its own text changes. */
final class StripView extends LinearLayout {
    interface Listener {
        void onPick(int index, String text);
        void onRemove(int index, String text);   // long-press: never suggest this word again
    }

    private static final int REMOVE_MS = 550;

    static final int MAX = 8;
    private final Slot[] slots = new Slot[MAX];
    private int count = -1;
    private Listener listener;
    private final float dp;

    StripView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFFFFFFFF);
        float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
        for (int i = 0; i < MAX; i++) {
            slots[i] = new Slot(ctx, i, sp);
            addView(slots[i], new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
        show(new String[]{"", "", ""}, -1);
    }

    void setListener(Listener l) { listener = l; }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(42 * dp), MeasureSpec.EXACTLY));
    }

    /** Shows items (1..MAX); bold marks the slot that space would apply (-1 none). */
    void show(String[] items, int bold) {
        if (items.length != count) {
            count = items.length;
            for (int i = 0; i < MAX; i++) slots[i].setVisibility(i < count ? VISIBLE : GONE);
        }
        for (int i = 0; i < count; i++) slots[i].set(items[i], i == bold);
    }

    final class Slot extends View {
        final int index;
        String text = "";
        boolean bold;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Slot(Context c, int index, float sp) {
            super(c);
            this.index = index;
            p.setColor(0xFF000000);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(19 * sp);
        }

        void set(String t, boolean b) {
            if (t.equals(text) && b == bold) return;
            text = t; bold = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            String t = text;
            float max = getWidth() - 8 * dp;
            if (p.measureText(t) > max) {
                int n = p.breakText(t, true, max - p.measureText("…"), null);
                t = t.substring(0, Math.max(0, n)) + "…";
            }
            c.drawText(t, getWidth() / 2f, getHeight() / 2f + p.getTextSize() * 0.36f, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && !text.isEmpty() && listener != null) {
                final String picked = text;
                listener.onPick(index, picked);   // a tap acts at once; holding on turns it into "remove"
                removeRunnable = new Runnable() { public void run() { listener.onRemove(index, picked); } };
                postDelayed(removeRunnable, REMOVE_MS);
            } else if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && removeRunnable != null) {
                removeCallbacks(removeRunnable);
                removeRunnable = null;
            }
            return true;
        }

        private Runnable removeRunnable;
    }
}
