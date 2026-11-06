package local.claude.hibreaklauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal e-ink home screen. At rest it shows only bookmarked apps; tapping the search box shows
 * every app, filtered as you type. Black on white, no animations or ripples, and the keyboard
 * opens only when the search box is tapped (NoLauncher ignored real touches on this ROM).
 * Tap launches; long press offers Bookmark/Remove bookmark, Rename and App info.
 * All apps is a grid (icon, small name below), like a typical launcher's app drawer:
 * a "More" tile at top left opens a second grid with the rest; long press moves apps between them.
 */
public final class HomeActivity extends Activity {
    private static final class App {
        final String label, key, original, originalKey; final ComponentName component; final android.os.UserHandle user;
        final boolean removable;
        App(String label, ComponentName component, android.os.UserHandle user, boolean removable) {
            this(label, label, component, user, removable);
        }
        /** label is the owner's name for the app (Rename), original the app's own name. */
        App(String label, String original, ComponentName component, android.os.UserHandle user, boolean removable) {
            this.label = label; this.original = original; this.component = component; this.user = user;
            this.removable = removable;
            key = label.toLowerCase(Locale.ROOT); originalKey = original.toLowerCase(Locale.ROOT);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<ComponentName, Drawable> icons = new HashMap<>();
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private static final String PREFS = "launcher", BOOKMARKS = "bookmarks", HIDDEN = "hidden",
        MORE = "more", MORE_SEEDED = "more_seeded", NAMES = "names", NAMES_SEEDED = "names_seeded";
    /** Package name -> the owner's name for it (Rename); stored in its own prefs file, NAMES. */
    private final Map<String, String> names = new HashMap<>();
    // First-run favorites and first-page apps come from Flavor (public/Flavor.java: none;
    // the owner's private personal/Flavor.java: their own lists). Apps installed later land on the first page.
    private static final String[] DEFAULT_BOOKMARKS = Flavor.DEFAULT_BOOKMARKS;
    private static final String[] DRAWER_MAIN = Flavor.DRAWER_MAIN;
    private static final int COLS = 5;
    // Grid tiles that are not apps: More (first page) and Back (inside More), both at top left.
    private static final App MORE_TILE = new App("More", new ComponentName("tile", "more"), null, false);
    private static final App BACK_TILE = new App("Back", new ComponentName("tile", "back"), null, false);
    private final Map<ComponentName, Drawable> gridIcons = new HashMap<>();
    private final java.util.Set<String> more = new java.util.HashSet<>();
    private boolean inMore;
    private final java.util.Set<String> bookmarks = new java.util.HashSet<>();
    private final java.util.Set<String> hidden = new java.util.HashSet<>();
    private boolean browsing;
    private TextView empty, menu;
    private View topBar, art;
    private long lastMenuTap;
    private View rootView;
    private PanelNudge nudge;
    private LauncherApps launcherApps;
    private EditText search;
    private ListView list;
    private Adapter adapter;
    private int rowHeight, iconSize, pad, cellHeight, gridIconSize;
    // Oct 30 (owner: the home screen "loads in the wrong place at first"): on a cold start it showed
    // "No bookmarks yet", then the favorites too low without icons, then jumped, then icons popped in one
    // by one. Now the screen stays blank white until the apps and favorite icons are ready, the list is
    // positioned before its first frame, and grid icons are made in one batch and appear together.
    private boolean loaded, gridLoading;

    // A tap that wakes the phone was delivered to the list once the launcher reappeared and
    // opened whatever app was under the finger (3/3 in the soak test). Ignore touches briefly.
    private static long ignoreTouchesUntil;   // static: survives recreate()
    private final BroadcastReceiver screenChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Wake to bookmarks. The reset used to run at SCREEN_OFF, while the window was being
            // hidden for the sleep dream; after that the process stopped rendering on later wakes
            // (Oct 20, also in v16). Resetting on wake gives the same result without drawing then.
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction()) || Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                ignoreTouchesUntil = android.os.SystemClock.uptimeMillis() + 700;
                if (browsing || search.length() > 0) reset();
                expectDraw("wake");
            }
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        launcherApps = getSystemService(LauncherApps.class);
        rowHeight = dp(52); iconSize = dp(30); pad = dp(16); cellHeight = dp(88); gridIconSize = dp(40);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        // Room for the status bar at the top only: the home picture runs to the very bottom edge (owner, Nov 6 2026);
        // fitsSystemWindows also padded the bottom for the gesture bar, leaving a white strip under the picture.
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bars = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return android.view.WindowInsets.CONSUMED;
        });
        // Takes focus from the search box on reset, so the keyboard cannot re-attach to it.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        rootView = root;

        search = new EditText(this);
        // Owner request: a search icon instead of the "Search all apps" text.
        search.setHint("");
        Drawable glass = getDrawable(android.R.drawable.ic_menu_search).mutate();
        glass.setTint(Color.BLACK);
        glass.setBounds(0, 0, dp(28), dp(28));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(dp(12));
        search.setContentDescription("Search all apps");
        search.setTextColor(Color.BLACK);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(pad, dp(14), pad, dp(14));
        search.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setShowSoftInputOnFocus(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        // Only a real tap opens the all-apps view: Android hands focus back to this box when it
        // is cleared, which previously reopened a filtered (often empty) list after Home.
        // At rest the box cannot take focus at all, so Android can never hand focus (and the
        // keyboard) back to it behind the launcher's back. A finger down on it enters search.
        setSearchActive(false);
        search.setOnTouchListener((v, e) -> {
            // Also from the all-apps view (owner: the search icon was dead there).
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && !search.isFocusable()) {
                browsing = true;
                inMore = false;
                setSearchActive(true);
                search.requestFocus();
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.showSoftInput(search, 0);
                filter(); list.setSelection(0);
            }
            return false;
        });
        search.setOnEditorActionListener((v, action, event) -> {
            boolean go = action == EditorInfo.IME_ACTION_GO
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go && !shown.isEmpty()) launch(shown.get(0));
            return go;
        });
        // Top bar: search field, and a menu icon on the right that shows all apps (owner request).
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        menu = new TextView(this);
        menu.setText("\u2630");
        menu.setTextColor(Color.BLACK);
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(pad, dp(6), pad, dp(6));
        menu.setContentDescription("All apps");
        // ☰ opens all apps; there it shows × and goes back to favorites (owner, Oct 20: a dead ☰
        // felt unclickable). Taps within 700 ms of the last are ignored so repeated taps on a slow
        // e-ink redraw cannot undo each other (the Oct 12 'frozen launcher').
        menu.setOnClickListener(v -> toggleAllApps());
        // Owner, Oct 20: no ☰ or search icon on the favorites screen; a sideways swipe opens all apps.
        // The bar (search box only) shows in the all-apps view so apps can still be typed.
        menu.setVisibility(View.GONE);
        top.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar = top;
        top.setVisibility(View.GONE);


        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setVerticalFadingEdgeEnabled(false);
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(Color.WHITE);
        list.setBackgroundColor(Color.WHITE);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> { App a = at(pos); if (a != null) open(a); });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            App a = at(pos);
            if (a != null && !isTile(a)) options(a);
            return true;
        });
        // Scrolling the list hides the keyboard so the whole list is reachable.
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {
                if (Flavor.DEBUG_LOG) android.util.Log.d("HiBreakLauncher", "scroll state " + s + " first=" + v.getFirstVisiblePosition());
                if (s == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            @Override public void onScroll(android.widget.AbsListView v, int a, int b, int c) {}
        });
        setUpPaging();
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // Owner, Oct 20: an optional picture under the favorites. The owner's private personal/res bundles one;
        // public builds ship none and show a hint until the user picks a picture.
        // Favorites screen only; a sideways swipe over it opens all apps like the list does.
        // Oct 29: anyone can pick their own picture here (long-press the area); it replaces the bundled one.
        android.widget.FrameLayout artBox = new android.widget.FrameLayout(this);
        final int artSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        final float[] start = new float[2];
        final android.os.Handler pressTimer = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable longPress = this::artOptions;
        artBox.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    start[0] = e.getX(); start[1] = e.getY();
                    pressTimer.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - start[0]) > artSlop || Math.abs(e.getY() - start[1]) > artSlop)
                        pressTimer.removeCallbacks(longPress);
                    break;
                case android.view.MotionEvent.ACTION_UP: {
                    pressTimer.removeCallbacks(longPress);
                    float dx = Math.abs(e.getX() - start[0]), dy = Math.abs(e.getY() - start[1]);
                    if (dx > artSlop && dx > dy) toggleAllApps();
                    break;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    pressTimer.removeCallbacks(longPress);
                    break;
            }
            return true;
        });
        root.addView(artBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        art = artBox;
        refreshArt();
        empty = new TextView(this);
        empty.setText("This is your homescreen.\n\nNo bookmarks yet.\nSwipe left or right for all apps, then long-press an app to bookmark it.");
        empty.setTextColor(Color.BLACK);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        empty.setPadding(pad, dp(24), pad, 0);
        empty.setVisibility(View.GONE);
        root.addView(empty, 1, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setVisibility(View.INVISIBLE);   // shown by the first reload, all at once
        setContentView(root);
        root.getViewTreeObserver().addOnDrawListener(() -> drawnAt = android.os.SystemClock.uptimeMillis());
        nudge = new PanelNudge(this);
        // Any relayout (list contents, keyboard opening/closing, resize) must reach the panel.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> nudge.burst());

        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) insets.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        IntentFilter s = new IntentFilter(Intent.ACTION_SCREEN_ON);
        s.addAction(Intent.ACTION_SCREEN_OFF); s.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenChanged, s);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(BOOKMARKS))
            prefs.edit().putStringSet(BOOKMARKS, new java.util.HashSet<>(java.util.Arrays.asList(DEFAULT_BOOKMARKS))).apply();
        bookmarks.addAll(prefs.getStringSet(BOOKMARKS, java.util.Collections.emptySet()));
        hidden.addAll(prefs.getStringSet(HIDDEN, java.util.Collections.emptySet()));
        more.addAll(prefs.getStringSet(MORE, java.util.Collections.emptySet()));
        android.content.SharedPreferences namePrefs = getSharedPreferences(NAMES, MODE_PRIVATE);
        if (!prefs.getBoolean(NAMES_SEEDED, false)) {
            android.content.SharedPreferences.Editor e = namePrefs.edit();
            for (int i = 0; i + 1 < Flavor.DEFAULT_NAMES.length; i += 2)
                if (!namePrefs.contains(Flavor.DEFAULT_NAMES[i])) e.putString(Flavor.DEFAULT_NAMES[i], Flavor.DEFAULT_NAMES[i + 1]);
            e.apply();
            prefs.edit().putBoolean(NAMES_SEEDED, true).apply();
        }
        for (Map.Entry<String, ?> n : namePrefs.getAll().entrySet()) names.put(n.getKey(), String.valueOf(n.getValue()));
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        nudge.burst();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { nudge.burst(); expectDraw("focus"); }   // e.g. back from a dialog or the keyboard
    }

    /**
     * Owner, Oct 20: after the phone slept (bird dream) the launcher kept handling taps but stopped
     * rendering: 0 frames on a ☰ tap, screencap and full panel refresh both showed the old frame,
     * while the window manager reported the window visible and drawn. So after a wake, focus
     * return or ☰, ask for a redraw and check one really happens. If not, restart the process:
     * recreate() was not enough (the new window drew once, then stalled too), but a fresh process
     * always draws, and Android relaunches the home activity at once.
     */
    private long drawnAt, expectSince;

    private void expectDraw(String why) {
        if (isFinishing() || isDestroyed()) return;
        final long since = android.os.SystemClock.uptimeMillis();
        expectSince = since;
        getWindow().getDecorView().invalidate();
        main.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || expectSince != since || !hasWindowFocus()) return;
            boolean drew = drawnAt >= since;
            if (Flavor.DEBUG_LOG) android.util.Log.d("HiBreakLauncher", "draw check (" + why + "): drew=" + drew);
            if (!drew) {
                android.util.Log.w("HiBreakLauncher", "renderer stalled; restarting process");
                Process.killProcess(Process.myPid());
            }
        }, 1000);
    }

    @Override protected void onDestroy() {
        nudge.stop();
        unregisterReceiver(packagesChanged);
        unregisterReceiver(screenChanged);
        worker.shutdownNow();
        super.onDestroy();
    }

    /** Touch trace for diagnosing missed touches (down/up/cancel only, positions only). */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int action = e.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN && android.os.SystemClock.uptimeMillis() < ignoreTouchesUntil) {
            if (Flavor.DEBUG_LOG) android.util.Log.d("HiBreakLauncher", "ignored wake touch");
            return true;
        }
        if (Flavor.DEBUG_LOG) if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL)
            android.util.Log.d("HiBreakLauncher", android.view.MotionEvent.actionToString(action)
                + " x=" + (int) e.getX() + " y=" + (int) e.getY() + " flags=0x" + Integer.toHexString(e.getFlags())
                + " first=" + (list == null ? -1 : list.getFirstVisiblePosition()) + " browsing=" + browsing + " n=" + shown.size());
        return super.dispatchTouchEvent(e);
    }

    /** Home pressed while already home: back to a clean, unscrolled list. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        reset();
    }

    @Override protected void onPause() {
        super.onPause();
        hideKeyboard();
    }

    @Override public void onBackPressed() {
        if (inMore) showMore(false); else reset();
    }

    /** The app under the finger: grid rows hold COLS apps, so the column comes from where the touch went down. */
    private App at(int row) {
        int i = row;
        if (browsing) {
            int col = (int) (downX * COLS / Math.max(1, list.getWidth()));
            i = row * COLS + Math.max(0, Math.min(COLS - 1, col));
        }
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    private static boolean isTile(App a) { return a == MORE_TILE || a == BACK_TILE; }

    private void open(App a) {
        if (a == MORE_TILE) showMore(true);
        else if (a == BACK_TILE) showMore(false);
        else launch(a);
    }

    /** More and Back sit on the same spot, so they share the 700 ms debounce: a double tap must not undo itself. */
    private void showMore(boolean show) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMenuTap < 700) return;
        lastMenuTap = now;
        inMore = show;
        filter();
        list.setSelection(0);
        expectDraw("more");
    }

    /**
     * E-ink paging: a vertical swipe of any length turns exactly one screen of apps, drawn once,
     * instead of smooth scrolling (which ghosted and moved only a little per flick). Taps and
     * long presses still go to the list.
     */
    private float downY, downX;
    private boolean paging, sideways;

    /** Favorites <-> all apps. Debounced: repeated input on a slow e-ink redraw must not undo itself. */
    private void toggleAllApps() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMenuTap < 700) return;
        lastMenuTap = now;
        if (browsing) { reset(); expectDraw("swipe"); return; }
        browsing = true;
        hideKeyboard();
        filter();
        list.setSelection(0);
        expectDraw("swipe");
    }

    private void setUpPaging() {
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 2;
        list.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); paging = false; sideways = false;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (!paging && !sideways && Math.max(dx, dy) > slop) {
                        if (dy > dx) paging = true; else sideways = true;   // vertical pages, sideways toggles
                        hideKeyboard();
                        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        list.onTouchEvent(cancel);   // drop the pending tap/long press
                        cancel.recycle();
                    }
                    return paging || sideways;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (sideways) { sideways = false; toggleAllApps(); return true; }
                    if (!paging) return false;
                    page(e.getY() < downY ? 1 : -1);
                    paging = false;
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    boolean was = paging || sideways; paging = false; sideways = false;
                    return was;
            }
            return paging || sideways;
        });
    }

    private void page(int direction) {
        int first = list.getFirstVisiblePosition();
        int step = Math.max(1, list.getChildCount() - 1);   // keep one row of overlap
        int target = Math.max(0, Math.min(first + direction * step, Math.max(0, adapter.getCount() - step)));
        list.setSelection(target);
        nudge.burst();
    }

    /** Back to the bookmarks view. */
    private void setSearchActive(boolean active) {
        search.setFocusable(active);
        search.setFocusableInTouchMode(active);
        search.setCursorVisible(active);
    }

    private void reset() {
        browsing = false;
        inMore = false;
        hideKeyboard();
        rootView.requestFocus();
        search.clearFocus();
        setSearchActive(false);
        search.setText("");
        filter();
        list.setSelection(0);
        // The keyboard can commit its unfinished word after the box is cleared; clear again.
        main.postDelayed(() -> { if (!browsing && search.length() > 0) search.setText(""); }, 400);
    }

    private void reload() {
        final java.util.Set<String> favorites = new java.util.HashSet<>(bookmarks);
        final Map<String, String> renamed = new HashMap<>(names);
        worker.execute(() -> {
            List<App> apps = new ArrayList<>();
            for (android.os.UserHandle user : launcherApps.getProfiles()) {
                for (LauncherActivityInfo info : launcherApps.getActivityList(null, user)) {
                    if (info.getComponentName().getPackageName().equals(getPackageName())) continue;
                    int flags = info.getApplicationInfo().flags;
                    String original = String.valueOf(info.getLabel());
                    String name = renamed.get(info.getComponentName().getPackageName());
                    apps.add(new App(name != null ? name : original, original, info.getComponentName(), user,
                        (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0));
                }
            }
            Collator collator = Collator.getInstance();
            apps.sort((a, b) -> collator.compare(a.label, b.label));
            // Favorite icons first, so the home screen appears complete.
            Map<ComponentName, Drawable> favIcons = new HashMap<>();
            for (App a : apps) if (favorites.contains(a.component.getPackageName())) {
                Drawable d = loadIcon(a, iconSize);
                if (d != null) favIcons.put(a.component, d);
            }
            main.post(() -> {
                all.clear(); all.addAll(apps); icons.clear(); icons.putAll(favIcons);
                gridLoading = true;
                seedMore();
                loaded = true;
                filter();
                rootView.setVisibility(View.VISIBLE);
            });
            // Then every grid icon, handed over together.
            Map<ComponentName, Drawable> grid = new HashMap<>();
            for (App a : apps) {
                if (Thread.currentThread().isInterrupted()) return;
                Drawable d = loadIcon(a, gridIconSize);
                if (d != null) grid.put(a.component, d);
            }
            main.post(() -> {
                gridIcons.keySet().removeIf(c -> !"tile".equals(c.getPackageName()));
                gridIcons.putAll(grid);
                gridLoading = false;
                if (browsing) { adapter.notifyDataSetChanged(); nudge.burst(); }
            });
        });
    }

    /** Once: everything not in Flavor.DRAWER_MAIN (or a favorite) goes to More; an empty list keeps all on page one. */
    private void seedMore() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (all.isEmpty() || prefs.getBoolean(MORE_SEEDED, false)) return;
        if (DRAWER_MAIN.length == 0) { prefs.edit().putBoolean(MORE_SEEDED, true).apply(); return; }   // public: all on page one
        java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(DRAWER_MAIN));
        for (App a : all) {
            String pkg = a.component.getPackageName();
            if (!keep.contains(pkg) && !bookmarks.contains(pkg)) more.add(pkg);
        }
        prefs.edit().putStringSet(MORE, new java.util.HashSet<>(more)).putBoolean(MORE_SEEDED, true).apply();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        // Hidden apps appear only in search results, so they can still be found and unhidden.
        if (!browsing) { for (App a : all) if (bookmarked(a) && !isHidden(a)) shown.add(a); }
        else if (q.isEmpty()) {
            shown.add(inMore ? BACK_TILE : MORE_TILE);
            for (App a : all) if (!isHidden(a) && inMoreFolder(a) == inMore) shown.add(a);
        }
        else {
            for (App a : all) if (a.key.startsWith(q)) shown.add(a);
            for (App a : all) if (!a.key.startsWith(q) && a.key.contains(q)) shown.add(a);
            // A renamed app is still found by its own name ("keep" finds Notes).
            for (App a : all) if (!a.key.contains(q) && a.originalKey.contains(q)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
        centreBookmarks();
        nudge.burst();
        empty.setVisibility(loaded && !browsing && shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (topBar != null) topBar.setVisibility(browsing ? View.VISIBLE : View.GONE);
        if (art != null) art.setVisibility(browsing ? View.GONE : View.VISIBLE);
        if (menu != null) {
            menu.setText(browsing ? "\u00D7" : "\u2630");
            menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, browsing ? 34 : 26);
            menu.setContentDescription(browsing ? "Back to favorites" : "All apps");
        }
    }

    /** Bookmarks view: left-aligned rows, block centred vertically (owner request). */
    private void centreBookmarks() {
        // Measured just before the next frame draws (it used to be posted, so one frame showed the old spot).
        // A frame with the wrong padding is skipped and redrawn after the relayout.
        list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                int top = 0;
                // Centred, then raised by one row (owner: centred list sat too low); Oct 20, with the picture
                // below shrinking the space, moved back down one row (owner).
                if (!browsing) top = Math.max(0, (list.getHeight() - shown.size() * rowHeight) / 2 - rowHeight) + rowHeight;
                if (list.getPaddingTop() == top) return true;
                list.setClipToPadding(false);
                list.setPadding(0, top, 0, 0);
                list.setSelection(0);
                return false;
            }
        });
    }

    /** Bookmarks are package names, so an app with several launcher entries is all-or-nothing. */
    private boolean bookmarked(App a) { return bookmarks.contains(a.component.getPackageName()); }
    private boolean isHidden(App a) { return hidden.contains(a.component.getPackageName()); }
    private boolean inMoreFolder(App a) { return more.contains(a.component.getPackageName()); }

    private void toggle(java.util.Set<String> set, String key, String pkg) {
        if (!set.remove(pkg)) set.add(pkg);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(key, new java.util.HashSet<>(set)).apply();
        filter();
    }

    private void options(App app) {
        hideKeyboard();
        String pkg = app.component.getPackageName();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(bookmarked(app) ? "Remove bookmark" : "Bookmark");
        actions.add(() -> toggle(bookmarks, BOOKMARKS, pkg));
        if (browsing) {
            labels.add(inMoreFolder(app) ? "Move to main apps" : "Move to More");
            actions.add(() -> toggle(more, MORE, pkg));
        }
        labels.add(isHidden(app) ? "Unhide" : "Hide");
        actions.add(() -> toggle(hidden, HIDDEN, pkg));
        if (app.removable) {
            labels.add("Uninstall");
            // The system asks for confirmation; the list refreshes from the package broadcast.
            actions.add(() -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))));
        }
        labels.add("Rename");
        actions.add(() -> rename(app));
        labels.add("App info");
        actions.add(() -> appInfo(app));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(app.label)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private static final int PICK_PICTURE = 7;
    private static final String ART_REMOVED = "home_art_removed";
    private static final String ART_BUILTIN = "home_art_builtin";
    /** Built-in patterns (public build, public/make_home_art.py); home_art is the default one. */
    private static final String[][] PATTERNS = {{"home_art", "Triangles"}, {"home_art_dots", "Dots"},
        {"home_art_stripes", "Stripes"}, {"home_art_lattice", "Lattice"}};

    private java.io.File pictureFile() { return new java.io.File(getFilesDir(), "home-picture.png"); }

    /** The picture under the favorites: the user's own, else a bundled one (owner build only), else a hint. */
    private void refreshArt() {
        android.widget.FrameLayout box = (android.widget.FrameLayout) art;
        if (box == null) return;
        box.removeAllViews();
        android.graphics.Bitmap own = pictureFile().exists()
            ? android.graphics.BitmapFactory.decodeFile(pictureFile().getPath()) : null;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int bundled = 0;
        if (!prefs.getBoolean(ART_REMOVED, false)) {
            bundled = getResources().getIdentifier(prefs.getString(ART_BUILTIN, "home_art"), "drawable", getPackageName());
            if (bundled == 0) bundled = getResources().getIdentifier("home_art", "drawable", getPackageName());
        }
        if (own != null || bundled != 0) {
            ImageView picture = new ImageView(this);
            if (own != null) picture.setImageBitmap(own); else picture.setImageResource(bundled);
            picture.setAdjustViewBounds(true);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(picture, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setContentDescription("Home picture. Long-press to change it.");
        } else {
            TextView hint = new TextView(this);
            hint.setText("Long-press here to add a picture");
            hint.setTextColor(Color.GRAY);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(18), 0, dp(18));
            box.addView(hint, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setContentDescription("Long-press to add a home picture");
        }
    }

    private void artOptions() {
        hideKeyboard();
        boolean showing = ((android.widget.FrameLayout) art).getChildAt(0) instanceof ImageView;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Choose picture…");
        actions.add(() -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { startActivityForResult(pick, PICK_PICTURE); } catch (Exception e) { /* no picker */ }
        });
        List<String[]> builtIn = new ArrayList<>();
        for (String[] p : PATTERNS)
            if (getResources().getIdentifier(p[0], "drawable", getPackageName()) != 0) builtIn.add(p);
        if (builtIn.size() > 1) {
            labels.add("Built-in patterns…");
            actions.add(() -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Built-in patterns")
                .setItems(java.util.stream.Stream.concat(builtIn.stream().map(p -> p[1]), java.util.stream.Stream.of("None"))
                    .toArray(CharSequence[]::new), (d, which) -> {
                    pictureFile().delete();
                    if (which == builtIn.size()) {   // "None": no pattern, just the hint
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ART_REMOVED, true).apply();
                    } else {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ART_REMOVED, false)
                            .putString(ART_BUILTIN, builtIn.get(which)[0]).apply();
                    }
                    refreshArt();
                }).show());
        }
        if (showing) {
            labels.add("Remove picture");
            actions.add(() -> {
                pictureFile().delete();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ART_REMOVED, true).apply();
                refreshArt();
            });
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Home picture")
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PICTURE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            boolean saved = false;
            try {
                int width = getResources().getDisplayMetrics().widthPixels;
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { android.graphics.BitmapFactory.decodeStream(in, null, bounds); }
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
                android.graphics.Bitmap decoded;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { decoded = android.graphics.BitmapFactory.decodeStream(in, null, options); }
                if (decoded != null) {
                    android.graphics.Bitmap fitted = decoded.getWidth() > width
                        ? android.graphics.Bitmap.createScaledBitmap(decoded, width, Math.max(1, decoded.getHeight() * width / decoded.getWidth()), true)
                        : decoded;
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(pictureFile())) {
                        saved = fitted.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
            } catch (Exception e) { saved = false; }
            final boolean ok = saved;
            runOnUiThread(() -> {
                if (ok) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ART_REMOVED, false).apply();
                refreshArt();
                if (!ok) android.widget.Toast.makeText(this, "Couldn't use that picture", android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "home-picture").start();
    }

    private void launch(App app) {
        hideKeyboard();
        try {
            launcherApps.startMainActivity(app.component, app.user, null, null);
        } catch (Exception e) {
            reload();
        }
    }

    private void rename(App app) {
        String pkg = app.component.getPackageName();
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(app.label);
        field.setSelectAllOnFocus(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Rename " + app.original)
            .setView(box)
            .setPositiveButton("Save", (d, w) -> setName(pkg, field.getText().toString().trim(), app.original))
            .setNegativeButton("Cancel", null);
        if (names.containsKey(pkg)) b.setNeutralButton("Use " + app.original, (d, w) -> setName(pkg, "", app.original));
        android.app.AlertDialog dialog = b.create();
        field.setOnEditorActionListener((v, id, e) -> {
            setName(pkg, field.getText().toString().trim(), app.original);
            dialog.dismiss();
            return true;
        });
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        field.requestFocus();
    }

    /** An empty name, or the app's own name, removes the rename. */
    private void setName(String pkg, String name, String original) {
        android.content.SharedPreferences.Editor e = getSharedPreferences(NAMES, MODE_PRIVATE).edit();
        if (name.isEmpty() || name.equals(original)) { names.remove(pkg); e.remove(pkg); }
        else { names.put(pkg, name); e.putString(pkg, name); }
        e.apply();
        reload();
    }

    private void appInfo(App app) {
        hideKeyboard();
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.component.getPackageName(), null)));
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && search != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private Drawable icon(App app, ImageView into, int size) {
        final Map<ComponentName, Drawable> cache = size == iconSize ? icons : gridIcons;
        if (app == MORE_TILE || app == BACK_TILE) {
            into.setTag(null);   // a recycled cell must not receive an app icon still loading
            Drawable t = cache.get(app.component);
            if (t == null) cache.put(app.component, t = tileIcon(app == MORE_TILE, size));
            return t;
        }
        Drawable d = cache.get(app.component);
        if (d != null) return d;
        into.setTag(app.component);
        if (cache == gridIcons && gridLoading) return null;   // the batch in reload() fills the whole grid at once
        worker.execute(() -> {
            final Drawable result = loadIcon(app, size);
            main.post(() -> {
                if (result == null) return;
                cache.put(app.component, result);
                if (app.component.equals(into.getTag())) into.setImageDrawable(result);
            });
        });
        return null;
    }

    /** Worker thread: the app's icon in the matching 1-bit style, or null. */
    private Drawable loadIcon(App app, int size) {
        Drawable loaded = null;
        try {
            for (LauncherActivityInfo info : launcherApps.getActivityList(app.component.getPackageName(), app.user))
                if (info.getComponentName().equals(app.component)) loaded = info.getIcon(0);
        } catch (Exception ignored) {}
        try { if (loaded != null) loaded = matching(loaded, size); } catch (Exception ignored) {}
        return loaded;
    }

    /**
     * Owner request: icons in one matching style. Every icon becomes 1-bit black on white (crisp on e-ink):
     * the app's themed monochrome layer when it has one, else the silhouette of its adaptive foreground,
     * else the dark parts of the normal icon. Near-empty results fall through to the next source, solid blobs
     * become outlines, and every icon is scaled to the same visual size.
     */
    private Drawable matching(Drawable src, int size) {
        int big = size * 3;   // work at 3x, then scale down and re-threshold
        boolean[] ink = null;
        if (src instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) src;
            Drawable[] layers = { Build.VERSION.SDK_INT >= 33 ? a.getMonochrome() : null, a.getForeground() };
            for (Drawable layer : layers) {
                if (layer == null) continue;
                // Adaptive layers are 108 units with the visible 72 in the middle.
                int[] px = render(layer.mutate(), big, -big / 4, big + big / 4);
                boolean[] on = new boolean[px.length];
                for (int i = 0; i < px.length; i++) on[i] = (px[i] >>> 24) > 110;
                int c = coverage(on);
                if (c >= 4 && c <= 55) { ink = on; break; }
            }
        }
        if (ink == null) {
            int[] px = render(src, big, 0, big);
            ink = new boolean[px.length];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int lum = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
                ink[i] = (p >>> 24) > 110 && lum < 170;
            }
        }
        if (fill(ink, big) > 72) ink = outline(ink, big, big / 30 + 1);
        return fit(ink, big, size);
    }

    /** 1-bit tile icons: four outlined squares for More (like a folder), an arrow for Back. */
    private Drawable tileIcon(boolean folder, int s) {
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2, s / 16f));
        float m = s * 0.1f, box = s * 0.8f, gap = s * 0.12f, side = (box - gap) / 2;
        if (folder) {
            for (int r = 0; r < 2; r++) for (int k = 0; k < 2; k++) {
                float x = m + k * (side + gap), y = m + r * (side + gap);
                c.drawRoundRect(x, y, x + side, y + side, side / 5, side / 5, paint);
            }
        } else {
            float mid = s / 2f;
            c.drawLine(m, mid, m + box, mid, paint);
            c.drawLine(m, mid, m + box * 0.4f, mid - box * 0.4f, paint);
            c.drawLine(m, mid, m + box * 0.4f, mid + box * 0.4f, paint);
        }
        return new BitmapDrawable(getResources(), out);
    }

    /** Percent of the ink's bounding box that is ink: a disc is ~79, a square 100, line icons far less. */
    private static int fill(boolean[] ink, int s) {
        int x0 = s, y0 = s, x1 = -1, y1 = -1, n = 0;
        for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) {
            if (!ink[y * s + x]) continue;
            n++; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        return x1 < 0 ? 0 : n * 100 / ((x1 - x0 + 1) * (y1 - y0 + 1));
    }

    private int[] render(Drawable d, int size, int from, int to) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(from, from, to, to);
        d.draw(new Canvas(bmp));
        int[] px = new int[size * size];
        bmp.getPixels(px, 0, size, 0, 0, size, size);
        return px;
    }

    /** Percent of pixels that are ink. */
    private static int coverage(boolean[] ink) {
        int n = 0;
        for (boolean b : ink) if (b) n++;
        return n * 100 / ink.length;
    }

    /** Keeps only ink within `w` pixels of the shape's edge, so a solid blob reads as a line drawing. */
    private static boolean[] outline(boolean[] ink, int s, int w) {
        boolean[] out = new boolean[ink.length];
        for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) {
            if (!ink[y * s + x]) continue;
            edge:
            for (int dy = -w; dy <= w; dy++) for (int dx = -w; dx <= w; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= s || ny >= s || !ink[ny * s + nx]) { out[y * s + x] = true; break edge; }
            }
        }
        return out;
    }

    /** Crops to the ink, scales it to fill 80% of the icon box, centres it, and re-thresholds to pure black. */
    private Drawable fit(boolean[] ink, int big, int s) {
        int x0 = big, y0 = big, x1 = -1, y1 = -1;
        int[] px = new int[ink.length];
        for (int y = 0; y < big; y++) for (int x = 0; x < big; x++) {
            if (!ink[y * big + x]) continue;
            px[y * big + x] = Color.BLACK;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
        }
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        if (x1 >= 0) {
            Bitmap src = Bitmap.createBitmap(px, big, big, Bitmap.Config.ARGB_8888);
            int w = x1 - x0 + 1, h = y1 - y0 + 1;
            float scale = s * 0.8f / Math.max(w, h);
            int dw = Math.max(1, Math.round(w * scale)), dh = Math.max(1, Math.round(h * scale));
            int left = (s - dw) / 2, top = (s - dh) / 2;
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            new Canvas(out).drawBitmap(src, new android.graphics.Rect(x0, y0, x1 + 1, y1 + 1),
                    new android.graphics.Rect(left, top, left + dw, top + dh), paint);
            int[] small = new int[s * s];
            out.getPixels(small, 0, s, 0, 0, s, s);
            for (int i = 0; i < small.length; i++) small[i] = (small[i] >>> 24) > 100 ? Color.BLACK : Color.TRANSPARENT;
            out.setPixels(small, 0, s, 0, 0, s, s);
        }
        return new BitmapDrawable(getResources(), out);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Favorites: one app per row. All apps: rows of COLS cells, each an icon with its name small below. */
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return browsing ? (shown.size() + COLS - 1) / COLS : shown.size(); }
        @Override public Object getItem(int p) { return p; }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return browsing ? 1 : 0; }

        @Override public View getView(int p, View convert, ViewGroup parent) {
            return browsing ? gridRow(p, (LinearLayout) convert) : listRow(p, (LinearLayout) convert);
        }

        private View gridRow(int p, LinearLayout row) {
            if (row == null) {
                row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(Color.WHITE);
                row.setPadding(dp(4), 0, dp(4), 0);
                for (int i = 0; i < COLS; i++) {
                    LinearLayout cell = new LinearLayout(HomeActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(2), dp(12), dp(2), 0);
                    ImageView image = new ImageView(HomeActivity.this);
                    cell.addView(image, new LinearLayout.LayoutParams(gridIconSize, gridIconSize));
                    TextView text = new TextView(HomeActivity.this);
                    text.setTextColor(Color.BLACK);
                    text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    text.setGravity(Gravity.CENTER_HORIZONTAL);
                    text.setMaxLines(2);
                    text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    text.setPadding(0, dp(6), 0, 0);
                    cell.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
                row.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight));
            }
            for (int i = 0; i < COLS; i++) {
                LinearLayout cell = (LinearLayout) row.getChildAt(i);
                ImageView image = (ImageView) cell.getChildAt(0);
                TextView text = (TextView) cell.getChildAt(1);
                int index = p * COLS + i;
                if (index >= shown.size()) {
                    cell.setVisibility(View.INVISIBLE);
                    image.setTag(null);
                    continue;
                }
                App app = shown.get(index);
                cell.setVisibility(View.VISIBLE);
                // One line for the name; hidden apps (search results only) say so on a second line.
                text.setText(isHidden(app) && !isTile(app) ? app.label + "\nhidden" : app.label);
                boolean twoLines = isHidden(app) && !isTile(app);
                text.setSingleLine(!twoLines);
                if (twoLines) text.setMaxLines(2);
                cell.setContentDescription(app.label);
                image.setImageDrawable(icon(app, image, gridIconSize));
            }
            return row;
        }

        private View listRow(int p, LinearLayout row) {
            if (row == null) {
                row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(pad, 0, pad, 0);
                row.setMinimumHeight(rowHeight);
                row.setBackgroundColor(Color.WHITE);
                ImageView image = new ImageView(HomeActivity.this);
                row.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));
                TextView text = new TextView(HomeActivity.this);
                text.setTextColor(Color.BLACK);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                text.setSingleLine(true);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.setPadding(dp(14), 0, 0, 0);
                row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                // Bookmark marker, shown only in the all-apps view.
                TextView star = new TextView(HomeActivity.this);
                star.setText("\u2605");
                star.setTextColor(Color.BLACK);
                star.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                star.setPadding(dp(12), 0, 0, 0);
                row.addView(star, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            }
            App app = shown.get(p);
            ImageView image = (ImageView) row.getChildAt(0);
            ((TextView) row.getChildAt(1)).setText(app.label);
            TextView marker = (TextView) row.getChildAt(2);
            boolean hiddenApp = isHidden(app);
            marker.setText(hiddenApp ? "hidden" : "\u2605");
            marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, hiddenApp ? 15 : 20);
            marker.setVisibility(browsing && (hiddenApp || bookmarked(app)) ? View.VISIBLE : View.GONE);
            image.setImageDrawable(icon(app, image, iconSize));
            return row;
        }
    }
}
