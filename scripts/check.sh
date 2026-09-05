#!/usr/bin/env bash
set -euo pipefail
./gradlew --offline --quiet \
  lint detekt \
  testDebugUnitTest \
  verifyRoborazziDebug \
  dependencyGuard
