#!/bin/bash
# Prints suggestions and the space correction for comma-separated typed words, using the app's Dictionary on the Mac JVM.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin'
OUT="$ROOT/out/check"; rm -rf "$OUT"; mkdir -p "$OUT"
"$JAVA/javac" -nowarn -d "$OUT" "$ROOT/src/local/claude/keyboard/Dictionary.java" "$ROOT/tools/check/local/claude/keyboard/DictCheck.java" 2>&1 | grep -v '^Note' || true
"$JAVA/java" -cp "$OUT" local.claude.keyboard.DictCheck "$ROOT/assets" "$1"
