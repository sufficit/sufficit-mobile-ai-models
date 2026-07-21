# sufficit-mobile-ai-models

Turns idle Android phones into AI providers for the Sufficit stack. Two
pieces live in this repo:

- **`./`** (this proxy) — the original desk POC: a phone plugged in via USB
  runs `llama-server` (llama.cpp, official android-arm64 build) **directly
  on the phone** — no Termux, no app install, no root — spawned over `adb
  shell`. This machine runs a thin OpenAI-compatible proxy that tunnels to
  it over `adb forward`. Useful for local dev/testing without the app.
- **[`./android/`](./android/)** — the real product: a native Android app
  (no PC/USB tether needed) that pairs with `sufficit-ai` and joins a
  dedicated Tailscale tailnet so any `*-ai` server (eveo-ai, apoint-ai,
  castrum-ai) can reach it as a provider. See
  `sufficit-ai/docs/PLAN-202607091200-mobile-device-ai-provider.md` for the
  full architecture and `android/README.md` for its current status.
  Improvement plan for this repo:
  `docs/PLAN-202607101500-hardening-arquitetura-ui-build.md`.

Model: [Qwen3-Embedding-4B](https://huggingface.co/Qwen/Qwen3-Embedding-4B-GGUF) (Q4_K_M, ~2.3GiB).

## Why this shape

- Android blocks executing arbitrary binaries from writable app storage on
  recent API levels (W^X), but **`adb shell`-spawned processes run as the
  `shell` user with exec rights on `/data/local/tmp`** — the standard way to
  run native tooling on a device without installing anything.
- llama.cpp publishes official prebuilt `android-arm64` binaries
  (`llama-server` + `libggml*.so`) on every GitHub release — no NDK/cross-compile
  needed on this machine.
- The phone's `llama-server` binds to `127.0.0.1` only; `adb forward` tunnels
  it to this machine's localhost. Nothing is exposed on the LAN by the phone
  itself.

## Requirements

- Phone connected over USB with debugging authorized (`adb devices` shows
  `device`, not `unauthorized`).
- `adb`, `curl`, `node >=20` on this machine.

## Setup

```bash
cp .env.example .env        # adjust PHONE_SERIAL if more than one device is attached
./scripts/provision.sh      # downloads llama.cpp + model (cached), pushes both to the phone
npm start                   # proxy API on :4870, starts llama-server on the phone on first request
```

`provision.sh` caches downloads in `~/.cache/sufficit-mobile-ai-models/` so
re-runs are instant. It only needs to run again after a phone factory reset
or to bump the model/llama.cpp version.

## API

OpenAI-compatible embeddings endpoint:

```bash
curl http://localhost:4870/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model": "qwen3-embedding:4b", "input": "texto de exemplo"}'
```

- `GET /health` — proxy + phone + llama-server status (always open, no auth)
- `GET /v1/models` — static model list (single entry: `qwen3-embedding:4b`)
- `POST /v1/embeddings` — proxied to the phone's `llama-server`, with `dimensions` handled in the proxy (see below)

The proxy lazily ensures the phone-side server is up (adb forward +
spawn-if-not-running) on every `/v1/embeddings` call, so it recovers from the
phone rebooting or `llama-server` crashing without restarting this process.

Binds to `127.0.0.1` by default (`PROXY_HOST`) — set it to `0.0.0.0` only
behind a firewall you trust. Optionally set `PROXY_API_KEY` to require
`Authorization: Bearer <key>` on every `/v1/*` route.

### `dimensions` (Matryoshka truncation)

`llama-server` has no built-in `dimensions` parameter — Qwen3-Embedding-4B
always returns its native 2560-dim vector. The model is trained with
Matryoshka Representation Learning (MRL), so the proxy accepts an OpenAI-style
`dimensions` field, truncates the returned vector to the first N values, and
re-normalizes it (L2) — the technique the model card itself recommends.

```bash
curl http://localhost:4870/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model": "qwen3-embedding:4b", "input": "texto de exemplo", "dimensions": 512}'
```

Valid range: `1..2560` (native size, configurable via `EMBEDDING_NATIVE_DIMS`).
Out-of-range or non-integer values get a `400 invalid_dimensions`.

## Manual phone control

```bash
./scripts/start-remote.sh   # start llama-server on the phone + adb forward
./scripts/stop-remote.sh    # kill it
./scripts/status.sh         # adb device state, process, health check
```

Logs live on the phone at `/data/local/tmp/sufficit-embed/server.log`:

```bash
adb shell tail -f /data/local/tmp/sufficit-embed/server.log
```

## Hardware notes (Galaxy A51 / Exynos 9611)

- 8 cores (4x Cortex-A73 + 4x Cortex-A53), 4GB RAM total, ~3.4GB usable.
- Defaults use 6 threads (leaves headroom for the OS) and a 2048 context —
  plenty for embedding inputs. Tune via `LLAMA_THREADS` / `LLAMA_CTX` in `.env`.
- Q4_K_M (~2.3GiB) is the smallest quant Qwen publishes for the 4B model. On
  a 4GB device this is tight; if `llama-server` gets OOM-killed, check
  `server.log` and consider freeing background apps on the phone, or dropping
  to a 0.6B/embedding-smaller model.
- Keep the phone charging — sustained inference on battery-only + doze mode
  will throttle or suspend the process.

## Not handled (out of scope for v1)

- Multiple phones / load balancing.
- Auto-provisioning after phone reboot without a request hitting the proxy first.
- Wireless adb (currently USB-only; `adb forward` requires a live adb transport).
