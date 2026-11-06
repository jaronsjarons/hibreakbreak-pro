package com.lmqr.ha9_comp_service

import SystemSettingsManager
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.lmqr.ha9_comp_service.button_mapper.ButtonActionManager
import com.lmqr.ha9_comp_service.command_runners.CommandRunner
import com.lmqr.ha9_comp_service.command_runners.Commands
import com.lmqr.ha9_comp_service.command_runners.UnixSocketCommandRunner
import kotlin.math.max


class A9AccessibilityService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object { var connected: A9AccessibilityService? = null; private set }
    private lateinit var commandRunner: CommandRunner
    private lateinit var refreshModeManager: RefreshModeManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var buttonActionManager: ButtonActionManager
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var shadeBrightness: ShadeBrightnessSync
    private lateinit var displaySettingsManager: DisplaySettingsManager
    private lateinit var imageToneManager: ImageToneManager
    private lateinit var imageTransform: ImageTransformController
    private lateinit var fullRefresh: FullRefreshController
    private lateinit var shutdownWhite: ShutdownWhite
    private lateinit var shadeRefresh: ShadeRefresh
    private lateinit var scrollClean: ScrollClean
    private lateinit var skipWatcher: SkipWatcher
    private lateinit var keyboardModeObserver: KeyboardModeObserver

    private var isScreenOn = true

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    FlashLog.add(this@A9AccessibilityService, "screen-off")
                    hardwareGestureDetector.cancel()
                    hardwareGestureDetectorTop.cancel()
                    isScreenOn = false
                    commandRunner.setDisplayAwake(false)
                    keyboardModeObserver.stop()
                    fullRefresh.cancel()
                    shadeRefresh.cancel()
                    scrollClean.stop()
                    skipWatcher.stop()
                    imageToneManager.resetForSleep()
                    menuBinding.close()
                    commandRunner.runCommands(arrayOf(Commands.SPEED_BALANCED))
                    refreshModeManager.forgetAppliedMode()
                    brightnessManager.turnOffBrightness()
                }
                Intent.ACTION_SCREEN_ON -> {
                    FlashLog.add(this@A9AccessibilityService, "screen-on")
                    isScreenOn = true
                    skipWatcher.start()
                    commandRunner.setDisplayAwake(true)
                    imageToneManager.apply()
                    refreshModeManager.applyMode()
                    brightnessManager.applyBrightness()
                    keyboardModeObserver.start()
                }
            }
        }
    }
    private val receiverEink: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if(!sharedPreferences.getBoolean("allow_custom_broadcast", false))
                return

            when (intent.action) {
                "EINK_FORCE_CLEAR" -> {
                    clearScreen("broadcast")
                }

                "EINK_REFRESH_SPEED_CLEAR" -> {
                    refreshModeManager.changeMode(RefreshMode.CLEAR)
                }

                "EINK_REFRESH_SPEED_BALANCED" -> {
                    refreshModeManager.changeMode(RefreshMode.BALANCED)
                }

                "EINK_REFRESH_SPEED_SMOOTH" -> {
                    refreshModeManager.changeMode(RefreshMode.SMOOTH)
                }

                "EINK_REFRESH_SPEED_FAST" -> {
                    refreshModeManager.changeMode(RefreshMode.SPEED)
                }
            }
        }
    }

    // Camera-test hook (Oct 25 2026): adb shell only (WRITE_SECURE_SETTINGS is not granted to apps).
    // am broadcast -a local.claude.FLASH_TEST [--ei flash_black_ms N ...] [--ez flash true]
    private val flashTest: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val edit = sharedPreferences.edit()
            for (key in listOf("flash_black_ms", "flash_white_ms", "flash_settle_ms", "flash_pre_ms"))
                if (intent.hasExtra(key)) edit.putInt(key, intent.getIntExtra(key, 400))
            edit.apply()
            if (intent.getBooleanExtra("flash", false)) clearScreen("adb-test")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        commandRunner =
            UnixSocketCommandRunner()
        isScreenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        commandRunner.setDisplayAwake(isScreenOn)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // Owner requested the fastest measured e-ink mode in Settings and its search UI.
        // Apply once, so later explicit per-app choices remain the owner's choices.
        if (!BuildConfig.PUBLIC_RELEASE && !sharedPreferences.getBoolean("hibreak_fast_settings_v1",false)) {
            sharedPreferences.edit()
                .putInt("package:com.android.settings",RefreshMode.HYPER.mode)
                .putInt("package:com.google.android.settings.intelligence",RefreshMode.HYPER.mode)
                .putBoolean("hibreak_fast_settings_v1",true).apply()
        }
        // Side-button defaults (the owner's layout, default for everyone since Nov 6 2026): top press = clean,
        // top hold = e-ink settings, bottom press = front light on/off, bottom hold = e-ink menu.
        // Applied once; later choices made in Settings survive service restarts and updates.
        if (!sharedPreferences.getBoolean("hibreak_buttons_v8_configured", false)) {
            sharedPreferences.edit()
                .putString("top_single_press_eink_action", "clear")
                .putString("top_long_press_eink_action", "open_settings")
                .putString("single_press_eink_action", "toggle_backlight")
                .putString("long_press_eink_action", "open_menu")
                .putBoolean("hibreak_buttons_v8_configured", true).apply()
        }
        // Camera, Oct 25 2026: with the keyboard up, Extreme typed as fast as Hyper (median echo
        // 274 vs 288 ms) and scrolled more smoothly, with about half the ghosting. Apply once; a
        // later choice of Hyper in Settings is kept.
        // Refresh Prince first-run look (Nov 6 2026): a fresh Evolution X shade was dark and see-through on e-ink.
        // Solid quick settings and a plain grey palette, only if the user never set them.
        if (!sharedPreferences.getBoolean("refresh_prince_look_v1", false)) {
            try {
                if (android.provider.Settings.System.getString(contentResolver, "qs_transparency") == null)
                    android.provider.Settings.System.putInt(contentResolver, "qs_transparency", 100)
                if (android.provider.Settings.Secure.getString(contentResolver, "theme_customization_overlay_packages").isNullOrEmpty())
                    android.provider.Settings.Secure.putString(contentResolver, "theme_customization_overlay_packages",
                        "{\"android.theme.customization.theme_style\":\"SPRITZ\",\"android.theme.customization.color_source\":\"preset\"," +
                        "\"android.theme.customization.system_palette\":\"333333\",\"android.theme.customization.accent_color\":\"333333\"}")
            } catch (e: Exception) { Log.w("HiBreakDefaults", "first-run look not applied", e) }
            sharedPreferences.edit().putBoolean("refresh_prince_look_v1", true).apply()
        }
        if (!sharedPreferences.getBoolean("hibreak_kb_extreme_v1", false)) {
            val edit = sharedPreferences.edit().putBoolean("hibreak_kb_extreme_v1", true)
            if (sharedPreferences.getString(RefreshModeManager.FAST_KEYBOARD_MODE, "3") == "4")
                edit.putString(RefreshModeManager.FAST_KEYBOARD_MODE, "3")
            edit.apply()
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        refreshModeManager = RefreshModeManager(
            sharedPreferences,
            commandRunner
        )
        brightnessManager = BrightnessManager(sharedPreferences, commandRunner)
        shadeBrightness = ShadeBrightnessSync(this, handler, brightnessManager) { isScreenOn }
        shadeBrightness.start()
        buttonActionManager = ButtonActionManager(commandRunner, refreshModeManager, brightnessManager)
        displaySettingsManager = DisplaySettingsManager(sharedPreferences, commandRunner)
        imageTransform = ImageTransformController()
        imageToneManager = ImageToneManager(sharedPreferences, imageTransform::apply)
        imageToneManager.resetForSleep()
        fullRefresh = FullRefreshController(this,commandRunner,
            { if (isScreenOn) { refreshModeManager.forgetAppliedMode(); refreshModeManager.applyMode(); imageToneManager.apply() } },
            { if (isScreenOn) { refreshModeManager.forgetAppliedMode(); refreshModeManager.applyMode() } },
            { refreshModeManager.currentMode == RefreshMode.SPEED || refreshModeManager.currentMode == RefreshMode.HYPER })
        // White-only quick clean: the full clean's black phase was distracting after every swipe.
        shadeRefresh = ShadeRefresh(this, sharedPreferences) { if (isScreenOn) fullRefresh.lightRefresh() }
        scrollClean = ScrollClean(this, sharedPreferences, { refreshModeManager.isKeyboardVisible }) { light ->
            // Quick clean keeps the image tone: re-applying it afterwards would be drawn in the
            // app's fast mode and leave text half-driven (camera-verified, Oct 10 2026).
            if (isScreenOn) { if (light) fullRefresh.lightRefresh() else clearScreen("after-scroll") }
        }
        skipWatcher = SkipWatcher(scrollClean.nudger)
        refreshModeManager.onFastModeEntered = { if (isScreenOn) scrollClean.nudger.burst(500L) }
        if (isScreenOn) skipWatcher.start()
        connected = this
        keyboardModeObserver = KeyboardModeObserver(this) { visible ->
            refreshModeManager.setKeyboardVisible(visible)
            menuBinding.updateButtons(refreshModeManager.currentMode)
        }

        val filterScreen = IntentFilter()
        filterScreen.addAction(Intent.ACTION_SCREEN_ON)
        filterScreen.addAction(Intent.ACTION_SCREEN_OFF)
        filterScreen.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(receiver, filterScreen)
        shutdownWhite = ShutdownWhite(this, commandRunner) {
            fullRefresh.cancel(); shadeRefresh.cancel(); scrollClean.stop(); menuBinding.close()
        }
        registerReceiver(shutdownWhite, IntentFilter(Intent.ACTION_SHUTDOWN))

        val filterEink = IntentFilter()
        filterEink.addAction("EINK_FORCE_CLEAR")
        filterEink.addAction("EINK_REFRESH_SPEED_CLEAR")
        filterEink.addAction("EINK_REFRESH_SPEED_BALANCED")
        filterEink.addAction("EINK_REFRESH_SPEED_SMOOTH")
        filterEink.addAction("EINK_REFRESH_SPEED_FAST")
        registerReceiver(receiverEink, filterEink, RECEIVER_EXPORTED)
        if (!BuildConfig.PUBLIC_RELEASE)
            registerReceiver(flashTest, IntentFilter("local.claude.FLASH_TEST"),
                android.Manifest.permission.WRITE_SECURE_SETTINGS, null, RECEIVER_EXPORTED)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private val hardwareGestureDetector = HardwareGestureDetector(
        object: HardwareGestureDetector.OnGestureListener{
            override fun onSinglePress() {
                Log.i("HiBreakButtons", "bottom short screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeSinglePress(this@A9AccessibilityService)
                else
                    buttonActionManager.executeSinglePressScreenOff(this@A9AccessibilityService)
            }

            override fun onDoublePress() {
                Log.i("HiBreakButtons", "bottom double screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeDoublePress(this@A9AccessibilityService)
                else
                    buttonActionManager.executeDoublePressScreenOff(this@A9AccessibilityService)
            }

            override fun onLongPress() {
                Log.i("HiBreakButtons", "bottom long screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeLongPress(this@A9AccessibilityService)
                else
                    buttonActionManager.executeLongPressScreenOff(this@A9AccessibilityService)
            }
        }
    )

    private val hardwareGestureDetectorTop = HardwareGestureDetector(
        object: HardwareGestureDetector.OnGestureListener{
            override fun onSinglePress() {
                Log.i("HiBreakButtons", "top short screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeSinglePressTop(this@A9AccessibilityService)
                else
                    buttonActionManager.executeSinglePressScreenOffTop(this@A9AccessibilityService)
            }

            override fun onDoublePress() {
                Log.i("HiBreakButtons", "top double screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeDoublePressTop(this@A9AccessibilityService)
                else
                    buttonActionManager.executeDoublePressScreenOffTop(this@A9AccessibilityService)
            }

            override fun onLongPress() {
                Log.i("HiBreakButtons", "top long screenOn=$isScreenOn")
                if(isScreenOn)
                    buttonActionManager.executeLongPressTop(this@A9AccessibilityService)
                else
                    buttonActionManager.executeLongPressScreenOffTop(this@A9AccessibilityService)
            }
        }
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val detector = when (event.scanCode) {
            59 -> hardwareGestureDetector
            60 -> hardwareGestureDetectorTop
            else -> return super.onKeyEvent(event)
        }
        Log.i("HiBreakButtons", "scan=${event.scanCode} action=${event.action} repeat=${event.repeatCount} canceled=${event.isCanceled}")
        if (event.isCanceled) detector.cancel()
        else if (!Settings.canDrawOverlays(baseContext)) requestOverlayPermission()
        else detector.onKeyEvent(event.action, event.eventTime)
        return true // Do not also deliver the mapped F1/F2 key to the foreground app.
    }


    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("OverlayPermission", "Activity not found exception", e)
        }
    }

    private fun getAppUsableScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        return size
    }

    private fun getRealScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    private fun getNavBarHeight(): Int {
        val appUsableSize: Point = getAppUsableScreenSize(this)
        val realScreenSize: Point = getRealScreenSize(this)

        if (appUsableSize.y < realScreenSize.y)
            return (realScreenSize.y - appUsableSize.y)

        return 0
    }

    private var menuBinding: FloatingMenuViewAccessor? = null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun openFloatingMenu() {
        try {
            menuBinding?.run {
                if (root.visibility == View.VISIBLE) {
                    root.visibility = View.GONE
                } else {
                    root.visibility = View.VISIBLE
                    updateButtons(refreshModeManager.currentMode)
                    bindImageControls(this)
                }
            } ?: run {

                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.floating_menu_layout, null, false)

                val layoutParams = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    flags =
                        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.BOTTOM
                    y = getNavBarHeight()
                }

                menuBinding = FloatingMenuViewAccessor(view).apply {
                    root.setOnTouchListener { v: View, event: MotionEvent ->
                        if (event.action == MotionEvent.ACTION_OUTSIDE)
                            close()
                        false
                    }

                    button1.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.CLEAR)
                        updateButtons(refreshModeManager.currentMode)
                    }
                    button2.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.BALANCED)
                        updateButtons(refreshModeManager.currentMode)
                    }
                    button3.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.SMOOTH)
                        updateButtons(refreshModeManager.currentMode)
                    }
                    button4.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.SPEED)
                        updateButtons(refreshModeManager.currentMode)
                    }

                    button5.setOnClickListener {
                        refreshModeManager.changeMode(RefreshMode.HYPER)
                        updateButtons(refreshModeManager.currentMode)
                    }

                    displaySettingsManager.setupSwitches(autoRefresh, antiShake)


                    settingsIcon.setOnClickListener {
                        val settingsIntent = Intent(
                            this@A9AccessibilityService,
                            SettingsActivity::class.java
                        )
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        close()
                        startActivity(settingsIntent)
                    }


                    brightnessManager.setupSeekBars(lightSeekbar, lightWarmSeekbar)
                    bindImageControls(this)
                    clearScreen.setOnClickListener { close(); this@A9AccessibilityService.clearScreen("menu") }
                    updateButtons(refreshModeManager.currentMode)

                    wm.addView(root, layoutParams)
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    override fun onDestroy() {
        keyboardModeObserver.stop()
        shadeBrightness.stop()
        hardwareGestureDetector.cancel()
        hardwareGestureDetectorTop.cancel()
        if (connected === this) connected = null
        scrollClean.stop()
        skipWatcher.stop()
        shadeRefresh.cancel()
        fullRefresh.cancel()
        imageTransform.close()
        handler.removeCallbacksAndMessages(null)
        commandRunner.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(receiver)
        unregisterReceiver(receiverEink)
        if (!BuildConfig.PUBLIC_RELEASE) unregisterReceiver(flashTest)
        unregisterReceiver(shutdownWhite)
        menuBinding?.let { if(it.root.isAttachedToWindow) getSystemService(WindowManager::class.java).removeView(it.root) }
        menuBinding = null
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("A9Service", "Service connected - applying all settings")
        if (isScreenOn) keyboardModeObserver.start()
        
        handler.postDelayed({
            if (isScreenOn) { refreshModeManager.applyMode(); imageToneManager.apply() }
            else commandRunner.runCommands(arrayOf(Commands.SPEED_BALANCED))
            Log.d("A9Service", "Applied refresh mode: ${refreshModeManager.currentMode}")
            
            handler.postDelayed({
                if (isScreenOn) brightnessManager.applyBrightness() else brightnessManager.turnOffBrightness()
                shadeBrightness.pushLevelToShade()
                Log.d("A9Service", "Applied brightness: cold=${brightnessManager.coldBrightness}, warm=${brightnessManager.warmBrightness}")
                
                handler.postDelayed({
                    displaySettingsManager.applySettings()
                    Log.d("A9Service", "Applied display settings: autoRefresh=${displaySettingsManager.isAutoRefreshEnabled}, antiShake=${displaySettingsManager.isAntiShakeEnabled}")
                    Log.d("A9Service", "Applied remaining settings")
                }, 500)
            }, 500)
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isScreenOn) return
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Scroll events only drive the clean-after-scroll timer; keep this path cheap.
            scrollClean.onScroll(event.packageName?.toString())
            return
        }
        event?.let(shadeRefresh::onEvent)
        keyboardModeObserver.start()
        // Settings search can report a view class instead of its Activity class.
        // The existing privileged system UID can resolve the actual foreground
        // component without retrieving screen content or polling in the background.
        foregroundActivity()?.let { component ->
            applyForegroundPackage(component.packageName)
            return
        }
        event.letPackageNameClassName { pkgName, clsName ->
            val componentName = ComponentName(
                pkgName,
                clsName
            )
            try {
                packageManager.getActivityInfo(componentName, 0)
                applyForegroundPackage(pkgName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun foregroundActivity(): ComponentName? {
        if (Process.myUid() != Process.SYSTEM_UID ||
            checkSelfPermission("android.permission.GET_TASKS") != PackageManager.PERMISSION_GRANTED) return null
        return try { getSystemService(ActivityManager::class.java)
            .getRunningTasks(1).firstOrNull()?.topActivity }
        catch (_: RuntimeException) { null }
    }

    private fun applyForegroundPackage(packageName: String) {
        scrollClean.onAppChange(packageName)
        refreshModeManager.onAppChange(packageName)
        imageToneManager.onAppChange(packageName)
        menuBinding.updateButtons(refreshModeManager.currentMode)
    }

    private fun bindImageControls(menu: FloatingMenuViewAccessor) {
        imageToneManager.bind(menu.imageContrast,menu.imageBrightness,menu.contrastLabel,menu.brightnessLabel,
            menu.useDefaultContrast,
            menu.greyDarkness,menu.greyDarknessLabel)
        val pkg=imageToneManager.currentPackage
        val label=try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }
            catch (_: PackageManager.NameNotFoundException) { pkg.ifEmpty { "Current app" } }
        menu.profileLabel.text="Display: $label"
    }

    fun clearScreen(reason: String = "other") {
        if (!isScreenOn) return
        FlashLog.add(this, "flash:$reason", imageToneManager.currentPackage)
        imageToneManager.resetForSleep(); fullRefresh.refresh()
    }

    fun openButtonSettings() {
        menuBinding.close()
        startActivity(Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun updateColorScheme(sharedPreferences: SharedPreferences) = sharedPreferences.run {
        val type = getString("color_scheme_type", "5")
        val colorString = getInt("color_scheme_color", 20).progressToHex()
        commandRunner.runCommands(arrayOf("theme $type $colorString"))
    }

    private fun updateMaxBrightness(sharedPreferences: SharedPreferences) = sharedPreferences.run {
        try{
            val brightness = Integer.parseInt(getString("override_max_brightness", "2000")?:"2000")
            commandRunner.runCommands(arrayOf("smb$brightness"))
        } catch (e: NumberFormatException) {
            e.printStackTrace()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            RefreshModeManager.FAST_KEYBOARD -> refreshModeManager.updateEffectiveMode()
            ImageToneManager.DEFAULT_CONTRAST, ImageToneManager.DEFAULT_DARKNESS -> {
                if (isScreenOn) imageToneManager.apply()
            }
            "override_max_brightness" -> {
                sharedPreferences?.let { updateMaxBrightness(it) }
            }

            "color_scheme_type", "color_scheme_color" -> {
                sharedPreferences?.let { updateColorScheme(it) }
            }

            "close_status_bar" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }

            "run_clear_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                handler.postDelayed({
                    clearScreen("tile")
                }, 700)
            }
        }
    }
}

fun AccessibilityEvent?.letPackageNameClassName(block: (String, String) -> Unit) {
    this?.run {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            this.className?.let { clsName ->
                this.packageName?.let { pkgName -> block(pkgName.toString(), clsName.toString()) }
            }
    }
}

fun Button.deselect() {
    setBackgroundResource(R.drawable.drawable_border_normal)
    setTextColor(Color.BLACK)
}

fun Button.select() {
    setBackgroundResource(R.drawable.drawable_border_pressed)
    setTextColor(Color.WHITE)
}
