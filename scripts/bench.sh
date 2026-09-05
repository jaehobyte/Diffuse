#!/usr/bin/env bash
# specs/testing.md §2: not part of green. Run by a human.
# The benchmarks assume DIFFUSE_BENCHMARK and are skipped by scripts/check.sh without it.
set -euo pipefail
DIFFUSE_BENCHMARK=true ./gradlew --offline -i \
  :core:imaging:testDebugUnitTest --rerun --tests '*RenderBenchmarkTest*' \
  | grep -E 'preview (run|p50)'
