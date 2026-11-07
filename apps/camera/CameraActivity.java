package local.claude.hibreakcamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Still camera for the HiBreak Pro (MediaTek HAL on a GSI), designed for the e-ink panel.
 * Turns on the HAL's own still pipeline that generic apps leave off: multi-frame noise
 * reduction / anti-shake (com.mediatek.mfnrfeature.*), high-quality NR and edge, and an
 * optional contrast tone curve. No network; photos go to DCIM/HiBreakCamera via MediaStore.
 * Test extras (adb, owner build only): mfb, ais, curve, zsl, flush, cap, scene, pfps, shoot.
 */
public class CameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final String TAG = "HiBreakCam";
    static final Size PREVIEW = new Size(1440, 1080);
    static final int TOP = 150;
    static final String[] FLASH_LABELS = {"Flash off", "Flash auto", "Flash on"};

    private CameraManager manager;
    private CameraCharacteristics chars;
    private volatile CameraDevice device;
    private volatile CameraCaptureSession session;
    private CaptureRequest.Builder preview;
    private ImageReader jpeg;
    private HandlerThread thread;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Rect active;
    private OrientationEventListener tilt;
    private ScaleGestureDetector pinch;
    private int deviceDegrees = 0;

    private TextureView view;
    private Ui.Focus focus;
    private TextView status, qualityChip, fastChip, flashChip, contrastChip, zoom1, zoom2, flip;
    private LinearLayout zooms;
    private ImageView thumb, reviewImage;
    private FrameLayout review;
    private Ui.Shutter shutter;
    private Uri lastUri;

    // Measured on this HAL: zsl and flush made shutter lag worse (260-285 / 392 ms vs 232 ms),
    // and the still exposure cap was ignored, so all three default off.
    // scene: 0 none, 2 action, 18 HDR. pfps 30 measured best: shutter 173-191 ms vs 233-246 ms
    // at 10-15 fps, with 1/30 s instead of 1/24 s exposure and ISO 1350 instead of 1712.
    private int mfb = 255, ais = 1, curve = 1, zsl = 0, flush = 0, cap = 0, scene = 0, pfps = 30;
    // Oct 30 (owner: "fast, but doesn't focus well"; photos came out soft): Fast mode used to shoot as soon as
    // continuous AF said PASSIVE_FOCUSED, which is often stale after the phone moves, and the e-ink preview can't
    // show focus. Now every shot runs a fresh AF scan (af=1) unless a tap just locked focus. af=0 = old behaviour,
    // kept as an owner test extra for comparison.
    private int af = 1;
    private boolean afConfirmed, triggerSeen;
    private int tapId;
    private boolean front, hasFlash, hasAf;
    private int flash = 0; // 0 off, 1 auto, 2 on
    private float zoom = 1f, maxZoom = 1f;
    private boolean busy, waitingFocus, waitingPrecapture, sawPrecapture, shootWhenReady, tapFocusing;
    private int lastAf = CaptureResult.CONTROL_AF_STATE_INACTIVE;
    private long tPress;
    // Nov 6 (stock-firmware report: "error 2", then a crash): on first launch the permission result and onResume
    // both opened the camera, the first on an already-quit thread, so the app evicted itself. Now there is one
    // open at a time (opening), every callback checks it belongs to the current open (gen), and the camera thread
    // lives as long as the activity, so a late callback can still close its device.
    private volatile int gen;
    private volatile boolean opening, resumed;
    private int retries;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        thread = new HandlerThread("HiBreakCam");
        thread.start();
        bg = new Handler(thread.getLooper());
        manager = getSystemService(CameraManager.class);
        prefs = getSharedPreferences("camera", MODE_PRIVATE);
        // Like other camera apps: no screen timeout while the viewfinder is open.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Dark status bar icons on the white UI.
        getWindow().getDecorView().post(() -> getWindow().getInsetsController().setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS));
        boolean quality = prefs.getBoolean("quality", true);
        mfb = quality ? 255 : 0;
        ais = quality ? 1 : 0;
        curve = prefs.getBoolean("contrast", true) ? 1 : 0;
        flash = prefs.getInt("flash", 0);
        front = prefs.getBoolean("front", false);
        buildUi();
        readOptions(getIntent());
        // The UI stays portrait; photos follow how the phone is actually held.
        tilt = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int d) {
                if (d != ORIENTATION_UNKNOWN) deviceDegrees = ((d + 45) / 90 * 90) % 360;
            }
        };
        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom * d.getScaleFactor());
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- layout

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAPER);

        // Viewfinder (3:4, below the top bar) with focus box and zoom buttons over it.
        view = new TextureView(this);
        view.setSurfaceTextureListener(this);
        view.setOnTouchListener(this::onPreviewTouch);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP);
        vp.topMargin = TOP;
        root.addView(view, vp);
        focus = new Ui.Focus(this);
        root.addView(focus, new FrameLayout.LayoutParams(vp));
        zooms = new LinearLayout(this);
        zoom1 = Ui.chip(this, "1×");
        zoom2 = Ui.chip(this, "2×");
        zoom1.setOnClickListener(v -> setZoom(1f));
        zoom2.setOnClickListener(v -> setZoom(2f));
        zooms.addView(zoom1, gap(16));
        zooms.addView(zoom2, gap(0));
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zp.topMargin = TOP + 24;
        root.addView(zooms, zp);

        // Top bar: capture mode, flash and contrast, remembered between uses.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(20, 0, 20, 0);
        top.setBackgroundColor(Ui.PAPER);
        qualityChip = Ui.chip(this, "Quality");
        fastChip = Ui.chip(this, "Fast");
        flashChip = Ui.chip(this, FLASH_LABELS[0]);
        contrastChip = Ui.chip(this, "Contrast");
        qualityChip.setOnClickListener(v -> setMode(true));
        fastChip.setOnClickListener(v -> setMode(false));
        flashChip.setOnClickListener(v -> setFlash((flash + 1) % 3));
        contrastChip.setOnClickListener(v -> setContrast(curve != 1));
        top.addView(qualityChip, gap(12));
        top.addView(fastChip, gap(0));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(flashChip, gap(12));
        top.addView(contrastChip, gap(0));
        root.addView(top, new FrameLayout.LayoutParams(-1, TOP, Gravity.TOP));

        // Bottom: one status line, then last photo | shutter | front/back.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Ui.PAPER);
        bar.setPadding(36, 12, 36, 40);
        status = new TextView(this);
        status.setTextColor(Ui.INK);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, 16);
        bar.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setContentDescription("Last photo");
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Ui.PAPER);
        frame.setStroke(3, Ui.INK);
        frame.setCornerRadius(18);
        thumb.setBackground(frame);
        thumb.setPadding(5, 5, 5, 5);
        thumb.setClipToOutline(true);
        thumb.setOnClickListener(v -> showReview());
        shutter = new Ui.Shutter(this);
        shutter.setOnClickListener(v -> shoot());
        flip = Ui.round(this, "Front");
        flip.setContentDescription("Switch camera");
        flip.setOnClickListener(v -> switchCamera());
        row.addView(thumb, new LinearLayout.LayoutParams(150, 150));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(shutter, new LinearLayout.LayoutParams(210, 210));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(flip, new LinearLayout.LayoutParams(150, 150));
        bar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Review: screen-sized photo with Close / Open / Delete.
        review = new FrameLayout(this);
        review.setBackgroundColor(Ui.PAPER);
        review.setVisibility(View.GONE);
        review.setClickable(true);
        reviewImage = new ImageView(this);
        reviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        reviewImage.setOnClickListener(v -> review.setVisibility(View.GONE));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
        ip.bottomMargin = 170;
        review.addView(reviewImage, ip);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(24, 0, 24, 40);
        TextView close = Ui.chip(this, "Close"), openIt = Ui.chip(this, "Open"), delete = Ui.chip(this, "Delete");
        close.setOnClickListener(v -> review.setVisibility(View.GONE));
        openIt.setOnClickListener(v -> openInGallery());
        delete.setOnClickListener(v -> confirmDelete());
        for (TextView t : new TextView[]{close, openIt, delete}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.leftMargin = lp.rightMargin = 12;
            actions.addView(t, lp);
        }
        review.addView(actions, new FrameLayout.LayoutParams(-1, 170, Gravity.BOTTOM));
        root.addView(review, new FrameLayout.LayoutParams(-1, -1));
        // Android 15 draws apps edge to edge: keep controls clear of the status and gesture bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            ((FrameLayout.LayoutParams) top.getLayoutParams()).topMargin = bars.top;
            ((FrameLayout.LayoutParams) view.getLayoutParams()).topMargin = TOP + bars.top;
            ((FrameLayout.LayoutParams) focus.getLayoutParams()).topMargin = TOP + bars.top;
            ((FrameLayout.LayoutParams) zooms.getLayoutParams()).topMargin = TOP + bars.top + 24;
            bar.setPadding(36, 12, 36, 40 + bars.bottom);
            review.setPadding(0, bars.top, 0, bars.bottom);
            v.requestLayout();
            return insets;
        });
        setContentView(root);
    }

    private static LinearLayout.LayoutParams gap(int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = right;
        return lp;
    }

    private void refreshChips() {
        if (qualityChip == null) return;
        Ui.select(qualityChip, mfb != 0);
        Ui.select(fastChip, mfb == 0);
        Ui.select(contrastChip, curve == 1);
        flashChip.setText(FLASH_LABELS[flash]);
        Ui.select(flashChip, flash != 0);
        flashChip.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
        Ui.select(zoom1, zoom < 1.5f);
        Ui.select(zoom2, zoom >= 1.5f);
        zooms.setVisibility(maxZoom >= 2f ? View.VISIBLE : View.GONE);
        flip.setText(front ? "Back" : "Front");
    }

    // ---------------------------------------------------------------- options

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readOptions(intent);
        if (session != null) { repeat(); if (Flavor.TEST_EXTRAS && intent.getIntExtra("shoot", 0) == 1) shoot(); }
    }

    private void readOptions(Intent i) {
        // Public builds ignore the test extras: the activity is exported, so any app could send them.
        if (!Flavor.TEST_EXTRAS) { shootWhenReady = false; refreshChips(); return; }
        mfb = i.getIntExtra("mfb", mfb);
        ais = i.getIntExtra("ais", ais);
        curve = i.getIntExtra("curve", curve);
        zsl = i.getIntExtra("zsl", zsl);
        flush = i.getIntExtra("flush", flush);
        cap = i.getIntExtra("cap", cap);
        scene = i.getIntExtra("scene", scene);
        pfps = i.getIntExtra("pfps", pfps);
        af = i.getIntExtra("af", af);
        shootWhenReady = i.getIntExtra("shoot", 0) == 1;
        Log.i(TAG, "options " + describeOptions());
        refreshChips();
    }

    private String describeOptions() {
        return "mfb=" + mfb + " ais=" + ais + " curve=" + curve + " zsl=" + zsl + " flush=" + flush + " cap=" + cap
                + " scene=" + scene + " pfps=" + pfps + " af=" + af + " flash=" + flash + " zoom=" + zoom + " front=" + front;
    }

    private String describe() {
        if (front) return "Front camera";
        return mfb == 0 ? "Fast · single frame" : "Quality · hold still for a moment";
    }

    private void setMode(boolean quality) {
        if (busy) return;
        mfb = quality ? 255 : 0;
        ais = quality ? 1 : 0;
        prefs.edit().putBoolean("quality", quality).apply();
        refreshChips();
        setStatus(describe());
    }

    private void setContrast(boolean on) {
        if (busy) return;
        curve = on ? 1 : 0;
        prefs.edit().putBoolean("contrast", on).apply();
        refreshChips();
        repeat();
    }

    private void setFlash(int mode) {
        if (busy || !hasFlash) return;
        flash = mode;
        prefs.edit().putInt("flash", mode).apply();
        refreshChips();
        repeat();
    }

    private void setZoom(float z) {
        zoom = Math.max(1f, Math.min(maxZoom, z));
        refreshChips();
        repeat();
    }

    private void switchCamera() {
        if (busy) return;
        front = !front;
        prefs.edit().putBoolean("front", front).apply();
        zoom = 1f;
        closeCamera();
        focus.clear();
        retries = 0;
        if (view.isAvailable()) open();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        retries = 0;
        if (tilt.canDetectOrientation()) tilt.enable();
        bg.post(this::loadLastPhoto);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!asked) { asked = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, 1); }
        } else if (view.isAvailable()) open();
    }

    private boolean asked; // once per visit: a denial must not re-open the dialog in a loop

    @Override protected void onStop() { asked = false; super.onStop(); }

    // Runs just before onResume, which opens the camera; opening here as well was the double open.
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED) setStatus("Camera permission is needed");
    }

    @Override protected void onPause() {
        resumed = false;
        tilt.disable();
        closeCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeCamera();
        thread.quitSafely();
        super.onDestroy();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
        if (resumed && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    // ---------------------------------------------------------------- camera setup

    private void open() {
        if (device != null || opening) return;
        try {
            String id = null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String c : manager.getCameraIdList()) {
                Integer f = manager.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { id = c; break; }
            }
            if (id == null) { front = false; id = manager.getCameraIdList()[0]; }
            chars = manager.getCameraCharacteristics(id);
            active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Boolean fl = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            hasAf = false;
            for (int m : chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))
                if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) hasAf = true;
            Range<Float> zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            maxZoom = zr == null ? 1f : zr.getUpper();
            // Full-resolution JPEG runs below 20 fps, so the HAL lists it under high-resolution sizes.
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            List<Size> sizes = new ArrayList<>(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)));
            Size[] high = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
            if (high != null) sizes.addAll(Arrays.asList(high));
            // After a failed session, retry with the largest regular size (some firmware rejects high-res sizes).
            if (safeSize) sizes = new ArrayList<>(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)));
            Size still = sizes.get(0);
            for (Size s : sizes) if (s.getWidth() * 3 == s.getHeight() * 4
                    && (long) s.getWidth() * s.getHeight() > (long) still.getWidth() * still.getHeight()) still = s;
            Log.i(TAG, "camera " + id + " still " + still + " af=" + hasAf + " flash=" + hasFlash + " safe=" + safeSize);
            if (jpeg != null) jpeg.close();
            jpeg = ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, 2);
            jpeg.setOnImageAvailableListener(this::onJpeg, bg);
            fitPreview();
            ui.post(() -> {
                view.setScaleX(front ? -1f : 1f); // selfie preview behaves like a mirror
                refreshChips();
            });
            final int mine = ++gen;
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (mine != gen) { d.close(); return; } // closed or replaced while opening
                    opening = false;
                    device = d;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (mine != gen) return;
                    opening = false;
                    device = null;
                    session = null;
                    setStatus("Camera taken by another app · reopen Fast Cam");
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close();
                    if (mine != gen) return;
                    opening = false;
                    device = null;
                    session = null;
                    Log.w(TAG, "camera error " + e);
                    // 1 in use, 2 too many cameras open: often brief (another app or the last close is
                    // still letting go), so try again a few times before giving up.
                    if ((e == ERROR_CAMERA_IN_USE || e == ERROR_MAX_CAMERAS_IN_USE) && retries < 3) {
                        retries++;
                        setStatus("Camera busy · trying again…");
                        ui.postDelayed(() -> { if (resumed && view.isAvailable()) open(); }, 800L * retries);
                        return;
                    }
                    setStatus(errorText(e));
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "open", e);
            opening = false;
            setStatus(e instanceof CameraAccessException
                    ? errorText(((CameraAccessException) e).getReason() == CameraAccessException.CAMERA_DISABLED
                        ? CameraDevice.StateCallback.ERROR_CAMERA_DISABLED : CameraDevice.StateCallback.ERROR_CAMERA_IN_USE)
                    : "Cannot open camera · " + e.getClass().getSimpleName());
        }
    }

    private boolean safeSize;

    private static String errorText(int e) {
        switch (e) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "Camera is in use by another app (error " + e + ") · close other camera apps, then reopen";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "Camera is turned off on this phone (error 3) · check privacy settings";
            default:
                return "Camera stopped working (error " + e + ") · reopen Fast Cam";
        }
    }

    private void fitPreview() {
        ui.post(() -> {
            int w = view.getRootView().getWidth();
            for (View target : new View[]{view, focus}) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) target.getLayoutParams();
                lp.width = w;
                lp.height = w * PREVIEW.getWidth() / PREVIEW.getHeight();
                target.setLayoutParams(lp);
            }
        });
    }

    private void startSession() {
        final CameraDevice dev = device;
        SurfaceTexture tex = view.getSurfaceTexture();
        if (dev == null || jpeg == null || tex == null) { Log.w(TAG, "session: nothing to show on"); return; }
        try {
            tex.setDefaultBufferSize(PREVIEW.getWidth(), PREVIEW.getHeight());
            Surface surface = new Surface(tex);
            preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            preview.addTarget(surface);
            // MediaTek ZSL is a session parameter (availableSessionKeys), not a per-shot setting.
            CaptureRequest.Builder params = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            setVendor(params, "com.mediatek.control.capture.zsl.mode", zsl);
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(new OutputConfiguration(surface), new OutputConfiguration(jpeg.getSurface())),
                    bg::post, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (dev != device) { s.close(); return; }
                    session = s;
                    retries = 0;
                    repeat();
                    setStatus(describe());
                    if (shootWhenReady) { shootWhenReady = false; ui.postDelayed(CameraActivity.this::shoot, 1500); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { sessionFailed(dev, "configure failed"); }
            });
            config.setSessionParameters(params.build());
            dev.createCaptureSession(config);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "session", e);
            sessionFailed(dev, e.getClass().getSimpleName());
        }
    }

    /** First failure: reopen with a regular-size photo stream. Second: say so instead of crashing. */
    private void sessionFailed(CameraDevice dev, String why) {
        if (dev != device) return;
        Log.w(TAG, "session failed (" + why + ") safe=" + safeSize);
        if (!safeSize) {
            safeSize = true;
            ui.post(() -> { closeCamera(); if (resumed && view.isAvailable()) open(); });
        } else {
            setStatus("Camera setup failed (" + why + ") · reopen Fast Cam");
        }
    }

    private void repeat() {
        if (session == null) return;
        try {
            applyScene(preview);
            if (hasAf && preview.get(CaptureRequest.CONTROL_AF_REGIONS) == null) {
                preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            applyFlash(preview);
            preview.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pfps >= 30 ? new Range<>(30, 30) : new Range<>(10, 15));
            preview.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            applyTone(preview);
            session.setRepeatingRequest(preview.build(), progress, bg);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "repeat", e);
        }
    }

    // ---------------------------------------------------------------- focus and metering

    private final CameraCaptureSession.CaptureCallback progress = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            if (waitingPrecapture && ae != null) {
                if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true;
                else if (sawPrecapture && (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                    waitingPrecapture = false;
                    Log.i(TAG, "precapture done +" + (SystemClock.elapsedRealtime() - tPress) + "ms");
                    focusThenStill();
                }
            }
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            if (afState == null) return;
            lastAf = afState;
            Integer trig = r.get(CaptureRequest.CONTROL_AF_TRIGGER);
            if (trig != null && trig == CaptureRequest.CONTROL_AF_TRIGGER_START) triggerSeen = true;
            boolean locked = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
            if ((locked || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) && (triggerSeen || !waitingFocus)) {
                ui.post(() -> focus.setState(locked ? Ui.Focus.LOCKED : Ui.Focus.FAILED));
                if (tapFocusing) {
                    tapFocusing = false;
                    setStatus(locked ? "Focused" : "Couldn't focus · try a spot with more detail");
                }
                if (waitingFocus) {
                    waitingFocus = false;
                    afConfirmed = locked;
                    Log.i(TAG, "focus " + (locked ? "locked" : "failed") + " +" + (SystemClock.elapsedRealtime() - tPress) + "ms");
                    still();
                }
            }
        }
    };

    private boolean onPreviewTouch(View v, MotionEvent e) {
        pinch.onTouchEvent(e);
        if (pinch.isInProgress() || e.getPointerCount() > 1) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || session == null || busy || !hasAf) return true;
        // View is portrait; the back sensor (orientation 90) is landscape: view x -> sensor y, view y -> sensor x.
        float u = e.getX() / v.getWidth(), w = e.getY() / v.getHeight();
        int cx = Math.round(w * active.width()), cy = Math.round((1 - u) * active.height());
        int half = active.width() / 10;
        MeteringRectangle m = new MeteringRectangle(
                Math.max(0, cx - half), Math.max(0, cy - half), half * 2, half * 2, MeteringRectangle.METERING_WEIGHT_MAX);
        try {
            preview.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{m});
            preview.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{m});
            preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(preview.build(), progress, bg);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(preview.build(), progress, bg);
            float fx = e.getX(), fy = e.getY();
            ui.post(() -> focus.show(fx, fy, Ui.Focus.SEARCHING));
            tapFocusing = true;
            triggerSeen = true;
            setStatus("Focusing…");
            final int id = ++tapId;
            // A tap lock is for the next photo; if none is taken, go back to continuous focus.
            bg.postDelayed(() -> {
                if (id != tapId || busy || session == null) return;
                releaseFocus();
                ui.post(() -> focus.clear());
            }, 5000);
        } catch (CameraAccessException | RuntimeException e2) {
            Log.e(TAG, "focus", e2);
        }
        return true;
    }

    // ---------------------------------------------------------------- capture

    private void shoot() {
        if (session == null || busy) return;
        busy = true;
        tPress = SystemClock.elapsedRealtime();
        Log.i(TAG, "press af=" + lastAf + " " + describeOptions());
        ui.post(() -> shutter.setBusy(true));
        setStatus("Hold still…");
        if (hasFlash && flash != 0) {
            // Pre-flash metering, so flash photos are exposed correctly.
            try {
                waitingPrecapture = true;
                sawPrecapture = false;
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                session.capture(preview.build(), progress, bg);
                preview.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                bg.postDelayed(() -> { if (waitingPrecapture) { waitingPrecapture = false; Log.i(TAG, "precapture timeout"); focusThenStill(); } }, 1500);
                return;
            } catch (CameraAccessException | RuntimeException e) {
                Log.e(TAG, "precapture", e);
                waitingPrecapture = false;
            }
        }
        focusThenStill();
    }

    private void focusThenStill() {
        tapId++;   // a photo is being taken: the tap lock's own timeout no longer applies
        boolean tapLocked = lastAf == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                && preview.get(CaptureRequest.CONTROL_AF_REGIONS) != null;
        boolean oldFast = af == 0 && (lastAf == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || (mfb == 0 && lastAf == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED));
        if (!hasAf || tapLocked || oldFast) {
            afConfirmed = !hasAf || lastAf == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
            still();
            return;
        }
        try {
            waitingFocus = true;
            triggerSeen = false;
            afConfirmed = false;
            // A full scan in AUTO mode: continuous AF's "focused" can be stale, and a trigger in that mode
            // only locks whatever it has.
            if (af == 1) preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(preview.build(), progress, bg);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(preview.build(), progress, bg);
            // Never wait forever for focus (dim light scans slowly).
            bg.postDelayed(() -> { if (waitingFocus) { waitingFocus = false; Log.i(TAG, "focus timeout"); still(); } }, 2500);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "trigger", e);
            done();
        }
    }

    private void still() {
        if (device == null || session == null || jpeg == null) { done(); return; }
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpeg.getSurface());
            if (hasAf) {
                b.set(CaptureRequest.CONTROL_AF_MODE, preview.get(CaptureRequest.CONTROL_AF_MODE));
                b.set(CaptureRequest.CONTROL_AF_REGIONS, preview.get(CaptureRequest.CONTROL_AF_REGIONS));
            }
            b.set(CaptureRequest.CONTROL_AE_REGIONS, preview.get(CaptureRequest.CONTROL_AE_REGIONS));
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            int sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            b.set(CaptureRequest.JPEG_ORIENTATION, front ? (sensor - deviceDegrees + 360) % 360 : (sensor + deviceDegrees) % 360);
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            applyTone(b);
            applyFlash(b);
            setVendor(b, "com.mediatek.mfnrfeature.mfbmode", mfb);
            setVendor(b, "com.mediatek.mfnrfeature.aismode", ais);
            applyScene(b);
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, zsl == 1);
            if (cap == 1) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));
            if (flush == 1) session.abortCaptures();
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureStarted(CameraCaptureSession s, CaptureRequest r, long ts, long n) {
                    Log.i(TAG, "shutter +" + (SystemClock.elapsedRealtime() - tPress) + "ms");
                    setStatus("Saving…");
                    if (flush == 1) repeat();
                }
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Log.i(TAG, "result +" + (SystemClock.elapsedRealtime() - tPress) + "ms afState=" + res.get(CaptureResult.CONTROL_AF_STATE)
                            + " focusDist=" + res.get(CaptureResult.LENS_FOCUS_DISTANCE) + " confirmed=" + afConfirmed
                            + " iso=" + res.get(CaptureResult.SENSOR_SENSITIVITY)
                            + " exp_us=" + (exp == null ? -1 : exp / 1000) + " " + describeOptions());
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) {
                    Log.w(TAG, "capture failed " + f.getReason());
                    setStatus("Photo failed · try again");
                    releaseFocus();
                    done();
                }
            }, bg);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "still", e);
            done();
        }
    }

    private void applyFlash(CaptureRequest.Builder b) {
        if (!hasFlash || flash == 0) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, flash == 1
                    ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH : CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        }
    }

    private void applyScene(CaptureRequest.Builder b) {
        if (scene == 0) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            b.set(CaptureRequest.CONTROL_SCENE_MODE, scene);
        }
    }

    @SuppressWarnings("unchecked")
    private void setVendor(CaptureRequest.Builder b, String name, int value) {
        for (CaptureRequest.Key<?> k : chars.getAvailableCaptureRequestKeys()) {
            if (k.getName().equals(name)) {
                // Vendor key Java types come from the HAL tag type; accept either int[] or Integer.
                try {
                    b.set((CaptureRequest.Key<int[]>) k, new int[]{value});
                } catch (RuntimeException e) {
                    b.set((CaptureRequest.Key<Integer>) k, value);
                }
                return;
            }
        }
        Log.w(TAG, "vendor key unavailable: " + name);
    }

    /** sRGB-style gamma followed by a gentle S-curve: lifts contrast for e-ink viewing. */
    private void applyTone(CaptureRequest.Builder b) {
        if (curve != 1) {
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
            return;
        }
        int n = 64;
        float[] pts = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float x = i / (float) (n - 1);
            float g = x <= 0.0031308f ? 12.92f * x : (float) (1.055 * Math.pow(x, 1 / 2.4) - 0.055);
            float s = (float) (0.5 + Math.atan(4.0 * (g - 0.5)) / (2 * Math.atan(2.0)));
            pts[2 * i] = x;
            pts[2 * i + 1] = Math.min(1f, Math.max(0f, s));
        }
        b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
        b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(pts, pts, pts));
    }

    private void onJpeg(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        long tJpeg = SystemClock.elapsedRealtime();
        ByteBuffer buf = img.getPlanes()[0].getBuffer();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        img.close();
        String name = "HBC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/HiBreakCamera");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream out = cr.openOutputStream(uri)) {
            out.write(data);
        } catch (Exception e) {
            Log.e(TAG, "save", e);
            setStatus("Could not save photo");
            done();
            return;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        long saved = SystemClock.elapsedRealtime();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = data.length > 3_000_000 ? 4 : 2; // about the panel's 824 px width
        Bitmap small = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        lastUri = uri;
        ui.post(() -> { thumb.setImageBitmap(small); reviewImage.setImageBitmap(small); });
        Log.i(TAG, "jpeg +" + (tJpeg - tPress) + "ms saved +" + (saved - tPress) + "ms bytes=" + data.length + " name=" + name
                + " sharp=" + String.format(java.util.Locale.ROOT, "%.1f", sharpness(small)) + " confirmed=" + afConfirmed);
        // No "may be soft" warning: in dim rooms the HAL reports NOT_FOCUSED_LOCKED on sharp photos (Oct 30 test,
        // sharp=1446 with confirmed=false), so it would cry wolf. The verdict and sharpness stay in the log.
        setStatus("Saved · tap the photo to review");
        releaseFocus();
        done();
    }

    /** Leave AF lock and tap regions, back to continuous focus. */
    private void releaseFocus() {
        if (session == null) return;
        if (hasAf) {
            try {
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(preview.build(), null, bg);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            } catch (Exception e) {
                Log.e(TAG, "cancel af", e);
            }
        }
        preview.set(CaptureRequest.CONTROL_AF_REGIONS, null);
        preview.set(CaptureRequest.CONTROL_AE_REGIONS, null);
        repeat();
    }

    /** Detail in the middle half of the photo (mean squared Laplacian of brightness), for the log only. */
    private static double sharpness(Bitmap b) {
        if (b == null) return -1;
        int w = b.getWidth() / 2, h = b.getHeight() / 2, x0 = b.getWidth() / 4, y0 = b.getHeight() / 4;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, x0, y0, w, h);
        double sum = 0; long n = 0;
        for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
            int i = y * w + x;
            double lap = 4 * lum(px[i]) - lum(px[i - 1]) - lum(px[i + 1]) - lum(px[i - w]) - lum(px[i + w]);
            sum += lap * lap; n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    private static double lum(int c) { return 0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255); }

    private void done() {
        busy = false;
        ui.post(() -> { shutter.setBusy(false); focus.clear(); });
    }

    // ---------------------------------------------------------------- photos

    /** Shows this app's most recent photo in the thumbnail, so it is never an empty box. */
    private void loadLastPhoto() {
        String[] cols = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{"DCIM/HiBreakCamera%"},
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null || !c.moveToFirst()) return;
            Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            Bitmap t = getContentResolver().loadThumbnail(uri, new Size(300, 300), null);
            ui.post(() -> {
                if (lastUri != null) return; // a new photo arrived meanwhile
                lastUri = uri;
                thumb.setImageBitmap(t);
                reviewImage.setImageDrawable(null);
            });
        } catch (Exception e) {
            Log.w(TAG, "last photo", e);
        }
    }

    private void showReview() {
        if (lastUri == null) return;
        if (reviewImage.getDrawable() == null) {
            Uri uri = lastUri;
            bg.post(() -> {
                try {
                    Bitmap b = getContentResolver().loadThumbnail(uri, new Size(1100, 1100), null);
                    ui.post(() -> { reviewImage.setImageBitmap(b); review.setVisibility(View.VISIBLE); });
                } catch (Exception e) {
                    Log.w(TAG, "review", e);
                }
            });
        } else {
            review.setVisibility(View.VISIBLE);
        }
    }

    private void openInGallery() {
        if (lastUri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException e) {
            setStatus("No gallery app installed");
        }
    }

    private void confirmDelete() {
        if (lastUri == null) return;
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setMessage("Delete this photo?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (x, w) -> {
                    getContentResolver().delete(lastUri, null, null);
                    lastUri = null;
                    thumb.setImageDrawable(null);
                    reviewImage.setImageDrawable(null);
                    review.setVisibility(View.GONE);
                    setStatus("Photo deleted");
                    bg.post(this::loadLastPhoto);
                }).create();
        d.setOnShowListener(x -> {
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                Button b = d.getButton(which);
                b.setTextColor(Ui.INK);
                b.setTextSize(18);
            }
        });
        d.show();
    }

    // ---------------------------------------------------------------- keys

    @Override public void onBackPressed() {
        if (review.getVisibility() == View.VISIBLE) review.setVisibility(View.GONE);
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0 && review.getVisibility() != View.VISIBLE) shoot();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void closeCamera() {
        gen++;            // callbacks from this open are now stale and close their own device
        opening = false;
        waitingFocus = waitingPrecapture = false;
        if (busy) done();
        if (session != null) { session.close(); session = null; }
        if (device != null) { device.close(); device = null; }
        if (jpeg != null) { jpeg.close(); jpeg = null; }
    }

    private void setStatus(String s) { ui.post(() -> status.setText(s)); }
}
