# HiBreak Launcher

A minimal black-and-white home screen for the Bigme HiBreak Pro e-ink phone (Android 10+).

- Home shows only your favorites, one per row. Swipe sideways for all apps; type in the search box to filter.
- All apps is a 5-column grid with a "More" page for apps you rarely use. Long-press an app to favorite it,
  move it to/from More, hide it, uninstall it or open App info.
- Every app icon is redrawn on the phone as a 1-bit black line icon so the panel stays crisp. No icon art is
  bundled; nothing leaves the phone.
- Paging instead of smooth scrolling, no animations, and a small helper that makes sure every change reaches
  the e-ink panel.

Permissions: `REQUEST_DELETE_PACKAGES` (for the Uninstall item; Android asks you to confirm). No internet.

## Building

Plain Java, no Gradle. Needs Android Studio's JDK and the Android SDK (platform 35, build-tools 35.0.0;
set `ANDROID_SDK` if it is not in `~/Library/Android/sdk`).

    ./build.sh

builds a debug-style APK signed with a locally generated key into `artifacts/hibreak-launcher/`.
An optional private `personal/` folder (not in this repository) can hold your own `Flavor.java` with
first-run favorites, and a `res/drawable-nodpi/home_art.png` picture to show under them.

## Public build

    KS=/path/to/launcher-release.p12 KS_PASS=... ./build.sh --release

uses `public/Flavor.java` (no favorites, no picture, no debug logging), signs with key alias `release`
from the keystore in `KS`, and writes `artifacts/hibreak-launcher-release/hibreak-launcher.apk`.
Never commit keystores, passwords or the `personal/` folder.

License: Apache-2.0.

## Home picture
Long-press the strip under the favorites (it says "Long-press here to add a picture" when empty), then **Choose picture…** to pick any picture on the phone. It's scaled to the screen width and stays on the phone. **Remove picture** takes it away again. A sideways swipe over the picture still opens all apps.
