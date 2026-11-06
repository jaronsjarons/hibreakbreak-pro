#!/usr/bin/env python3
"""Line-art boot animation for the HiBreak Pro (824 x 1648, e-ink).

"Times table" string art: N points on a circle, point i joined to point i*m.
part0 plays once and draws the lines in; part1 loops while Android starts,
morphing m slowly (2 -> 3 -> 2). Pure 1-bit black on white, few frames, no text,
so it reads cleanly on e-ink and contains nothing but our own geometry.

Usage: python3 make_bootanimation.py <output folder> [diamond-still|diamond|tunnel|squares|fullscreen|circle] [fps]
Writes bootanimation.zip (stored, as Android requires) and preview.gif.
"""
import math
import sys
import zipfile
from pathlib import Path
from PIL import Image, ImageDraw

W, H = 824, 1648
FPS = int(sys.argv[3]) if len(sys.argv) > 3 else 15   # panel managed ~18 updates/s in Normal mode (Oct 25 camera test)
CX, CY, R = W // 2, 760, 360
N = 200
LINE = 2


def points(n, radius, rotation=-math.pi / 2):
    return [(CX + radius * math.cos(rotation + 2 * math.pi * k / n),
             CY + radius * math.sin(rotation + 2 * math.pi * k / n)) for k in range(n)]


RIM = points(N, R)


EDGE_N = 320


def edge_point(t):
    """Point at position t (0..EDGE_N, fractional ok) walking clockwise round the screen edge."""
    d = (t % EDGE_N) / EDGE_N * 2 * (W + H)
    if d < W:
        return d, 0
    d -= W
    if d < H:
        return W - 1, d
    d -= H
    if d < W:
        return W - 1 - d, H - 1
    return 0, H - 1 - (d - W)


def fullscreen_frame(multiplier, upto=EDGE_N):
    """Full-screen version: the same times-table chords, between points on the screen edges."""
    img = Image.new('1', (W, H), 1)
    d = ImageDraw.Draw(img)
    for k in range(upto):
        d.line((*edge_point(k), *edge_point(k * multiplier)), fill=0, width=1)
    return img


BAND = 30                # tunnel: width of each black or white band


def tunnel_frame(phase, reveal=None):
    """Tunnel version: concentric rectangles, alternating solid black and white bands,
    flowing outward. phase 0..1 moves the pattern by one black+white pair (seamless loop).
    reveal (0..1) shows only the inner part, growing from the centre, for the intro."""
    img = Image.new('1', (W, H), 1)
    d = ImageDraw.Draw(img)
    shift = phase * 2 * BAND
    limit = W // 2 if reveal is None else W // 2 * reveal   # how far out from the centre to draw
    i = -2
    while True:
        m = i * BAND - shift                       # margin from the screen edge for ring i
        if m >= W // 2:
            break
        if W // 2 - m <= limit and i % 2 == 0:
            m0 = max(m, 0)
            d.rectangle((m0, m0, W - 1 - m0, H - 1 - m0), fill=0)
            inner = m + BAND
            if inner < W // 2:
                d.rectangle((inner, inner, W - 1 - inner, H - 1 - inner), fill=1)
        i += 1
    return img


def diamond_frame(phase, reveal=None):
    """Tunnel turned 45 degrees: concentric squares standing on a point (|dx| + |dy| = r),
    alternating solid black and white bands, flowing outward; same phase/reveal as tunnel_frame."""
    img = Image.new('1', (W, H), 1)
    d = ImageDraw.Draw(img)
    cx, cy = W / 2, H / 2
    band = BAND * math.sqrt(2)                     # same visible band width as the tunnel
    far = cx + cy                                  # r that reaches the screen corners
    limit = far if reveal is None else far * reveal
    shift = phase * 2 * band
    rings = int(far / band) + 3
    for i in range(rings, -1, -1):                 # largest first, smaller ones paint over
        r = i * band + shift
        if r > limit + band or r <= 0:
            continue
        d.polygon([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], fill=0 if i % 2 == 0 else 1)
    if reveal is not None:                         # keep the area outside the revealed part white
        mask = Image.new('1', (W, H), 1)
        ImageDraw.Draw(mask).polygon([(cx, cy - limit), (cx + limit, cy), (cx, cy + limit), (cx - limit, cy)], fill=0)
        img = Image.composite(img, Image.new('1', (W, H), 1), mask.point(lambda v: 255 - v))
    return img


TILE, LEVELS = 412, 14   # 824 x 1648 = 2 x 4 squares of 412


def squares_frame(multiplier, upto=LEVELS):
    """Square version: 8 tiles of nested squares, each turned a little inside the last
    ("whirl"), neighbours spinning opposite ways. multiplier 2..3 sets the turn per level."""
    img = Image.new('1', (W, H), 1)
    d = ImageDraw.Draw(img)
    step = 0.08 + (multiplier - 2) * 0.10          # 0.08 .. 0.18 of each side per level
    for row in range(H // TILE):
        for col in range(W // TILE):
            x, y = col * TILE, row * TILE
            quad = [(x, y), (x + TILE - 1, y), (x + TILE - 1, y + TILE - 1), (x, y + TILE - 1)]
            clockwise = (row + col) % 2 == 0
            for _ in range(upto):
                d.line(quad + [quad[0]], fill=0, width=LINE)
                nxt = []
                for i in range(4):
                    a, b = quad[i], quad[(i + 1) % 4] if clockwise else quad[(i - 1) % 4]
                    nxt.append((a[0] + (b[0] - a[0]) * step, a[1] + (b[1] - a[1]) * step))
                quad = nxt
    return img


def frame(multiplier, upto=N):
    img = Image.new('1', (W, H), 1)
    d = ImageDraw.Draw(img)
    # Outer frame: a thin circle plus 48 tick lines pointing inward.
    d.ellipse((CX - R - 18, CY - R - 18, CX + R + 18, CY + R + 18), outline=0, width=LINE)
    for (x0, y0), (x1, y1) in zip(points(48, R + 18), points(48, R + 40)):
        d.line((x0, y0, x1, y1), fill=0, width=LINE)
    # The string art: chord from point k to point k*multiplier (fractional = interpolated on the rim).
    for k in range(upto):
        t = (k * multiplier) % N
        a = 2 * math.pi * t / N - math.pi / 2
        d.line((*RIM[k], CX + R * math.cos(a), CY + R * math.sin(a)), fill=0, width=1)
    # Base rule: three parallel lines under the figure.
    for i, half in enumerate((220, 160, 100)):
        y = CY + R + 120 + i * 22
        d.line((CX - half, y, CX + half, y), fill=0, width=LINE)
    return img


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
    out.mkdir(parents=True, exist_ok=True)
    style = sys.argv[2] if len(sys.argv) > 2 else 'diamond'
    if style == 'diamond-still':
        # On the HiBreak the panel takes only the first couple of updates during boot (Oct 26:
        # froze at the same frame at 15 and at 2 fps), so show one full frame and hold it.
        intro, loop = [], [diamond_frame(0)]
    elif style in ('tunnel', 'diamond'):
        draw = tunnel_frame if style == 'tunnel' else diamond_frame
        intro = [draw(0, reveal=(i + 1) / 8) for i in range(8)]               # grows from the centre
        loop = [draw(i / 8) for i in range(8)]                                 # flows outward, seamless
    else:
        draw, count = {'squares': (squares_frame, LEVELS), 'fullscreen': (fullscreen_frame, EDGE_N),
                       'circle': (frame, N)}[style]
        intro = [draw(2, upto=round(count * (i + 1) / 10)) for i in range(10)]  # lines draw in
        steps = [2 + i / 8 for i in range(9)]                                   # 2.0 .. 3.0
        loop = [draw(m) for m in steps + steps[-2:0:-1]]                        # ping-pong, seamless
    with zipfile.ZipFile(out / 'bootanimation.zip', 'w', zipfile.ZIP_STORED) as z:
        parts = [('part0', intro), ('part1', loop)] if intro else [('part0', loop)]
        desc = f'{W} {H} {FPS}\n' + ''.join(f'p {1 if (intro and n == "part0") else 0} 0 {n}\n' for n, _ in parts)
        z.writestr('desc.txt', desc)
        for part, frames in parts:
            for i, img in enumerate(frames):
                path = out / f'{part}_{i:03d}.png'
                img.save(path, optimize=True)
                z.write(path, f'{part}/{i:03d}.png')
                path.unlink()
    preview = [f.convert('L').resize((W // 2, H // 2)) for f in intro + loop * 12]   # like the phone: intro once, then the loop
    preview[0].save(out / 'preview.gif', save_all=True, append_images=preview[1:],
                    duration=1000 // FPS, loop=0)
    print(out / 'bootanimation.zip', (out / 'bootanimation.zip').stat().st_size, 'bytes,',
          len(intro), 'intro +', len(loop), 'loop frames at', FPS, 'fps')


if __name__ == '__main__':
    main()
