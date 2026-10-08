#!/usr/bin/env bash
# Weight-free, GPU-free check: byte-compile the app and run every test. SAM 3 is faked (service
# level) or mocked at the HTTP wire (adapter level); nothing is downloaded. Exits non-zero on failure.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${MULTISHOT_PYTHON:-$ROOT/.venv/bin/python}"
if [[ ! -x "$PYTHON" ]]; then
  echo "check.sh: $PYTHON not found; create the venv first (see server/multishot/README.md)" >&2
  exit 2
fi

cd "$ROOT"
"$PYTHON" -m compileall -q app scripts
"$PYTHON" -m pytest -q -p no:cacheprovider tests
