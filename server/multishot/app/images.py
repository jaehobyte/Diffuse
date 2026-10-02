"""Input validation and normalisation (specs/multishot_api.md §3.1).

Each spooled input is checked by the real decoder, made EXIF-upright, converted to sRGB RGBA, shrunk
to ≤ 4096 px on its long side and written back to the spool as `.npy`, one image at a time.
"""

from __future__ import annotations

import io
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, UnidentifiedImageError

from app.config import MAX_EXTRACTION_SIDE, MAX_SOURCE_PIXELS, MAX_WORKING_SIDE
from app.errors import ApiError

# Our own header check runs first with the configured limit; this keeps Pillow's bomb guard on too.
Image.MAX_IMAGE_PIXELS = MAX_SOURCE_PIXELS

ACCEPTED_FORMATS = {"JPEG", "PNG", "MPO"}  # MPO: a camera JPEG with an extra picture; frame 0 is used
EXIF_ORIENTATION = 0x0112
TRANSPOSE = {
    2: Image.Transpose.FLIP_LEFT_RIGHT,
    3: Image.Transpose.ROTATE_180,
    4: Image.Transpose.FLIP_TOP_BOTTOM,
    5: Image.Transpose.TRANSPOSE,
    6: Image.Transpose.ROTATE_270,
    7: Image.Transpose.TRANSVERSE,
    8: Image.Transpose.ROTATE_90,
}
_SRGB = ImageCms.createProfile("sRGB")


def _orientation(img: Image.Image) -> int:
    try:
        return int(img.getexif().get(EXIF_ORIENTATION, 1))
    except Exception:  # noqa: BLE001 - unreadable EXIF is no rotation
        return 1


def _has_alpha(img: Image.Image) -> bool:
    return img.mode in ("RGBA", "LA", "PA", "RGBa", "La") or "transparency" in img.info


def _to_srgb(img: Image.Image) -> Image.Image:
    """RGBA in sRGB. An embedded profile is converted from; a profile LittleCMS refuses is ignored."""
    icc = img.info.get("icc_profile")
    if icc and img.mode in ("RGB", "RGBA", "CMYK", "L"):
        try:
            source = ImageCms.ImageCmsProfile(io.BytesIO(icc))
            out_mode = "RGBA" if img.mode == "RGBA" else "RGB"
            converted = ImageCms.profileToProfile(img, source, _SRGB, outputMode=out_mode)
            if converted is not None:
                img = converted
        except (ImageCms.PyCMSError, OSError, ValueError):
            pass
    if img.mode == "RGBA":
        return img
    if _has_alpha(img):
        return img.convert("RGBA")
    return img.convert("RGB").convert("RGBA")


def normalize(source: Path, index: int, target: Path, max_pixels: int) -> tuple[int, int]:
    """Validate input [index] and write its normalised RGBA array to [target]. Returns (w, h)."""
    if source.stat().st_size == 0:
        raise ApiError(422, "invalid_image", index)
    try:
        with Image.open(source) as img:
            if img.format not in ACCEPTED_FORMATS:
                raise ApiError(415, "unsupported_media_type", index)
            if img.format == "PNG" and getattr(img, "is_animated", False):
                raise ApiError(415, "unsupported_media_type", index)
            width, height = img.size
            if width <= 0 or height <= 0:
                raise ApiError(422, "invalid_image", index)
            if width * height > max_pixels:
                raise ApiError(413, "too_large", index)
            if img.format == "MPO":
                img.seek(0)
            img.load()
            orientation = _orientation(img)
            rgba = _to_srgb(img)
            if orientation in TRANSPOSE:
                rgba = rgba.transpose(TRANSPOSE[orientation])
            # A copy, also when nothing above made one: the opened image is closed below.
            array = np.array(fit_long_side(rgba, MAX_WORKING_SIDE), dtype=np.uint8)
    except (ApiError, MemoryError):
        raise
    except Image.DecompressionBombError:
        raise ApiError(413, "too_large", index) from None
    except UnidentifiedImageError:
        raise ApiError(422, "invalid_image", index) from None
    except Exception:  # noqa: BLE001 - truncated data, bad CRC, broken stream, ...
        raise ApiError(422, "invalid_image", index) from None
    np.save(target, array, allow_pickle=False)
    return array.shape[1], array.shape[0]


def fit_long_side(img: Image.Image, limit: int) -> Image.Image:
    """Aspect kept, never enlarged. Pillow resamples RGBA premultiplied."""
    width, height = img.size
    long_side = max(width, height)
    if long_side <= limit:
        return img
    scale = limit / long_side
    size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return img.resize(size, Image.Resampling.LANCZOS)


def load(path: Path) -> np.ndarray:
    return np.load(path, allow_pickle=False)


def extraction_png(rgba: np.ndarray) -> tuple[bytes, int, int]:
    """The SAM 3 upload: ≤ 1080 px long side, RGB PNG. Returns (png, width, height)."""
    img = fit_long_side(Image.fromarray(rgba, "RGBA"), MAX_EXTRACTION_SIDE).convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="PNG", compress_level=1)
    return buf.getvalue(), img.size[0], img.size[1]


def encode_png(rgba: np.ndarray) -> bytes:
    """The result: 8-bit RGBA PNG, no EXIF/GPS or other metadata copied from any input."""
    buf = io.BytesIO()
    Image.fromarray(rgba, "RGBA").save(buf, format="PNG", compress_level=6)
    return buf.getvalue()
