# Changelog: HiBreakBreak Pro

**HiBreakBreak Pro: a usability mod for HiBreak Evolution X on the Bigme HiBreak Pro**

## 0.1.6 (November 2026)

- **Fast Cam 0.1.1: fixed "Camera error 2" and a crash on first launch.** Reported on Bigme's stock software. Right after you allowed camera access, Fast Cam opened the camera twice at once and got in its own way; stock Android refuses the second open, which showed as "error 2" and then crashed. It now opens the camera once. If the camera is busy it tries again a few times, and if it still can't, it says why instead of crashing.

**Already on 0.1.x?** No need to reflash. Install `Fast-Cam-0.1.1.apk` from this release over your current Fast Cam.

**Just using the apps?** Same: install `Fast-Cam-0.1.1.apk` over your current Fast Cam. The other apps are unchanged.

## 0.1.5 (November 2026)

The screen and typing settings a new phone starts with are now the ones tuned on the author's phone.

- **"Clean when switching apps" is on by default.** One quick white flash about 0.15 s after you open an app. Measured with a webcam: half a second after a switch, ghosting covered 17.6% of the screen without it and 4.7% with it, and nothing after 2.5 s. Turn it off in the e-ink settings if you don't like the flash.
- **Fast Keys 0.1.5 leaves room for Android's navigation strip.** Its bottom row was drawn under the strip's hide-keyboard arrow (and the keyboard globe, with more than one keyboard). With Pie Pro as your only navigation (keyboard down to the bottom edge), the installer now also keeps those buttons out of the way.

## 0.1.4 (November 2026)

Measured on the phone itself (Fast Keys and the e-ink driver both log their timings).

- **Fast Keys 0.1.4: suggestions about twice as fast.** Fast Keys waited 150 ms after each key before showing suggestions. It now waits 40 ms, so the suggestions appear in the same screen update as the letter: about 0.1 s after the touch instead of 0.22 s. The letter itself is not slower.
- **The automatic clean stays on while you type.** Pausing it (0.1.2) made typing no faster: a letter reaches the screen in about 40 ms either way. It only left more ghosting behind.
- **"Clean when switching apps" is quicker** (still off by default): it now cleans about 0.15 s after the switch and hands the screen back to the fast mode sooner.

## 0.1.3 (November 2026)

Found with a webcam pointed at the phone.

- **Fixed: ghosting that followed you from app to app.** After typing, the outlines of the keyboard's keys stayed on the screen as a grid of faint lines, in every app, until a full refresh. The screen now does one quick clean when the keyboard closes. You can turn it off in the e-ink settings ("Clean when the keyboard closes").
- **Measured: typing no longer competes with the screen driver.** While typing in the fast keyboard mode, the driver's automatic clean used about 1.8 CPU cores; it is paused while the keyboard is up (since 0.1.2) and that is now confirmed at 0.
- **New option, off by default:** "Clean when switching apps" does one quick flash after each app switch.

## 0.1.2 (November 2026)

The phone felt slower and ghostier on a fresh install than on the author's own phone. The difference was settings, so the author's tuned settings are now the defaults.

- **Faster typing and Pie.** In Extreme, the screen driver's automatic clean keeps about two CPU cores busy. It now pauses while the keyboard is up or the Pie Pro pie is open, and comes back as soon as they close.
- **Normal is the default mode again** (was Extreme). Extreme left white ghosting, especially in menus, and kept the driver's clean busy all the time. Normal looks clean and leaves the CPU free. Switch any app you want faster to Extreme in the e-ink menu; the keyboard still switches to Extreme by itself while you type.
- **Darker greys by default** (grey darkness 41), anti-flicker off, and a short press of either side button while the screen is off does a clean refresh.
- **Pie Pro 0.1.2** tells the e-ink controller when the pie opens and closes. Without HiBreakBreak Pro it does nothing different.
- These defaults only fill in settings you never changed. Your own choices are kept.

## 0.1.1 (November 2026)

- **Fixed: the screen could ghost much more.** The e-ink controller switched on the screen driver's automatic clean only once, when it started. If that didn't reach the driver, the clean stayed off (found off on the author's phone). It is now switched on again every time the screen wakes up, and twice more after the phone starts.
- **Auto-rotate is off by default.** You can still turn it on in Settings.

## 0.1.0 (November 2026)

First public version. It builds on vbbot's HiBreak Evolution X (Android 15, May 7, 2025) and its e-ink controller by damianmqr.

### E-ink controller (fork of the upstream controller)
- **Extreme by default** for every app (set any app or the default back in the e-ink menu).
- Side buttons by default: top press = clean refresh, top hold = e-ink settings, bottom press = front light on/off, bottom hold = e-ink menu.
- The quick-settings brightness slider drives the front light.
- Per-app refresh modes, with a faster keyboard mode (Extreme) that switches on automatically while typing.
- Faster full refresh (about 0.4 s black, then 0.4 s white) that works reliably after the fast modes.
- Cleaning after scrolling and live scroll, cleaning of the notification shade, and image tone controls (contrast, brightness, grey darkness).
- The first frame is no longer lost when entering a fast mode.
- **The screen turns white when the phone shuts down,** instead of keeping "Shutting down" on the panel.
- The helper that talks to the e-ink driver checks who is calling, and gets its own strict security rules.
- The driver's own automatic clean stays on: turning it off left much more ghosting.

### Sleep screen
- Shows one of **your own pictures** while the phone sleeps, with the screen and lights off.
- Fixed: black screen instead of the picture, the phone dropping into black sleep on a key or touch, and the phone waking itself up.

### Out of the box (no installer needed)
- Light mode by default, with the white notification shade and status bar look built in and always on. Android's own navigation (gestures or 3-button bar) works as usual.
- Plain white default wallpaper, and no swipe lock screen by default (set a PIN in Settings for a real lock screen).
- The e-ink controller switches itself on after a fresh install.

### Boot screen
- Full-screen black and white diamond pattern after the Bigme logo. Evolution X started with the boot screen switched off.

### Apps
- **HiBreak Launcher:** home screen made for e-ink, with favourites, swipe sideways, a 5-column grid and matching 1-bit icons.
- **Fast Keys:**
  - a fast keyboard with no animations, word suggestions and autocorrect;
  - next-word prediction and learning (kept on the phone);
  - space-bar cursor, emoji, and number and phone pads.
- **Pie Pro:** Back/Home/Recents and app shortcuts from the right edge.
- **Fast Cam** (camera), **Crop**, **Read aloud** (select text, then Read aloud).
- Fast Keys in Chrome's address bar: picking a suggestion replaces the word you were typing (it used to add it twice, like "pizzapizza").

### Look
- Overlays for an edge-to-edge status bar with black icons, a white shade and quick settings, and the keyboard down to the bottom edge.
- Optional Magisk module: Atkinson Hyperlegible Next, Lexend and Literata as system fonts.

### Install
- ROM patcher: adds the pack to vbbot's image on your computer. You download vbbot's image yourself.
- Installer with dry run and undo for the apps, overlays and settings.
- `preloader_fastboot.py`: gets to fastboot without any buttons, when Android doesn't start.
- Security fix: the USB-debugging "Allow this computer?" prompt is back. vbbot's original allowed any computer.
