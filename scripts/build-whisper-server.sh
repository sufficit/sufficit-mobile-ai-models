#!/usr/bin/env bash
# Cross-compiles whisper.cpp's whisper-server AND whisper-cli for arm64-v8a and drops the
# renamed .so files straight into android/app/src/main/jniLibs/arm64-v8a — the same shape as
# the existing (separately-built) llama-server binaries. whisper-cli is the one-shot local
# counterpart to whisper-server: a direct file-in/text-out transcription, no HTTP involved,
# used for testing a model without exercising the API layer. Re-run this whenever whisper.cpp
# needs updating.
#
# Requires: NDK r27c (ndk;27.2.12479018 via sdkmanager — matches the llama-server build's
# toolchain), cmake, ninja, patchelf, go (only if you're also touching android-tsgo).
#
# Flags mirror the llama.cpp lessons already documented in LlamaServerManager.kt's kdoc:
#   -march=armv8-a      Cortex-A55 "LITTLE" cores on Exynos 9611 SIGILL on armv8.2-a.
#   GGML_BACKEND_DL=OFF  runtime backend-plugin dlopen fails in this app's private exec context.
#   GGML_OPENMP=OFF      avoids depending on libomp.so, which isn't bundled anywhere else here.
set -euo pipefail

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WHISPER_BUILD_DIR:-/tmp/whisper-cpp-build}"
JNILIBS_DIR="$REPO_ROOT/android/app/src/main/jniLibs/arm64-v8a"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found at $ANDROID_NDK_HOME — install with:" >&2
  echo "  sdkmanager --install 'ndk;27.2.12479018'" >&2
  exit 1
fi

if [ ! -d "$WORK_DIR/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git "$WORK_DIR"
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
  -DWHISPER_BUILD_SERVER=ON \
  -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_SDL2=OFF \
  -DWHISPER_CURL=OFF \
  -DWHISPER_FFMPEG=OFF

cmake --build "$WORK_DIR/build-android" --config Release -j"$(nproc)" --target whisper-server whisper-cli

BIN_DIR="$WORK_DIR/build-android/bin"
STAGE_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGE_DIR"' EXIT

# whisper.cpp vendors its own ggml fork at a different revision than llama.cpp's — same
# filenames (libggml-base.so, libggml-cpu.so, libggml.so) would collide/overwrite in the
# single jniLibs/arm64-v8a directory both binaries share. Rename + patch ELF NEEDED/SONAME so
# the dynamic linker still resolves everything correctly under the new names. libwhisper.so
# and the whisper-server executable itself don't collide with anything llama-side, so only the
# three ggml libs need renaming.
cp "$BIN_DIR/libggml-base.so" "$STAGE_DIR/libwhisper-ggml-base.so"
cp "$BIN_DIR/libggml-cpu.so" "$STAGE_DIR/libwhisper-ggml-cpu.so"
cp "$BIN_DIR/libggml.so" "$STAGE_DIR/libwhisper-ggml.so"
cp "$BIN_DIR/libwhisper.so" "$STAGE_DIR/libwhisper.so"
cp "$BIN_DIR/whisper-server" "$STAGE_DIR/libwhisperserver.so"
cp "$BIN_DIR/whisper-cli" "$STAGE_DIR/libwhispercli.so"
cd "$STAGE_DIR"

patchelf --set-soname libwhisper-ggml-base.so libwhisper-ggml-base.so

patchelf --set-soname libwhisper-ggml-cpu.so libwhisper-ggml-cpu.so
patchelf --replace-needed libggml-base.so libwhisper-ggml-base.so libwhisper-ggml-cpu.so

patchelf --set-soname libwhisper-ggml.so libwhisper-ggml.so
patchelf --replace-needed libggml-cpu.so libwhisper-ggml-cpu.so libwhisper-ggml.so
patchelf --replace-needed libggml-base.so libwhisper-ggml-base.so libwhisper-ggml.so

patchelf --replace-needed libggml.so libwhisper-ggml.so libwhisper.so
patchelf --replace-needed libggml-cpu.so libwhisper-ggml-cpu.so libwhisper.so
patchelf --replace-needed libggml-base.so libwhisper-ggml-base.so libwhisper.so

patchelf --replace-needed libggml.so libwhisper-ggml.so libwhisperserver.so
patchelf --replace-needed libggml-cpu.so libwhisper-ggml-cpu.so libwhisperserver.so
patchelf --replace-needed libggml-base.so libwhisper-ggml-base.so libwhisperserver.so

patchelf --replace-needed libggml.so libwhisper-ggml.so libwhispercli.so
patchelf --replace-needed libggml-cpu.so libwhisper-ggml-cpu.so libwhispercli.so
patchelf --replace-needed libggml-base.so libwhisper-ggml-base.so libwhispercli.so

mkdir -p "$JNILIBS_DIR"
cp ./*.so "$JNILIBS_DIR/"
echo "Updated $JNILIBS_DIR:"
ls -la "$JNILIBS_DIR"/libwhisper*.so
