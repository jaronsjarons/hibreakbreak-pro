#!/bin/bash
# Build the white shade RRO. Needs the phone's framework-res.apk and SystemUIGoogle.apk
# (pulled read-only) as $1 and $2; output: artifacts/white-shade-overlay/whiteshade.apk (platform key).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); D="$ROOT/tools/white-shade-overlay"
BT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/35.0.0; KEY="$ROOT/artifacts/prototype/aosp-test-platform"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
OUT="$ROOT/artifacts/white-shade-overlay"; W=$(mktemp -d); mkdir -p "$OUT"
"$BT/aapt2" compile -o "$W/res.zip" --dir "$D/res"
"$BT/aapt2" link -o "$W/unsigned.apk" --manifest "$D/AndroidManifest.xml" --min-sdk-version 35 --target-sdk-version 35 --package-id 0x7e --allow-reserved-package-id -I "$1" -I "$2" "$W/res.zip"
"$BT/zipalign" -f -p 4 "$W/unsigned.apk" "$W/aligned.apk"
"$BT/apksigner" sign --key "$KEY/platform.pk8" --cert "$KEY/platform.x509.pem" --out "$OUT/whiteshade.apk" "$W/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/whiteshade.apk" | grep SHA-256
