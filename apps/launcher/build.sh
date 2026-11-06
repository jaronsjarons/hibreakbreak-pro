#!/bin/bash
# Builds HiBreak Launcher without Gradle (same toolchain as tools/display-probe/build.sh).
#
#   ./build.sh            owner build: private personal/ settings + picture, local key, artifacts/hibreak-launcher
#   ./build.sh --release  public build: public/ settings, triangle-mosaic picture, no debug logs, release key,
#                         artifacts/hibreak-launcher-release. Needs KS (keystore path) and KS_PASS (password)
#                         in the environment; key alias "release".
# Without a personal/ folder (e.g. a fresh clone), the plain build also uses the public settings.
set -euo pipefail
RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --release) RELEASE=1 ;;
    *) echo "usage: $0 [--release]" >&2; exit 2 ;;
  esac
done
TASK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TASK_SRC="$TASK_ROOT/tools/hibreak-launcher"
TASK_JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
TASK_SDK="${ANDROID_SDK:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
TASK_BUILD="$TASK_SDK/build-tools/35.0.0"
if [ "$RELEASE" = 1 ]; then
  : "${KS:?--release needs KS=<keystore.p12>}"
  : "${KS_PASS:?--release needs KS_PASS in the environment}"
  TASK_OUT="$TASK_ROOT/artifacts/hibreak-launcher-release"
  FLAVOR_DIR="$TASK_SRC/public"
  RES_ARGS=(-S "$TASK_SRC/public/res" -S "$TASK_SRC/res" --auto-add-overlay)   # public home picture (public/make_home_art.py)
else
  TASK_OUT="$TASK_ROOT/artifacts/hibreak-launcher"
  TASK_KS="$TASK_OUT/keys/hibreak-launcher.p12"
  if [ -d "$TASK_SRC/personal" ]; then
    FLAVOR_DIR="$TASK_SRC/personal"
    RES_ARGS=(-S "$TASK_SRC/personal/res" -S "$TASK_SRC/res" --auto-add-overlay)
  else
    FLAVOR_DIR="$TASK_SRC/public"
    RES_ARGS=(-S "$TASK_SRC/public/res" -S "$TASK_SRC/res" --auto-add-overlay)   # public home picture (public/make_home_art.py)
  fi
fi
rm -rf "$TASK_OUT/classes" "$TASK_OUT/dex"
mkdir -p "$TASK_OUT/classes" "$TASK_OUT/dex"
if [ "$RELEASE" = 0 ]; then
  mkdir -p "$TASK_OUT/keys"
  if [ ! -f "$TASK_KS" ]; then
    "$TASK_JAVA/bin/keytool" -genkeypair -keystore "$TASK_KS" -storetype PKCS12 -storepass hibreaklauncher \
      -alias hibreaklauncher -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=HiBreak Launcher (local)"
  fi
fi
"$TASK_JAVA/bin/javac" -source 8 -target 8 -Xlint:-options -cp "$TASK_SDK/platforms/android-35/android.jar" \
  -d "$TASK_OUT/classes" "$TASK_SRC/"*.java "$FLAVOR_DIR/Flavor.java"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/d8" --min-api 29 --lib "$TASK_SDK/platforms/android-35/android.jar" \
  --output "$TASK_OUT/dex" "$TASK_OUT/classes/local/claude/hibreaklauncher/"*.class
rm -f "$TASK_OUT/unsigned.apk"
"$TASK_BUILD/aapt" package -f -M "$TASK_SRC/AndroidManifest.xml" \
  "${RES_ARGS[@]}" -I "$TASK_SDK/platforms/android-35/android.jar" -F "$TASK_OUT/unsigned.apk"
(cd "$TASK_OUT/dex" && zip -q "$TASK_OUT/unsigned.apk" classes.dex)
"$TASK_BUILD/zipalign" -f 4 "$TASK_OUT/unsigned.apk" "$TASK_OUT/aligned.apk"
if [ "$RELEASE" = 1 ]; then
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --ks "$KS" --ks-key-alias release --ks-pass env:KS_PASS \
    --out "$TASK_OUT/hibreak-launcher.apk" "$TASK_OUT/aligned.apk"
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify --print-certs "$TASK_OUT/hibreak-launcher.apk"
else
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --ks "$TASK_KS" --ks-pass pass:hibreaklauncher \
    --out "$TASK_OUT/hibreak-launcher.apk" "$TASK_OUT/aligned.apk"
  JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify "$TASK_OUT/hibreak-launcher.apk"
fi
shasum -a 256 "$TASK_OUT/hibreak-launcher.apk"
