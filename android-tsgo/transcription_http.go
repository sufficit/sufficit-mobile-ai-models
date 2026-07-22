package tsgo

// POST /v1/audio/transcriptions and /v1/audio/translations handlers — portable (no build tag):
// calls the platform-specific transcribe()/isTranscriptionModelLoaded() (real cgo/whisper.cpp
// implementation in transcription.go under "android", stub in transcription_stub.go otherwise),
// same split as embedding_http.go. Replaces newWhisperReverseProxy/newWhisperTranslateReverseProxy
// (reverse-proxying to a spawned whisper-server subprocess, both removed) — response shape
// (task/language/duration/text/segments plus the sufficit-services-whisper compatibility fields
// model/server/device/cached/processing_time) matches what those used to produce, so no client
// of this endpoint needs to change.

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"time"
)

// maxTranscriptionBodyBytes bounds multipart form parsing — generous for a voice note/short
// recording while still bounding worst-case memory use (matches the old translate-rewrite
// proxy's own cap).
const maxTranscriptionBodyBytes = 64 << 20 // 64MB

// transcriptionSegment/transcriptionResult are shared between transcription.go (android, real
// cgo/whisper.cpp implementation) and transcription_stub.go (!android) — declared here, in the
// portable file, rather than duplicated in both build-tagged files.
type transcriptionSegment struct {
	Text  string
	Start float64 // seconds
	End   float64 // seconds
}

type transcriptionResult struct {
	Text     string
	Language string
	Segments []transcriptionSegment
}

func newTranscriptionsHandler() http.Handler { return newAudioHandler(false) }

// newTranslationsHandler forces translate=true regardless of what the caller sends — whisper.cpp
// only supports translation via a per-request field on its transcription endpoint, not a
// separate URL, so this just pins that field the way newWhisperTranslateReverseProxy's request
// rewrite used to.
func newTranslationsHandler() http.Handler { return newAudioHandler(true) }

func newAudioHandler(forceTranslate bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		if !isTranscriptionModelLoaded() {
			log.Printf("[tsgo] POST %s: no transcription model loaded", r.URL.Path)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(noModelBody))
			return
		}

		if err := r.ParseMultipartForm(maxTranscriptionBodyBytes); err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"failed to parse multipart form"}`))
			return
		}

		fileHeaders := r.MultipartForm.File["file"]
		if len(fileHeaders) != 1 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"missing \"file\" part"}`))
			return
		}
		f, err := fileHeaders[0].Open()
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		audioBytes, err := io.ReadAll(f)
		f.Close()
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		pcm, err := decodeWAVToPCM16kMono(audioBytes)
		if err != nil {
			log.Printf("[tsgo] POST %s: WAV decode failed: %v", r.URL.Path, err)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"unsupported or invalid audio (WAV only)"}`))
			return
		}

		translate := forceTranslate || r.FormValue("translate") == "true"

		start := time.Now()
		result, err := transcribe(pcm, translate, r.FormValue("language"))
		if err != nil {
			log.Printf("[tsgo] POST %s: transcribe failed: %v", r.URL.Path, err)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte(`{"error":"transcription inference failed"}`))
			return
		}

		respLanguage := result.Language
		task := "transcribe"
		if forceTranslate {
			// sufficit-services-whisper always reports the OUTPUT language here (translation
			// output is always English) — same override the old translate proxy applied.
			respLanguage = "english"
			task = "translate"
		}

		segments := make([]map[string]any, len(result.Segments))
		for i, s := range result.Segments {
			segments[i] = map[string]any{"text": s.Text, "start": s.Start, "end": s.End}
		}

		transcriptionMetaMu.RLock()
		modelName := activeModelName
		hostname := deviceHostname
		transcriptionMetaMu.RUnlock()

		out, err := json.Marshal(map[string]any{
			"task":            task,
			"language":        respLanguage,
			"duration":        float64(len(pcm)) / whisperSampleRate,
			"text":            result.Text,
			"segments":        segments,
			"model":           modelName,
			"server":          hostname,
			"device":          "cpu",
			"cached":          false,
			"processing_time": time.Since(start).Seconds(),
		})
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(out)
	})
}
