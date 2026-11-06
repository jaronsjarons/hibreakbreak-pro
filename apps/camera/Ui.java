package local.claude.hibreakcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** E-ink friendly widgets: pure black and white, solid strokes, no animation. */
final class Ui {
    static final int INK = Color.BLACK, PAPER = Color.WHITE, MUTED = 0xFF8C8C8C;

    private Ui() {}

    /** Text button; selected = black fill with white text. */
    static TextView chip(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(26, 0, 26, 0);
        t.setMinHeight(92);
        select(t, false);
        return t;
    }

    /** Circular outlined text button (front/back switch). */
    static TextView round(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(INK);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(PAPER);
        d.setStroke(3, INK);
        t.setBackground(d);
        return t;
    }

    static void select(TextView t, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(48);
        d.setColor(on ? INK : PAPER);
        d.setStroke(3, INK);
        t.setBackground(d);
        t.setTextColor(on ? PAPER : INK);
    }

    /** Round shutter: black ring, black disc; grey disc while a photo is in progress. */
    static final class Shutter extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean busy;

        Shutter(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(8);
            ring.setColor(INK);
            setContentDescription("Take photo");
        }

        void setBusy(boolean b) {
            if (busy != b) { busy = b; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 8;
            canvas.drawCircle(cx, cy, r, ring);
            disc.setColor(busy ? MUTED : INK);
            canvas.drawCircle(cx, cy, r - 22, disc);
        }
    }

    /** Focus box over the preview: thin while focusing, thick when locked, crossed if focus failed. */
    static final class Focus extends View {
        static final int NONE = 0, SEARCHING = 1, LOCKED = 2, FAILED = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x, y;
        private int state = NONE;

        Focus(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(INK);
        }

        void show(float x, float y, int state) {
            this.x = x;
            this.y = y;
            this.state = state;
            invalidate();
        }

        void setState(int state) {
            if (this.state != NONE && this.state != state) { this.state = state; invalidate(); }
        }

        void clear() {
            if (state != NONE) { state = NONE; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            if (state == NONE) return;
            float h = getWidth() / 10f;
            RectF r = new RectF(x - h, y - h, x + h, y + h);
            // White halo keeps the box readable over dark scenes on a monochrome panel.
            p.setColor(PAPER);
            p.setStrokeWidth(state == LOCKED ? 14 : 8);
            canvas.drawRect(r, p);
            p.setColor(INK);
            p.setStrokeWidth(state == LOCKED ? 8 : 3);
            canvas.drawRect(r, p);
            if (state == FAILED) {
                canvas.drawLine(r.left, r.top, r.right, r.bottom, p);
                canvas.drawLine(r.right, r.top, r.left, r.bottom, p);
            }
        }
    }
}
