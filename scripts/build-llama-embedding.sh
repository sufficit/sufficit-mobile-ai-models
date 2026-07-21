#!/usr/bin/env bash
# Cross-compiles llama.cpp's llama-embedding for arm64-v8a and drops the renamed binary into
# android/app/src/main/jniLibs/arm64-v8a — a one-shot CLI counterpart to the existing
# libllamaserver.so, used for testing an embedding model directly (no HTTP/server involved).
# Re-run this whenever the pinned tag below changes.
#
# Pinned to the SAME tag as the prebuilt libllama.so/libllama-common.so/libggml*.so already in
# jniLibs (extracted from llama.cpp's official android-arm64 release, see git blame on those
# files) — same source commit keeps this binary ABI-compatible with those already-bundled libs
# without needing to rebuild/replace them too. Built with our OWN NDK r27c toolchain rather than
# using the official release's prebuilt llama-cli/llama-embedding binaries (which are built with
# NDK r29): confirmed on-device that the r29 prebuilt llama-cli fails to dynamically link against
# this app's libc++ ("cannot locate symbol ... bad_function_call") — building ourselves with the
# same r27c toolchain as everything else here avoids that entirely.
#
# Requires: NDK r27c (ndk;27.2.12479018), cmake, ninja — same toolchain as
# build-whisper-server.sh.
set -euo pipefail

LLAMA_CPP_TAG="${LLAMA_CPP_TAG:-b9940}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${LLAMA_BUILD_DIR:-/tmp/llama-cpp-build}"
JNILIBS_DIR="$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found at $ANDROID_NDK_HOME — install with:" >&2
  echo "  sdkmanager --install 'ndk;27.2.12479018'" >&2
  exit 1
fi

if [ ! -d "$WORK_DIR/.git" ]; then
  git clone --depth 1 --branch "$LLAMA_CPP_TAG" https://github.com/ggml-org/llama.cpp.git "$WORK_DIR"
fi

cmake -B "$WORK_DIR/build-android" -G Ninja -S "$WORK_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_CPU_ALL_VARIANTS=OFF \
  -DGGML_OPENMP=OFF \
  -DCMAKE_C_FLAGS="-march=armv8-a" \
  -DCMAKE_CXX_FLAGS="-march=armv8-a" \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=ON \
  -DLLAMA_BUILD_EXAMPLES=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_CURL=OFF

cmake --build "$WORK_DIR/build-android" --config Release -j"$(nproc)" --target llama-embedding

mkdir -p "$JNILIBS_DIR"
cp "$WORK_DIR/build-android/bin/llama-embedding" "$JNILIBS_DIR/libllamaembedding.so"
echo "Updated $JNILIBS_DIR/libllamaembedding.so"
