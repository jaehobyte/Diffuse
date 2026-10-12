"""Fakes and request helpers. Nothing here needs model files or a GPU."""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.config import KINDS, Settings
from app.engines.base import EngineOutput
from app.main import create_app

TOKEN = "test-token-not-a-secret"


@dataclass
class FakeEngine:
    kind: str
    version: str = ""
    #: (image, allowed, stop) -> EngineOutput | None. Default: brighten inside allowed.
    behavior: Callable | None = None
    load_gate: threading.Event | None = None
    warm_gate: threading.Event | None = None
    fail_load: bool = False
    calls: int = 0
    stops: list = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.version:
            self.version = f"{self.kind}/fake@1"

    def load(self) -> None:
        if self.load_gate is not None:
            self.load_gate.wait(10)
        if self.fail_load:
            raise RuntimeError("model file missing")

    def warm_up(self) -> None:
        if self.warm_gate is not None:
            self.warm_gate.wait(10)

    def run(self, image, allowed, stop):
        self.calls += 1
        self.stops.append(stop)
        if self.behavior is not None:
            return self.behavior(image, allowed, stop)
        candidate = image.copy()
        candidate[allowed, :3] = 255 - candidate[allowed, :3]
        return EngineOutput(candidate, allowed.copy(), {"fake": 0.1})


def settings(**overrides) -> Settings:
    """Fake engines stand in for gate-passed ones unless a test passes `qualified_kinds` itself."""
    values = dict(auth_token=TOKEN, model_dir=Path("/nonexistent"), qualified_kinds=KINDS)
    values.update(overrides)
    return Settings(**values)


def factory_of(engines: dict[str, FakeEngine]):
    return lambda _settings: engines


def png_rgba(array: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", cv2.cvtColor(array, cv2.COLOR_RGBA2BGRA))
    assert ok
    return buf.tobytes()


def png_gray(array: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", array)
    assert ok
    return buf.tobytes()


def png_rgb(array: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", cv2.cvtColor(array, cv2.COLOR_RGB2BGR))
    assert ok
    return buf.tobytes()


def decode_png(data: bytes) -> np.ndarray:
    array = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if array.ndim == 3 and array.shape[2] == 4:
        array = cv2.cvtColor(array, cv2.COLOR_BGRA2RGBA)
    return array


def png_color_type(data: bytes) -> tuple[int, int]:
    return data[24], data[25]  # bit depth, colour type


def sample_image(width: int = 32, height: int = 24, seed: int = 1) -> np.ndarray:
    rng = np.random.default_rng(seed)
    image = rng.integers(0, 256, (height, width, 4), dtype=np.uint8)
    image[:, :, 3] = 255
    return image


def full_mask(width: int = 32, height: int = 24, value: int = 255) -> np.ndarray:
    return np.full((height, width), value, dtype=np.uint8)


def metadata(**overrides) -> dict:
    meta = {
        "request_id": "req-1",
        "contract_version": 1,
        "kind": "blemish",
        "expected_engine_version": "blemish/fake@1",
        "width": 32,
        "height": 24,
    }
    meta.update(overrides)
    return meta


def multipart(parts: list[tuple[str, str | None, bytes]], boundary: str = "testboundary123") -> tuple[bytes, str]:
    chunks = []
    for name, content_type, body in parts:
        chunks.append(f"--{boundary}\r\n".encode())
        chunks.append(f'Content-Disposition: form-data; name="{name}"\r\n'.encode())
        if content_type:
            chunks.append(f"Content-Type: {content_type}\r\n".encode())
        chunks.append(b"\r\n" + body + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def standard_parts(meta: dict | None = None, image: np.ndarray | None = None, mask: np.ndarray | None = None):
    meta = metadata() if meta is None else meta
    image = sample_image(meta["width"], meta["height"]) if image is None else image
    mask = full_mask(meta["width"], meta["height"]) if mask is None else mask
    return [
        ("metadata", "application/json", json.dumps(meta).encode()),
        ("image", "image/png", png_rgba(image)),
        ("allowed_mask", "image/png", png_gray(mask)),
    ]


def auth(token: str = TOKEN) -> dict:
    return {"Authorization": f"Bearer {token}"}


def post(client, parts, token: str = TOKEN, extra_headers: dict | None = None):
    body, content_type = multipart(parts)
    headers = {**auth(token), "Content-Type": content_type, **(extra_headers or {})}
    return client.post("/v1/retouch", content=body, headers=headers)


def parse_response_parts(response) -> dict[str, tuple[str, bytes]]:
    """Response multipart → {name: (content_type, body)}; asserts each name appears once."""
    from python_multipart.multipart import parse_options_header

    _, options = parse_options_header(response.headers["content-type"])
    boundary = options[b"boundary"].decode()
    body = response.content
    out: dict[str, tuple[str, bytes]] = {}
    for section in body.split(f"--{boundary}".encode())[1:]:
        if section.startswith(b"--"):
            break
        head, _, content = section[2:].partition(b"\r\n\r\n")
        content = content[:-2]
        headers = dict(line.split(": ", 1) for line in head.decode().split("\r\n"))
        name = parse_options_header(headers["Content-Disposition"])[1][b"name"].decode()
        assert name not in out
        out[name] = (headers["Content-Type"], content)
    return out


def wait_ready(client, timeout: float = 5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get("/health", headers=auth())
        if response.status_code == 200:
            return response
        time.sleep(0.01)
    raise AssertionError("server never became ready")


@pytest.fixture
def blemish_engine():
    return FakeEngine("blemish", version="blemish/fake@1")


@pytest.fixture
def client(blemish_engine):
    app = create_app(settings(enabled_kinds=("blemish",)), factory_of({"blemish": blemish_engine}))
    with TestClient(app) as test_client:
        wait_ready(test_client)
        yield test_client
