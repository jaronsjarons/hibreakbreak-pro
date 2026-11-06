# Patcher

Adds HiBreakBreak Pro to vbbot's HiBreak Evolution X image (May 7, 2025) on your computer. You download Evolution X yourself, and this script adds only our files. It never connects to a phone and never flashes anything.

## What it changes

| Where | What | Why |
|---|---|---|
| `/system/priv-app/A9AccessibilityService/` | E-ink controller (our fork), replacing the original | Per-app modes, faster full refresh, keyboard and scroll handling |
| `/system/bin/a9_eink_server` + `/system/etc/init/a9_eink_daemon.rc` | Helper that talks to the e-ink driver | The original has no caller check and doesn't support the new controller |
| `/system/app/HiBreakProbe/` | Sleep-image dream | Shows your picture while the phone sleeps |
| `/system/product/overlay/HiBreakBirdDoze.apk` | Tells Android to use that dream when dozing | Must be in the system partition |
| `plat_sepolicy.cil`, `plat_property_contexts` | The e-ink helper gets its own security domain (`hibreak_eink`), the front-light and e-ink driver files it uses get their own labels, and 4 compositor properties get their own label (see `policy/`) | The helper used to run as unrestricted root; now it can only reach the files and settings it needs. Checked enforcing on a real HiBreak Pro |
| `/system/product/media/bootanimation.zip` + `/system/etc/init/hibreak_bootanimation.rc` | Full-screen black and white diamond boot screen (one still frame; see `../bootanimation/`), and an init line that turns the boot screen on | Evolution X starts with it switched off; on e-ink the panel only takes the first frames during boot, so a still frame |
| `/system/product/etc/sysconfig/initial-package-stopped-states-refresh-prince.xml` | The e-ink controller doesn't start "stopped" after a fresh install | Otherwise it can't switch itself on, and the side buttons do nothing until something enables it |
| `/system/product/overlay/RefreshPrince*.apk` | The look overlays (status bar, white shade and quick settings), built in and always on. Normal Android navigation (gestures or 3-button bar) stays; the keyboard-to-the-edge overlay is only offered by the installer together with Pie Pro, plus **light mode by default** | Work right after flashing, without the installer; Evolution X otherwise starts in dark mode, where the shade stays black |
| `/system/build.prop` | `ro.adb.secure` 0 → 1 | **Security fix:** the original lets any computer use USB debugging without asking |
| Controller `oat/` files | Removed | Stale pre-compiled code for the old controller |

Nothing else changes. Kernel, vendor, modem, radio, calibration, boot and vbmeta stay untouched.

Some file and package names inside the image (`RefreshPrince*.apk`, `local.refreshprince.defaults`, `initial-package-stopped-states-refresh-prince.xml`) come from the project's working name, Refresh Prince. They stay as they are so installed phones keep matching.

## Use

```bash
python3 patch_system_img.py evolution_arm64_bgN-10.5-unofficial-20250507.img.xz payload/ out/
```

The script:
- checks the download (SHA-256 `6ff4357e…3df6`) and every payload file;
- unpacks a copy;
- adds the pack;
- runs `e2fsck`;
- writes the result's SHA-256.

It needs Python 3.9+ and e2fsprogs (Mac: `brew install e2fsprogs`).

## For maintainers

`make_payload.py <stock .img> <payload folder>` collects the built files and runs the release checks. It refuses `release: true` when any of these are true:
- a test hook is left in;
- an APK bundles pictures;
- any file contains the owner's serial, paths, email or IP.

A payload that fails is still written, but marked TEST, and the image name says `TEST-NOT-FOR-RELEASE`.
