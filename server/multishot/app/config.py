"""Runtime settings, read once from the environment.

The request limits of API contract v1 (specs/multishot_api.md §5) are defaults here and in the README;
tests may build `Settings` with smaller ones, the environment cannot change them. Only operational
knobs (where SAM 3 is, tokens, bind address, admission, deadline, upstream timeouts) are variables.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse

CONTRACT_VERSION = 1

MIN_IMAGES = 3
MAX_IMAGES = 6

#: §5 limits.
MAX_IMAGE_BYTES = 20 * 1024 * 1024  # 20 MiB per image part
MAX_BODY_BYTES = 125 * 1024 * 1024  # 125 MiB per request body
MAX_SOURCE_PIXELS = 40_000_000  # per image, read from the header before decoding
MAX_PARTS = 7  # six images and one metadata
MAX_METADATA_BYTES = 64 * 1024

#: Normalised photo (and output canvas) and SAM 3 upload sizes, long side.
MAX_WORKING_SIDE = 4096
MAX_EXTRACTION_SIDE = 1080

#: Seconds a client is told to wait after a 429.
RETRY_AFTER_S = 5


class ConfigError(ValueError):
    """The environment cannot start a server."""


@dataclass(frozen=True)
class Settings:
    auth_token: str
    sam3_url: str
    sam3_token: str
    host: str = "127.0.0.1"
    port: int = 8086
    max_running: int = 1
    max_queued: int = 0
    #: The whole request, upload included.
    deadline_s: float = 300.0
    sam3_connect_timeout_s: float = 10.0
    sam3_read_timeout_s: float = 60.0
    #: Session DELETE and health probes: short and separate from the request budget.
    sam3_cleanup_timeout_s: float = 5.0
    #: Where request spools go; None is the system temp directory.
    tmp_dir: str | None = None
    max_image_bytes: int = MAX_IMAGE_BYTES
    max_body_bytes: int = MAX_BODY_BYTES
    max_source_pixels: int = MAX_SOURCE_PIXELS
    max_parts: int = MAX_PARTS

    def __post_init__(self) -> None:
        if not self.auth_token:
            raise ConfigError("MULTISHOT_AUTH_TOKEN is empty; refusing to start without auth")
        url = urlparse(self.sam3_url)
        if url.scheme not in ("http", "https") or not url.netloc:
            raise ConfigError("MULTISHOT_SAM3_URL must be an http(s) base URL")
        if not self.sam3_token:
            raise ConfigError("MULTISHOT_SAM3_TOKEN is empty")
        if self.max_running < 1 or self.max_queued < 0:
            raise ConfigError("MULTISHOT_MAX_RUNNING must be ≥ 1 and MULTISHOT_MAX_QUEUED ≥ 0")
        timeouts = (self.deadline_s, self.sam3_connect_timeout_s, self.sam3_read_timeout_s, self.sam3_cleanup_timeout_s)
        if any(not t > 0 for t in timeouts):
            raise ConfigError("deadline and timeouts must be positive")


def load_settings(env: dict[str, str] | None = None) -> Settings:
    env = dict(os.environ) if env is None else env
    try:
        return Settings(
            auth_token=env.get("MULTISHOT_AUTH_TOKEN", "").strip(),
            sam3_url=env.get("MULTISHOT_SAM3_URL", "").strip().rstrip("/"),
            sam3_token=env.get("MULTISHOT_SAM3_TOKEN", "").strip(),
            host=env.get("MULTISHOT_HOST", "127.0.0.1"),
            port=int(env.get("MULTISHOT_PORT", "8086")),
            max_running=int(env.get("MULTISHOT_MAX_RUNNING", "1")),
            max_queued=int(env.get("MULTISHOT_MAX_QUEUED", "0")),
            deadline_s=float(env.get("MULTISHOT_DEADLINE_S", "300")),
            sam3_connect_timeout_s=float(env.get("MULTISHOT_SAM3_CONNECT_TIMEOUT_S", "10")),
            sam3_read_timeout_s=float(env.get("MULTISHOT_SAM3_READ_TIMEOUT_S", "60")),
            tmp_dir=env.get("MULTISHOT_TMP_DIR") or None,
        )
    except ValueError as exc:
        if isinstance(exc, ConfigError):
            raise
        raise ConfigError(f"invalid numeric setting: {exc.__class__.__name__}") from None
