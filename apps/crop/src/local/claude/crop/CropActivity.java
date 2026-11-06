package local.claude.crop;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Quick image cropper for the HiBreak e-ink phone.
 * Share an image to "Crop" -> crop -> Send re-shares it (pick any app), Save keeps a copy.
 * An app asking for an image (GET_CONTENT / PICK) can choose "Crop an image": pick, crop, Done
 * hands the cropped picture straight back. From the launcher: pick an image, crop, Send.
 */
public final class CropActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int MAX_SIDE = 3000;
    private CropView crop;
    private TextView squareButton;
    private boolean returnResult;   // GET_CONTENT / PICK: hand the result back instead of sharing
    private String outMime = "image/jpeg";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        cleanOldOutput();
        Intent in = getIntent();
        String action = in.getAction();
        returnResult = Intent.ACTION_GET_CONTENT.equals(action) || Intent.ACTION_PICK.equals(action);
        buildUi();
        Uri src = null;
        if (Intent.ACTION_SEND.equals(action)) src = in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (src != null) load(src, in.getType());
        else if (state == null) pick();
    }

    private void pick() {
        Intent p = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        p.addCategory(Intent.CATEGORY_OPENABLE);
        p.setType("image/*");
        try { startActivityForResult(p, REQ_PICK); }
        catch (Exception e) { toast("No file picker found"); finish(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PICK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { setResult(RESULT_CANCELED); finish(); return; }
        load(data.getData(), getContentResolver().getType(data.getData()));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);
        crop = new CropView(this);
        root.addView(crop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(8);
        bar.setPadding(pad, pad, pad, pad);
        bar.addView(button("Cancel", v -> { setResult(RESULT_CANCELED); finish(); }), weight());
        squareButton = button("Square", v -> toggleSquare());
        bar.addView(squareButton, weight());
        bar.addView(button("Rotate", v -> rotate()), weight());
        if (!returnResult) bar.addView(button("Save", v -> save()), weight());
        TextView go = button(returnResult ? "Done" : "Send", v -> finishCrop());
        go.setTextColor(Color.WHITE);
        go.setBackground(box(true));
        bar.addView(go, weight());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView button(String label, android.view.View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(56));
        t.setBackground(box(false));
        t.setOnClickListener(click);
        return t;
    }

    private GradientDrawable box(boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(filled ? Color.BLACK : Color.WHITE);
        g.setStroke(dp(2), Color.BLACK);
        return g;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private void toggleSquare() {
        crop.setSquare(!crop.isSquare());
        squareButton.setText(crop.isSquare() ? "Free" : "Square");
    }

    private void load(Uri uri, String type) {
        try {
            if (type != null && (type.contains("png") || type.contains("webp"))) outMime = "image/png";
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream s = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(s, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b;
            try (InputStream s = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(s, null, d); }
            if (b == null) throw new IllegalStateException("not an image");
            int degrees = 0;
            try (InputStream s = getContentResolver().openInputStream(uri)) {
                int or = new ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                degrees = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {}
            crop.setBitmap(degrees == 0 ? b : rotated(b, degrees));
        } catch (Exception e) {
            android.util.Log.w("Crop", "open failed: " + e.getClass().getSimpleName()); // no URI or path: it can name the user's file
            toast("Couldn't open that image");
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private static Bitmap rotated(Bitmap b, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private void rotate() {
        if (crop.bitmap() == null) return;
        crop.setBitmap(rotated(crop.bitmap(), 90));
    }

    private Bitmap cropped() {
        Rect r = crop.cropInBitmap();
        return Bitmap.createBitmap(crop.bitmap(), r.left, r.top, r.width(), r.height());
    }

    private boolean png() { return "image/png".equals(outMime); }

    private void write(Bitmap b, OutputStream out) throws java.io.IOException {
        if (!b.compress(png() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, out))
            throw new java.io.IOException("compress failed");
    }

    private void finishCrop() {
        if (crop.bitmap() == null) return;
        try {
            File dir = new File(getCacheDir(), "out");
            dir.mkdirs();
            String name = "crop-" + System.currentTimeMillis() + (png() ? ".png" : ".jpg");
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { write(cropped(), out); }
            Uri uri = ShareProvider.uriFor(name);
            if (returnResult) {
                Intent result = new Intent();
                result.setDataAndType(uri, outMime);
                result.setClipData(ClipData.newRawUri("", uri));
                result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                setResult(RESULT_OK, result);
            } else {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(outMime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Send cropped image");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            }
            finish();
        } catch (Exception e) {
            toast("Couldn't crop: " + e.getMessage());
        }
    }

    private void save() {
        if (crop.bitmap() == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "crop-" + System.currentTimeMillis() + (png() ? ".png" : ".jpg"));
            v.put(MediaStore.Images.Media.MIME_TYPE, outMime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Crop");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri item = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new IllegalStateException("no media store");
            try (OutputStream out = getContentResolver().openOutputStream(item)) { write(cropped(), out); }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(item, v, null, null);
            toast("Saved to Pictures/Crop");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    /** Shared copies only need to live until the receiving app has read them. */
    private void cleanOldOutput() {
        File[] old = new File(getCacheDir(), "out").listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File f : old) if (f.lastModified() < cutoff) f.delete();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
