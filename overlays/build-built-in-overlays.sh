#!/bin/bash
# Builds the overlays that HiBreakBreak Pro puts INTO the system image (patcher), so they work right after flashing:
# - the five look overlays as static (always on) copies of the ones in tools/*-overlay
# - refresh-prince-defaults-overlay (light mode by default)
# Signed with the public AOSP platform test key, like the image's other overlays.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
KEYS="$ROOT/artifacts/prototype/aosp-test-platform"
OUT="$ROOT/artifacts/refresh-prince-overlays"
# framework-res.apk and SystemUI.apk from the phone (/system/framework, pm path com.android.systemui), as the overlays' own build.sh use.
LINK="${LINK:-$ROOT/artifacts/refresh-prince-overlays-link}"
rm -rf "$OUT"; mkdir -p "$OUT/src"
build() {  # build <source dir> <apk name> [static priority]
  local src="$1" name="$2" prio="${3:-}" man="$OUT/src/$2/AndroidManifest.xml"
  mkdir -p "$OUT/src/$2"
  if [ -n "$prio" ]; then
    sed -E "s#<overlay ([^/]*)android:priority=\"[0-9]+\"#<overlay \1android:isStatic=\"true\" android:priority=\"$prio\"#" "$src/AndroidManifest.xml" > "$man"
    grep -q 'isStatic="true"' "$man"
  else
    cp "$src/AndroidManifest.xml" "$man"
  fi
  "$BT/aapt2" compile -o "$OUT/$2-res.zip" --dir "$src/res"
  "$BT/aapt2" link -o "$OUT/$2-unsigned.apk" --manifest "$man" --min-sdk-version 35 --target-sdk-version 35 \
    --package-id 0x7e --allow-reserved-package-id -I "$LINK/framework-res.apk" -I "$LINK/SystemUI.apk" "$OUT/$2-res.zip"
  rm "$OUT/$2-res.zip"
  "$BT/zipalign" -f 4 "$OUT/$2-unsigned.apk" "$OUT/$2-aligned.apk"
  JAVA_HOME="$JAVA" "$BT/apksigner" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" --out "$OUT/$2.apk" "$OUT/$2-aligned.apk"
  rm "$OUT/$2-unsigned.apk" "$OUT/$2-aligned.apk"
}
build "$ROOT/tools/statusbar-edge-overlay" RefreshPrinceStatusBarEdge 101
build "$ROOT/tools/white-shade-overlay" RefreshPrinceWhiteShade 102
build "$ROOT/tools/light-qs-overlay" RefreshPrinceLightQs 103
build "$ROOT/tools/light-shade-colors-overlay" RefreshPrinceLightShade 104
build "$ROOT/tools/refresh-prince-defaults-overlay" RefreshPrinceDefaults
rm -rf "$OUT/src"
cd "$OUT" && shasum -a 256 *.apk
