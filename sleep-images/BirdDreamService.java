package local.codex.hibreakprobe;

import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.widget.ImageView;

/** One shuffled image per sleep. No timers after drawing, no wake locks, no networking.
 * Android's dream manager and secure keyguard retain control of sleep/wake.
 * A DOZE_SUSPEND request is not proof the hardware has actually suspended.
 */
public final class BirdDreamService extends DreamService {
    private static final String TAG="HiBreakBirdDream";
    private static final long START_DELAY_MS=500;
    private static final long OFF_DELAY_MS=6000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean stopping;
    private Bitmap bitmap;
    private String imageName;
    private SleepImageDeck.Pick selected;
    private boolean selectionSaved;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        stopping=false;
        setInteractive(false); setFullscreen(true); setScreenBright(false);
        ImageView image=new ImageView(this);
        image.setBackgroundColor(Color.WHITE);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if(bitmap==null && Variant.PUBLIC) {
            // Public build: only the user's own pictures; a plain note until they pick one.
            java.util.List<java.io.File> files=SleepImages.list(this);
            if(files.isEmpty()) { imageName="placeholder"; bitmap=SleepImages.placeholder(this); }
            else {
                SharedPreferences state=getSharedPreferences("sleep_image_shuffle_v1",MODE_PRIVATE);
                selected=SleepImageDeck.next(state.getString("remaining",""),state.getInt("last",-1),
                    files.size(),new java.util.Random());
                imageName=files.get(selected.index).getName();
                bitmap=BitmapFactory.decodeFile(files.get(selected.index).getPath());
                if(bitmap==null) { selected=null; imageName="placeholder"; bitmap=SleepImages.placeholder(this); }
            }
        }
        if(bitmap==null) {
            SharedPreferences state=getSharedPreferences("sleep_image_shuffle_v1",MODE_PRIVATE);
            selected=SleepImageDeck.next(state.getString("remaining",""),state.getInt("last",-1),
                new java.util.Random());
            imageName=SleepImageDeck.FILES[selected.index];
            try { bitmap=loadImage("sleep-images/"+imageName); }
            catch(Exception e) {
                Log.e(TAG,"Could not load "+imageName+"; using original bird",e);
                selected=null; imageName="Bird.png";
                try { bitmap=loadImage("Bird.png"); }
                catch(Exception fallback) {Log.e(TAG,"Could not load fallback bird",fallback);finish();return;}
            }
        }
        image.setImageBitmap(bitmap);
        setContentView(image);
        getWindow().getDecorView().setSystemUiVisibility(5894);
    }
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if(bitmap==null) {finish();return;}
        stopping=false;
        handler.removeCallbacksAndMessages(null);
        if(selected!=null && !selectionSaved) {
            selectionSaved=getSharedPreferences("sleep_image_shuffle_v1",MODE_PRIVATE).edit()
                .putString("remaining",selected.remaining).putInt("last",selected.index).commit();
            if(!selectionSaved) Log.w(TAG,"Image sequence could not be saved");
        }
        Log.i(TAG,"Showing sleep image="+imageName);
        // The image must be drawn while the display is off and then reach the panel when doze
        // switches the display on: drawing it in DOZE left the old screen on the panel (the HWC
        // skipped the frame) with the frontlight on, which looked like a frozen home screen
        // (camera, Oct 12 2026). Doze starts as soon as the image frame has been committed
        // (a few ms) instead of after a fixed wait; START_DELAY_MS remains as the fallback.
        final boolean[] started={false};
        final Runnable startDoze=() -> {
            if(stopping || started[0]) return;
            started[0]=true;
            try {
                boolean can=(Boolean)DreamService.class.getMethod("canDoze").invoke(this);
                Log.i(TAG,"canDoze="+can);
                if(!can) {finish();return;}
                DreamService.class.getMethod("setDozeScreenBrightness",int.class).invoke(this,0);
                // Display OFF, not DOZE_SUSPEND: in doze the MediaTek display path holds
                // cmdq_wake_lock and the CPU never deep-sleeps (20 h of 22 h screen-off,
                // Oct 10 2026). E-ink keeps the drawn image with the panel off.
                // Requesting OFF straight away left the panel blank (camera, Oct 11 2026), so the
                // image is shown in DOZE_SUSPEND first and the display turned off afterwards.
                DreamService.class.getMethod("setDozeScreenState",int.class).invoke(this,4);
                DreamService.class.getMethod("startDozing").invoke(this);
                Log.i(TAG,"Requested DOZE_SUSPEND after drawing "+imageName+"; display OFF follows.");
                handler.postDelayed(() -> turnDisplayOff(1),OFF_DELAY_MS);
            } catch(Exception e) {Log.e(TAG,"Doze request failed",e);finish();}
        };
        android.view.View decor=getWindow().getDecorView();
        decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
            Log.i(TAG,"Image frame committed");
            handler.post(startDoze);
        });
        decor.invalidate();
        handler.postDelayed(startDoze,START_DELAY_MS);
    }
    // A non-interactive dream calls wakeUp() on any key or touch it receives. While dozing that
    // ends the dream and Android drops to Asleep with the panel black until the power key
    // (Oct 23 2026: all 12 sleeps on battery, 'Gently waking up from dream' 0.1-2.7 s after start;
    // reproduced with one injected KEYCODE_F9, which the HiBreak's power key also sends). Real
    // wakes (power key) are handled by the window policy before dispatch, so input is swallowed.
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {return true;}
    @Override public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) {return true;}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {return true;}
    @Override public boolean dispatchTrackballEvent(android.view.MotionEvent event) {return true;}
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {return true;}
    // Turning the display OFF races inside Android: when WindowManager empties the display
    // (Layerstack -1) before SurfaceFlinger powers it off, the HWC paints that empty frame and
    // the panel goes black (camera, Oct 20 2026: 2 of 10 sleeps, both with an HWC
    // "[normal commit]" after the OFF request; none in the 8 good ones). So after each OFF
    // request the HWC log is checked; if the panel was repainted, the image is shown again
    // (DOZE_SUSPEND) and OFF retried. After MAX_OFF_ATTEMPTS the dream stays in DOZE_SUSPEND:
    // the image is kept at the cost of a higher-drain sleep.
    private static final int MAX_OFF_ATTEMPTS=3;
    private static final long OFF_CHECK_MS=1500;
    private static final long RETRY_DELAY_MS=2500;
    private void turnDisplayOff(int attempt) {
        if(stopping) return;
        final long since=System.currentTimeMillis();
        try {
            DreamService.class.getMethod("setDozeScreenState",int.class).invoke(this,1);
            Log.i(TAG,"Requested display OFF (attempt "+attempt+"); no app wake lock or recurring timer.");
        } catch(Exception e) {Log.e(TAG,"Display OFF request failed",e);return;}
        handler.postDelayed(() -> {
            if(stopping) return;
            new Thread(() -> {
                final boolean repainted=panelRepaintedSince(since);
                handler.post(() -> {
                    if(stopping || !repainted) return;
                    try {
                        DreamService.class.getMethod("setDozeScreenState",int.class).invoke(this,4);
                        if(attempt>=MAX_OFF_ATTEMPTS) {
                            Log.w(TAG,"Panel repainted during display OFF "+attempt+"x; keeping DOZE_SUSPEND.");
                            return;
                        }
                        Log.w(TAG,"Panel repainted during display OFF; showing image again, retry OFF.");
                        handler.postDelayed(() -> turnDisplayOff(attempt+1),RETRY_DELAY_MS);
                    } catch(Exception e) {Log.e(TAG,"Image re-show request failed",e);}
                });
            },"sleep-off-check").start();
        },OFF_CHECK_MS);
    }
    /** True when the HWC committed a frame to the panel after the given wall-clock time. */
    private static boolean panelRepaintedSince(long sinceMs) {
        String since=String.format(java.util.Locale.US,"%d.%03d",sinceMs/1000,sinceMs%1000);
        try {
            Process p=new ProcessBuilder("logcat","-d","-b","main","-v","epoch","-T",since,
                "-s","hwcomposer:I").redirectErrorStream(true).start();
            boolean found=false;
            try(java.io.BufferedReader in=new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                for(String line; (line=in.readLine())!=null;) if(line.contains("[normal commit]")) found=true;
            }
            p.waitFor();
            return found;
        } catch(Exception e) {Log.e(TAG,"Could not read HWC log",e);return false;}
    }
    @Override public void onDreamingStopped() {
        stopping=true; handler.removeCallbacksAndMessages(null);
        Log.i(TAG,"Dream stopped"); super.onDreamingStopped();
    }
    @Override public void onDetachedFromWindow() {
        stopping=true; handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
        // Let Android reclaim the bitmap after the detached ImageView is released.
        bitmap=null; selected=null; imageName=null; selectionSaved=false;
    }
    private Bitmap loadImage(String asset) throws java.io.IOException {
        try(java.io.InputStream in=getAssets().open(asset)) {
            Bitmap result=BitmapFactory.decodeStream(in);
            if(result==null) throw new java.io.IOException("Invalid image: "+asset);
            return result;
        }
    }
}
