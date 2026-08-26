package tsgo

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"time"
)

const (
	maxTranscriptionBodyBytes = 64 << 20 // 64 MiB audio payload
	maxMultipartOverheadBytes = 1 << 20  // multipart fields and headers
)

type transcriptionSegment struct {
	Text  string
	Start float64
	End   float64
}

type transcriptionResult struct {
	Text     string
	Language string
	Segments []transcriptionSegment
}

type audioHTTPRuntime struct {
	snapshot   func() engineSnapshot
	tryBegin   func() (engineSnapshot, bool)
	finish     func(error)
	transcribe func([]float32, bool, string) (transcriptionResult, error)
}

func newTranscriptionsHandler() http.Handler { return newAudioHandler(false) }

func newTranslationsHandler() http.Handler { return newAudioHandler(true) }

func newAudioHandler(forceTranslate bool) http.Handler {
	return newAudioHandlerWithRuntime(forceTranslate, audioHTTPRuntime{
		snapshot:   transcriptionEngine.snapshot,
		tryBegin:   transcriptionEngine.tryBeginInference,
		finish:     transcriptionEngine.finishInference,
		transcribe: transcribe,
	})
}

func newAudioHandlerWithRuntime(forceTranslate bool, runtime audioHTTPRuntime) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		snapshot := runtime.snapshot()
		if snapshot.State != engineReady {
			writeEngineStateResponse(w, snapshot)
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxTranscriptionBodyBytes+maxMultipartOverheadBytes)
		if err := r.ParseMultipartForm(8 << 20); err != nil {
			var maxBytesError *http.MaxBytesError
			if errors.As(err, &maxBytesError) {
				writeJSONError(w, http.StatusRequestEntityTooLarge, "request_too_large", "audio request exceeds 65 MiB")
				return
			}
			writeJSONError(w, http.StatusBadRequest, "invalid_multipart", "failed to parse multipart form")
			return
		}
		if r.MultipartForm != nil {
			defer r.MultipartForm.RemoveAll()
		}

		requestedModel := r.FormValue("model")
		if !modelMatches(requestedModel, snapshot.Model) {
			writeJSONError(w, http.StatusConflict, "model_not_loaded",
				"the requested transcription model is not the active model")
			return
		}

		fileHeaders := r.MultipartForm.File["file"]
		if len(fileHeaders) != 1 {
			writeJSONError(w, http.StatusBadRequest, "invalid_file", "exactly one file part is required")
			return
		}
		if fileHeaders[0].Size > maxTranscriptionBodyBytes {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "request_too_large", "audio file exceeds 64 MiB")
			return
		}

		file, err := fileHeaders[0].Open()
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_file", "failed to open audio file")
			return
		}
		audioBytes, err := io.ReadAll(io.LimitReader(file, maxTranscriptionBodyBytes+1))
		_ = file.Close()
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_file", "failed to read audio file")
			return
		}
		if len(audioBytes) > maxTranscriptionBodyBytes {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "request_too_large", "audio file exceeds 64 MiB")
			return
		}

		pcm, err := decodeWAVToPCM16kMono(audioBytes)
		if err != nil {
			log.Printf("[tsgo] POST %s: WAV decode failed: %v", r.URL.Path, err)
			writeJSONError(w, http.StatusBadRequest, "unsupported_audio", "unsupported or invalid audio (WAV only)")
			return
		}

		admitted, ok := runtime.tryBegin()
		if !ok {
			writeEngineStateResponse(w, admitted)
			return
		}
		var inferenceErr error
		defer func() { runtime.finish(inferenceErr) }()
		if !modelMatches(requestedModel, admitted.Model) {
			writeJSONError(w, http.StatusConflict, "model_not_loaded",
				"the requested transcription model is not the active model")
			return
		}

		translate := forceTranslate || r.FormValue("translate") == "true"
		start := time.Now()
		result, inferenceErr := runtime.transcribe(pcm, translate, r.FormValue("language"))
		if inferenceErr != nil {
			log.Printf("[tsgo] POST %s: transcribe failed: %v", r.URL.Path, inferenceErr)
			writeJSONError(w, http.StatusInternalServerError, "inference_failed", "transcription inference failed")
			return
		}

		responseLanguage := result.Language
		task := "transcribe"
		if forceTranslate {
			responseLanguage = "english"
			task = "translate"
		}

		segments := make([]map[string]any, len(result.Segments))
		for index, segment := range result.Segments {
			segments[index] = map[string]any{
				"text": segment.Text, "start": segment.Start, "end": segment.End,
			}
		}

		transcriptionMetaMu.RLock()
		hostname := deviceHostname
		transcriptionMetaMu.RUnlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"task":            task,
			"language":        responseLanguage,
			"duration":        float64(len(pcm)) / whisperSampleRate,
			"text":            result.Text,
			"segments":        segments,
			"model":           admitted.Model,
			"server":          hostname,
			"device":          "cpu",
			"cached":          false,
			"processing_time": time.Since(start).Seconds(),
		})
	})
}
