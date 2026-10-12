"""Wire contract v1 encoding: multipart in/out, strict PNG checks, metadata validation.

Everything is in memory. Nothing here logs content.
"""

from __future__ import annotations

import io
import json
import re
import secrets
import struct
import zlib
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image
from python_multipart.multipart import MultipartParser, parse_options_header

from app.config import CONTRACT_VERSION, MAX_PIXELS, MAX_SIDE

REQUEST_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PART_TYPES = {"metadata": "application/json", "image": "image/png", "allowed_mask": "image/png"}

# Headers are checked against the §8.1 limits before decoding; this only keeps Pillow's own bomb
# guard consistent with them.
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


class WireError(Exception):
    def __init__(self, status: int, code: str, request_id: str | None = None) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        self.request_id = request_id


def invalid(request_id: str | None = None) -> WireError:
    return WireError(400, "invalid_request", request_id)


# ---------------------------------------------------------------------------------------------
# Multipart request
# ---------------------------------------------------------------------------------------------


def boundary_of(content_type: str | None) -> bytes:
    if not content_type:
        raise invalid()
    media, options = parse_options_header(content_type)
    boundary = options.get(b"boundary")
    if media != b"multipart/form-data" or not boundary:
        raise invalid()
    return boundary


def parse_multipart(body: bytes, boundary: bytes) -> dict[str, bytes]:
    """Parts by name. Missing, duplicated, unknown, or wrongly typed parts are invalid_request."""
    parts: dict[str, bytes] = {}
    state: dict = {"headers": {}, "field": b"", "value": b"", "chunks": [], "ended": False, "bad": False}

    def on_part_begin() -> None:
        state["headers"] = {}
        state["chunks"] = []

    def on_header_field(data: bytes, start: int, end: int) -> None:
        state["field"] += data[start:end]

    def on_header_value(data: bytes, start: int, end: int) -> None:
        state["value"] += data[start:end]

    def on_header_end() -> None:
        state["headers"][state["field"].decode("latin-1").lower()] = state["value"]
        state["field"], state["value"] = b"", b""

    def on_part_data(data: bytes, start: int, end: int) -> None:
        state["chunks"].append(data[start:end])

    def on_part_end() -> None:
        disposition = state["headers"].get("content-disposition")
        if disposition is None:
            state["bad"] = True
            return
        kind, options = parse_options_header(disposition)
        name = options.get(b"name", b"").decode("utf-8", "replace")
        if kind != b"form-data" or name not in PART_TYPES or name in parts:
            state["bad"] = True
            return
        declared = state["headers"].get("content-type")
        if declared is not None and parse_options_header(declared)[0].decode("latin-1") != PART_TYPES[name]:
            state["bad"] = True
            return
        parts[name] = b"".join(state["chunks"])

    def on_end() -> None:
        state["ended"] = True

    parser = MultipartParser(
        boundary,
        {
            "on_part_begin": on_part_begin,
            "on_part_data": on_part_data,
            "on_part_end": on_part_end,
            "on_header_field": on_header_field,
            "on_header_value": on_header_value,
            "on_header_end": on_header_end,
            "on_end": on_end,
        },
    )
    try:
        parser.write(body)
        parser.finalize()
    except Exception:  # noqa: BLE001 - any parser failure is a malformed request
        raise invalid() from None
    if state["bad"] or not state["ended"] or set(parts) != set(PART_TYPES):
        raise invalid()
    return parts


@dataclass(frozen=True)
class Metadata:
    request_id: str
    kind: str
    expected_engine_version: str
    width: int
    height: int


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def parse_metadata(raw: bytes) -> Metadata:
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise invalid() from None
    if not isinstance(data, dict):
        raise invalid()
    request_id = data.get("request_id")
    if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
        raise invalid()
    version = data.get("contract_version")
    if not _is_int(version):
        raise invalid(request_id)
    if version != CONTRACT_VERSION:
        raise WireError(400, "unsupported_contract", request_id)
    kind = data.get("kind")
    expected = data.get("expected_engine_version")
    width, height = data.get("width"), data.get("height")
    if not isinstance(kind, str) or not isinstance(expected, str):
        raise invalid(request_id)
    if not _is_int(width) or not _is_int(height) or width <= 0 or height <= 0:
        raise invalid(request_id)
    if width > MAX_SIDE or height > MAX_SIDE or width * height > MAX_PIXELS:
        raise WireError(413, "too_large", request_id)
    return Metadata(request_id, kind, expected, width, height)


# ---------------------------------------------------------------------------------------------
# PNG
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Ihdr:
    width: int
    height: int
    bit_depth: int
    color_type: int


def read_ihdr(data: bytes, request_id: str) -> Ihdr:
    """The PNG header, read without decoding. Pixel limits are checked here, before any decode."""
    if len(data) < 33 or data[:8] != PNG_SIGNATURE:
        raise invalid(request_id)
    length, chunk_type = struct.unpack(">I4s", data[8:16])
    if length != 13 or chunk_type != b"IHDR":
        raise invalid(request_id)
    if zlib.crc32(data[12:29]) != struct.unpack(">I", data[29:33])[0]:
        raise invalid(request_id)
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    if width == 0 or height == 0:
        raise invalid(request_id)
    if width > MAX_SIDE or height > MAX_SIDE or width * height > MAX_PIXELS:
        raise WireError(413, "too_large", request_id)
    return Ihdr(width, height, bit_depth, color_type)


def check_png(data: bytes, meta: Metadata, color_type: int) -> None:
    header = read_ihdr(data, meta.request_id)
    if header.bit_depth != 8 or header.color_type != color_type:
        raise invalid(meta.request_id)
    if (header.width, header.height) != (meta.width, meta.height):
        raise invalid(meta.request_id)


def decode_png(data: bytes, meta: Metadata, mode: str) -> np.ndarray:
    """Decode an already header-checked PNG. `RGBA` → H×W×4, `L` → H×W. Any damage is invalid."""
    try:
        with Image.open(io.BytesIO(data), formats=["PNG"]) as img:
            if img.mode != mode or img.size != (meta.width, meta.height) or "transparency" in img.info:
                raise invalid(meta.request_id)
            img.load()
            array = np.asarray(img, dtype=np.uint8)
    except WireError:
        raise
    except Exception:  # noqa: BLE001 - truncated, bad CRC, bad zlib stream, ...
        raise invalid(meta.request_id) from None
    expected = (meta.height, meta.width, 4) if mode == "RGBA" else (meta.height, meta.width)
    if array.shape != expected:
        raise invalid(meta.request_id)
    return array


def decode_request_images(parts: dict[str, bytes], meta: Metadata) -> tuple[np.ndarray, np.ndarray]:
    """(image H×W×4 uint8, allowed H×W bool). Both headers are checked before either is decoded."""
    check_png(parts["image"], meta, color_type=6)
    check_png(parts["allowed_mask"], meta, color_type=0)
    image = decode_png(parts["image"], meta, "RGBA")
    mask = decode_png(parts["allowed_mask"], meta, "L")
    if np.any((mask != 0) & (mask != 255)):
        raise invalid(meta.request_id)
    image.flags.writeable = False
    allowed = mask == 255
    allowed.flags.writeable = False
    return image, allowed


def encode_rgba_png(rgba: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGRA), [cv2.IMWRITE_PNG_COMPRESSION, 1])
    if not ok:
        raise RuntimeError("PNG encode failed")
    return buf.tobytes()


def encode_mask_png(support: np.ndarray) -> bytes:
    gray = np.where(support, np.uint8(255), np.uint8(0))
    ok, buf = cv2.imencode(".png", gray, [cv2.IMWRITE_PNG_COMPRESSION, 1])
    if not ok:
        raise RuntimeError("PNG encode failed")
    return buf.tobytes()


# ---------------------------------------------------------------------------------------------
# Multipart response
# ---------------------------------------------------------------------------------------------


def encode_multipart(parts: list[tuple[str, str, bytes]]) -> tuple[bytes, str]:
    """[(name, content_type, body)] → (body, Content-Type header value)."""
    boundary = "retouch-" + secrets.token_hex(16)
    out = io.BytesIO()
    for name, content_type, body in parts:
        out.write(f"--{boundary}\r\n".encode())
        out.write(f'Content-Disposition: form-data; name="{name}"\r\n'.encode())
        out.write(f"Content-Type: {content_type}\r\n\r\n".encode())
        out.write(body)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"
