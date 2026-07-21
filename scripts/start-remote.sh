#!/usr/bin/env bash
# Starts llama-server on the phone directly (manual/debug use — the Node
# proxy does this automatically on first request via src/adb.js).
set -euo pipefail

PHONE_REMOTE_DIR="${PHONE_REMOTE_DIR:-/data/local/tmp/sufficit-embed}"
MODEL_FILE="${MODEL_FILE:-Qwen3-Embedding-4B-Q4_K_M.gguf}"
MODEL_ALIAS="${MODEL_ALIAS:-qwen3-embedding:4b}"
PHONE_PORT="${PHONE_PORT:-8090}"
LLAMA_THREADS="${LLAMA_THREADS:-6}"
LLAMA_CTX="${LLAMA_CTX:-2048}"
LLAMA_BATCH="${LLAMA_BATCH:-512}"
LLAMA_UBATCH="${LLAMA_UBATCH:-512}"
PHONE_SERIAL="${PHONE_SERIAL:-}"

ADB_ARGS=()
if [[ -n "$PHONE_SERIAL" ]]; then
  ADB_ARGS=(-s "$PHONE_SERIAL")
fi

# `adb shell "... &"` does not return once launched — the local adb client
# stays attached to the device-side session until the backgrounded process
# itself exits. Background the local adb call too so this script doesn't hang.
adb "${ADB_ARGS[@]}" shell "cd $PHONE_REMOTE_DIR && LD_LIBRARY_PATH=$PHONE_REMOTE_DIR nohup ./llama-server \
  -m $PHONE_REMOTE_DIR/$MODEL_FILE --embedding --pooling last \
  -c $LLAMA_CTX -b $LLAMA_BATCH -ub $LLAMA_UBATCH -t $LLAMA_THREADS \
  --host 127.0.0.1 --port $PHONE_PORT --alias $MODEL_ALIAS \
  > $PHONE_REMOTE_DIR/server.log 2>&1 < /dev/null &" &
disown

adb "${ADB_ARGS[@]}" forward "tcp:$PHONE_PORT" "tcp:$PHONE_PORT"

echo "==> started, forwarded 127.0.0.1:$PHONE_PORT -> phone:$PHONE_PORT"
echo "==> tail logs with: adb ${ADB_ARGS[*]} shell tail -f $PHONE_REMOTE_DIR/server.log"
