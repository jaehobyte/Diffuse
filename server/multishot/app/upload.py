"""Streaming `multipart/form-data` request parsing into a per-request spool directory.

Image parts go straight to files, in the order their parts appear; nothing is keyed by name or
file name, so a repeated `images` part is one more image every time. Nothing here logs content.
"""

from __future__ import annotations

import shutil
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO

from python_multipart.multipart import MultipartParser, parse_options_header

from app.config import MAX_IMAGES, MAX_METADATA_BYTES, Settings
from app.errors import ApiError

IMAGES = "images"
METADATA = "metadata"


def invalid_request() -> ApiError:
    return ApiError(400, "invalid_request")


def boundary_of(content_type: str | None) -> bytes:
    if not content_type:
        raise invalid_request()
    media, options = parse_options_header(content_type)
    boundary = options.get(b"boundary")
    if media != b"multipart/form-data" or not boundary:
        raise invalid_request()
    return boundary


class Spool:
    """A request's temporary files. Removed when the last holder (handler, worker) lets go."""

    def __init__(self, root: str | None) -> None:
        self.path = Path(tempfile.mkdtemp(prefix="multishot-", dir=root))
        self._holders = 1
        self._lock = threading.Lock()

    def retain(self) -> None:
        with self._lock:
            self._holders += 1

    def release(self) -> None:
        with self._lock:
            self._holders -= 1
            last = self._holders == 0
        if last:
            shutil.rmtree(self.path, ignore_errors=True)


@dataclass
class Upload:
    """What the body held: image files in part order, and the raw metadata part if there was one."""

    images: list[Path] = field(default_factory=list)
    metadata: bytes | None = None


class UploadParser:
    """Feeds body bytes to python-multipart; enforces the part rules as soon as a part's headers end."""

    def __init__(self, boundary: bytes, spool: Spool, settings: Settings) -> None:
        self.upload = Upload()
        self._spool = spool
        self._settings = settings
        self._parts = 0
        self._field = b""
        self._value = b""
        self._headers: dict[str, bytes] = {}
        self._name: str | None = None
        self._file: BinaryIO | None = None
        self._size = 0
        self._metadata = bytearray()
        self._ended = False
        self._parser = MultipartParser(
            boundary,
            {
                "on_part_begin": self._on_part_begin,
                "on_header_field": self._on_header_field,
                "on_header_value": self._on_header_value,
                "on_header_end": self._on_header_end,
                "on_headers_finished": self._on_headers_finished,
                "on_part_data": self._on_part_data,
                "on_part_end": self._on_part_end,
                "on_end": self._on_end,
            },
        )

    def feed(self, data: bytes) -> None:
        try:
            self._parser.write(data)
        except ApiError:
            self.close()
            raise
        except Exception:  # noqa: BLE001 - any parser failure is a malformed request
            self.close()
            raise invalid_request() from None

    def finish(self) -> Upload:
        try:
            self._parser.finalize()
        except Exception:  # noqa: BLE001
            self.close()
            raise invalid_request() from None
        if not self._ended or self._file is not None:
            self.close()
            raise invalid_request()
        return self.upload

    def close(self) -> None:
        if self._file is not None:
            self._file.close()
            self._file = None

    # -- callbacks ----------------------------------------------------------------------------

    def _on_part_begin(self) -> None:
        self._parts += 1
        self._headers = {}
        self._name = None
        self._size = 0

    def _on_header_field(self, data: bytes, start: int, end: int) -> None:
        self._field += data[start:end]

    def _on_header_value(self, data: bytes, start: int, end: int) -> None:
        self._value += data[start:end]

    def _on_header_end(self) -> None:
        self._headers[self._field.decode("latin-1").lower()] = self._value
        self._field, self._value = b"", b""

    def _on_headers_finished(self) -> None:
        disposition = self._headers.get("content-disposition")
        if disposition is None:
            raise invalid_request()
        kind, options = parse_options_header(disposition)
        name = options.get(b"name", b"").decode("utf-8", "replace")
        if kind != b"form-data":
            raise invalid_request()
        if name == IMAGES:
            # A seventh image is a count error, not a size or part-limit error.
            if len(self.upload.images) >= MAX_IMAGES:
                raise ApiError(422, "invalid_image_count")
            path = self._spool.path / f"input-{len(self.upload.images)}"
            self._file = open(path, "wb")  # noqa: SIM115 - closed in _on_part_end / close()
            self.upload.images.append(path)
        elif name == METADATA:
            if self.upload.metadata is not None:
                raise invalid_request()
            declared = self._headers.get("content-type")
            if declared is not None and parse_options_header(declared)[0] != b"application/json":
                raise invalid_request()
            self._metadata = bytearray()
        else:
            raise invalid_request()
        if self._parts > self._settings.max_parts:
            raise invalid_request()
        self._name = name

    def _on_part_data(self, data: bytes, start: int, end: int) -> None:
        self._size += end - start
        if self._name == IMAGES:
            if self._size > self._settings.max_image_bytes:
                raise ApiError(413, "too_large", len(self.upload.images) - 1)
            assert self._file is not None
            self._file.write(data[start:end])
        else:
            if self._size > MAX_METADATA_BYTES:
                raise ApiError(413, "too_large")
            self._metadata += data[start:end]

    def _on_part_end(self) -> None:
        if self._name == IMAGES:
            assert self._file is not None
            self._file.close()
            self._file = None
        elif self._name == METADATA:
            self.upload.metadata = bytes(self._metadata)

    def _on_end(self) -> None:
        self._ended = True
