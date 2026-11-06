#!/usr/bin/env python3
"""Turns the frames written by scripts/record_demo.mjs (f0000_<ms>.png) into one GIF, shrinking it until it is under --max-mb.

  python scripts/frames_to_gif.py <frames-dir> docs/images/demo.gif [--max-mb 5]
"""
import argparse, glob, os, re
from PIL import Image


def build(files, durations, out, scale, colors):
    frames = []
    for f in files:
        im = Image.open(f).convert("RGB")
        if scale != 1:
            im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
        frames.append(im.quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True, disposal=1)
    return os.path.getsize(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames")
    ap.add_argument("out")
    ap.add_argument("--max-mb", type=float, default=5)
    a = ap.parse_args()
    files = sorted(glob.glob(os.path.join(a.frames, "f*_*.png")))
    durations = [int(re.search(r"_(\d+)\.png$", f).group(1)) for f in files]
    limit = a.max_mb * 1024 * 1024 * 0.9   # keep a margin under the limit
    for scale, colors in [(1.0, 128), (0.85, 96), (0.75, 64), (0.65, 64), (0.55, 48)]:
        size = build(files, durations, a.out, scale, colors)
        print(f"{len(files)} frames, scale {scale}, {colors} colors -> {size / 1024 / 1024:.2f} MB")
        if size <= limit:
            return
    raise SystemExit("could not get under the size limit; record fewer frames")


if __name__ == "__main__":
    main()
