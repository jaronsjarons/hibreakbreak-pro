# HiBreak Pro hardware notes (for developers)

Lessons learned while building this pack. Most were measured with a webcam filming the panel.

- **The compositor skips unchanged frames.** The vendor's hardware composer drops a frame when it thinks nothing changed (`[POLICY] skip: No dirty region`). Two symptoms: a stale screen after wake, and frozen windows.
  - **Fix:** a tiny invisible overlay nudge (the controller's FrameNudger) forces a commit.
- **Windows drawn in software freeze.** An app window with `android:hardwareAccelerated="false"` had almost every frame skipped. Keep GPU drawing; cache static parts in a hardware layer instead.
- **The renderer can stall after sleep.** If the woken app's frame is unchanged, the old (black or sleep) image can stay on the panel. Nudge after wake if you see it.
- **Fast modes lose their first frame.** On entering Extreme/Hyper, the first frame was lost (78% wrong in a grey test). A short nudge burst on entering fixes it.
- **The black flash is swallowed after A2 modes.** Going straight from Extreme/Hyper to HD dropped the black frame most of the time. Passing through Normal for 300 ms first made it 8/8.
- **The driver's auto clean (`clean_a2`) matters.** It keeps cleaning ghosting in fast modes all the time. Turning it off and cleaning only at pauses left far more ghosting. Keep it on.
- **Hyper is not better than Extreme.** On this panel, Hyper was slower and dirtier for scrolling and typing.
- **The boot screen.** During start-up the panel takes only the first couple of updates, at any frame rate, so use one still frame. Evolution X sets `debug.sf.nobootanimation=1`; init or Magisk can set it back to 0 at post-fs-data.
- **Sleep and doze.** A non-interactive DreamService wakes on *any* key or touch, and the power key also sends `KEYCODE_F9`. Swallow input in the dream, or the phone drops into black sleep.
- **Screen off.** Requesting display OFF straight away can leave the panel blank. Show the image in DOZE_SUSPEND first, then turn the display off, and retry if the HWC repainted.
- **adb ≠ finger.** Injected taps behave differently from real touches; always check with a real finger.
- **adb over USB** drops when the phone sleeps and on big transfers.
- **Fastboot without buttons.** The MediaTek preloader accepts `FASTBOOT` for about 2 s after you plug in a phone that's off (`tools/preloader_fastboot.py`). The usual Volume + Power combinations do nothing special on this phone.
- **Don't touch:** the `vcom`, `waveform`, `nvram`, `nvdata`, `protect*` and `md1img` partitions (panel calibration and radio identity), and the `logo` partition (a single copy, read by the bootloader).
