#!/usr/bin/env python3
"""Generate specs/testing.md §7 fixtures from test/kodim23.png.

Fixtures are committed and read-only; this script exists so the derivation is
reviewable and reproducible, not so the build can regenerate them.
"""
import os
import random
import struct
from PIL import Image

SRC = "test/kodim23.png"
OUT = "fixtures"

# DESIGN-neutral reference patches. specs/testing.md §4 requires the golden input to
# carry skin tone, sky, deep shadow and a neutral gray patch; kodim23 supplies the
# saturated red on its own but none of these.
PATCHES = [
    ("neutral gray", (128, 128, 128)),
    ("skin tone", (224, 172, 105)),
    ("sky", (135, 206, 235)),
    ("deep shadow", (10, 10, 10)),
]


def photo_512(src):
    """512x384: a 16:9 crop of kodim23 over a row of reference patches."""
    w, h = src.size                     # 768x512
    crop_h = int(w * 288 / 512)         # keep the 512x288 aspect before scaling
    top = (h - crop_h) // 2
    photo = src.crop((0, top, w, top + crop_h)).resize((512, 288), Image.LANCZOS)

    out = Image.new("RGB", (512, 384))
    out.paste(photo, (0, 0))
    patch_w = 512 // len(PATCHES)
    for i, (_, rgb) in enumerate(PATCHES):
        x = i * patch_w
        width = patch_w if i < len(PATCHES) - 1 else 512 - x
        out.paste(Image.new("RGB", (width, 96), rgb), (x, 288))
    out.save(f"{OUT}/photo_512.png")


def photo_12mp(src):
    """4000x3000 JPEG carrying EXIF orientation 6 (rotate 90 CW)."""
    img = src.convert("RGB").resize((4000, 3000), Image.LANCZOS)
    exif = Image.Exif()
    exif[0x0112] = 6
    for quality in (95, 92, 88, 84, 80):
        img.save(f"{OUT}/photo_12mp.jpg", "JPEG", quality=quality, exif=exif)
        if os.path.getsize(f"{OUT}/photo_12mp.jpg") <= 3_500_000:
            break


def huge(src):
    """6000x4000, the downsample input for T04."""
    src.convert("RGB").resize((6000, 4000), Image.LANCZOS).save(
        f"{OUT}/huge_6000x4000.jpg", "JPEG", quality=85
    )


def transparent_256(src):
    """256x256 RGBA: one fully transparent quadrant plus a horizontal alpha ramp."""
    img = src.crop((256, 128, 512, 384)).resize((256, 256), Image.LANCZOS).convert("RGBA")
    px = img.load()
    for y in range(256):
        for x in range(256):
            r, g, b, _ = px[x, y]
            if x >= 128 and y < 128:
                alpha = 0
            else:
                alpha = int(255 * x / 255)
            px[x, y] = (r, g, b, alpha)
    img.save(f"{OUT}/transparent_256.png")


def corrupt():
    """32 random bytes; must fail with Unsupported, not crash."""
    random.seed(23)
    with open(f"{OUT}/corrupt.jpg", "wb") as f:
        f.write(bytes(random.randrange(256) for _ in range(32)))


def main():
    os.makedirs(OUT, exist_ok=True)
    src = Image.open(SRC)
    photo_512(src)
    photo_12mp(src)
    huge(src)
    transparent_256(src)
    corrupt()


if __name__ == "__main__":
    main()
