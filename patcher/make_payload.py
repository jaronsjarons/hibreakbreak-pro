#!/usr/bin/env python3
"""Maintainer tool: collect built files into a payload folder for patch_system_img.py.

Usage:
  python3 make_payload.py <stock evolution .img> <payload folder> [name=path ...]

Inputs (override any with name=path): controller, daemon, daemon_rc, doze_overlay,
dream, cil_append, pc_append, helper_policy, bootanimation, bootanimation_rc. Option helper_mode=permissive puts the helper's
SELinux domain in observe-only mode for its first run on a phone (never a release). Release checks decide whether the payload is marked
release=true; anything failing them is still written, but marked TEST.
"""
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROM = Path(__file__).resolve().parents[2]
DEFAULTS = {
    'controller': ROM / 'artifacts/controller-v85-public/controller-v85-public.apk',
    'daemon': ROM / 'artifacts/native-refresh/hibreak-control-arm64',
    'daemon_rc': ROM / 'artifacts/native-refresh/a9_eink_daemon-refresh.rc',
    'doze_overlay': ROM / 'artifacts/bird-doze-overlay/bird-doze-boot.apk',
    'dream': ROM / 'artifacts/sleep-public/sleep-images.apk',
    'cil_append': Path(__file__).parent / 'policy/plat_sepolicy.append.cil',
    'pc_append': Path(__file__).parent / 'policy/plat_property_contexts.append',
    'helper_policy': Path(__file__).parent / 'policy/hibreak_eink.cil',
    'bootanimation': Path(__file__).parents[1] / 'bootanimation/bootanimation.zip',
    'bootanimation_rc': Path(__file__).parents[1] / 'bootanimation/hibreak_bootanimation.rc',
    'initial_state': Path(__file__).parent / 'initial-package-stopped-states-refresh-prince.xml',
    'ov_RefreshPrinceDefaults': ROM / 'artifacts/refresh-prince-overlays/RefreshPrinceDefaults.apk',
    'ov_RefreshPrinceStatusBarEdge': ROM / 'artifacts/refresh-prince-overlays/RefreshPrinceStatusBarEdge.apk',
    'ov_RefreshPrinceWhiteShade': ROM / 'artifacts/refresh-prince-overlays/RefreshPrinceWhiteShade.apk',
    'ov_RefreshPrinceLightQs': ROM / 'artifacts/refresh-prince-overlays/RefreshPrinceLightQs.apk',
    'ov_RefreshPrinceLightShade': ROM / 'artifacts/refresh-prince-overlays/RefreshPrinceLightShade.apk',
}
# Set to True only after the enforcing helper domain has run on a real HiBreak without denials.
HELPER_DOMAIN_VERIFIED_ON_HARDWARE = True   # Nov 6 2026: enforcing on the owner's HiBreak Pro, no denials; light writes and refresh modes worked
BASE = {
    'url': 'https://github.com/vbbot/HibreakProPatchingService/releases/download/20250507/'
           'evolution_arm64_bgN-10.5-unofficial-20250507.img.xz',
    'xz_sha256': '6ff4357e8055c513f90ae9f54fa256afab1c4b94285dd01cd599f952a25f3df6',
    'img_sha256': '3f75a4d060b77537d3690c0b1ca7f601896d6d931c4b1b7c6a5e758183658700',
    'img_bytes': 5054300160,
}
# Strings that must never ship. The personal ones (names, device serial, home paths, addresses) live in
# personal-patterns.txt next to this script: one regular expression per line, never published.
GENERIC = [rb'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY', rb'ks-pass pass:(?!hibreak)[A-Za-z0-9_-]{6,}']
PATTERNS_FILE = Path(__file__).parent / 'personal-patterns.txt'
FORBIDDEN = GENERIC + ([l.strip().encode() for l in PATTERNS_FILE.read_text().splitlines()
                        if l.strip() and not l.startswith('#')] if PATTERNS_FILE.exists() else [])
DEV_HOOKS = {rb'local.claude.FLASH_TEST': 'controller still has the adb-only FLASH_TEST test hook',
             rb'ProbeReceiver': 'dream app still contains the probe test receiver'}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def unpacked(path):
    """Yield (name, bytes) for a file, or for every entry inside it if it is an APK/zip.

    APK entries are compressed, so searching the raw file would miss text inside them.
    Dex strings are also checked as UTF-16 in case of resources.arsc.
    """
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for entry in z.namelist():
                data = z.read(entry)
                yield entry, data
                yield entry + ' (utf-16)', data.replace(b'\0', b'')
    else:
        yield Path(path).name, Path(path).read_bytes()


def apk_scan(path):
    """Return (all bytes of dex and manifest, list of asset names)."""
    with zipfile.ZipFile(path) as z:
        blobs = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex') or n == 'AndroidManifest.xml')
        assets = [n for n in z.namelist() if n.startswith('assets/') and not n.startswith('assets/dexopt/')]
    return blobs, assets


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    stock, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    inputs = dict(DEFAULTS)
    helper_mode = 'enforcing'
    for arg in sys.argv[3:]:
        key, _, value = arg.partition('=')
        if key == 'helper_mode':
            assert value in ('enforcing', 'permissive'), 'helper_mode is enforcing or permissive'
            helper_mode = value
            continue
        assert key in inputs, f'unknown input {key}'
        inputs[key] = Path(value).resolve()
    if out.exists():
        sys.exit(f'{out} exists; refusing to overwrite.')
    debugfs = shutil.which('debugfs') or '/opt/homebrew/opt/e2fsprogs/sbin/debugfs'

    def stock_file(path):
        return subprocess.run([debugfs, '-R', f'cat {path}', str(stock)], capture_output=True, check=True).stdout

    assert sha(stock) == BASE['img_sha256'], 'not the expected Evolution X image'
    if not PATTERNS_FILE.exists():
        print('NOTE: no personal-patterns.txt; only key material is checked. Add your own name, device serial etc.')
    problems = []
    for name in ('controller', 'dream', 'doze_overlay'):
        blobs, assets = apk_scan(inputs[name])
        for needle, why in DEV_HOOKS.items():
            if needle in blobs:
                problems.append(why)
        if assets:
            problems.append(f'{name} bundles pictures or other assets ({len(assets)} files, e.g. {assets[0]}); '
                            'sleep images must be the user\'s own, not shipped')
    for name, path in inputs.items():
        for member, data in unpacked(path):
            for needle in FORBIDDEN:
                if re.search(needle, data, re.I):
                    problems.append(f'{name} ({member}) contains personal data matching {needle.decode()}')
    if helper_mode == 'permissive':
        problems.append('helper SELinux domain is in observe-only (permissive) mode for a hardware trial')
    elif not HELPER_DOMAIN_VERIFIED_ON_HARDWARE:
        problems.append('enforcing helper SELinux domain not yet verified on a real phone')

    out.mkdir(parents=True)
    files = [
        ('controller', 'A9AccessibilityService.apk', '/system/priv-app/A9AccessibilityService/A9AccessibilityService.apk', '0644', 'u:object_r:system_file:s0'),
        ('daemon', 'a9_eink_server', '/system/bin/a9_eink_server', '0755', 'u:object_r:hibreak_eink_exec:s0'),
        ('daemon_rc', 'a9_eink_daemon.rc', '/system/etc/init/a9_eink_daemon.rc', '0644', 'u:object_r:system_file:s0'),
        ('doze_overlay', 'HiBreakBirdDoze.apk', '/system/product/overlay/HiBreakBirdDoze.apk', '0644', 'u:object_r:system_file:s0'),
        ('dream', 'HiBreakProbe.apk', '/system/app/HiBreakProbe/HiBreakProbe.apk', '0644', 'u:object_r:system_file:s0'),
        # Boot screen: the full-screen diamond replaces Evolution X's animation (bootanimation-dark.zip
        # is a symlink to it), and a small init file turns the boot screen on (off on this GSI).
        ('bootanimation', 'bootanimation.zip', '/system/product/media/bootanimation.zip', '0644', 'u:object_r:system_file:s0'),
        ('bootanimation_rc', 'hibreak_bootanimation.rc', '/system/etc/init/hibreak_bootanimation.rc', '0644', 'u:object_r:system_file:s0'),
        # Fresh installs leave system apps 'stopped' unless listed here; the controller then never switches itself on.
        ('initial_state', 'initial-package-stopped-states-refresh-prince.xml',
         '/system/product/etc/sysconfig/initial-package-stopped-states-refresh-prince.xml', '0644', 'u:object_r:system_file:s0'),
        # Look overlays built in and always on, plus light mode by default (tools/refresh-prince-overlays-build.sh).
        ('ov_RefreshPrinceDefaults', 'RefreshPrinceDefaults.apk', '/system/product/overlay/RefreshPrinceDefaults.apk', '0644', 'u:object_r:system_file:s0'),
        ('ov_RefreshPrinceStatusBarEdge', 'RefreshPrinceStatusBarEdge.apk', '/system/product/overlay/RefreshPrinceStatusBarEdge.apk', '0644', 'u:object_r:system_file:s0'),
        ('ov_RefreshPrinceWhiteShade', 'RefreshPrinceWhiteShade.apk', '/system/product/overlay/RefreshPrinceWhiteShade.apk', '0644', 'u:object_r:system_file:s0'),
        ('ov_RefreshPrinceLightQs', 'RefreshPrinceLightQs.apk', '/system/product/overlay/RefreshPrinceLightQs.apk', '0644', 'u:object_r:system_file:s0'),
        ('ov_RefreshPrinceLightShade', 'RefreshPrinceLightShade.apk', '/system/product/overlay/RefreshPrinceLightShade.apk', '0644', 'u:object_r:system_file:s0'),
    ]
    entries = []
    for key, name, dest, mode, label in files:
        shutil.copyfile(inputs[key], out / name)
        entries.append({'file': name, 'dest': dest, 'mode': mode, 'label': label, 'sha256': sha(out / name)})
    appends = []
    policy = Path(inputs['cil_append']).read_bytes() + Path(inputs['helper_policy']).read_bytes()
    if helper_mode == 'permissive':
        policy += b'\n; TRIAL ONLY: log, do not block, while the domain is checked on hardware.\n(typepermissive hibreak_eink)\n'
    (out / 'plat_sepolicy.append.cil').write_bytes(policy)
    shutil.copyfile(inputs['pc_append'], out / 'plat_property_contexts.append')
    for key, name, dest in (('cil_append', 'plat_sepolicy.append.cil', '/system/etc/selinux/plat_sepolicy.cil'),
                            ('pc_append', 'plat_property_contexts.append', '/system/etc/selinux/plat_property_contexts')):
        appends.append({'file': name, 'dest': dest, 'sha256': sha(out / name),
                        'base_sha256': hashlib.sha256(stock_file(dest)).hexdigest()})
    release = not problems
    payload = {
        'name': 'HiBreakBreak Pro', 'version': '0.1.0' + ('' if release else '-test'),
        'release': release, 'not_release_reasons': problems, 'base': BASE,
        'output_name': 'hibreakbreak-pro-0.1.0-android15-' + datetime.date.today().strftime('%Y%m%d')
                       + ('' if release else '-TEST-NOT-FOR-RELEASE') + '.img',
        'props': [{'dest': '/system/build.prop', 'key': 'ro.adb.secure', 'from': '0', 'to': '1', 'count': 3,
                   'why': 'USB debugging asks before a computer may connect'}],
        'remove': ['/system/priv-app/A9AccessibilityService/oat/arm64/A9AccessibilityService.odex',
                   '/system/priv-app/A9AccessibilityService/oat/arm64/A9AccessibilityService.vdex'],
        'append': appends, 'files': entries,
    }
    (out / 'payload.json').write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps({'release': release, 'not_release_reasons': problems}, indent=2))


if __name__ == '__main__':
    main()
