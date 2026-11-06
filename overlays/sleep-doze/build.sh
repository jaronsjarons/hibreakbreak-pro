#!/bin/bash
set -euo pipefail
TASK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TASK_JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
TASK_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TASK_BUILD="$TASK_SDK/build-tools/35.0.0"
TASK_OUT="$TASK_ROOT/artifacts/bird-doze-overlay"
TASK_KEYS="$TASK_ROOT/artifacts/prototype/aosp-test-platform"
mkdir -p "$TASK_OUT"
"$TASK_BUILD/aapt" package -f -M "$TASK_ROOT/tools/bird-doze-overlay/AndroidManifest.xml" -S "$TASK_ROOT/tools/bird-doze-overlay/res" -I "$TASK_SDK/platforms/android-35/android.jar" -F "$TASK_OUT/unsigned.apk"
"$TASK_BUILD/zipalign" -f 4 "$TASK_OUT/unsigned.apk" "$TASK_OUT/aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --key "$TASK_KEYS/platform.pk8" --cert "$TASK_KEYS/platform.x509.pem" --out "$TASK_OUT/bird-doze-overlay.apk" "$TASK_OUT/aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify "$TASK_OUT/bird-doze-overlay.apk"
mkdir -p "$TASK_OUT/boot-source"
cp "$TASK_ROOT/tools/bird-doze-overlay/AndroidManifest-boot.xml" "$TASK_OUT/boot-source/AndroidManifest.xml"
"$TASK_BUILD/aapt" package -f -M "$TASK_OUT/boot-source/AndroidManifest.xml" -S "$TASK_ROOT/tools/bird-doze-overlay/res" -I "$TASK_SDK/platforms/android-35/android.jar" -F "$TASK_OUT/boot-unsigned.apk"
"$TASK_BUILD/zipalign" -f 4 "$TASK_OUT/boot-unsigned.apk" "$TASK_OUT/boot-aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --key "$TASK_KEYS/platform.pk8" --cert "$TASK_KEYS/platform.x509.pem" --out "$TASK_OUT/bird-doze-boot.apk" "$TASK_OUT/boot-aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify "$TASK_OUT/bird-doze-boot.apk"
