# How to go back to Bigme's original Android, like you're 5

> **DRAFT. NOT TESTED YET.** Nobody has done this full restore on a phone yet, not even the author. The steps come from what the install changed and from Android's own fastboot rules. Until someone reports that it worked, read every step twice and stop at anything unexpected. ⚠️ TODO: test on a phone, then remove this warning.

## The big picture

Remember the house? Installing HiBreakBreak Pro changed only three things:

1. **The walls and furniture:** the `system` partition (Android itself) became Evolution X with HiBreakBreak Pro.
2. **The "is this the original Android?" check** was turned off in `vbmeta`.
3. **The front door lock** was removed (the bootloader was unlocked).

If you did the optional Step 8 (Magisk), a fourth thing changed: the `boot` partition.

Going back means putting Bigme's own `system` and `vbmeta` back (and `boot` if you used Magisk). The foundation (drivers, modem, the screen's secret recipes) was never touched, so it doesn't need restoring.

## Before you start

1. **Everything on the phone gets erased again.** Back up your photos, files and app data first.
2. **You need Bigme's firmware for the same version your phone had before you installed.** The new Android sits on top of Bigme's own drivers, and those drivers stay at the version they were. Putting a different version's Android on them can make the phone restart over and over.
   - Not sure which version you had? Plug in the phone with USB debugging on and run:

     ```bash
     adb shell getprop ro.vendor.build.fingerprint
     ```

     Compare it with the `post-build=` line inside the update file (unzip it and open `META-INF/com/android/metadata`). The number near the end should match. For firmware 3.1.6 it's `1772711616`. ⚠️ TODO: confirm this on a phone running HiBreakBreak Pro.
3. Charge the phone above 60%, and keep about an hour free.

## What you need

- The same computer setup as the install: **Android platform tools** (`adb` and `fastboot`) and a good USB-C cable.
- **Bigme's full update file** for your firmware version, the same kind you took `vbmeta.img` from for the install.
- A **payload dumper** to unpack it, for example `payload-dumper-go` (<https://github.com/ssut/payload-dumper-go>). vbbot's guide shows how.
- `preloader_fastboot.py` from the HiBreakBreak Pro Releases page, in case the phone won't start.

## Step 1: Unpack Bigme's images

Unpack the update file's `payload.bin` with the payload dumper. You get a folder with about 23 `.img` files. You need only:

- `system.img`
- `vbmeta.img`
- `boot.img` (only if you installed Magisk)

> 🛑 The folder also holds files called `preloader`, `lk`, `md1img` (modem), `scp`, `tee` and others. **Don't flash them.** They were never changed, and a bad write there can break the phone for good.

For firmware **3.1.6** (`B651_NO_EEA_M2_3.1.6`), the author's copies had these SHA-256 checksums. If yours match, you have the right files:

| File | Size (bytes) | SHA-256 |
|---|---|---|
| `system.img` | 2,411,188,224 | `59a335488180d1a125e7019442c2703ad8b7ede75b187ac53a6f74af2f09fc3c` |
| `vbmeta.img` | 8,192 | `ce09a24df808144f2628ea917a64d4a1572a857f77b4fc1005ff315d7d90f81d` |
| The whole update file | | `4207f4e517ecee2b3b9da7364084127784e9ac686e9a7e3c75ed63075ac30d6d` |

## Step 2: Put Bigme's Android back

1. With USB debugging on, go to the special flashing mode:

   ```bash
   adb reboot fastboot
   ```

   Phone won't start? Turn it off, run `python3 preloader_fastboot.py`, plug the phone in, then run `fastboot reboot fastboot`.

   This mode can take **over a minute** to show up. Check with:

   ```bash
   fastboot getvar is-userspace
   ```

   It must say `is-userspace: yes`.
2. Write Bigme's Android. It's smaller than Evolution X, and fastboot resizes the space for you:

   ```bash
   fastboot flash system system.img
   ```

   This takes a few minutes. Don't unplug anything. If it fails with a USB error, run it again with `fastboot -S 64M flash system system.img`.
3. Go to bootloader mode:

   ```bash
   fastboot reboot bootloader
   ```

   ```bash
   fastboot getvar is-userspace
   ```

   Now it must say `is-userspace: no`.
4. Turn the "is this the original Android?" check back on by flashing Bigme's `vbmeta` **without** any extra words:

   ```bash
   fastboot flash vbmeta vbmeta.img
   ```

5. **Only if you installed Magisk:**

   ```bash
   fastboot flash boot boot.img
   ```

6. Erase and start:

   ```bash
   fastboot -w
   ```

   ```bash
   fastboot reboot
   ```

7. The first start is slow. Wait up to 10 minutes. You should see Bigme's own welcome screens.

> 🛑 Fastboot sometimes says "Finished" even when a step failed. Read what each command prints. If you see `FAILED`, stop and ask before doing anything else.

## Step 3 (optional, risky): lock the phone again

Locking puts the front door lock back. Some banking apps want that. **You don't have to.** An unlocked phone running Bigme's Android works fine.

Only lock if **all** of these are true:

- the phone has started Bigme's Android normally at least once after Step 2;
- you put back **every** changed partition (`system`, `vbmeta` and, if you used Magisk, `boot`);
- you understand that locking erases the phone again.

> 🛑 If even one partition is still not Bigme's own, a locked phone may refuse to start, and you can't unlock it from Android anymore. That's the one way this can go really wrong.

Then turn on USB debugging and run:

```bash
adb reboot bootloader
```

```bash
fastboot flashing lock
```

Confirm on the phone (usually **Volume up**). It erases itself and starts.

## If something goes wrong

| What happens | What to do |
|---|---|
| The phone keeps restarting after Step 2 | The firmware version probably doesn't match your drivers. Use `preloader_fastboot.py` to get back into fastboot and flash HiBreakBreak Pro again (install guide, Step 5), or try the matching firmware. |
| `fastboot flash system` says the partition doesn't exist | You're in bootloader mode, not the special flashing mode. Run `fastboot reboot fastboot` and wait. |
| Stuck on the Bigme logo for over 15 minutes | Get into fastboot with `preloader_fastboot.py` and run `fastboot -w`, then `fastboot reboot`. |
| Anything else | Stop and ask in the Reddit thread or GitHub Discussions, with the exact text the commands printed. |
