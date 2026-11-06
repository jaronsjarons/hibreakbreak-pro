#!/bin/bash
# Builds HiBreak Camera without Gradle (same toolchain as tools/display-probe/build.sh).
#
#   ./build.sh            owner build: owner/ settings (adb test extras on), local key, artifacts/hibreak-camera
#   ./build.sh --release  public build: public/ settings (test extras off), release key,
#                         artifacts/hibreak-camera-release. Needs KS (keystore path) and KS_PASS (password)
#                         in the environment; key alias "release".
set -euo pipefail
RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --release) RELEASE=1 ;;
    *) echo "usage: $0 [--release]" >&2; exit 2 ;;
  esac
done
TASK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TASK_SRC="$TASK_ROOT/tools/hibreak-camera"
TASK_JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
TASK_SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
TASK_BUILD="$TASK_SDK/build-tools/35.0.0"
if [ "$RELEASE" = 1 ]; then
  : "${KS:?--release needs KS=<keystore.p12>}"
  : "${KS_PASS:?--release needs KS_PASS in the environment}"
  TASK_OUT="$TASK_ROOT/artifacts/hibreak-camera-release"
  FLAVOR_DIR="$TASK_SRC/public"
else
  TASK_OUT="$TASK_ROOT/artifacts/hibreak-camera"
  TASK_KS="$TASK_OUT/keys/hibreak-camera.p12"
  FLAVOR_DIR="$TASK_SRC/owner"
fi
rm -rf "$TASK_OUT/classes" "$TASK_OUT/dex"
mkdir -p "$TASK_OUT/classes" "$TASK_OUT/dex"
if [ "$RELEASE" = 0 ]; then
  mkdir -p "$TASK_OUT/keys"
  if [ ! -f "$TASK_KS" ]; then
    "$TASK_JAVA/bin/keytool" -genkeypair -keystore "$TASK_KS" -storetype PKCS12 -storepass hibreakcam \
      -alias hibreakcam -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=HiBreak Camera (local)"
  fi
fi
"$TASK_JAVA/bin/javac" -source 8 -target 8 -Xlint:-options -cp "$TASK_SDK/platforms/android-35/android.jar" \
  -d "$TASK_OUT/classes" "$TASK_SRC/"*.java "$FLAVOR_DIR/Flavor.java"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/d8" --min-api 29 --lib "$TASK_SDK/platforms/android-35/android.jar" \
  --output "$TASK_OUT/dex" "$TASK_OUT/classes/local/claude/hibreakcamera/"*.class
rm -f "$TASK_OUT/unsigned.apk"
"$TASK_BUILD/aapt" package -f -M "$TASK_SRC/AndroidManifest.xml" \
  -S "$TASK_SRC/res" -I "$TASK_SDK/platforms/android-35/android.jar" -F "$TASK_OUT/unsigned.apk"
(cd "$TASK_OUT/dex" && zip -q "$TASK_OUT/unsigned.apk" classes.dex)
"$TASK_BUILD/zipalign" -f 4 "$TASK_OUT/unsigned.apk" "$TASK_OUT/aligned.apk"
if [ "$RELEASE" = 1 ]; then
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --ks "$KS" --ks-key-alias release --ks-pass env:KS_PASS \
    --out "$TASK_OUT/hibreak-camera.apk" "$TASK_OUT/aligned.apk"
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify --print-certs "$TASK_OUT/hibreak-camera.apk"
else
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --ks "$TASK_KS" --ks-pass pass:hibreakcam \
    --out "$TASK_OUT/hibreak-camera.apk" "$TASK_OUT/aligned.apk"
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify "$TASK_OUT/hibreak-camera.apk"
fi
shasum -a 256 "$TASK_OUT/hibreak-camera.apk"
