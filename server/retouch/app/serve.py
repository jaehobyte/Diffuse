"""Deployment entry point: `python -m app`. Always real engines; there is no fake switch here."""

from __future__ import annotations

import logging
import sys

import uvicorn

from app.config import ConfigError, load_settings
from app.engines import build_real_engines
from app.main import create_app


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"retouch-server: {exc}", file=sys.stderr)
        return 2
    app = create_app(settings, build_real_engines)
    # One process: the models are loaded once. uvicorn's access log is off; the app logs one line
    # per request with request_id, kind, status and stage timings only.
    uvicorn.run(app, host=settings.host, port=settings.port, workers=1, access_log=False, log_level="info")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
