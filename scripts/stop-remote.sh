#!/usr/bin/env bash
set -euo pipefail

PHONE_SERIAL="${PHONE_SERIAL:-}"
ADB_ARGS=()
if [[ -n "$PHONE_SERIAL" ]]; then
  ADB_ARGS=(-s "$PHONE_SERIAL")
fi

adb "${ADB_ARGS[@]}" shell "pkill -f llama-server" || echo "==> nothing running"
