#!/usr/bin/env bash
# Cross-compiles llama.cpp as STATIC libs (libllama.a, libggml*.a) for arm64-v8a and drops
# them + the public headers into android-tsgo/.llama-static/ — consumed by embedding.go's cgo
# directives so tsgo.go can call llama.cpp's C API in-process (no subprocess, no HTTP hop to a
# spawned server binary). Re-run this whenever the pinned tag below changes.
#
# Pinned to the SAME tag as the prebuilt libllama.so et al already in android/app's jniLibs, for
# the same ABI-compatibility reasoning documented in build-llama-embedding.sh — this script's
# output is otherwise independent of that one (static vs shared build of the same source).
#
# Requires: NDK r27c (ndk;27.2.12479018), cmake, ninja — same toolchain as
# build-whisper-server.sh / build-llama-embedding.sh.
#
# Static libc++ linking (see embedding.go's LDFLAGS): the official prebuilt llama-server/
# llama-embedding binaries link libc++ statically too (confirmed via readelf -d — no
# libc++_shared.so in their NEEDED list), avoiding the need to bundle that extra .so. A cgo
# prototype confirmed -lc++_static -lc++abi (not -static-libstdc++, which NDK clang/lld didn't
# honor the same way GCC does) is what actually produces a static link here.
set -euo pipefail

LLAMA_CPP_TAG="${LLAMA_CPP_TAG:-b9940}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${LLAMA_BUILD_DIR:-/tmp/llama-cpp-build}"
OUT_DIR="$REPO_ROOT/android-tsgo/.llama-static"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found at $ANDROID_NDK_HOME — install with:" >&2
  echo "  sdkmanager --install 'ndk;27.2.12479018'" >&2
  exit 1
fi

if [ ! -d "$WORK_DIR/.git" ]; then
  git clone --depth 1 --branch "$LLAMA_CPP_TAG" https://github.com/ggml-org/llama.cpp.git "$WORK_DIR"
fi

cmake -B "$WORK_DIR/build-android-static" -G Ninja -S "$WORK_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_BACKEND_DL=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_CPU_ALL_VARIANTS=OFF \
  -DGGML_OPENMP=OFF \
  -DCMAKE_C_FLAGS="-march=armv8-a" \
  -DCMAKE_CXX_FLAGS="-march=armv8-a" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_CURL=OFF

cmake --build "$WORK_DIR/build-android-static" --config Release -j"$(nproc)" --target llama ggml ggml-base ggml-cpu

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/lib" "$OUT_DIR/include"
cp "$WORK_DIR/build-android-static/src/libllama.a" "$OUT_DIR/lib/"
cp "$WORK_DIR/build-android-static/ggml/src/libggml.a" "$OUT_DIR/lib/"
cp "$WORK_DIR/build-android-static/ggml/src/libggml-base.a" "$OUT_DIR/lib/"
cp "$WORK_DIR/build-android-static/ggml/src/libggml-cpu.a" "$OUT_DIR/lib/"
cp "$WORK_DIR/include/llama.h" "$OUT_DIR/include/"
cp "$WORK_DIR/ggml/include/"*.h "$OUT_DIR/include/"

echo "Updated $OUT_DIR:"
ls -la "$OUT_DIR/lib" "$OUT_DIR/include"
