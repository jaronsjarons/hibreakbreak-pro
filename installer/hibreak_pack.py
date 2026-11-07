#!/usr/bin/env python3
"""HiBreakBreak Pro installer, with undo.

  python3 hibreak_pack.py install        asks before each step; remembers every old value
  python3 hibreak_pack.py install --yes  same, accepting the suggested answer each time
  python3 hibreak_pack.py install --dry-run   only reads the phone and prints what would change
  python3 hibreak_pack.py undo           puts back everything the last install changed

Needs: adb (Android platform tools) and USB debugging allowed on the phone.
Expects the phone to run HiBreak Evolution X patched with this pack (see the install guide).
Never touches system partitions; only installs apps/overlays and changes normal settings.
Standard library only; works on macOS, Linux and Windows.
"""
import datetime
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APPS = HERE / 'apps'           # <name>.apk files
OVERLAYS = HERE / 'overlays'   # <name>.apk files
STATE = HERE / 'undo'

APP_LIST = [  # (file, package, what it is)
    ('launcher.apk', 'local.claude.hibreaklauncher', 'HiBreak Launcher (home screen made for e-ink)'),
    ('keyboard.apk', 'local.claude.keyboard', 'Fast Keys keyboard'),
    ('pie.apk', 'local.claude.pie', 'Pie Pro (Back/Home/Recents from the right edge)'),
    ('camera.apk', 'local.claude.hibreakcamera', 'Fast Cam (camera)'),
    ('crop.apk', 'local.claude.crop', 'Crop (crop and rotate pictures)'),
    ('speak.apk', 'local.claude.speak', 'Read aloud (select text > Read aloud)'),
]
OVERLAY_LIST = [  # (file, package, what it does)
    ('statusbaredge.apk', 'com.hibreak.overlay.statusbaredge', 'Status bar: black icons, closer to the edges, bigger'),
    ('whiteshade.apk', 'com.hibreak.overlay.whiteshade', 'White notification shade and quick settings'),
    ('lightqs.apk', 'com.hibreak.overlay.lightqs', 'Light quick-settings tiles'),
    ('lightshade.apk', 'com.hibreak.overlay.lightshade', 'Light notification colours'),
]
# Only with Pie Pro: hides Android's nav bar and turns off the swipe-up Home gesture.
PIE_NAV_OVERLAY = ('keyboardbottom.apk', 'com.hibreak.overlay.keyboardbottom')
KEYBOARD_IME = 'local.claude.keyboard/.FastIME'
DRY = '--dry-run' in sys.argv
CHANGING = ('cmd uimode night no', 'settings put', 'settings delete', 'ime enable', 'ime set', 'cmd role add', 'cmd overlay enable',
            'cmd overlay set-priority',
            'cmd overlay disable', 'cmd deviceidle whitelist +', 'cmd deviceidle whitelist -', 'appops set')
PIE_SERVICE = 'local.claude.pie/local.claude.pie.PieService'
CONTROLLER_SERVICE = 'com.lmqr.ha9_comp_service/com.lmqr.ha9_comp_service.A9AccessibilityService'


class Phone:
    def __init__(self):
        self.adb = shutil.which('adb') or str(HERE / 'platform-tools' / ('adb.exe' if sys.platform == 'win32' else 'adb'))
        devices = [l.split()[0] for l in self.run_host('devices').splitlines()[1:] if l.strip().endswith('device')]
        if len(devices) != 1:
            sys.exit('Connect exactly one phone with USB debugging allowed (found %d).' % len(devices))
        self.serial = devices[0]

    def run_host(self, *args):
        try:
            return subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=120).stdout
        except FileNotFoundError:
            sys.exit('adb not found. Install Android platform tools (see the install guide).')

    def sh(self, command):
        if DRY and command.startswith(CHANGING):
            print(f'   (dry run) would run: {command}')
            return ''
        return self.run_host('-s', self.serial, 'shell', command).strip()

    def install(self, apk):
        if DRY:
            print(f'   (dry run) would install {apk.name}')
            return True, ''
        out = self.run_host('-s', self.serial, 'install', '-r', str(apk))
        return 'Success' in out, out.strip().splitlines()[-1] if out.strip() else ''

    def setting(self, ns, key):
        value = self.sh(f'settings get {ns} {key}')
        return None if value == 'null' else value

    def has_package(self, pkg):
        return f'package:{pkg}' in self.sh(f'pm list packages {pkg}').split()


def ask(question, default, assume_yes):
    hint = 'Y/n' if default else 'y/N'
    if assume_yes:
        print(f'{question} [{hint}] {"y" if default else "n"}')
        return default
    answer = input(f'{question} [{hint}] ').strip().lower()
    return default if not answer else answer.startswith('y')


def install(assume_yes):
    phone = Phone()
    model = phone.sh('getprop ro.product.model')
    print(f'Phone: {model}, Android {phone.sh("getprop ro.build.version.release")}')
    if 'hibreak' not in model.lower() and not ask('This does not look like a HiBreak. Continue anyway?', False, assume_yes):
        return
    if not phone.has_package('local.codex.hibreakprobe') or 'hibreak' not in phone.sh(
            'dumpsys package com.lmqr.ha9_comp_service | grep versionName'):
        print('Note: the e-ink core (patched system image) was not found. The apps will work,'
              ' but the e-ink controller features need the patched image from the install guide.')
        if not ask('Continue with the apps and settings only?', True, assume_yes):
            return
    if DRY:
        print('DRY RUN: nothing on the phone will change.')
    else:
        STATE.mkdir(exist_ok=True)
    undo = {'serial': phone.serial, 'made': datetime.datetime.now().isoformat(timespec='seconds'),
            'new_packages': [], 'settings': [], 'overlays': [], 'deviceidle': [], 'ime': None, 'home': None,
            'accessibility': None, 'appops': []}
    state_file = STATE / f'undo-{datetime.datetime.now():%Y%m%d-%H%M%S}.json'

    def save():
        if not DRY:
            state_file.write_text(json.dumps(undo, indent=2))

    def put(ns, key, value, why):
        undo['settings'].append({'ns': ns, 'key': key, 'old': phone.setting(ns, key)})
        save()
        phone.sh(f'settings put {ns} {key} {value}')
        print(f'   {why}')

    save()
    print('\nApps')
    for file, pkg, what in APP_LIST:
        apk = APPS / file
        if not apk.exists():
            print(f' - {what}: {file} missing from apps/, skipped')
            continue
        if ask(f' Install {what}?', True, assume_yes):
            existed = phone.has_package(pkg)
            ok, message = phone.install(apk)
            print('   installed' if ok else f'   FAILED: {message}')
            if ok and not existed:
                undo['new_packages'].append(pkg)
                save()

    print('\nDisplay overlays (look of the system screens)')
    for file, pkg, what in OVERLAY_LIST:
        apk = OVERLAYS / file
        if '/data/app/' not in (phone.sh(f'pm path {pkg}') or '/data/app/'):
            print(f' - {what}: built into the system already')   # HiBreakBreak Pro images include the look overlays
            continue
        if apk.exists() and ask(f' {what}?', True, assume_yes):
            existed = phone.has_package(pkg)
            before = phone.sh(f'cmd overlay list | grep " {pkg}$"')
            ok, message = phone.install(apk)
            if ok:
                undo['overlays'].append({'pkg': pkg, 'new': not existed, 'was_enabled': before.startswith('[x]')})
                save()
                phone.sh(f'cmd overlay enable {pkg}')
                print('   on')
            else:
                print(f'   FAILED: {message}')

    print('\nE-ink controller')
    # After a fresh install Android leaves the controller 'stopped', so it can't switch itself on (Nov 6 2026).
    current = phone.setting('secure', 'enabled_accessibility_services') or ''
    if phone.has_package('com.lmqr.ha9_comp_service') and CONTROLLER_SERVICE not in current and \
            ask(' Switch on the e-ink controller (side buttons, refresh modes)?', True, assume_yes):
        if undo['accessibility'] is None:
            undo['accessibility'] = current
            save()
        services = ':'.join(s for s in (current, CONTROLLER_SERVICE) if s)
        phone.sh(f'settings put secure enabled_accessibility_services "{services}"')
        phone.sh('settings put secure accessibility_enabled 1')
        print('   on')

    print('\nDefaults')
    if phone.has_package('local.claude.hibreaklauncher') and ask(' Make HiBreak Launcher the home screen?', True, assume_yes):
        undo['home'] = phone.sh('cmd role get-role-holders android.app.role.HOME')
        save()
        phone.sh('cmd role add-role-holder android.app.role.HOME local.claude.hibreaklauncher 0')
        print('   done')
    if phone.has_package('local.claude.keyboard') and ask(' Make Fast Keys the keyboard?', True, assume_yes):
        undo['ime'] = {'default': phone.setting('secure', 'default_input_method'),
                       'enabled': phone.setting('secure', 'enabled_input_methods')}
        save()
        phone.sh(f'ime enable {KEYBOARD_IME}')
        phone.sh(f'ime set {KEYBOARD_IME}')
        print('   done')
    if phone.has_package('local.claude.pie') and ask(' Turn on Pie Pro (Back/Home/Recents from the right edge)?', True, assume_yes):
        current = phone.setting('secure', 'enabled_accessibility_services') or ''
        if undo['accessibility'] is None:
            undo['accessibility'] = current
        undo['appops'].append({'pkg': 'local.claude.pie', 'op': 'ACCESS_RESTRICTED_SETTINGS',
                               'old': phone.sh('appops get local.claude.pie ACCESS_RESTRICTED_SETTINGS')})
        save()
        phone.sh('appops set local.claude.pie ACCESS_RESTRICTED_SETTINGS allow')
        if PIE_SERVICE not in current:
            services = ':'.join(s for s in (current, PIE_SERVICE) if s)
            phone.sh(f'settings put secure enabled_accessibility_services "{services}"')
            phone.sh('settings put secure accessibility_enabled 1')
        print('   on')
        if ask(' Turn off the right-edge Back swipe (Pie Pro uses that edge)?', True, assume_yes):
            put('secure', 'back_gesture_inset_scale_right', '0', 'right-edge Back swipe off')
        file, pkg = PIE_NAV_OVERLAY
        if (OVERLAYS / file).exists() and ask(' Make Pie Pro your only navigation (keyboard down to the bottom edge,'
                                              ' Android nav bar and swipe-up Home gesture off)?', False, assume_yes):
            existed = phone.has_package(pkg)
            ok, message = phone.install(OVERLAYS / file)
            if ok:
                undo['overlays'].append({'pkg': pkg, 'new': not existed, 'was_enabled': False})
                save()
                phone.sh(f'cmd overlay enable {pkg}')
                # Above the gesture-navigation overlay, so its keyboard-button setting wins (Nov 2026).
                phone.sh(f'cmd overlay set-priority {pkg} highest')
                print('   on (turn it off any time: python3 hibreak_pack.py undo, or Settings > Accessibility for Pie Pro)')
            else:
                print(f'   FAILED: {message}')

    print('\nE-ink friendly tweaks')
    # Evolution X starts in dark mode after a fresh install; the white shade overlays need light mode (Nov 6 2026).
    if phone.sh('cmd uimode night') != 'Night mode: no' and \
            ask(' Use light mode (white backgrounds; needed for the white notification shade)?', True, assume_yes):
        put('secure', 'ui_night_mode', '1', 'light mode')
        phone.sh('cmd uimode night no')
    if ask(' Turn off animations (they smear on e-ink)?', True, assume_yes):
        for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            put('global', key, '0', f'{key} = 0')
    if ask(' Lock the screen upright (no auto-rotate)?', True, assume_yes):
        put('system', 'accelerometer_rotation', '0', 'auto-rotate off')
        put('system', 'user_rotation', '0', 'upright')
        put('secure', 'show_rotation_suggestions', '0', 'no rotate button')
    if ask(' Turn off high-contrast text (the e-ink controller handles contrast)?', True, assume_yes):
        put('secure', 'high_text_contrast_enabled', '0', 'high-contrast text off')
    for pkg, what in (('local.claude.keyboard', 'Fast Keys'), ('local.claude.pie', 'Pie Pro')):
        if phone.has_package(pkg) and pkg not in phone.sh('cmd deviceidle whitelist') and \
                ask(f' Let {what} skip battery optimisation (stays responsive)?', True, assume_yes):
            phone.sh(f'cmd deviceidle whitelist +{pkg}')
            undo['deviceidle'].append(pkg)
            save()
    # Fresh installs run uncompiled until Android's overnight job, which feels laggy (Nov 6 2026). Compile the
    # apps that react to every touch now; it changes no setting, so there's nothing to undo.
    print('\nSpeeding up the e-ink apps (compiling them; about a minute)')
    for pkg in ('com.lmqr.ha9_comp_service', 'local.claude.keyboard', 'local.claude.pie', 'local.claude.hibreaklauncher'):
        if phone.has_package(pkg):
            if DRY:
                print(f' - would compile {pkg}')
            else:
                print(f' - {pkg}: {phone.sh(f"cmd package compile -m speed -f {pkg}") or "done"}')
    save()
    if DRY:
        print('\nDry run finished: nothing on the phone was changed.')
    else:
        print(f'\nDone. To undo all of this later: python3 {Path(__file__).name} undo\n(undo file: {state_file})')


def undo_last(_assume_yes):
    files = sorted(STATE.glob('undo-*.json'))
    if not files:
        sys.exit('Nothing to undo (no undo file found).')
    state_file = files[-1]
    undo = json.loads(state_file.read_text())
    phone = Phone()
    if phone.serial != undo['serial']:
        sys.exit(f'This undo file is for another phone ({undo["serial"]}).')
    print(f'Undoing the install from {undo["made"]}')
    for pkg in undo['deviceidle']:
        phone.sh(f'cmd deviceidle whitelist -{pkg}')
    for item in reversed(undo['settings']):
        if item['old'] is None:
            phone.sh(f'settings delete {item["ns"]} {item["key"]}')
        else:
            phone.sh(f'settings put {item["ns"]} {item["key"]} "{item["old"]}"')
    if undo['accessibility'] is not None:
        if undo['accessibility']:
            phone.sh(f'settings put secure enabled_accessibility_services "{undo["accessibility"]}"')
        else:
            phone.sh('settings delete secure enabled_accessibility_services')
    for item in undo['appops']:
        mode = item['old'].split(':')[-1].split(';')[0].strip() if ':' in item['old'] else 'default'
        phone.sh(f'appops set {item["pkg"]} {item["op"]} {mode}')
    if undo['ime']:
        if undo['ime']['enabled']:
            phone.sh(f'settings put secure enabled_input_methods "{undo["ime"]["enabled"]}"')
        if undo['ime']['default']:
            phone.sh(f'ime set {undo["ime"]["default"]}')
    if undo['home']:
        holder = undo['home'].split(';')[0].strip()
        if holder:
            phone.sh(f'cmd role add-role-holder android.app.role.HOME {holder} 0')
    for item in reversed(undo['overlays']):
        if item['new']:
            phone.run_host('-s', phone.serial, 'uninstall', item['pkg'])
        elif not item['was_enabled']:
            phone.sh(f'cmd overlay disable {item["pkg"]}')
    for pkg in reversed(undo['new_packages']):
        phone.run_host('-s', phone.serial, 'uninstall', pkg)
    state_file.rename(state_file.with_suffix('.undone.json'))
    print('Undone. Apps that were already on the phone before the install were left installed.')


if __name__ == '__main__':
    commands = {'install': install, 'undo': undo_last}
    if len(sys.argv) < 2 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    commands[sys.argv[1]]('--yes' in sys.argv or DRY)
