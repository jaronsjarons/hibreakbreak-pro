package local.claude.speak;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Read aloud" for highlighted text (owner, Oct 24 2026). Appears in the text-selection menu
 * (PROCESS_TEXT) and the share menu (SEND text/plain). Reads with the phone's default speech
 * engine (SherpaTTS with a Piper voice). A small panel at the bottom shows progress and Stop;
 * touches outside it reach the app underneath, so reading continues while you scroll.
 */
public final class SpeakActivity extends Activity {
    private TextToSpeech tts;
    private final List<String> chunks = new ArrayList<>();
    private TextView status;
    private boolean ready;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        setFinishOnTouchOutside(false);
        buildUi();
        String text = textFrom(getIntent());
        if (text == null || text.trim().isEmpty()) { finish(); return; }
        split(text.trim());
        status.setText("Starting voice…");
        tts = new TextToSpeech(this, code -> runOnUiThread(() -> onEngine(code)));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String text = textFrom(intent);
        if (text == null || text.trim().isEmpty()) return;
        if (tts != null) tts.stop();
        chunks.clear();
        split(text.trim());
        if (ready) speakAll();
    }

    private static String textFrom(Intent in) {
        if (in == null) return null;
        CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (t == null) t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        return t == null ? null : t.toString();
    }

    /** Sentence-sized pieces under the engine's input limit, so long passages never fail. */
    private void split(String text) {
        int limit = Math.min(1000, TextToSpeech.getMaxSpeechInputLength());
        Matcher m = Pattern.compile("[^.!?\\n]+[.!?]*[\"')\\]]*\\s*|\\n+").matcher(text);
        StringBuilder cur = new StringBuilder();
        while (m.find()) {
            String s = m.group();
            if (cur.length() + s.length() > limit && cur.length() > 0) { chunks.add(cur.toString()); cur.setLength(0); }
            while (s.length() > limit) { chunks.add(s.substring(0, limit)); s = s.substring(limit); }
            cur.append(s);
        }
        if (cur.toString().trim().length() > 0) chunks.add(cur.toString());
    }

    private void onEngine(int code) {
        if (code != TextToSpeech.SUCCESS) { status.setText("No speech engine available"); return; }
        ready = true;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                runOnUiThread(() -> status.setText("Reading aloud… " + (Integer.parseInt(id) + 1) + "/" + chunks.size()));
            }
            @Override public void onDone(String id) {
                if (Integer.parseInt(id) == chunks.size() - 1) runOnUiThread(SpeakActivity.this::finish);
            }
            @Override public void onError(String id) {
                runOnUiThread(() -> status.setText("Couldn't read that part"));
            }
        });
        speakAll();
    }

    private void speakAll() {
        for (int i = 0; i < chunks.size(); i++)
            tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, String.valueOf(i));
    }

    private void buildUi() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(12);
        bar.setPadding(dp(18), p, p, p);
        bar.setBackgroundColor(Color.WHITE);
        status = new TextView(this);
        status.setTextColor(Color.BLACK);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView stop = new TextView(this);
        stop.setText("Stop");
        stop.setTextColor(Color.WHITE);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stop.setGravity(Gravity.CENTER);
        stop.setMinWidth(dp(96));
        stop.setMinHeight(dp(52));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.BLACK);
        g.setCornerRadius(dp(8));
        stop.setBackground(g);
        stop.setOnClickListener(v -> finish());
        bar.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(bar);
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
