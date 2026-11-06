#!/bin/bash
# Speed regression check: types the same ~130 keys into the Fast Keys test box with adb taps and prints the keyboard's own
# per-key timing (logcat FastKeys, one line per 100 keys). TEXT="..." types other text instead. Only run while nobody is using the phone; it drives the UI.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# Phone to use: ANDROID_SERIAL, else the serial in device.local (one line, kept out of the public repo), else adb's only device.
if [ -z "${ANDROID_SERIAL:-}" ] && [ -f "$ROOT/device.local" ]; then export ANDROID_SERIAL=$(tr -d ' \n' < "$ROOT/device.local"); fi
focus=$(adb shell dumpsys window | grep mCurrentFocus)
adb shell 'input keyevent KEYCODE_WAKEUP; am start -n local.claude.keyboard/.SetupActivity >/dev/null'
sleep 1.5
focus=$(adb shell dumpsys window | grep mCurrentFocus)
case "$focus" in *SetupActivity*) ;; *) echo "not on the test screen ($focus), stopping"; exit 1;; esac
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
b=$(adb shell cat /sdcard/ui.xml | grep -o 'class="android.widget.EditText"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ')
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 1
adb shell input keyevent KEYCODE_MOVE_END
adb shell input swipe 762 1497 762 1497 4000   # hold delete: clear the box
adb logcat -c
python3 - <<'PY'
import subprocess
U = 82.4; TOP = 1648 - 4 * 101
rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]; off = [0, 0.5, 1.5]
def pos(ch):
    for r, s in enumerate(rows):
        if ch in s: return ((off[r] + s.index(ch) + 0.5) * U, TOP + r * 101 + 50)
    return {' ': (5 * U, TOP + 353), '.': (8 * U, TOP + 353)}[ch]
import os
text = os.environ.get("TEXT") or ("the quick brown fox jumps over the lazy dog. i think we can go in the morning if you want to come "
                                  "teh dont thier justwant ")
cmds = []
for ch in text:
    x, y = pos(ch); cmds.append(f"input tap {int(x)} {int(y)}")
subprocess.run(["adb", "shell", ";".join(cmds)], check=True)
PY
sleep 1
adb logcat -d -s FastKeys:I | grep "100 keys" || echo "no timing line"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*" resource-id="" class="android.widget.EditText"' | cut -d'"' -f2
adb logcat -d -b crash | grep -c keyboard || true
adb shell input keyevent KEYCODE_HOME
