#!/bin/bash
# Installs Fast Keys on the HiBreak and keeps it at full speed: every install drops ART's speed compile, so redo it.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
# Phone to use: ANDROID_SERIAL, else the serial in device.local (one line, kept out of the public repo), else adb's only device.
if [ -z "${ANDROID_SERIAL:-}" ] && [ -f "$ROOT/device.local" ]; then export ANDROID_SERIAL=$(tr -d ' \n' < "$ROOT/device.local"); fi
P=local.claude.keyboard
adb install -r "$ROOT/out/keyboard.apk"
adb shell "cmd package compile -m speed -f $P; cmd deviceidle whitelist +$P >/dev/null; am set-standby-bucket $P active;
  cmd appops set $P RUN_ANY_IN_BACKGROUND allow; cmd appops set $P RUN_IN_BACKGROUND allow"
adb shell dumpsys package dexopt | grep -A3 "\[$P\]" | grep -o "status=[a-z-]*"
