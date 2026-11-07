# How to install HiBreakBreak Pro on your HiBreak Pro, like you're 5

**HiBreakBreak Pro: a usability mod for HiBreak Evolution X on the Bigme HiBreak Pro**

> This is a usability mod made over four weeks by pointing my HiBreak Pro at a webcam and having Claude debug the shit out of it.

*Unofficial. Built on vbbot's HiBreak Evolution X (Android 15). Not made or endorsed by the Evolution X team, vbbot or Bigme.*

> ⚠️ **Only for the black and white Bigme HiBreak Pro (model B651).** Not for the HiBreak Pro Color, HiBreak S, HiBreak S Color or HiBreak Plus: they are different phones, and flashing this on them can stop them from starting.

> **Tested by the author on one phone** (firmware 3.1.6, November 2026). Nobody else has tried it yet, so please tell us how it went.

## First, the big picture

Your phone is like a house.

- **The foundation** (Bigme's drivers, the modem, the screen's secret recipes) stays exactly as it is. We never touch it.
- **The walls and furniture** (Android itself) get swapped for a newer, cleaner Android called **HiBreak Evolution X**. Someone else (vbbot) made it. We just tell you where to get it.
- **Then we add the nice stuff**: a faster e-ink screen controller, a launcher, a keyboard and more. That's **our pack**.

## Before you start: read this, really

1. **Everything on the phone gets erased.** Photos, messages, apps, all of it. Back them up first.
2. **Your phone gets less safe.** To do this we have to "unlock" it. An unlocked phone is like a house with the front door lock removed. If someone steals it, they can get in more easily. Banking apps may refuse to work.
3. **Things can go wrong.** If you're scared of a phone that won't start, stop here. That's a good choice.
4. **Nobody gets paid to help you.** This is a hobby project, shared for free and **as is**. Ask in the Reddit thread or the GitHub Discussions; the author and other users help when they can, but there's no promise of help, fixes or updates. Read [RISKS.md](RISKS.md) first.

## What you need

- Your HiBreak Pro (**black and white, model B651**), **charged above 60%**.
- A computer (Mac, Windows or Linux) and a **good USB-C cable**.
- **Android platform tools** (the programs `adb` and `fastboot`). Get them from Google: <https://developer.android.com/tools/releases/platform-tools>
- The **HiBreak Evolution X** image (May 7, 2025) from vbbot: <https://github.com/vbbot/HibreakProPatchingService/releases>. The patcher checks the download for you; for reference, the SHA-256 of `evolution_arm64_bgN-10.5-unofficial-20250507.img.xz` is `6ff4357e8055c513f90ae9f54fa256afab1c4b94285dd01cd599f952a25f3df6`.
- The **stock `vbmeta.img`** from Bigme's firmware for your phone. It's inside Bigme's full update file (`payload.bin`); unpack it with a payload dumper, as described in vbbot's guide. Tested only with firmware **3.1.6** (`B651_NO_EEA_M2_3.1.6`). ⚠️ If yours is different, ask first.
- From the HiBreakBreak Pro **Releases** page: `hibreakbreak-pro-0.1.6-patcher.zip` (the patcher and its files, for Step 5) and `hibreakbreak-pro-0.1.6-apps.zip` (apps and installer, for Step 7).
- About an hour, and some patience.

## Step 1: Back up

Copy your photos and files to the computer. Write down your Wi-Fi password. Make sure your Google account password works. After the next steps the phone will be empty.

## Step 2: Let the computer talk to the phone

1. Open **Settings → About phone** and tap **Build number** 7 times. It says "You are now a developer!"
2. Go to **Settings → System → Developer options**.
3. Turn on **OEM unlocking**.
4. Turn on **USB debugging**.
5. Plug the phone into the computer. A box pops up on the phone asking to "Allow USB debugging". Tap **Allow**.
6. On the computer, open a terminal and type:

   ```bash
   adb devices
   ```

   You should see a line with a code and the word `device`. If not, check the cable and the "Allow" box.

## Step 3: Unlock the phone (this erases everything)

1. Put the phone into "bootloader mode":

   ```bash
   adb reboot bootloader
   ```

   The e-ink screen may not change much (sometimes it just keeps showing the Bigme logo). Check from the computer instead:

   ```bash
   fastboot devices
   ```

   You should see a line with a code and the word `fastboot`.
2. Ask it to unlock:

   ```bash
   fastboot flashing unlock
   ```

3. The phone asks if you're sure. Follow the text on the screen (usually **Volume up** = yes). You only have a few seconds.
4. The phone erases itself.
5. Check it worked:

   ```bash
   fastboot getvar unlocked
   ```

   It must say `unlocked: yes`.

## Step 4: Turn off the "is this the original Android?" check

The phone normally refuses to start any Android that isn't Bigme's. This step tells it to stop checking. While the phone is still in bootloader mode:

```bash
fastboot flash vbmeta --disable-verity --disable-verification vbmeta.img
```

> 🛑 **Never** run `adb enable-verity` later. It can stop the phone from starting.

## Step 5: Put the new Android on

1. Unzip `hibreakbreak-pro-0.1.6-patcher.zip`, then add HiBreakBreak Pro to the Evolution X file with the patcher (it checks the download for you):

   ```bash
   python3 patch_system_img.py evolution_arm64_bgN-10.5-unofficial-20250507.img.xz payload/ out/
   ```

   You get a file named `hibreakbreak-pro-0.1.6-android15-<date>.img` in `out/`. That's Evolution X with HiBreakBreak Pro added; use it below.
2. Go to the special flashing mode:

   ```bash
   fastboot reboot fastboot
   ```

3. Write the new Android:

   ```bash
   fastboot flash system out/hibreakbreak-pro-0.1.6-android15-<date>.img
   ```

   This takes a few minutes. Don't unplug anything.
4. Clean up and start the phone:

   ```bash
   fastboot reboot bootloader
   ```

   ```bash
   fastboot -w
   ```

   ```bash
   fastboot reboot
   ```

5. The first start is **slow**, up to 5–10 minutes. The e-ink screen may look frozen. Wait.

> 🛑 Only ever flash `system` and `vbmeta`. **Never** flash anything called radio, modem, nvram, nvdata, protect or calibration. Those hold your phone's identity and can't be replaced.

## Step 6: Set up Android

Go through the welcome screens like a new phone: Wi-Fi, Google account. Then turn on **USB debugging** again (Step 2, points 1–5).

## Step 7: Add our pack

1. Unzip `hibreakbreak-pro-0.1.6-apps.zip` on the computer.
2. In the terminal, go into that folder and run:

   ```bash
   python3 hibreak_pack.py install
   ```

   On Windows, run `python hibreak_pack.py install` instead. Tip: add `--dry-run` first to see what it would change, without changing anything.
3. The script:
   - checks that this is a HiBreak with HiBreakBreak Pro (from Step 5);
   - **remembers your current settings** so you can undo everything later;
   - asks before each app: launcher, Fast Keys keyboard, Pie Pro, Fast Cam, Crop, Read aloud;
   - with Pie Pro, asks whether it should be your only navigation (keyboard to the bottom edge, Android's nav bar and swipe-up Home off). Say no to keep normal gestures or the 3-button bar;
   - asks before each default and tweak: home screen, keyboard, Pie Pro on, animations off, screen upright.

   - checks the e-ink controller is on (it's built into the Android from Step 5 and switches itself on; the installer only steps in if it didn't).
4. **Read aloud** speaks with your phone's text-to-speech voice. For a nice offline voice, install **SherpaTTS** from F-Droid and pick a Piper voice.
5. Turn **USB debugging off** again when you're done. That's safer.

## Step 8 (optional, for grown-ups): root and extra fonts

You don't need this. If you want the extra system fonts, install **Magisk** (<https://github.com/topjohnwu/Magisk>) yourself, then install our `hibreak_fonts` module zip in the Magisk app. Root makes the phone even less safe.

## Oops! Undo buttons

| I want to… | Do this |
|---|---|
| Remove our pack, keep Evolution X | `python3 hibreak_pack.py undo` (puts your old settings back) |
| Remove only one app | Long-press it → Uninstall |
| Go back to Bigme's original Android | Follow [RESTORE-STOCK.md](RESTORE-STOCK.md) (⚠️ draft, not tested on a phone yet) |
| The phone won't start | Turn it off (or let it try to restart). On the computer run `python3 preloader_fastboot.py`, then plug the phone in. It starts in fastboot mode, and from there you can put the old system back. |

## What's normal on an e-ink phone

- **A black-and-white flash** when you clean the screen. That's on purpose.
- **Faint ghost images** in fast modes. Press the e-ink button once to clean.
- An app looks **frozen**? Tap it once, or press the e-ink button. (Some apps draw in a way this screen doesn't like.)

## Thank you

- **damianmqr** and **vbbot** for the original HiBreak e-ink controller and the Evolution X build. Licences are in `LICENSES/`.
- The fonts (Atkinson Hyperlegible Next, Lexend, Literata) are under the SIL Open Font License.
