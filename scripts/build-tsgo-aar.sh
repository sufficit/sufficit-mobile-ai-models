#!/usr/bin/env bash
# Rebuilds android/app/libs/tsgo.aar from android-tsgo/ via gomobile bind. Run this after any
# change to android-tsgo/tsgo.go.
#
# Requires: NDK r27c (ndk;27.2.12479018), go, and gomobile/gobind pinned to the versions in
# android-tsgo/go.mod's `tool` block (installed below via `go install` from inside that module —
# do NOT `go install` a global gomobile first, it won't match go.sum).
set -euo pipefail

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TSGO_DIR="$REPO_ROOT/android-tsgo"
OUT="$REPO_ROOT/android/app/libs/tsgo.aar"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK not found at $ANDROID_NDK_HOME — install with:" >&2
  echo "  sdkmanager --install 'ndk;27.2.12479018'" >&2
  exit 1
fi

# embedding.go's cgo directives (android build tag) link against these — see
# scripts/build-llama-static.sh's own doc for why static/pinned-tag. Skipped if already built
# (LLAMA_BUILD_DIR/tag change invalidation is the caller's responsibility, same as the other
# build-*.sh scripts here).
if [ ! -f "$TSGO_DIR/.llama-static/lib/libllama.a" ]; then
  echo "==> .llama-static missing, building it first (scripts/build-llama-static.sh)"
  "$REPO_ROOT/scripts/build-llama-static.sh"
fi

cd "$TSGO_DIR"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$PATH:$(go env GOPATH)/bin"
export ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME}"
export ANDROID_NDK_HOME

# ts_omit_portmapper: excludes NAT-PMP/PCP/UPnP port mapping (tailscale.com/feature/portmapper,
# pulled in via github.com/huin/goupnp) entirely. UPnP discovery calls raw net.Interfaces()
# directly, bypassing our SetInterfacesJSON/netmon.RegisterInterfaceGetter override (that only
# covers tailscale's own netmon package) — a real, SELinux-blocked call site on Android, just
# not the one that caused the tailnet-never-connects bug investigated in tsgo.go's
# SetInterfacesJSON (that was netmon's own %zone CIDR-parsing fallback). Kept as defensive
# hardening: portmapper is purely a direct-connection optimization — DERP relay + STUN-based
# hole punching still work without it — so omitting it trades a minor optimization for one
# less raw-netlink call site on devices with strict untrusted_app SELinux policies.
gomobile bind -target=android/arm64 -androidapi 28 -tags ts_omit_portmapper -o "$OUT" .
echo "Wrote $OUT"
