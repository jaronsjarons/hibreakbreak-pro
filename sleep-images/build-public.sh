#!/bin/bash
# Public release of the sleep-image app: the dream and a picker for the user's own pictures.
# No bundled pictures, no ProbeReceiver or GrayActivity test parts. build.sh (owner build) is unchanged.
set -euo pipefail
TASK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TASK_JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
TASK_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TASK_BUILD="$TASK_SDK/build-tools/35.0.0"
TASK_OUT="${HIBREAK_PROBE_OUT:-$TASK_ROOT/artifacts/sleep-public}"
TASK_KEYS="$TASK_ROOT/artifacts/prototype/aosp-test-platform"
SRC="$TASK_ROOT/tools/display-probe"
rm -rf "$TASK_OUT/classes" "$TASK_OUT/dex" "$TASK_OUT/src"
mkdir -p "$TASK_OUT/classes" "$TASK_OUT/dex" "$TASK_OUT/src"
for f in BirdDreamService SleepImageDeck SleepImages SleepImagesActivity; do cp "$SRC/$f.java" "$TASK_OUT/src/"; done
sed 's/PUBLIC=false/PUBLIC=true/' "$SRC/Variant.java" > "$TASK_OUT/src/Variant.java"
grep -q 'PUBLIC=true' "$TASK_OUT/src/Variant.java"
"$TASK_JAVA/bin/javac" -source 8 -target 8 -Xlint:-options -cp "$TASK_SDK/platforms/android-35/android.jar" -d "$TASK_OUT/classes" "$TASK_OUT/src/"*.java
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/d8" --min-api 28 --lib "$TASK_SDK/platforms/android-35/android.jar" --output "$TASK_OUT/dex" "$TASK_OUT/classes/local/codex/hibreakprobe/"*.class
cp "$SRC/AndroidManifest-public.xml" "$TASK_OUT/AndroidManifest.xml"
"$TASK_BUILD/aapt" package -f -M "$TASK_OUT/AndroidManifest.xml" -I "$TASK_SDK/platforms/android-35/android.jar" -F "$TASK_OUT/unsigned.apk"
python3 -I - "$TASK_OUT" <<'PY'
import zipfile,sys,pathlib
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(p/'unsigned.apk','a') as z:z.write(p/'dex/classes.dex','classes.dex')
PY
"$TASK_BUILD/zipalign" -f 4 "$TASK_OUT/unsigned.apk" "$TASK_OUT/aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" sign --key "$TASK_KEYS/platform.pk8" --cert "$TASK_KEYS/platform.x509.pem" --out "$TASK_OUT/sleep-images.apk" "$TASK_OUT/aligned.apk"
rm "$TASK_OUT/unsigned.apk" "$TASK_OUT/aligned.apk"
JAVA_HOME="$TASK_JAVA" "$TASK_BUILD/apksigner" verify "$TASK_OUT/sleep-images.apk"
shasum -a 256 "$TASK_OUT/sleep-images.apk"
