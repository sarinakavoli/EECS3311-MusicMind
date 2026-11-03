#!/usr/bin/env python3
"""Crop the UML PNGs in docs/diagrams/png/ into the readable per-page figures in docs/report/figures/.

Dependency: Pillow (pip install pillow). Usage: python3 docs/report/make-figures.py
The report (stage2-report.html) shows one crop per landscape page. Crops overlap by OVERLAP pixels.
"""
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("make-figures.py needs Pillow: pip install pillow")

ROOT = Path(__file__).resolve().parent
SRC = ROOT.parent / "diagrams" / "png"
OUT = ROOT / "figures"
OVERLAP = 120

# (source file, output prefix, columns, rows)
JOBS = [
    ("MusicMind_ClassArchitecture.png", "class", 2, 2),
    ("MusicMind_Domain.png", "domain", 1, 2),
    ("MusicMind_SD01_CreatePlaylist.png", "sd01", 2, 3),
    ("MusicMind_SD02_ModifyPlaylist.png", "sd02", 1, 2),
    ("MusicMind_SD03_TasteProfile.png", "sd03", 1, 2),
]


def crop(name, prefix, cols, rows):
    im = Image.open(SRC / name)
    w, h = im.size
    for r in range(rows):
        for c in range(cols):
            x0 = max(0, int(c * w / cols) - (OVERLAP if c else 0))
            x1 = min(w, int((c + 1) * w / cols) + (OVERLAP if c < cols - 1 else 0))
            y0 = max(0, int(r * h / rows) - (OVERLAP if r else 0))
            y1 = min(h, int((r + 1) * h / rows) + (OVERLAP if r < rows - 1 else 0))
            im.crop((x0, y0, x1, y1)).save(OUT / f"{prefix}_{r * cols + c + 1}.png", optimize=True)


OUT.mkdir(exist_ok=True)
for job in JOBS:
    crop(*job)
print(f"Wrote figures to {OUT}")
