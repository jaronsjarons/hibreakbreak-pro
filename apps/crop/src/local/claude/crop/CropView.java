package local.claude.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows the picture fitted to the view with a crop box on top. Drag a corner (big touch zones for
 * e-ink fingers) to resize, drag inside the box to move it. Everything outside the box is washed
 * out so the kept part stands out on a grey e-ink panel. No animation: each move draws once.
 */
final class CropView extends View {
    private Bitmap bitmap;
    private final RectF image = new RectF();   // where the bitmap is drawn, in view pixels
    private final RectF box = new RectF();     // crop box, in view pixels, always inside image
    private final Paint wash = new Paint();
    private final Paint frame = new Paint();
    private final Paint handle = new Paint();
    private final Paint draw = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float touchZone, minSize, handleSize;
    private boolean square;
    private int dragCorner = -1;   // 0 TL, 1 TR, 2 BR, 3 BL, 4 move
    private float lastX, lastY;

    CropView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        touchZone = 36 * d; minSize = 48 * d; handleSize = 18 * d;
        wash.setColor(0xB3FFFFFF);
        frame.setColor(Color.BLACK); frame.setStyle(Paint.Style.STROKE); frame.setStrokeWidth(3 * d);
        handle.setColor(Color.BLACK);
        setBackgroundColor(Color.WHITE);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        layoutImage();
        resetBox();
        invalidate();
    }

    Bitmap bitmap() { return bitmap; }

    void setSquare(boolean on) {
        square = on;
        if (on) {
            float s = Math.min(box.width(), box.height());
            float cx = box.centerX(), cy = box.centerY();
            box.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            clampMove();
        }
        invalidate();
    }

    boolean isSquare() { return square; }

    /** The crop box in bitmap pixels. */
    Rect cropInBitmap() {
        float scale = bitmap.getWidth() / image.width();
        int l = Math.max(0, Math.round((box.left - image.left) * scale));
        int t = Math.max(0, Math.round((box.top - image.top) * scale));
        int r = Math.min(bitmap.getWidth(), Math.round((box.right - image.left) * scale));
        int b = Math.min(bitmap.getHeight(), Math.round((box.bottom - image.top) * scale));
        return new Rect(l, t, Math.max(l + 1, r), Math.max(t + 1, b));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (bitmap == null) return;
        layoutImage();
        resetBox();
    }

    private void layoutImage() {
        if (bitmap == null || getWidth() == 0) return;
        float pad = touchZone / 2;
        float aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad;
        float s = Math.min(aw / bitmap.getWidth(), ah / bitmap.getHeight());
        float w = bitmap.getWidth() * s, h = bitmap.getHeight() * s;
        float l = (getWidth() - w) / 2, t = (getHeight() - h) / 2;
        image.set(l, t, l + w, t + h);
    }

    private void resetBox() {
        box.set(image);
        if (square) setSquare(true);
    }

    @Override protected void onDraw(Canvas c) {
        if (bitmap == null) return;
        c.drawBitmap(bitmap, null, image, draw);
        c.drawRect(image.left, image.top, image.right, box.top, wash);
        c.drawRect(image.left, box.bottom, image.right, image.bottom, wash);
        c.drawRect(image.left, box.top, box.left, box.bottom, wash);
        c.drawRect(box.right, box.top, image.right, box.bottom, wash);
        c.drawRect(box, frame);
        float h = handleSize;
        c.drawRect(box.left - h / 2, box.top - h / 2, box.left + h / 2, box.top + h / 2, handle);
        c.drawRect(box.right - h / 2, box.top - h / 2, box.right + h / 2, box.top + h / 2, handle);
        c.drawRect(box.right - h / 2, box.bottom - h / 2, box.right + h / 2, box.bottom + h / 2, handle);
        c.drawRect(box.left - h / 2, box.bottom - h / 2, box.left + h / 2, box.bottom + h / 2, handle);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragCorner = cornerAt(x, y);
                if (dragCorner < 0 && box.contains(x, y)) dragCorner = 4;
                lastX = x; lastY = y;
                return dragCorner >= 0;
            case MotionEvent.ACTION_MOVE:
                if (dragCorner < 0) return false;
                if (dragCorner == 4) {
                    box.offset(x - lastX, y - lastY);
                    clampMove();
                } else {
                    moveCorner(dragCorner, x, y);
                }
                lastX = x; lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragCorner = -1;
                return true;
        }
        return false;
    }

    private int cornerAt(float x, float y) {
        float[][] p = {{box.left, box.top}, {box.right, box.top}, {box.right, box.bottom}, {box.left, box.bottom}};
        int best = -1; float bestD = touchZone;
        for (int i = 0; i < 4; i++) {
            float d = (float) Math.hypot(x - p[i][0], y - p[i][1]);
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void moveCorner(int corner, float x, float y) {
        x = Math.max(image.left, Math.min(image.right, x));
        y = Math.max(image.top, Math.min(image.bottom, y));
        float l = box.left, t = box.top, r = box.right, b = box.bottom;
        if (corner == 0 || corner == 3) l = Math.min(x, r - minSize); else r = Math.max(x, l + minSize);
        if (corner == 0 || corner == 1) t = Math.min(y, b - minSize); else b = Math.max(y, t + minSize);
        if (square) {
            float s = Math.min(r - l, b - t);
            if (corner == 0) { l = r - s; t = b - s; }
            else if (corner == 1) { r = l + s; t = b - s; }
            else if (corner == 2) { r = l + s; b = t + s; }
            else { l = r - s; b = t + s; }
        }
        box.set(Math.max(l, image.left), Math.max(t, image.top), Math.min(r, image.right), Math.min(b, image.bottom));
    }

    private void clampMove() {
        float dx = 0, dy = 0;
        if (box.left < image.left) dx = image.left - box.left;
        if (box.right > image.right) dx = image.right - box.right;
        if (box.top < image.top) dy = image.top - box.top;
        if (box.bottom > image.bottom) dy = image.bottom - box.bottom;
        box.offset(dx, dy);
        box.intersect(image);
    }
}
