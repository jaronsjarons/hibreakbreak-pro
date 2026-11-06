#!/usr/bin/env python3
"""Add HiBreakBreak Pro to vbbot's HiBreak Evolution X system image.

Usage:
  python3 patch_system_img.py <evolution...img.xz | .img> <payload folder> <output folder>

Offline only: it never talks to a phone and never flashes anything. The input
image is never changed; a patched copy is written to the output folder.
Needs Python 3.9+, e2fsprogs (debugfs, e2fsck) and, for .xz input, xz.
"""
import hashlib
import json
import lzma
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

CHUNK = 1 << 20


def die(message):
    sys.exit('\nSTOPPED: ' + message)


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(CHUNK), b''):
            h.update(block)
    return h.hexdigest()


def find_tool(name):
    for candidate in (shutil.which(name), f'/opt/homebrew/opt/e2fsprogs/sbin/{name}',
                      f'/usr/local/opt/e2fsprogs/sbin/{name}', f'/sbin/{name}', f'/usr/sbin/{name}'):
        if candidate and Path(candidate).is_file():
            return candidate
    die(f'{name} not found. Install e2fsprogs (Mac: brew install e2fsprogs, Linux: your package manager).')


class Image:
    def __init__(self, path):
        self.path = path
        self.debugfs = find_tool('debugfs')
        self.e2fsck = find_tool('e2fsck')

    def dbg(self, command, write=False):
        result = subprocess.run([self.debugfs] + (['-w'] if write else []) + ['-R', command, str(self.path)],
                                capture_output=True)
        if result.returncode or re.search(rb'not found|Usage:|error|could not|already exists', result.stderr, re.I):
            raise RuntimeError(f'debugfs "{command}": {result.stderr.decode(errors="replace").strip()}')
        return result.stdout

    def exists(self, path):
        r = subprocess.run([self.debugfs, '-R', f'stat {path}', str(self.path)], capture_output=True)
        return b'File not found' not in r.stderr

    def label(self, path):
        out = self.dbg(f'ea_get {path} security.selinux').decode(errors='replace')
        m = re.search(r'"(u:object_r:[a-z0-9_]+:s0)', out)
        if not m:
            raise RuntimeError(f'no SELinux label on {path}')
        return m.group(1)

    def free_bytes(self):
        out = subprocess.run([str(Path(self.debugfs).with_name('dumpe2fs')), '-h', str(self.path)],
                             capture_output=True, text=True).stdout
        free = int(re.search(r'Free blocks:\s+(\d+)', out).group(1))
        size = int(re.search(r'Block size:\s+(\d+)', out).group(1))
        return free * size

    def make_parents(self, dest, tmp):
        missing = []
        parent = Path(dest).parent
        while not self.exists(str(parent)):
            missing.append(parent)
            parent = parent.parent
        label_file = Path(tmp) / 'label'
        for folder in reversed(missing):
            self.dbg(f'mkdir {folder}', True)
            for field, value in (('mode', '040755'), ('uid', '0'), ('gid', '0')):
                self.dbg(f'set_inode_field {folder} {field} {value}', True)
            label_file.write_bytes(self.label(str(folder.parent)).encode() + b'\0')
            self.dbg(f'ea_set -f {label_file} {folder} security.selinux', True)

    def put(self, source, dest, mode, label, tmp):
        self.make_parents(dest, tmp)
        if self.exists(dest):
            self.dbg(f'rm {dest}', True)
        self.dbg(f'write {source} {dest}', True)
        for field, value in (('mode', '0100' + mode[-3:]), ('uid', '0'), ('gid', '0')):
            self.dbg(f'set_inode_field {dest} {field} {value}', True)
        label_file = Path(tmp) / 'label'
        label_file.write_bytes(label.encode() + b'\0')
        self.dbg(f'ea_set -f {label_file} {dest} security.selinux', True)
        if sha_bytes(self.dbg(f'cat {dest}')) != sha(source):
            raise RuntimeError(f'{dest} does not match after writing')

    def edit_in_place(self, dest, old, new):
        """Same-length byte edit inside an existing file, keeping its inode and label."""
        assert len(old) == len(new)
        blocks = [int(n) for n in self.dbg(f'blocks {dest}').split()]
        with open(self.path, 'r+b') as f:
            for offset, (a, b) in enumerate(zip(old, new)):
                if a != b:
                    physical = blocks[offset // 4096] * 4096 + offset % 4096
                    f.seek(physical)
                    if f.read(1) != bytes([a]):
                        raise RuntimeError(f'unexpected byte in {dest}')
                    f.seek(physical)
                    f.write(bytes([b]))
        if self.dbg(f'cat {dest}') != new:
            raise RuntimeError(f'{dest} edit did not apply')

    def check(self):
        r = subprocess.run([self.e2fsck, '-f', '-n', str(self.path)], capture_output=True, text=True)
        return r.returncode == 0, r.stdout + r.stderr


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    source, payload_dir, out_dir = (Path(a).expanduser().resolve() for a in sys.argv[1:])
    payload = json.loads((payload_dir / 'payload.json').read_text())
    base = payload['base']

    print('1/6 Checking the payload files')
    for entry in payload['files'] + payload['append']:
        if sha(payload_dir / entry['file']) != entry['sha256']:
            die(f'payload file {entry["file"]} is damaged or changed. Download the pack again.')
    if not payload['release']:
        print('    WARNING: this is a TEST payload, not for release:')
        for reason in payload['not_release_reasons']:
            print('     -', reason)

    print('2/6 Checking your Evolution X download (takes a minute)')
    digest = sha(source)
    if digest not in (base['xz_sha256'], base['img_sha256']):
        die(f'{source.name} is not the expected file.\n'
            f'Download exactly {base["url"]}\n(SHA-256 {base["xz_sha256"]}).')

    out_dir.mkdir(parents=True, exist_ok=True)
    image_path = out_dir / payload['output_name']
    if image_path.exists():
        die(f'{image_path} already exists. Move it away first; nothing was overwritten.')
    need = base['img_bytes']
    if shutil.disk_usage(out_dir).free < need + (1 << 30):
        die(f'not enough free disk space in {out_dir} (needs about {need >> 30} GB plus 1 GB).')

    print('3/6 Unpacking a copy (the original stays untouched)')
    if digest == base['xz_sha256']:
        with lzma.open(source) as src, open(image_path, 'wb') as dst:
            shutil.copyfileobj(src, dst, CHUNK)
    else:
        shutil.copyfile(source, image_path)
    if sha(image_path) != base['img_sha256']:
        image_path.unlink()
        die('the unpacked image is not the expected one.')

    image = Image(image_path)
    added = sum((payload_dir / e['file']).stat().st_size for e in payload['files'])
    if image.free_bytes() < added + (4 << 20):
        image_path.unlink()
        die('the image has too little free space for the pack.')

    print('4/6 Adding HiBreakBreak Pro')
    try:
        add_pack(image, payload, payload_dir)
    except Exception:
        image_path.unlink()
        print('    (half-patched image deleted)')
        raise
    finish(image, image_path, out_dir, payload, base)


def add_pack(image, payload, payload_dir):
    with tempfile.TemporaryDirectory() as tmp:
        for edit in payload['props']:
            text = image.dbg(f'cat {edit["dest"]}')
            new, count = re.subn(rb'(?m)^' + re.escape(edit['key'].encode()) + rb'=' + re.escape(edit['from'].encode()) + rb'$',
                                 (edit['key'] + '=' + edit['to']).encode(), text)
            if count != edit['count']:
                raise RuntimeError(f'{edit["key"]} found {count} times, expected {edit["count"]}')
            image.edit_in_place(edit['dest'], text, new)
            print(f'    {edit["dest"]}: {edit["key"]} {edit["from"]} -> {edit["to"]} ({edit["why"]})')
        for dest in payload['remove']:
            if image.exists(dest):
                image.dbg(f'rm {dest}', True)
                print(f'    removed {dest}')
        for entry in payload['append']:
            current = image.dbg(f'cat {entry["dest"]}')
            if sha_bytes(current) != entry['base_sha256']:
                raise RuntimeError(f'{entry["dest"]} is not the expected original')
            label = image.label(entry['dest'])
            combined = Path(tmp) / 'combined'
            combined.write_bytes(current + (payload_dir / entry['file']).read_bytes())
            image.put(combined, entry['dest'], '0644', label, tmp)
            print(f'    {entry["dest"]}: added {entry["file"]}')
        for entry in payload['files']:
            image.put(payload_dir / entry['file'], entry['dest'], entry['mode'], entry['label'], tmp)
            if entry['dest'].endswith('.apk'):
                parent = str(Path(entry['dest']).parent)
                stamp = image.dbg(f'stat {entry["dest"]}')
                m = re.search(rb'ctime: 0x([0-9a-f]+)', stamp)
                if m:
                    for field in ('mtime', 'ctime'):
                        image.dbg(f'set_inode_field {parent} {field} {int(m.group(1), 16)}', True)
            print(f'    {entry["dest"]}')


def finish(image, image_path, out_dir, payload, base):
    print('5/6 Checking the patched file system')
    ok, report = image.check()
    (out_dir / 'filesystem-check.txt').write_text(report)
    if not ok:
        bad = image_path.with_suffix('.BROKEN')
        image_path.rename(bad)
        die(f'file system check failed; see filesystem-check.txt. The broken image was renamed to {bad.name}.')

    print('6/6 Writing checksum')
    result = sha(image_path)
    (out_dir / (image_path.name + '.sha256')).write_text(f'{result}  {image_path.name}\n')
    (out_dir / 'patch-report.json').write_text(json.dumps({
        'payload': payload['name'], 'payload_version': payload['version'], 'release': payload['release'],
        'base_img_sha256': base['img_sha256'], 'output': image_path.name, 'output_sha256': result}, indent=2) + '\n')
    print(f'\nDone: {image_path}\nSHA-256 {result}\n')
    print('Next: follow the install guide, step "Put the new Android on", using this file instead of the plain Evolution X image.')
    if not payload['release']:
        print('\nREMINDER: TEST payload. Do not share this image.')


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        die(str(error))
