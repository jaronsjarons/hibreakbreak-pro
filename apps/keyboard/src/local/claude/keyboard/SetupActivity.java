package local.claude.keyboard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.SharedPreferences;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launcher entry: turn the keyboard on, switch to it, and a box to try it. */
public final class SetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        int pad = Math.round(20 * dp);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Fast Keys");
        title.setTextSize(26);
        title.setTextColor(0xFF000000);
        root.addView(title);
        root.addView(button("1. Turn on Fast Keys", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); }
        }, dp));
        root.addView(button("2. Switch keyboard", new View.OnClickListener() {
            public void onClick(View v) {
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
            }
        }, dp));
        TextView hint = new TextView(this);
        hint.setText("Long-press a key for its symbol (vowels and n, c, s, y also offer accents in the strip). Slide on the space bar to move the cursor. Long-press a suggestion to remove it. Long-press the emoji key to switch keyboards.");
        hint.setTextSize(16);
        hint.setTextColor(0xFF000000);
        hint.setPadding(0, Math.round(16 * dp), 0, Math.round(8 * dp));
        root.addView(hint);
        final SharedPreferences settings = getSharedPreferences("settings", MODE_PRIVATE);
        root.addView(check("Number row above the letters", "number_row", settings, dp));
        root.addView(check("Arrow keys beside the space bar", "arrows", settings, dp));
        root.addView(check("Fix typos when I press space", "autocorrect_space", settings, dp));
        final TextView heightLabel = new TextView(this);
        heightLabel.setTextSize(16);
        heightLabel.setTextColor(0xFF000000);
        heightLabel.setPadding(0, Math.round(8 * dp), 0, 0);
        int rowDp = settings.getInt("row_dp", 54);
        heightLabel.setText("Key height: " + rowDp + " dp");
        root.addView(heightLabel);
        SeekBar height = new SeekBar(this);
        height.setMax(22);   // 44..66 dp
        height.setProgress(rowDp - 44);
        height.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                heightLabel.setText("Key height: " + (44 + v) + " dp");
                settings.edit().putInt("row_dp", 44 + v).apply();
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        root.addView(height);
        EditText test = new EditText(this);
        test.setHint("Try typing here");
        test.setTextColor(0xFF000000);
        test.setMinLines(3);
        test.setGravity(Gravity.TOP);
        root.addView(test, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    /** A setting checkbox; the keyboard picks it up the next time it opens. */
    private CheckBox check(String text, final String key, final SharedPreferences settings, float dp) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(16);
        c.setTextColor(0xFF000000);
        c.setChecked(settings.getBoolean(key, false));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { settings.edit().putBoolean(key, on).apply(); }
        });
        c.setPadding(0, Math.round(4 * dp), 0, Math.round(4 * dp));
        return c;
    }

    private Button button(String text, View.OnClickListener l, float dp) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(18);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(56 * dp));
        lp.topMargin = Math.round(12 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }
}
