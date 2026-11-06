# HiBreakBreak Pro

**HiBreakBreak Pro: a usability mod for HiBreak Evolution X on the Bigme HiBreak Pro**

> This is a usability mod made over four weeks by pointing my HiBreak Pro at a webcam and having Claude debug the shit out of it.

*Unofficial. Built on vbbot's HiBreak Evolution X (Android 15). Not made or endorsed by the Evolution X team, vbbot or Bigme.*

> ⚠️ **Only for the black and white Bigme HiBreak Pro (model B651).** Not for the HiBreak Pro Color, HiBreak S, HiBreak S Color or HiBreak Plus: they are different phones, and flashing this on them can stop them from starting.

Makes the **Bigme HiBreak Pro** (black and white e-ink phone) nicer to use on **HiBreak Evolution X** (Android 15, by vbbot): a faster, cleaner e-ink screen, a sleep screen with your own pictures, and apps built for e-ink.

> **Shared for free, as is.** No warranty and no promise of support or updates. Read [docs/RISKS.md](docs/RISKS.md) before installing.

## What's in it

- **E-ink controller:**
  - per-app refresh modes;
  - a fast typing mode;
  - a quick full refresh, and cleaning after scrolling;
  - image tone controls;
  - a white screen at shutdown.
- **Sleep screen:** one of your own pictures while the phone sleeps, with the screen and lights off.
- **Boot screen:** a full-screen black and white pattern.
- **Apps:**
  - HiBreak Launcher
  - Fast Keys keyboard
  - Pie Pro (Back/Home/Recents from the right edge)
  - Fast Cam (camera)
  - Crop
  - Read aloud
- **Look:** an edge-to-edge status bar and a white notification shade. Normal Android navigation stays (gestures or the 3-button bar); with Pie Pro you can optionally make it your only navigation.
- **Optional (root):** extra fonts (Atkinson Hyperlegible Next, Lexend, Literata).

See [docs/CHANGELOG.md](docs/CHANGELOG.md) for details.

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
