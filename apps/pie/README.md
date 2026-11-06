# Pie Pro (package local.claude.pie)

A faster replacement for Pie Control (`jun.ace.piecontrol`) on the HiBreak Pro: a right-edge side pie with the same
trigger strip, rings and slot geometry as Pie Control's side pie, plus optional long-press apps.
`src/local/claude/pie/Layout.java` holds the geometry; the outer-ring defaults are in `defaults/`.

- One accessibility service (`PieService`) with two `TYPE_ACCESSIBILITY_OVERLAY` windows: a 32 px touch strip
  (x 792-824, y 323-916) and a non-touchable window the pie is drawn in. These windows stay above the lock screen,
  shade, Quick Settings, keyboard, dialogs and full-screen apps.
- The pie is pre-rendered once into two bitmaps. A frame is bitmap + one sector fill + bitmap, drawn only when the
  pie opens, closes or the selected slot changes. GPU drawing (software drawing freezes on this panel). No animation.
- Swipe in from the strip, lift on a slot. Holding on an outer slot for 0.7 s opens its long-press app right away.
  Lifting in the empty centre cancels. A haptic click plays on open and on each slot change, as in Pie Control.
- Geometry is in pixels for the HiBreak Pro's 824 x 1648 screen.

## Changing the apps

Open **Pie Pro** from the app list. Each outer slot (top to bottom) has a **Tap** and a **Hold** row: tap a row,
pick an app or None. The choice is saved in the app's `slots` preferences (`tap0`-`tap4`, `hold0`-`hold4`, value
`package/activity`, empty = None; unset = the default from `defaults/`) and the pie redraws right away. The inner ring
(Back, Home, Recents) is fixed.

## Build and install

```bash
./build.sh
adb install -r out/pie.apk        # add -s <serial> if more than one device is connected
```

After an install, run `adb shell cmd package compile -m speed -f local.claude.pie` (installs drop the compile).
A sideloaded accessibility service needs `adb shell appops set local.claude.pie ACCESS_RESTRICTED_SETTINGS allow`
(or Settings > Apps > Pie Pro > Allow restricted settings) before it can be turned on in Settings > Accessibility.

To go back to Pie Control, turn Pie Pro off and Pie Control on in Settings > Accessibility.

## Public build

`./build.sh --release` makes the build that is published:

```bash
KS=/path/to/pie-release.p12 KS_PASS=... ./build.sh --release   # -> out-release/pie.apk
```

- Signed with the release keystore in `KS` (PKCS12, alias `release`); the password is read from `KS_PASS`, never
  put on the command line of the signer. Keep the keystore out of the repository.
- Always uses `defaults/public` (inner ring Back/Home/Recents, all five outer slots empty: set them in the app) and
  the icons in `assets-public/` (drawn for this project).
- A plain `./build.sh` uses `defaults/owner` and `assets/` when those folders exist (a private local setup, not
  published), otherwise the same public defaults; it signs with a local key it creates in `keys/`.
- Not published: `out/`, `out-release/`, `keys/`, `releases/`, `assets/`, `defaults/owner/`, `pie-control-backup/`,
  `PIE-CONTROL-CONFIG.md`.
