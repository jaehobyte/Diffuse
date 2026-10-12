#!/usr/bin/env bash
# Weight-free, GPU-free server check: byte-compile the app and run every test not marked `model`.
# It does not create the venv or download anything, and exits non-zero on any failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${RETOUCH_PYTHON:-$ROOT/.venv/bin/python}"
if [[ ! -x "$PYTHON" ]]; then
  echo "check.sh: $PYTHON not found; create the venv first (see server/retouch/README.md)" >&2
  exit 2
fi

cd "$ROOT"
"$PYTHON" -m compileall -q app
"$PYTHON" -m pytest -m "not model" -q -p no:cacheprovider tests
