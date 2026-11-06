#!/usr/bin/env python3
"""Built-in home pictures for the public launcher: four black and white geometric patterns, solid at the
bottom and fading out going up (owner, Nov 6 2026). Writes res/drawable-nodpi/home_art*.png:
home_art (triangle mosaic, the default), home_art_dots, home_art_stripes, home_art_lattice."""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw

W, H = 824, 480                       # screen width
OUT = Path(__file__).parent / 'res/drawable-nodpi'


def canvas():
    im = Image.new('L', (W, H), 255)
    return im, ImageDraw.Draw(im)


def triangles():
    rnd, side = random.Random(3), 46  # fixed seed: the same picture every build
    im, d = canvas()
    h = side * math.sqrt(3) / 2
    for row in range(int(H / h) + 2):
        y0, t = H - row * h, row * h / H  # t: 0 at the bottom, 1 at the top
        for col in range(-1, int(W / (side / 2)) + 2):
            x, up = col * side / 2, (col + row) % 2 == 0
            tri = [(x, y0), (x + side / 2, y0 - h), (x + side, y0)] if up else \
                  [(x, y0 - h), (x + side, y0 - h), (x + side / 2, y0)]
            if rnd.random() > t ** 0.8 * 1.15:  # fewer triangles the higher up
                d.polygon(tri, fill=0 if col % 3 else 255, outline=0)
    return im


def dots():
    im, d = canvas()
    s = 24
    for row, y in enumerate(range(H, -s, -s)):
        r = (1 - (H - y) / H) ** 1.6 * s * 0.62  # dots shrink going up
        off = s / 2 if row % 2 else 0
        for x in range(-s, W + s, s):
            if r > 0.6:
                d.ellipse((x + off - r, y - r, x + off + r, y + r), fill=0)
    return im


def stripes():
    im, d = canvas()
    y = H
    while y > 0:
        t = (H - y) / H
        thick, gap = max(1, round(18 * (1 - t) ** 1.5)), round(8 + 40 * t ** 1.3)
        if t < 0.92:
            d.rectangle((0, y - thick, W, y), fill=0)
        y -= thick + gap
    return im


def lattice():
    im, d = canvas()
    s = 56
    for i in range(-H // s - 2, W // s + H // s + 3):
        for sign in (1, -1):
            for yy in range(H, 0, -4):
                width = round(7 * (1 - (H - yy) / H) ** 1.4)  # lines thin out going up
                if width:
                    x = i * s + sign * (H - yy)
                    d.line((x, yy, x + sign * 4, yy - 4), fill=0, width=width)
    return im


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    for name, draw in (('home_art', triangles), ('home_art_dots', dots),
                       ('home_art_stripes', stripes), ('home_art_lattice', lattice)):
        draw().save(OUT / f'{name}.png', optimize=True)
        print(OUT / f'{name}.png')
