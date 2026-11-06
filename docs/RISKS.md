# Risks: read this before installing HiBreakBreak Pro

This pack makes the HiBreak Pro much nicer as an e-ink phone. It also makes it **less secure** than Bigme's own software. Decide with open eyes.

## What gets weaker

| | What it means for you |
|---|---|
| **Unlocked bootloader, verified boot off** | Someone holding your phone can install their own software on it, and the phone can't tell whether its system was tampered with. Use a screen lock, and treat a lost phone as compromised. |
| **The base Android is signed with public "test keys"** | vbbot's HiBreak Evolution X (which this pack builds on) uses Android's public test signing key. Any app signed with that same public key can install itself with system-level powers. Only install apps from sources you trust. This pack cannot fix it. Only a fully self-built Android could. |
| **Old security patches** | Android: April 2025. Bigme's drivers and kernel: December 2023, and only Bigme can update those. Known security holes stay unpatched. |
| **The phone reports itself as "secure"** | Evolution X makes the phone *claim* a locked, verified state so that apps like banking keep working. Don't rely on that claim. Some banking or work apps may still refuse to run, or may stop working later. |
| **No lock screen by default** | On e-ink, Android's swipe lock screen drew over the old screen and looked broken, so it's off. Anyone who picks up your phone can use it. **Set a PIN or fingerprint in Settings → Security** if that matters to you; that brings a proper lock screen back. |
| **Root (optional)** | If you also install Magisk, any app you grant root can do anything. The pack works without root. |

## What this pack does to help

- **Turns on the USB-debugging question.** vbbot's original lets *any* computer use USB debugging without asking. The pack restores the "Allow this computer?" prompt.
- **The e-ink helper gets its own strict security rules,** instead of running with full root and no restrictions. Checked on a real HiBreak Pro with the rules enforced.
- **Every app in the pack is open source,** with no internet permission unless it needs one, and none that collect or send data.
- **No hidden test hooks** in the release builds.

## Your data

- Installing erases the phone (unlocking wipes it). Back up first.
- The pack's apps keep everything on the phone. The keyboard learns your words locally and never sends them anywhere.

## Things that can go wrong

- **A failed flash can leave the phone not starting.** The guide's "Oops" table and `preloader_fastboot.py` (fastboot without buttons) are the way back. Going back to Bigme's original software is **not yet tested step by step**.
- **Never** flash, erase or "fix" partitions named radio, modem, nvram, nvdata, protect, vcom, waveform or calibration. They hold your phone's identity and screen calibration, and can't be replaced.
- **Never** run `adb enable-verity` on this setup; it can stop the phone from starting.

## Support

As is, no warranty, shared for free. Help happens in the Reddit thread or GitHub Discussions when people have time.
