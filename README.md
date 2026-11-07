# HiBreakBreak Pro

**A custom ROM for the black and white Bigme HiBreak Pro (B651): vbbot's Evolution X with usability improvements, plus a bunch of e-ink apps.** Personal project, free, as is.

- **ROM with apps:** follow the [install guide](docs/INSTALL-LIKE-IM-5.md)
- **Just the apps:** [download them here](#just-want-the-apps), no flashing needed

The apps were tested with the ROM, so I can't vouch for how they run on stock.

> This is a usability mod made over four weeks by pointing my HiBreak Pro at a webcam and having Claude debug the shit out of it, then daily driving it and fixing whatever annoyed me.

*Unofficial. Built on vbbot's HiBreak Evolution X (Android 15). Not made or endorsed by the Evolution X team, vbbot or Bigme.*

> ⚠️ **Only for the black and white Bigme HiBreak Pro (model B651).** Not for the HiBreak Pro Color, HiBreak S, HiBreak S Color or HiBreak Plus: they are different phones, and flashing this on them can stop them from starting.

> **Heads up:** you have to unlock the bootloader and flash it, which wipes your phone, and it's less secure than Bigme's software. Read [docs/RISKS.md](docs/RISKS.md) first. No warranty and no promise of support or updates.

## ROM changes

Compared to Bigme's own software:

- **Android 15** (vbbot's Evolution X), and none of Bigme's own apps.
- **E-ink controller:** refresh mode per app (Normal by default; switch any app to Extreme for speed), automatic fast mode while typing, quick full refresh, image tone controls, and a white screen when you power off.
- **The screen cleans itself** after scrolling and after you close the notification shade.
- **Side buttons:** top press cleans the screen, bottom press turns the front light on/off, hold either one for the e-ink settings. The brightness slider in quick settings controls the front light.
- **Sleep images:** your own picture stays on the screen while the phone sleeps.
- **Light mode everything:** white notification shade, quick settings and status bar.
- **Boot screen:** a full-screen black and white pattern.
- **Auto-rotate off** by default. You can turn it back on in Settings.
- **No swiping lock screen,** because of ghosting issues. You can turn it back on in Settings, or set a PIN.
- **Optional (root):** extra fonts (Atkinson Hyperlegible Next, Lexend, Literata).

## The apps

- **Fast Keys:** a SwiftKey-like keyboard with no animations and no accounts. Word suggestions, learns your words (on the phone only), space-bar cursor, emoji. It's not perfect, but it's the fastest I've been able to get on this device.
- **HiBreak Launcher:** a Niagara-like home screen made for e-ink. Favourites, a 5-column grid, 1-bit icons, and an optional pattern or picture along the bottom.
- **Pie Pro:** Back, Home, Recents and app shortcuts from the right edge. Made for speed. You can switch it off; normal Android navigation stays.
- **Fast Cam:** a camera tuned for a black and white screen. It tells you when the picture is focused, since you can't really tell from an e-ink preview.
- **Crop:** share a picture, crop it, send it on.
- **Read aloud:** select any text and have it read out. It works with any text-to-speech engine; SherpaTTS (F-Droid) keeps it fully offline.

**About changes:** the apps are easy for me to update, so if something in them bugs you, tell me. The system side is a pain to change. Every change has to be flashed and tested on a real phone, and I only have mine. So unless someone's up for testing builds on their own HiBreak Pro, that part will mostly stay as it is.

See [docs/CHANGELOG.md](docs/CHANGELOG.md) for details.

## Just want the apps?

Six of the apps are normal Android apps and don't need the ROM.

**[Download all six apps (zip)](https://github.com/jaronsjarons/hibreakbreak-pro/releases/latest/download/HiBreakBreak-Pro-Apps.zip)**

Or tap an app below to download just that one, then open the file on your phone to install it:

| App | Needs |
|---|---|
| [HiBreak Launcher](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.0/HiBreak-Launcher-0.1.0.apk) | Android 10+ |
| [Fast Keys](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.4/Fast-Keys-0.1.4.apk) (keyboard) | Android 10+ |
| [Pie Pro](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.2/Pie-Pro-0.1.2.apk) (edge navigation) | Android 11+, turn it on in Settings → Accessibility |
| [Fast Cam](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.0/Fast-Cam-0.1.0.apk) | Android 10+ (the extra photo modes only on MediaTek phones like the HiBreak Pro) |
| [Crop](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.0/Crop-0.1.0.apk) | Android 10+ |
| [Read aloud](https://github.com/jaronsjarons/hibreakbreak-pro/releases/download/v0.1.0/Read-Aloud-0.1.0.apk) | Android 10+, uses your phone's text-to-speech engine |

The e-ink controller and Sleep images are part of the system, so they only work with the full ROM. The apps were tested with the ROM, so I can't vouch for how they run on stock. Tell me if they work for you.

**Updates:** with [Obtainium](https://github.com/ImranR98/Obtainium), add `https://github.com/jaronsjarons/hibreakbreak-pro` and filter by the app's file name (for example `Fast-Keys`). It then updates the app whenever there's a new release.

## Install

Follow **[docs/INSTALL-LIKE-IM-5.md](docs/INSTALL-LIKE-IM-5.md)**. In short:
1. Unlock the bootloader. This erases the phone.
2. Run [`patcher/patch_system_img.py`](patcher/) on vbbot's Evolution X image. You download vbbot's image yourself, and we only add our files.
3. Flash the result.
4. Run [`installer/hibreak_pack.py install`](installer/). It has a dry run and an undo.

To go back to Bigme's own Android: [docs/RESTORE-STOCK.md](docs/RESTORE-STOCK.md) (draft, untested).

If the phone won't start: [`tools/preloader_fastboot.py`](tools/preloader_fastboot.py) gets it into fastboot with no buttons.

## Folders

| Folder | What |
|---|---|
| `controller/` | E-ink controller and its helper (fork of damianmqr's controller, via vbbot) |
| `sleep-images/` | Sleep-screen dream and picture picker |
| `apps/` | Launcher, Fast Keys, Pie Pro, Fast Cam, Crop, Read aloud |
| `overlays/` | Look overlays (status bar, shade, keyboard, sleep doze setting) |
| `boot-screen/` | Boot screen generator, plus an optional Magisk module |
| `fonts-module/` | Optional Magisk font module |
| `patcher/`, `installer/`, `tools/` | Install tools |
| `docs/` | Guide, risks, changelog, hardware notes |

Ready-made APKs and files are on the **Releases** page, never in the repository.

## Building

Each part has a `build.sh` or README. You need the Android SDK (`ANDROID_HOME`) and a JDK 17+. Some parts must be signed with the **public AOSP platform test key** (as vbbot's image is), which you download from AOSP's `build/target/product/security/`. That key is public and gives no security; see [docs/RISKS.md](docs/RISKS.md). The apps build with your own key: `./build.sh --release` with `KS`/`KS_PASS`.

## Licence

Our code is **Apache-2.0** ([LICENSE](LICENSE)). Parts from others keep their own licences; see [NOTICE.md](NOTICE.md).
