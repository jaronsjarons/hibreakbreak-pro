package local.claude.pie;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.io.InputStream;

/**
 * Draws the pie. Everything is pre-rendered once into two bitmaps (rings, icons); a frame is
 * at most bitmap + one sector fill + bitmap, and a new frame is only asked for when the open
 * state or the selected slot changes. No animation.
 */
final class PieView extends View {
    static final int NONE = -1;
    private static final int R = Layout.R_OUTER, SIZE = R + 1;

    private final Bitmap rings;
    private Bitmap icons;
    private final Path[] sectors = new Path[Layout.INNER.length + Layout.OUTER.length];
    private final Paint select = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean open;
    private int centerY, selected = NONE;

    PieView(Context c, Layout.Slot[] outer) {
        super(c);
        select.setColor(Layout.SELECT);
        for (int i = 0; i < Layout.INNER.length; i++)
            sectors[i] = sector(Layout.R_HOLE, Layout.R_INNER, i, Layout.INNER.length);
        for (int i = 0; i < Layout.OUTER.length; i++)
            sectors[Layout.INNER.length + i] = sector(Layout.R_INNER, Layout.R_OUTER, i, Layout.OUTER.length);
        rings = drawRings();
        icons = drawIcons(c, outer);
    }

    /** Re-renders the icons after the slots were changed in Settings. */
    void setOuter(Layout.Slot[] outer) {
        Bitmap old = icons;
        icons = drawIcons(getContext(), outer);
        old.recycle();
        invalidate();
    }

    void show(int y) { tellEink(true); open = true; centerY = y; selected = NONE; invalidate(); }
    void hide() { tellEink(false); open = false; selected = NONE; invalidate(); }

    /** Tells the HiBreakBreak Pro e-ink controller (if installed) that the pie opened or closed, so it can pause the
     *  screen driver's auto clean meanwhile: that clean keeps the CPU busy and made the pie lag. No-op elsewhere. */
    private void tellEink(boolean nowOpen) {
        if (nowOpen == open) return;
        try {
            getContext().sendBroadcast(new android.content.Intent("local.claude.pie.PIE_OPEN")
                    .setPackage("com.lmqr.ha9_comp_service").putExtra("open", nowOpen));
        } catch (RuntimeException ignored) { }
    }
    boolean isOpen() { return open; }
    int selected() { return selected; }

    /** Updates the selection from screen coordinates; returns true when it changed. */
    boolean track(float rawX, float rawY, int screenRight) {
        int s = slotAt(screenRight - rawX, rawY - centerY);
        if (s == selected) return false;
        selected = s;
        invalidate();
        return true;
    }

    /** dx = distance left of the edge, dy = below the centre. */
    static int slotAt(float dx, float dy) {
        double d = Math.hypot(dx, dy);
        if (d < Layout.R_HOLE) return NONE;
        double deg = Math.toDegrees(Math.atan2(Math.max(dx, 0), -dy)); // 0 = up, 180 = down
        if (d < Layout.R_INNER) return clamp((int) (deg / (180.0 / Layout.INNER.length)), Layout.INNER.length);
        return Layout.INNER.length + clamp((int) (deg / (180.0 / Layout.OUTER.length)), Layout.OUTER.length);
    }

    private static int clamp(int i, int n) { return i < 0 ? 0 : i >= n ? n - 1 : i; }

    @Override protected void onDraw(Canvas c) {
        if (!open) return;
        c.translate(getWidth() - SIZE, centerY - R);
        c.drawBitmap(rings, 0, 0, null);
        if (selected != NONE) c.drawPath(sectors[selected], select);
        c.drawBitmap(icons, 0, 0, null);
    }

    // ---- one-time rendering, in bitmap coordinates (centre at x = R, y = R) ----

    private static Path sector(int r0, int r1, int i, int n) {
        float span = 180f / n, start = -90f - i * span; // sweep counter-clockwise from the top
        Path p = new Path();
        p.arcTo(new RectF(R - r1, R - r1, R + r1, R + r1), start, -span, true);
        p.arcTo(new RectF(R - r0, R - r0, R + r0, R + r0), start - span, span, false);
        p.close();
        return p;
    }

    private static float[] at(int r, double deg) {
        double a = Math.toRadians(deg);
        return new float[] { (float) (R - r * Math.sin(a)), (float) (R - r * Math.cos(a)) };
    }

    private Bitmap drawRings() {
        Bitmap b = Bitmap.createBitmap(SIZE, 2 * R + 1, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Layout.RING);
        Path ring = new Path();
        ring.addCircle(R, R, Layout.R_OUTER, Path.Direction.CW);
        ring.addCircle(R, R, Layout.R_HOLE, Path.Direction.CCW);
        c.drawPath(ring, fill);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Layout.DIVIDER);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2);
        c.drawCircle(R, R, Layout.R_OUTER - 1, line);
        c.drawCircle(R, R, Layout.R_INNER, line);
        c.drawCircle(R, R, Layout.R_HOLE, line);
        for (int i = 1; i < Layout.INNER.length; i++) divider(c, line, Layout.R_HOLE, Layout.R_INNER, 180.0 * i / Layout.INNER.length);
        for (int i = 1; i < Layout.OUTER.length; i++) divider(c, line, Layout.R_INNER, Layout.R_OUTER, 180.0 * i / Layout.OUTER.length);
        return b;
    }

    private static void divider(Canvas c, Paint p, int r0, int r1, double deg) {
        float[] a = at(r0, deg), b = at(r1, deg);
        c.drawLine(a[0], a[1], b[0], b[1], p);
    }

    private Bitmap drawIcons(Context ctx, Layout.Slot[] outer) {
        Bitmap b = Bitmap.createBitmap(SIZE, 2 * R + 1, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        PackageManager pm = ctx.getPackageManager();
        for (int i = 0; i < Layout.INNER.length; i++) {
            Layout.Slot s = Layout.INNER[i];
            float[] p = at(Layout.R_ICON_INNER, 180.0 * (i + 0.5) / Layout.INNER.length);
            ring(c, p[0], p[1], Layout.ICON, s.color);
            Bitmap glyph = asset(ctx, s.asset);
            if (glyph != null) {
                float h = 35; // the PNGs carry their own padding (glyph is ~57% of the image)
                c.drawBitmap(glyph, null, new RectF(p[0] - h, p[1] - h, p[0] + h, p[1] + h),
                        new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
            }
        }
        for (int i = 0; i < Layout.OUTER.length; i++) {
            Layout.Slot s = outer[i];
            double deg = 180.0 * (i + 0.5) / Layout.OUTER.length;
            float[] p = at(Layout.R_ICON_OUTER, deg);
            if (s.pkg != null) appIcon(c, pm, s.pkg, s.cls, p[0], p[1], Layout.ICON, s.color);
            if (s.longPkg != null) {
                float[] q = at(Layout.R_ICON_OUTER - Layout.BADGE_SHIFT, deg);
                appIcon(c, pm, s.longPkg, s.longCls, q[0], q[1], Layout.BADGE, s.longColor);
            }
        }
        return b;
    }

    private static void ring(Canvas c, float x, float y, int size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        float w = size > 50 ? 2.5f : 2;
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawCircle(x, y, size / 2f - w / 2, p);
    }

    private static void appIcon(Canvas c, PackageManager pm, String pkg, String cls, float x, float y, int size, int color) {
        Drawable d;
        try { d = pm.getActivityIcon(new ComponentName(pkg, cls)); }
        catch (Exception e) {
            try { d = pm.getApplicationIcon(pkg); } catch (Exception e2) { d = null; }
        }
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, size / 2f, white);
        if (d != null) {
            float r = size / 2f - (size > 50 ? 5 : 3);
            c.save();
            Path clip = new Path();
            clip.addCircle(x, y, r, Path.Direction.CW);
            c.clipPath(clip);
            if (d instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
                float h = r * 1.5f; // 108 dp layers, 72 dp visible
                int l = Math.round(x - h), t = Math.round(y - h), rr = Math.round(x + h), b = Math.round(y + h);
                if (a.getBackground() != null) { a.getBackground().setBounds(l, t, rr, b); a.getBackground().draw(c); }
                if (a.getForeground() != null) { a.getForeground().setBounds(l, t, rr, b); a.getForeground().draw(c); }
            } else {
                d.setBounds(Math.round(x - r), Math.round(y - r), Math.round(x + r), Math.round(y + r));
                d.draw(c);
            }
            c.restore();
        }
        ring(c, x, y, size, color);
    }

    private static Bitmap asset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) { return BitmapFactory.decodeStream(in); }
        catch (Exception e) { return null; }
    }
}
