#!/usr/bin/env bash
set -euo pipefail

PHONE_PORT="${PHONE_PORT:-8090}"
PHONE_SERIAL="${PHONE_SERIAL:-}"
ADB_ARGS=()
if [[ -n "$PHONE_SERIAL" ]]; then
  ADB_ARGS=(-s "$PHONE_SERIAL")
fi

echo "==> adb devices"
adb devices

echo "==> process on phone"
adb "${ADB_ARGS[@]}" shell "pgrep -fa llama-server" || echo "  (not running)"

echo "==> local health check (via adb forward)"
curl -sf "http://127.0.0.1:$PHONE_PORT/health" && echo || echo "  (unreachable)"
