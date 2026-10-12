"""Deployment entry point: `python -m app`. Always the real SAM 3 adapter; there is no fake switch here."""

from __future__ import annotations

import logging
import sys

import uvicorn

from app.config import ConfigError, load_settings
from app.main import create_app
from app.sam import Sam3Extractor


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    # httpx logs every upstream URL at INFO; keep the log to the app's one line per request.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"multishot-server: {exc}", file=sys.stderr)
        return 2
    app = create_app(settings, Sam3Extractor)
    uvicorn.run(app, host=settings.host, port=settings.port, workers=1, access_log=False, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
