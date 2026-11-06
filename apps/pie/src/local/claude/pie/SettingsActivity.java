package local.claude.pie;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the apps in the outer ring: tap a slot's "Tap" or "Hold" row, then pick an app (or None).
 * Plain black-on-white views, no animation; the pie re-renders as soon as a choice is saved.
 */
public final class SettingsActivity extends Activity {
    private static final String[] POSITION = { "Top", "Upper", "Middle", "Lower", "Bottom" };

    private SharedPreferences prefs;
    private List<ResolveInfo> apps;
    private boolean picking;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setWindowAnimations(0);
        prefs = getSharedPreferences(Layout.PREFS, MODE_PRIVATE);
        showSlots();
    }

    @Override public void onBackPressed() {
        if (picking) showSlots(); else super.onBackPressed();
    }

    private void showSlots() {
        picking = false;
        Layout.Slot[] outer = Layout.outer(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        list.addView(text("Pie Pro", 26, true, 0));
        android.widget.Switch onOff = new android.widget.Switch(this);
        onOff.setText("Pie on");
        onOff.setTextSize(18);
        onOff.setTextColor(0xFF000000);
        onOff.setPadding(0, dp(12), 0, dp(4));
        onOff.setChecked(prefs.getBoolean(Layout.KEY_ENABLED, true));
        onOff.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(Layout.KEY_ENABLED, on).apply());
        list.addView(onOff);
        list.addView(text("Off: the right edge does nothing. Swipe in from the left edge for Back.", 14, false, dp(12)));
        list.addView(text("Inner ring: Back, Home, Recents (fixed).\nOuter ring, top to bottom. Hold = opens after 0.7 s on the slot.",
                15, false, dp(16)));
        for (int i = 0; i < outer.length; i++) {
            list.addView(text(POSITION[i] + " slot", 18, true, dp(8)));
            list.addView(row("Tap", outer[i].pkg, outer[i].cls, Layout.tapKey(i)));
            list.addView(row("Hold", outer[i].longPkg, outer[i].longCls, Layout.holdKey(i)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(list);
        setContentView(scroll);
    }

    private View row(String what, String pkg, String cls, final String key) {
        final String title = what;
        TextView t = text(what + ":  " + label(pkg, cls), 18, false, 0);
        t.setMinHeight(dp(52));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(key, title); }
        });
        return t;
    }

    private void pick(final String key, String what) {
        picking = true;
        if (apps == null) {
            final PackageManager pm = getPackageManager();
            apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            final Collator col = Collator.getInstance();
            Collections.sort(apps, new Comparator<ResolveInfo>() {
                @Override public int compare(ResolveInfo a, ResolveInfo b) {
                    return col.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
                }
            });
        }
        List<String> names = new ArrayList<>();
        names.add("None");
        for (ResolveInfo r : apps) names.add(r.loadLabel(getPackageManager()).toString());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFFFFFFF);
        TextView head = text("Choose the " + what.toLowerCase() + " app", 20, true, 0);
        head.setPadding(dp(16), dp(16), dp(16), dp(8));
        page.addView(head);
        ListView lv = new ListView(this);
        lv.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView v = (TextView) super.getView(pos, convert, parent);
                v.setTextColor(0xFF000000);
                v.setMinHeight(dp(52));
                return v;
            }
        });
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                String value = "";
                if (pos > 0) {
                    ResolveInfo r = apps.get(pos - 1);
                    value = r.activityInfo.packageName + "/" + r.activityInfo.name;
                }
                prefs.edit().putString(key, value).apply();
                showSlots();
            }
        });
        page.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(page);
    }

    private String label(String pkg, String cls) {
        if (pkg == null) return "None";
        try {
            return getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0)
                    .loadLabel(getPackageManager()).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private TextView text(String s, int sp, boolean bold, int topMargin) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF000000);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
