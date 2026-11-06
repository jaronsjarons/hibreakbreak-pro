#!/bin/bash
# Builds Crop without Gradle (same toolchain as the HiBreak launcher).
#   ./build.sh            local build -> out/crop.apk, own local signing key in keys/.
#   ./build.sh --release  public build -> out-release/crop.apk, signed with KS=<keystore .p12> and
#                         KS_PASS=<password> (alias "release").
# Override the toolchain with ANDROID_HOME (SDK) and JBR (Java home) if yours live elsewhere.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
RELEASE=0
case "${1:-}" in --release) RELEASE=1 ;; "") ;; *) echo "usage: $0 [--release]" >&2; exit 2 ;; esac
JAVA="${JBR:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
if [ "$RELEASE" = 1 ]; then
  OUT="$ROOT/out-release"
  : "${KS:?set KS to the release keystore (.p12)}"; : "${KS_PASS:?set KS_PASS to its password}"
  [ -f "$KS" ] || { echo "no keystore at KS" >&2; exit 1; }
else
  OUT="$ROOT/out"; KS="$ROOT/keys/crop.p12"
  mkdir -p "$ROOT/keys"
  if [ ! -f "$KS" ]; then
    "$JAVA/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -storepass hibreakcrop \
      -alias crop -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Crop (local)"
  fi
fi
rm -rf "$OUT/classes" "$OUT/dex"; mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA/bin/javac" -source 8 -target 8 -Xlint:-options -cp "$SDK/platforms/android-35/android.jar" \
  -d "$OUT/classes" "$ROOT/src/local/claude/crop/"*.java
JAVA_HOME="$JAVA" "$BT/d8" --min-api 29 --lib "$SDK/platforms/android-35/android.jar" \
  --output "$OUT/dex" "$OUT/classes/local/claude/crop/"*.class
"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" \
  -I "$SDK/platforms/android-35/android.jar" -F "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$RELEASE" = 1 ]; then
  JAVA_HOME="$JAVA" "$BT/apksigner" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias release \
    --ks-pass env:KS_PASS --out "$OUT/crop.apk" "$OUT/aligned.apk"
else
  JAVA_HOME="$JAVA" "$BT/apksigner" sign --ks "$KS" --ks-pass pass:hibreakcrop --out "$OUT/crop.apk" "$OUT/aligned.apk"
fi
JAVA_HOME="$JAVA" "$BT/apksigner" verify "$OUT/crop.apk"
shasum -a 256 "$OUT/crop.apk"
