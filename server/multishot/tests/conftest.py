"""Fakes, fixtures and request helpers. Nothing here needs SAM 3, a GPU or model weights.

Fixture photos are a flat grey background with flat coloured rectangles ("people"). The fake
extractor segments by colour: each palette colour present in the upload is one person, so a
photo with two colours is "several people" and a point picks the one under it.
"""

from __future__ import annotations

import asyncio
import io
import json
import threading
import time
from dataclasses import dataclass, field
from typing import Callable

import numpy as np
from PIL import Image

from app.config import Settings
from app.errors import ApiError
from app.main import create_app
from app.sam import JobContext

TOKEN = "test-token-not-a-secret"
GREY = (128, 128, 128)
PALETTE = {
    "red": (230, 20, 20),
    "green": (20, 200, 40),
    "blue": (30, 40, 230),
    "yellow": (240, 220, 10),
    "magenta": (220, 20, 220),
    "cyan": (10, 220, 230),
    "orange": (250, 130, 0),
}
TOLERANCE = 60


def settings(**overrides) -> Settings:
    values = dict(auth_token=TOKEN, sam3_url="http://sam3.test", sam3_token="sam3-token-not-a-secret")
    values.update(overrides)
    return Settings(**values)


def auth() -> dict[str, str]:
    return {"Authorization": f"Bearer {TOKEN}"}


# -- fixture photos ------------------------------------------------------------------------------

Rect = tuple[int, int, int, int, str]  # x0, y0, x1 (excl), y1 (excl), palette name


def photo(width: int, height: int, rects: list[Rect], background=GREY, alpha: int = 255) -> np.ndarray:
    array = np.zeros((height, width, 4), dtype=np.uint8)
    array[..., :3] = background
    array[..., 3] = alpha
    for x0, y0, x1, y1, colour in rects:
        array[y0:y1, x0:x1, :3] = PALETTE[colour]
        array[y0:y1, x0:x1, 3] = 255
    return array


def encode(array: np.ndarray, fmt: str = "PNG", orientation: int | None = None, **kwargs) -> bytes:
    img = Image.fromarray(array, "RGBA")
    if fmt == "JPEG":
        img = img.convert("RGB")
    if orientation is not None:
        exif = Image.Exif()
        exif[0x0112] = orientation
        kwargs["exif"] = exif
    buf = io.BytesIO()
    img.save(buf, format=fmt, **kwargs)
    return buf.getvalue()


def decode_png(data: bytes) -> np.ndarray:
    with Image.open(io.BytesIO(data), formats=["PNG"]) as img:
        assert img.mode == "RGBA"
        return np.asarray(img).copy()


def colour_mask(rgb: np.ndarray, colour: str) -> np.ndarray:
    diff = np.abs(rgb[..., :3].astype(np.int32) - np.array(PALETTE[colour], dtype=np.int32))
    return diff.max(axis=-1) <= TOLERANCE


# -- fake SAM 3 ----------------------------------------------------------------------------------


@dataclass
class Call:
    size: tuple[int, int]
    point: tuple[float, float] | None
    colours: list[str]


@dataclass
class FakeExtractor:
    """Colour segmentation. [behavior] (call index, png, size, point, ctx) may replace the answer."""

    is_ready: bool = True
    behavior: Callable | None = None
    calls: list[Call] = field(default_factory=list)

    def ready(self) -> bool:
        return self.is_ready

    def extract(self, png, size, point, ctx: JobContext):
        with Image.open(io.BytesIO(png), formats=["PNG"]) as img:
            assert img.size == size and max(size) <= 1080
            rgb = np.asarray(img.convert("RGB"))
        masks = {name: colour_mask(rgb, name) for name in PALETTE}
        present = [name for name, mask in masks.items() if mask.sum() >= 4]
        self.calls.append(Call(size, point, present))
        if self.behavior is not None:
            answer = self.behavior(len(self.calls) - 1, rgb, size, point, ctx)
            if answer is not None:
                return answer
        if point is None:
            return [masks[name] for name in present]
        x = min(int(point[0] * size[0]), size[0] - 1)
        y = min(int(point[1] * size[1]), size[1] - 1)
        return [masks[name] for name in present if masks[name][y, x]] or [np.zeros(rgb.shape[:2], bool)]


def factory_of(extractor) -> Callable[[Settings], object]:
    return lambda _settings: extractor


# -- requests ------------------------------------------------------------------------------------


def multipart(parts: list[tuple[str, bytes, str | None, str | None]], boundary: str = "test-boundary-1234"):
    """[(name, body, filename, content_type)] in order → (body, content type)."""
    out = io.BytesIO()
    for name, body, filename, content_type in parts:
        out.write(f"--{boundary}\r\n".encode())
        disposition = f'form-data; name="{name}"'
        if filename is not None:
            disposition += f'; filename="{filename}"'
        out.write(f"Content-Disposition: {disposition}\r\n".encode())
        if content_type is not None:
            out.write(f"Content-Type: {content_type}\r\n".encode())
        out.write(b"\r\n")
        out.write(body)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"


def image_parts(images: list[bytes], names: list[str] | None = None, metadata: dict | bytes | None = None):
    names = names or ["photo.png"] * len(images)
    parts = [("images", data, name, "image/png") for data, name in zip(images, names)]
    if metadata is not None:
        raw = metadata if isinstance(metadata, bytes) else json.dumps(metadata).encode()
        parts.append(("metadata", raw, None, "application/json"))
    return parts


def parse_multipart_response(response) -> tuple[dict, bytes]:
    from python_multipart.multipart import parse_options_header

    _, options = parse_options_header(response.headers["content-type"])
    boundary = options[b"boundary"]
    sections = response.content.split(b"--" + boundary)
    parts = {}
    for section in sections[1:-1]:
        head, _, body = section.strip(b"\r\n").partition(b"\r\n\r\n")
        name = head.split(b'name="')[1].split(b'"')[0].decode()
        content_type = [line for line in head.split(b"\r\n") if line.lower().startswith(b"content-type")][0]
        parts[name] = (content_type.split(b":")[1].strip().decode(), body)
    assert list(parts) == ["metadata", "image"]
    assert parts["metadata"][0] == "application/json" and parts["image"][0] == "image/png"
    return json.loads(parts["metadata"][1]), parts["image"][1]


def client(extractor, **overrides):
    from fastapi.testclient import TestClient

    return TestClient(create_app(settings(**overrides), factory_of(extractor)))


def post(test_client, parts, headers=None):
    body, content_type = multipart(parts)
    return test_client.post("/v1/multishot", content=body, headers={**auth(), "Content-Type": content_type, **(headers or {})})


async def wait_for(predicate, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition not reached")


def blocking(release: threading.Event, started: threading.Event | None = None):
    """A behaviour that blocks the first extraction until [release]."""

    def behavior(index, rgb, size, point, ctx):
        if index == 0:
            if started is not None:
                started.set()
            release.wait(10)
        return None

    return behavior


def raises(error: ApiError, at: int = 0):
    def behavior(index, rgb, size, point, ctx):
        if index == at:
            raise error
        return None

    return behavior
