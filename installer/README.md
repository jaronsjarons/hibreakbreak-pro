# Installer

Installs the HiBreakBreak Pro apps and display overlays and applies the e-ink friendly settings, asking before each step. Every old value is saved, so `undo` can put it back.

```
python3 hibreak_pack.py install --dry-run   # only reads the phone, prints what would change
python3 hibreak_pack.py install             # asks before each step
python3 hibreak_pack.py undo                # reverses the last install
```

It needs:
- `adb` from Android's platform tools;
- USB debugging allowed on the phone;
- the release folder layout: `apps/*.apk` and `overlays/*.apk` next to the script.

**What it never does:** touch system partitions, root, or anything outside normal app installs and settings.

**Undo limits:**
- Apps that were already installed before are left installed (they're only uninstalled if this script added them).
- Undo has been checked by reading the code, but not yet on a phone. Test it on the second phone or with the first volunteer.
