//go:build android

package tsgo

// cgo bindings straight to llama.cpp's C API — in-process embedding inference, no subprocess,
// no HTTP hop to a spawned server binary. Replaces the old architecture (LlamaServerManager.kt
// spawning llama-server as a subprocess bound to 127.0.0.1:ModelPort, tsgo.go reverse-proxying
// to it) with a direct call from this Go layer, which already owns the tailnet-facing HTTP
// server (buildRouter) — Kotlin already calls into this same Go layer via gomobile bind for
// everything else, so this is the one bridge this app needs, not two.
//
// Static libs + headers come from scripts/build-llama-static.sh (run before building
// tsgo.aar — see scripts/build-tsgo-aar.sh) into android-tsgo/.llama-static/, gitignored
// (~110MB, rebuilt locally). Pinned to the same llama.cpp tag as this repo's other native
// binaries for consistency, though this build is independent (static libs, not the shared
// .so / subprocess executables in android/app's jniLibs).
//
// Static libc++ (-lc++_static -lc++abi, not -static-libstdc++ — NDK clang/lld didn't honor
// that the same way GCC does, confirmed via a standalone prototype) avoids needing to bundle
// an extra libc++_shared.so — matches how the official prebuilt llama-server/llama-embedding
// binaries already ship (readelf -d shows no libc++_shared.so in their NEEDED list either).
//
// Params mirror LlamaServerManager.kt's old subprocess launch args exactly (--embedding
// --pooling last -c 2048 -b 512 -ub 512 --parallel 1) so behavior doesn't change out from
// under callers during the cutover — see that file's kdoc for why those specific values.

/*
#cgo CFLAGS: -I${SRCDIR}/.llama-static/include
#cgo LDFLAGS: -L${SRCDIR}/.llama-static/lib -lllama -lggml -lggml-cpu -lggml-base -lm -ldl -lc++_static -lc++abi

#include <stdlib.h>
#include "llama.h"

static struct llama_model_params embeddingModelParams() {
	struct llama_model_params p = llama_model_default_params();
	p.n_gpu_layers = 0; // CPU-only — same reasoning as every other native binary in this repo
	                    // (see LlamaServerManager.kt kdoc: GPU builds crash/SIGSEGV on the
	                    // reference devices' GPUs, never shipped).
	return p;
}

static struct llama_context_params embeddingContextParams(int32_t nThreads) {
	struct llama_context_params p = llama_context_default_params();
	p.n_ctx = 2048;
	p.n_batch = 512;
	p.n_ubatch = 512;
	p.n_seq_max = 1; // --parallel 1: one consumer at a time, no reason for more slots.
	p.embeddings = true;
	p.pooling_type = LLAMA_POOLING_TYPE_LAST;
	p.n_threads = nThreads;
	p.n_threads_batch = nThreads;
	return p;
}
*/
import "C"

import (
	"fmt"
	"math"
	"runtime"
	"strings"
	"sync"
	"unsafe"
)

var (
	embeddingMu        sync.Mutex
	embeddingModel     *C.struct_llama_model
	embeddingCtx       *C.struct_llama_context
	embeddingModelPath string
	backendInitOnce    sync.Once
)

// loadEmbeddingModel loads modelPath as the resident embedding model, unloading whatever was
// previously loaded first — mirrors LlamaServerManager.switchTo's "stop whatever's running,
// start the new one" semantics, except swapping a loaded model in this process's memory is
// just pointer teardown/rebuild, no OS process spawn/kill race to wait out.
func loadEmbeddingModel(modelPath string) error {
	embeddingMu.Lock()
	defer embeddingMu.Unlock()

	if embeddingModelPath == modelPath && embeddingCtx != nil {
		return nil // already loaded, no-op — same idempotency as the old start()
	}

	unloadEmbeddingModelLocked()

	backendInitOnce.Do(func() {
		C.llama_backend_init()
	})

	cPath := C.CString(modelPath)
	defer C.free(unsafe.Pointer(cPath))

	mparams := C.embeddingModelParams()
	model := C.llama_model_load_from_file(cPath, mparams)
	if model == nil {
		return fmt.Errorf("llama_model_load_from_file failed for %s", modelPath)
	}

	nThreads := int32(runtime.NumCPU())
	if nThreads > 6 {
		nThreads = 6 // matches LlamaServerManager's availableProcessors().coerceAtMost(6)
	}
	cparams := C.embeddingContextParams(C.int32_t(nThreads))
	ctx := C.llama_init_from_model(model, cparams)
	if ctx == nil {
		C.llama_model_free(model)
		return fmt.Errorf("llama_init_from_model failed for %s", modelPath)
	}

	embeddingModel = model
	embeddingCtx = ctx
	embeddingModelPath = modelPath
	return nil
}

// unloadEmbeddingModel frees whatever's resident, if anything. Safe to call unconditionally.
func unloadEmbeddingModel() {
	embeddingMu.Lock()
	defer embeddingMu.Unlock()
	unloadEmbeddingModelLocked()
}

func unloadEmbeddingModelLocked() {
	if embeddingCtx != nil {
		C.llama_free(embeddingCtx)
		embeddingCtx = nil
	}
	if embeddingModel != nil {
		C.llama_model_free(embeddingModel)
		embeddingModel = nil
	}
	embeddingModelPath = ""
}

// isEmbeddingModelLoaded reports whether any model is currently resident.
func isEmbeddingModelLoaded() bool {
	embeddingMu.Lock()
	defer embeddingMu.Unlock()
	return embeddingCtx != nil
}

// embeddingDimensions returns the resident model's native embedding width, or 0 if none loaded.
func embeddingDimensions() int {
	embeddingMu.Lock()
	defer embeddingMu.Unlock()
	if embeddingModel == nil {
		return 0
	}
	return int(C.llama_model_n_embd(embeddingModel))
}

// embed tokenizes text against the resident model, runs it through the model, and returns the
// L2-normalized pooled embedding — same normalization OpenAI's API (and this app's HTTP
// contract) expects. Not safe to call concurrently with itself: a llama_context isn't
// reentrant for decode, hence the mutex around the whole call, same "one consumer at a time"
// assumption as the old --parallel 1 subprocess flag.
func embed(text string) ([]float32, error) {
	embeddingMu.Lock()
	defer embeddingMu.Unlock()

	if embeddingCtx == nil || embeddingModel == nil {
		return nil, fmt.Errorf("no embedding model loaded")
	}

	vocab := C.llama_model_get_vocab(embeddingModel)

	cText := C.CString(text)
	defer C.free(unsafe.Pointer(cText))

	// First call with a nil token buffer to learn how many tokens we need — avoids guessing
	// a fixed max and truncating long inputs silently.
	nTokensNeeded := C.llama_tokenize(vocab, cText, C.int32_t(len(text)), nil, 0, C.bool(true), C.bool(false))
	if nTokensNeeded >= 0 {
		return nil, fmt.Errorf("unexpected tokenize result: empty input")
	}
	nTokensMax := -nTokensNeeded

	tokens := make([]C.llama_token, nTokensMax)
	nTokens := C.llama_tokenize(vocab, cText, C.int32_t(len(text)), &tokens[0], nTokensMax, C.bool(true), C.bool(false))
	if nTokens < 0 {
		return nil, fmt.Errorf("llama_tokenize failed: %d", nTokens)
	}

	// llama_memory_clear would be ideal between calls (matches the official embedding
	// example), but this context is embeddings-only/no-KV-reuse-across-calls by construction
	// (n_seq_max=1, fresh batch per call) — decode alone is sufficient here.
	batch := C.llama_batch_get_one(&tokens[0], nTokens)
	rc := C.llama_decode(embeddingCtx, batch)
	if rc != 0 {
		return nil, fmt.Errorf("llama_decode failed: %d", rc)
	}

	embdPtr := C.llama_get_embeddings_seq(embeddingCtx, 0)
	if embdPtr == nil {
		return nil, fmt.Errorf("llama_get_embeddings_seq returned nil")
	}

	nEmbd := int(C.llama_model_n_embd(embeddingModel))
	raw := unsafe.Slice((*float32)(unsafe.Pointer(embdPtr)), nEmbd)

	out := make([]float32, nEmbd)
	copy(out, raw)
	l2Normalize(out)
	return out, nil
}

// embeddingModelID mirrors LlamaServerManager.aliasFor(modelFile) exactly — the id embedding
// responses report, and the same id embeddingModelEntry (tsgo.go) expects to dedupe against
// for GET /v1/models. Empty string if nothing is loaded.
func embeddingModelID() string {
	embeddingMu.Lock()
	path := embeddingModelPath
	embeddingMu.Unlock()
	if path == "" {
		return ""
	}
	base := path
	if i := strings.LastIndexByte(base, '/'); i >= 0 {
		base = base[i+1:]
	}
	if i := strings.LastIndexByte(base, '.'); i >= 0 {
		base = base[:i]
	}
	return strings.ToLower(base) + "-embedding"
}

func l2Normalize(v []float32) {
	var sumSq float64
	for _, x := range v {
		sumSq += float64(x) * float64(x)
	}
	if sumSq == 0 {
		return
	}
	norm := float32(math.Sqrt(sumSq))
	for i := range v {
		v[i] /= norm
	}
}
