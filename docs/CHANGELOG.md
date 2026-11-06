# Changelog: HiBreakBreak Pro

**HiBreakBreak Pro: a usability mod for HiBreak Evolution X on the Bigme HiBreak Pro**

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
