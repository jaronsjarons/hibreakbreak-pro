#!/bin/bash
# Builds Fast Keys without Gradle (same toolchain as the HiBreak launcher and Crop). Own local signing key.
# ./build.sh            -> out/keyboard.apk, signed with the local key in keys/ (made on first run).
# ./build.sh --release  -> out-release/keyboard.apk for publishing: signed with KS (keystore path) / KS_PASS (password),
#                          alias "release"; refuses to build with touch logging on.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
RELEASE=0; [ "${1:-}" = "--release" ] && RELEASE=1
if [ $RELEASE = 1 ]; then
  : "${KS:?set KS to the release keystore path}"; : "${KS_PASS:?set KS_PASS to the keystore password}"
  [ -f "$KS" ] || { echo "no keystore at $KS" >&2; exit 1; }
  grep -q 'DEBUG_TOUCH = false' "$ROOT/src/local/claude/keyboard/KeyboardView.java" || { echo "DEBUG_TOUCH must be false for a release" >&2; exit 1; }
  OUT="$ROOT/out-release"
  rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"
else
OUT="$ROOT/out"; KS="$ROOT/keys/keyboard.p12"
rm -rf "$OUT/classes" "$OUT/dex"; mkdir -p "$OUT/classes" "$OUT/dex" "$ROOT/keys"
if [ ! -f "$KS" ]; then
  "$JAVA/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -storepass hibreakkeys \
    -alias keyboard -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Fast Keys (local)"
fi
fi
[ -f "$ROOT/assets/words.txt" ] || python3 "$ROOT/tools/make_dict.py"
"$JAVA/bin/javac" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar" \
  -d "$OUT/classes" "$ROOT/src/local/claude/keyboard/"*.java
JAVA_HOME="$JAVA" "$BT/d8" --min-api 29 --lib "$SDK/platforms/android-35/android.jar" \
  --output "$OUT/dex" "$OUT/classes/local/claude/keyboard/"*.class
"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -A "$ROOT/assets" \
  -I "$SDK/platforms/android-35/android.jar" -F "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
if [ $RELEASE = 1 ]; then   # data attributions travel with the published APK
  mkdir -p "$OUT/extra/assets"; cp "$ROOT/THIRD-PARTY.md" "$OUT/extra/assets/"
  (cd "$OUT/extra" && zip -q "$OUT/unsigned.apk" assets/THIRD-PARTY.md)
fi
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ $RELEASE = 1 ]; then
  JAVA_HOME="$JAVA" "$BT/apksigner" sign --ks "$KS" --ks-key-alias release --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --out "$OUT/keyboard.apk" "$OUT/aligned.apk"
  rm -f "$OUT/keyboard.apk.idsig"
else
JAVA_HOME="$JAVA" "$BT/apksigner" sign --ks "$KS" --ks-pass pass:hibreakkeys --out "$OUT/keyboard.apk" "$OUT/aligned.apk"
fi
JAVA_HOME="$JAVA" "$BT/apksigner" verify "$OUT/keyboard.apk"
shasum -a 256 "$OUT/keyboard.apk"
