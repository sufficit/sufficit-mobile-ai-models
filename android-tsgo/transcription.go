//go:build android

package tsgo

// cgo bindings straight to whisper.cpp's C API — in-process transcription inference, no
// subprocess, no HTTP hop to a spawned server binary. Replaces the old architecture
// (WhisperServerManager.kt spawning whisper-server as a subprocess bound to
// 127.0.0.1:WhisperPort, tsgo.go reverse-proxying to it) the same way embedding.go already
// replaced LlamaServerManager — see that file's doc for the shared reasoning.
//
// Static libs + headers come from scripts/build-whisper-static.sh (run before building
// tsgo.aar — see scripts/build-tsgo-aar.sh) into android-tsgo/.whisper-static/, gitignored.
// That script's own doc explains the one genuinely tricky part of this migration: whisper.cpp
// vendors its own ggml fork, API-incompatible with llama.cpp's (the one embedding.go links) at
// these pinned versions, so simply linking both archives together produces "multiple
// definition" errors. The static libs here have every ggml/gguf symbol they export prefixed
// "wsp_" (objcopy --redefine-syms) to avoid colliding with embedding.go's copy — invisible from
// this file's side, since none of the whisper_* functions called below were renamed.
//
// Static libc++ (-lc++_static -lc++abi) — same reasoning as embedding.go.
//
// flash_attn = false (whisper_context_params, set in transcriptionContextParams below) is load-
// bearing, not a tuning knob: WhisperServerManager's kdoc documents that flash attention hangs
// every /v1/audio/transcriptions request forever on the reference device's CPU (confirmed with
// a direct curl bypassing the app entirely) — that was whisper-server's "-nfa" flag; this is the
// same fix at the C API level.

/*
#cgo CFLAGS: -I${SRCDIR}/.whisper-static/include
#cgo LDFLAGS: -L${SRCDIR}/.whisper-static/lib -lwhisper -lggml-wsp -lggml-base-wsp -lggml-cpu-wsp -lm -ldl -lc++_static -lc++abi

#include <stdlib.h>
#include "whisper.h"

static struct whisper_context_params transcriptionContextParams() {
	struct whisper_context_params p = whisper_context_default_params();
	p.use_gpu = false;    // CPU-only — same reasoning as embedding.go/every native binary here.
	p.flash_attn = false; // see this file's doc: hangs inference forever on the reference device.
	return p;
}

static struct whisper_full_params transcriptionFullParams(int32_t nThreads, bool translate, const char* language) {
	struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
	p.n_threads = nThreads;
	p.translate = translate;
	p.language = language; // NULL -> auto-detect
	p.print_progress = false;
	p.print_realtime = false;
	p.print_timestamps = false;
	p.print_special = false;
	return p;
}
*/
import "C"

import (
	"fmt"
	"runtime"
	"strings"
	"sync"
	"unsafe"
)

var (
	transcriptionMu        sync.Mutex
	transcriptionCtx       *C.struct_whisper_context
	transcriptionModelPath string
)

// loadTranscriptionModel loads modelPath as the resident transcription model, unloading
// whatever was previously loaded first — mirrors loadEmbeddingModel's swap semantics.
func loadTranscriptionModel(modelPath string) error {
	transcriptionEngine.beginLifecycle()
	err := loadTranscriptionModelNative(modelPath)
	if err != nil {
		transcriptionEngine.finishLifecycle("", 0, err)
		return err
	}
	transcriptionEngine.finishLifecycle(transcriptionModelIDFromPath(modelPath), 0, nil)
	return nil
}

func loadTranscriptionModelNative(modelPath string) error {
	transcriptionMu.Lock()
	defer transcriptionMu.Unlock()

	if transcriptionModelPath == modelPath && transcriptionCtx != nil {
		return nil // already loaded, no-op
	}

	unloadTranscriptionModelLocked()

	cPath := C.CString(modelPath)
	defer C.free(unsafe.Pointer(cPath))

	cparams := C.transcriptionContextParams()
	ctx := C.whisper_init_from_file_with_params(cPath, cparams)
	if ctx == nil {
		return fmt.Errorf("whisper_init_from_file_with_params failed for %s", modelPath)
	}

	transcriptionCtx = ctx
	transcriptionModelPath = modelPath
	return nil
}

// unloadTranscriptionModel frees whatever's resident, if anything. Safe to call unconditionally.
func unloadTranscriptionModel() {
	transcriptionEngine.beginLifecycle()
	transcriptionMu.Lock()
	unloadTranscriptionModelLocked()
	transcriptionMu.Unlock()
	transcriptionEngine.finishLifecycle("", 0, nil)
}

func unloadTranscriptionModelLocked() {
	if transcriptionCtx != nil {
		C.whisper_free(transcriptionCtx)
		transcriptionCtx = nil
	}
	transcriptionModelPath = ""
}

// isTranscriptionModelLoaded reports whether any model is currently resident. Uses TryLock, not
// Lock: this is called from the HTTP handler's readiness precheck (see newAudioHandler), and
// loadTranscriptionModel holds transcriptionMu for its ENTIRE duration — whisper_init_from_file
// on a real model file takes real wall time (confirmed on-device: ~25s for a small model). A
// blocking Lock here would make the precheck itself block for that whole load instead of
// answering "not ready yet" immediately, which is what actually breaks LocalTranscriptionTester's
// poll-until-ready loop: OkHttp's client-level read timeout can then fire mid-load instead of the
// loop getting a fast 503 to retry on. Held-lock (busy loading, or a transcribe() call already in
// flight) reports "not loaded" — correct for the former, an acceptable narrow race for the
// latter (mutual exclusion already limits this app to one inference at a time anyway).
func isTranscriptionModelLoaded() bool {
	state := transcriptionEngine.snapshot().State
	return state == engineReady || state == engineBusy
}

// transcribe runs pcm (mono float32 @ 16kHz — see decodeWAVToPCM16kMono) through the resident
// model. Not safe to call concurrently with itself: a whisper_context isn't reentrant for
// whisper_full, hence the mutex around the whole call — same "one consumer at a time"
// assumption as the old --parallel-implicit single whisper-server process handled one request
// at a time in practice (its own request queue), mirrored here explicitly.
func transcribe(pcm []float32, translate bool, language string) (transcriptionResult, error) {
	transcriptionMu.Lock()
	defer transcriptionMu.Unlock()

	if transcriptionCtx == nil {
		return transcriptionResult{}, fmt.Errorf("no transcription model loaded")
	}
	if len(pcm) == 0 {
		return transcriptionResult{}, fmt.Errorf("empty audio")
	}

	nThreads := int32(runtime.NumCPU())
	if nThreads > 4 {
		// Mobile SoCs are usually heterogeneous (big.LITTLE). Using six workers on the
		// reference Galaxy A51 pulled two efficiency cores into every Whisper graph and
		// made a 5-second sample take more than 150 seconds. Four workers keep inference
		// on the performant cluster and also match whisper.cpp's conservative default.
		nThreads = 4
	}

	var cLanguage *C.char
	lang := strings.TrimSpace(language)
	if lang != "" && lang != "auto" {
		cLanguage = C.CString(lang)
		defer C.free(unsafe.Pointer(cLanguage))
	}

	params := C.transcriptionFullParams(C.int32_t(nThreads), C.bool(translate), cLanguage)

	rc := C.whisper_full(transcriptionCtx, params, (*C.float)(unsafe.Pointer(&pcm[0])), C.int(len(pcm)))
	if rc != 0 {
		return transcriptionResult{}, fmt.Errorf("whisper_full failed: %d", rc)
	}

	nSegments := int(C.whisper_full_n_segments(transcriptionCtx))
	segments := make([]transcriptionSegment, 0, nSegments)
	var text strings.Builder
	for i := 0; i < nSegments; i++ {
		segText := C.GoString(C.whisper_full_get_segment_text(transcriptionCtx, C.int(i)))
		t0 := int64(C.whisper_full_get_segment_t0(transcriptionCtx, C.int(i)))
		t1 := int64(C.whisper_full_get_segment_t1(transcriptionCtx, C.int(i)))
		text.WriteString(segText)
		segments = append(segments, transcriptionSegment{
			Text:  segText,
			Start: float64(t0) / 100, // whisper.cpp segment timestamps are centiseconds
			End:   float64(t1) / 100,
		})
	}

	detectedLang := "en"
	if langID := int(C.whisper_full_lang_id(transcriptionCtx)); langID >= 0 {
		if s := C.whisper_lang_str(C.int(langID)); s != nil {
			detectedLang = C.GoString(s)
		}
	}

	return transcriptionResult{
		Text:     text.String(),
		Language: detectedLang,
		Segments: segments,
	}, nil
}
