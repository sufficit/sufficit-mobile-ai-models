#!/usr/bin/env bash
# Cross-compiles whisper.cpp as STATIC libs for arm64-v8a and drops them + whisper.h into
# android-tsgo/.whisper-static/ — consumed by transcription.go's cgo directives so tsgo.go can
# call whisper.cpp's C API in-process, the transcription counterpart to build-llama-static.sh /
# embedding.go. Re-run this whenever the pinned tag below changes.
#
# THE HARD PART — two independently-vendored ggml copies in one process: whisper.cpp vendors its
# own ggml fork, at a different revision than llama.cpp's — the two forks are NOT ABI/API
# compatible with each other at these pinned tags (confirmed by hand: this pinned llama.cpp
# tag's ggml is missing GGML_KQ_MASK_PAD that this whisper.cpp tag's whisper.cpp.o needs, and
# this whisper.cpp tag's ggml is missing several newer ggml features this llama.cpp tag's
# llama.cpp needs, e.g. GGML_TYPE_MXFP4/ggml_opt_optimizer_type — the two forks simply are not
# close enough in time to share one ggml build).
#
# For the OLD subprocess architecture this was a non-issue: llama-server and whisper-server were
# two separate OS processes, each with its own address space, so both linking their own ggml
# .so was fine (dynamic linker per-process namespace). Now that BOTH run in-process inside the
# SAME tsgo.aar binary (cgo, see embedding.go/transcription.go), statically linking two archives
# that both define strong global symbols like ggml_init/ggml_backend_cpu_init/etc is a straight
# "multiple definition" link error — static linking has no per-library symbol namespace the way
# dynamic linking does.
#
# THE FIX: build whisper.cpp against ITS OWN vendored ggml (WHISPER_USE_SYSTEM_GGML=OFF, i.e.
# whisper.cpp's default), then rename every strong global symbol whisper's ggml build exports —
# via `objcopy --redefine-syms` across every .o in libggml*.a AND libwhisper.a (so whisper.cpp's
# own internal calls into its ggml get rewritten to match) — prefixing them all with "wsp_".
# whisper.cpp's own public API (whisper_init_from_file_with_params, whisper_full, etc.) is left
# untouched: none of those symbols start with ggml_/gguf_, so they're never in the rename map,
# and transcription.go calls them completely normally. Confirmed via a standalone link test
# (llama's unmodified libggml*.a + libllama.a + this script's renamed libggml*-wsp.a +
# libwhisper.a, linked into one .so) that this produces zero duplicate-symbol errors — the same
# fundamental idea as renaming SONAME/NEEDED entries to avoid a shared-library collision (see
# git history for how the old subprocess-era whisper-server .so build handled the equivalent
# problem), just applied to static archives via objcopy instead.
#
# The renamed archive FILENAMES also can't collide with .llama-static/lib's libggml*.a — cgo's
# `-l` flags resolve by filename within each `-L` dir in order, so if both directories had a
# file literally named libggml.a, only the first `-L` entry's copy would ever get linked (the
# second would be silently ignored, not merged) — hence libggml-wsp.a/libggml-base-wsp.a/
# libggml-cpu-wsp.a here, not libggml.a/libggml-base.a/libggml-cpu.a.
#
# Requires: NDK r27c (ndk;27.2.12479018), cmake, ninja — same toolchain as
# build-llama-static.sh.
set -euo pipefail

WHISPER_CPP_TAG="${WHISPER_CPP_TAG:-v1.7.6}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WHISPER_STATIC_BUILD_DIR:-/tmp/whisper-cpp-static-build}"
OUT_DIR="$REPO_ROOT/android-tsgo/.whisper-static"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found at $ANDROID_NDK_HOME — install with:" >&2
  echo "  sdkmanager --install 'ndk;27.2.12479018'" >&2
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
NM="$TOOLCHAIN/bin/llvm-nm"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
AR="$TOOLCHAIN/bin/llvm-ar"

if [ ! -d "$WORK_DIR/.git" ]; then
  git clone --depth 1 --branch "$WHISPER_CPP_TAG" https://github.com/ggml-org/whisper.cpp.git "$WORK_DIR"
fi

# whisper.cpp's ggml/CMakeLists.txt force-sets GGML_STANDALONE=ON whenever ggml/ is configured
# as the top-level source dir (it checks CMAKE_SOURCE_DIR == CMAKE_CURRENT_SOURCE_DIR, ignoring
# any -D override) — which then tries to configure_file a ggml.pc.in that doesn't exist in this
# vendored copy. Harmless empty placeholder: we don't consume the resulting pkg-config .pc file,
# only the CMake package (ggml-config.cmake) generated unconditionally further down the same
# CMakeLists.txt.
touch "$WORK_DIR/ggml/ggml.pc.in"

GGML_BUILD_DIR="$WORK_DIR/build-ggml-android"
GGML_INSTALL_DIR="$WORK_DIR/install-ggml-android"
cmake -B "$GGML_BUILD_DIR" -G Ninja -S "$WORK_DIR/ggml" \
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
  -DGGML_BUILD_TESTS=OFF \
  -DGGML_BUILD_EXAMPLES=OFF \
  -DCMAKE_INSTALL_PREFIX="$GGML_INSTALL_DIR"
cmake --build "$GGML_BUILD_DIR" --config Release -j"$(nproc)" --target ggml ggml-base ggml-cpu
cmake --install "$GGML_BUILD_DIR" --config Release

WHISPER_BUILD_DIR="$WORK_DIR/build-whisper-android"
cmake -B "$WHISPER_BUILD_DIR" -G Ninja -S "$WORK_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_C_FLAGS="-march=armv8-a" \
  -DCMAKE_CXX_FLAGS="-march=armv8-a" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DWHISPER_USE_SYSTEM_GGML=ON \
  -Dggml_DIR="$GGML_INSTALL_DIR/lib/cmake/ggml" \
  -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
  -DWHISPER_BUILD_SERVER=OFF \
  -DWHISPER_BUILD_EXAMPLES=OFF \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_SDL2=OFF \
  -DWHISPER_CURL=OFF
cmake --build "$WHISPER_BUILD_DIR" --config Release -j"$(nproc)" --target whisper

# --- symbol prefixing (see this script's header doc for why) ---
RENAME_DIR="$WORK_DIR/rename-android"
rm -rf "$RENAME_DIR"
mkdir -p "$RENAME_DIR"

GGML_LIBS=(
  "$GGML_BUILD_DIR/src/libggml.a"
  "$GGML_BUILD_DIR/src/libggml-base.a"
  "$GGML_BUILD_DIR/src/libggml-cpu.a"
)

# Every strong (non-weak) global symbol whisper's ggml build exports — both plain C names
# (ggml_*, gguf_*, quantize_row_*, ...) and C++-mangled ones (ggml::cpu::tensor_traits's vtable/
# typeinfo/dtor, defined in ggml-cpu/traits.cpp) all collide with llama's ggml build's identical
# symbols. `__`-prefixed compiler-generated helpers (e.g. __clang_call_terminate) are excluded —
# those showed up as weak/COMDAT in practice (the linker dedupes them across archives without
# renaming; confirmed no such symbol appeared in the "duplicate symbol" errors before this filter
# was narrowed to exclude them).
"$NM" -g --defined-only "${GGML_LIBS[@]}" 2>/dev/null \
  | awk 'NF==3 && $2 ~ /^[A-Z]$/ {print $3}' \
  | grep -v '^__' \
  | sort -u > "$RENAME_DIR/redefine-syms.txt.tmp"
awk '{print $0, "wsp_" $0}' "$RENAME_DIR/redefine-syms.txt.tmp" > "$RENAME_DIR/redefine-syms.txt"

# Operates on the .o files still sitting in CMake's own build tree (per-target CMakeFiles/*.dir/
# directories), NOT on files extracted from the packed .a — `ar x` extracts every member into a
# flat directory using just its basename, and ggml-cpu.dir alone has TWO members both named
# quants.c.o (the generic one and arch/arm/quants.c.o's ARM-optimized override) and two both
# named repack.cpp.o; a flat `ar x` silently overwrites one with the other, dropping every symbol
# the clobbered object defined. Found the hard way: the resulting libgojni.so linked "successfully"
# (undefined symbols in a shared library aren't a link-time error, only a dlopen-time one) but
# crashed at runtime with `UnsatisfiedLinkError: dlopen failed: cannot locate symbol
# "wsp_quantize_row_q4_0"` the first time :modelruntime touched the tsgo.Tsgo class. Working
# directly from the build tree's per-target directories sidesteps the collision entirely — each
# .o keeps its own distinct path, only the final `ar rcs` groups them back into one archive
# (which tolerates duplicate member basenames fine, same as the original libggml-cpu.a already
# did before this script touched it).
rename_and_pack() {
  local build_target_dir="$1" out_name="$2"
  local objs=()
  while IFS= read -r -d '' obj; do
    "$OBJCOPY" --redefine-syms="$RENAME_DIR/redefine-syms.txt" "$obj"
    objs+=("$obj")
  done < <(find "$build_target_dir" -name '*.o' -print0)
  "$AR" rcs "$RENAME_DIR/$out_name" "${objs[@]}"
}

rename_and_pack "$GGML_BUILD_DIR/src/CMakeFiles/ggml.dir" libggml-wsp.a
rename_and_pack "$GGML_BUILD_DIR/src/CMakeFiles/ggml-base.dir" libggml-base-wsp.a
rename_and_pack "$GGML_BUILD_DIR/src/CMakeFiles/ggml-cpu.dir" libggml-cpu-wsp.a
# libwhisper.a itself only needs its ggml_*/gguf_* CALL SITES rewritten to match (its own
# whisper_* symbols aren't in the rename map, so they pass through unchanged) — same
# `objcopy --redefine-syms` step, just applied to whisper.cpp's own object file too.
rename_and_pack "$WHISPER_BUILD_DIR/src/CMakeFiles/whisper.dir" libwhisper.a

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/lib" "$OUT_DIR/include"
cp "$RENAME_DIR/libggml-wsp.a" "$RENAME_DIR/libggml-base-wsp.a" "$RENAME_DIR/libggml-cpu-wsp.a" "$RENAME_DIR/libwhisper.a" "$OUT_DIR/lib/"
cp "$WORK_DIR/include/whisper.h" "$OUT_DIR/include/"
cp "$GGML_INSTALL_DIR/include/"*.h "$OUT_DIR/include/"

# Self-check: catches a repeat of the duplicate-basename bug documented in rename_and_pack's doc
# at build time, not at dlopen time on a device. A shared library (the final libgojni.so) links
# "successfully" even with unresolved symbols — the linker only complains about those at dlopen —
# so a "wsp_"-prefixed symbol that's referenced somewhere in the renamed archives but never
# defined anywhere in them would otherwise go unnoticed until a native call crashes the app.
RENAMED_ARCHIVES=("$OUT_DIR/lib/libggml-wsp.a" "$OUT_DIR/lib/libggml-base-wsp.a" "$OUT_DIR/lib/libggml-cpu-wsp.a" "$OUT_DIR/lib/libwhisper.a")
"$NM" -u "${RENAMED_ARCHIVES[@]}" 2>/dev/null | awk '{print $NF}' | grep '^wsp_' | LC_ALL=C sort -u > "$RENAME_DIR/undefined-wsp-syms.txt"
"$NM" --defined-only "${RENAMED_ARCHIVES[@]}" 2>/dev/null | awk 'NF>=2{print $NF}' | LC_ALL=C sort -u > "$RENAME_DIR/defined-syms.txt"
MISSING="$(LC_ALL=C comm -23 "$RENAME_DIR/undefined-wsp-syms.txt" "$RENAME_DIR/defined-syms.txt")"
if [ -n "$MISSING" ]; then
  echo "ERROR: symbols referenced but never defined after renaming (likely a duplicate-basename" >&2
  echo "object file silently overwritten during packing — see rename_and_pack's doc):" >&2
  echo "$MISSING" >&2
  exit 1
fi

echo "Updated $OUT_DIR:"
ls -la "$OUT_DIR/lib" "$OUT_DIR/include"
