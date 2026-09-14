#!/usr/bin/env bash
# Build a debug APK that already knows the servers, and put it on the attached device.
#
# `-Pdiffuse.localCreds` is what makes core/ai read `.env` into BuildConfig, so 서버 설정 comes up
# filled in and 선택 / 자동 보정 work on the first tap. An ordinary `./gradlew assembleDebug` has
# no flag and still produces the credential-free APK that dist/README.md publishes — this script
# is the only thing that turns the flag on.
#
# Set ADB_SERVER_SOCKET when the device hangs off another machine's adb server, e.g.
#   ADB_SERVER_SOCKET=tcp:127.0.0.1:5038 scripts/install.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# A loopback address in `.env` is the phone's own loopback once it is on the phone, not this
# server's. It only works through `adb reverse tcp:<port> tcp:<port>` against an adb server on the
# machine that runs the service — a development path, never what a downloaded APK can use. Say so
# before building an APK that would otherwise report "연결하지 못했어요" on every tap.
if [[ -f .env ]]; then
  while IFS='=' read -r key value; do
    case "$key" in
      SAM3_BASE_URL | MONET_BASE_URL)
        if [[ "$value" =~ ^https?://(127\.0\.0\.1|localhost)(:|/|$) ]]; then
          echo "warning: $key in .env is a loopback address; on a phone it needs adb reverse (USB only)." >&2
        fi
        ;;
    esac
  done < .env
fi

./gradlew :app:assembleDebug -Pdiffuse.localCreds "$@"
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
