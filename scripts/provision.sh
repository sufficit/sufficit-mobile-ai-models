#!/usr/bin/env bash
# Downloads the llama.cpp android-arm64 release + the Qwen3-Embedding-4B GGUF
# (cached locally), then pushes only what's needed to the phone over adb.
set -euo pipefail

LLAMA_CPP_TAG="${LLAMA_CPP_TAG:-b9940}"
MODEL_FILE="${MODEL_FILE:-Qwen3-Embedding-4B-Q4_K_M.gguf}"
MODEL_REPO="${MODEL_REPO:-Qwen/Qwen3-Embedding-4B-GGUF}"
PHONE_REMOTE_DIR="${PHONE_REMOTE_DIR:-/data/local/tmp/sufficit-embed}"
CACHE_DIR="${CACHE_DIR:-$HOME/.cache/sufficit-mobile-ai-models}"
PHONE_SERIAL="${PHONE_SERIAL:-}"

ADB_ARGS=()
if [[ -n "$PHONE_SERIAL" ]]; then
  ADB_ARGS=(-s "$PHONE_SERIAL")
fi

mkdir -p "$CACHE_DIR"

RELEASE_DIR="$CACHE_DIR/llama-$LLAMA_CPP_TAG"
if [[ ! -x "$RELEASE_DIR/llama-server" ]]; then
  echo "==> downloading llama.cpp $LLAMA_CPP_TAG (android-arm64)"
  TARBALL="$CACHE_DIR/llama-$LLAMA_CPP_TAG-bin-android-arm64.tar.gz"
  curl -sL --fail -o "$TARBALL" \
    "https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_CPP_TAG}/llama-${LLAMA_CPP_TAG}-bin-android-arm64.tar.gz"
  mkdir -p "$RELEASE_DIR"
  tar xzf "$TARBALL" -C "$RELEASE_DIR" --strip-components=1
  rm -f "$TARBALL"
else
  echo "==> llama.cpp $LLAMA_CPP_TAG already cached at $RELEASE_DIR"
fi

MODEL_PATH="$CACHE_DIR/$MODEL_FILE"
if [[ ! -f "$MODEL_PATH" ]]; then
  echo "==> downloading $MODEL_FILE from $MODEL_REPO"
  curl -sL --fail -o "$MODEL_PATH" \
    "https://huggingface.co/${MODEL_REPO}/resolve/main/${MODEL_FILE}"
else
  echo "==> $MODEL_FILE already cached"
fi

echo "==> pushing runtime to phone:$PHONE_REMOTE_DIR"
adb "${ADB_ARGS[@]}" shell "mkdir -p $PHONE_REMOTE_DIR"

# Only the binary + shared libs actually needed by llama-server (the release
# tarball also ships llama-cli, llama-quantize, and other tools we don't use).
NEEDED_FILES=(
  llama-server
  libllama-server-impl.so
  libllama-common.so
  libmtmd.so
  libllama.so
  libggml.so
  libggml-base.so
  libggml-cpu-android_armv8.0_1.so
  libggml-cpu-android_armv8.2_1.so
  libggml-cpu-android_armv8.2_2.so
  libggml-cpu-android_armv8.6_1.so
  libggml-cpu-android_armv9.0_1.so
  libggml-cpu-android_armv9.2_1.so
  libggml-cpu-android_armv9.2_2.so
)

for f in "${NEEDED_FILES[@]}"; do
  adb "${ADB_ARGS[@]}" push "$RELEASE_DIR/$f" "$PHONE_REMOTE_DIR/$f" >/dev/null
done
adb "${ADB_ARGS[@]}" shell "chmod 755 $PHONE_REMOTE_DIR/llama-server"

echo "==> pushing model (this is the slow part, ~2.5GB)"
adb "${ADB_ARGS[@]}" push "$MODEL_PATH" "$PHONE_REMOTE_DIR/$MODEL_FILE"

echo "==> done. Runtime + model live at $PHONE_REMOTE_DIR on the phone."
