# HiBreak Camera

A still camera for the Bigme HiBreak Pro e-ink phone (Android 10+), designed for a black-and-white panel.

- Quality mode turns on the phone's MediaTek multi-frame noise reduction and anti-shake; Fast takes a single frame.
- Optional contrast curve for e-ink viewing, flash off/auto/on, 1x/2x and pinch zoom, tap to focus,
  volume keys as shutter, front/back switch.
- Photos are saved through MediaStore to `DCIM/HiBreakCamera`. Tap the thumbnail to review, open or delete.

Permissions: `CAMERA` only. No internet. The MediaTek-specific settings are skipped on other phones.

## Building

Plain Java, no Gradle. Needs Android Studio's JDK and the Android SDK (platform 35, build-tools 35.0.0;
set `ANDROID_SDK` if it is not in `~/Library/Android/sdk`).

    ./build.sh

builds with `owner/Flavor.java` (adb test extras such as `--ei shoot 1` enabled) and a locally generated key,
into `artifacts/hibreak-camera/`.

## Public build

    KS=/path/to/camera-release.p12 KS_PASS=... ./build.sh --release

uses `public/Flavor.java` (test extras ignored, so other apps cannot change settings or trigger the shutter),
signs with key alias `release` from the keystore in `KS`, and writes
`artifacts/hibreak-camera-release/hibreak-camera.apk`. Never commit keystores or passwords.

License: Apache-2.0.
