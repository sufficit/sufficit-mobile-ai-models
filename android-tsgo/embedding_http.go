package tsgo

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
)

const maxEmbeddingBodyBytes = 1 << 20 // 1 MiB

type embeddingsRequestBody struct {
	Model          string          `json:"model"`
	Input          json.RawMessage `json:"input"`
	Dimensions     json.RawMessage `json:"dimensions"`
	EncodingFormat string          `json:"encoding_format"`
}

type embeddingInferenceResult struct {
	Vector []float32
	Tokens int
}

type embeddingHTTPRuntime struct {
	snapshot      func() engineSnapshot
	tryBegin      func() (engineSnapshot, bool)
	finish        func(error)
	embedText     func(string) (embeddingInferenceResult, error)
	modelMetadata func(string, int) embeddingModelEntry
}

var errInvalidDimensions = errors.New("invalid dimensions")

func parseRequestedDimensions(raw json.RawMessage) (dimensions int, present bool, err error) {
	if len(raw) == 0 {
		return 0, false, nil
	}
	if err := json.Unmarshal(raw, &dimensions); err != nil || dimensions < 1 {
		return 0, true, errInvalidDimensions
	}
	return dimensions, true, nil
}

func parseEmbeddingInputs(raw json.RawMessage) ([]string, error) {
	var single string
	if err := json.Unmarshal(raw, &single); err == nil {
		if strings.TrimSpace(single) == "" {
			return nil, errors.New("input cannot be empty")
		}
		return []string{single}, nil
	}

	var batch []string
	if err := json.Unmarshal(raw, &batch); err != nil || len(batch) == 0 {
		return nil, errors.New("input must be a string or a non-empty array of strings")
	}
	for _, input := range batch {
		if strings.TrimSpace(input) == "" {
			return nil, errors.New("input entries cannot be empty")
		}
	}
	return batch, nil
}

func writeJSONError(w http.ResponseWriter, status int, code, detail string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code, "detail": detail})
}

func writeInvalidDimensions(w http.ResponseWriter, minimum, maximum int) {
	detail := "dimensions must be a positive integer"
	if maximum > 0 {
		if minimum < 1 {
			minimum = 1
		}
		detail = fmt.Sprintf("dimensions must be an integer between %d and %d", minimum, maximum)
	}
	writeJSONError(w, http.StatusBadRequest, "invalid_dimensions", detail)
}

func newEmbeddingsHandler() http.Handler {
	return newEmbeddingsHandlerWithRuntime(embeddingHTTPRuntime{
		snapshot:      embeddingEngine.snapshot,
		tryBegin:      embeddingEngine.tryBeginInference,
		finish:        embeddingEngine.finishInference,
		embedText:     embedWithUsage,
		modelMetadata: embeddingMetadata,
	})
}

func newEmbeddingsHandlerWithRuntime(runtime embeddingHTTPRuntime) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		body, err := io.ReadAll(io.LimitReader(r.Body, maxEmbeddingBodyBytes+1))
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "failed to read request body")
			return
		}
		if len(body) > maxEmbeddingBodyBytes {
			writeJSONError(w, http.StatusRequestEntityTooLarge, "request_too_large", "request body exceeds 1 MiB")
			return
		}

		var req embeddingsRequestBody
		if err := json.Unmarshal(body, &req); err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "request body must be valid JSON")
			return
		}
		inputs, err := parseEmbeddingInputs(req.Input)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_input", err.Error())
			return
		}
		if req.EncodingFormat != "" && !strings.EqualFold(req.EncodingFormat, "float") {
			writeJSONError(w, http.StatusBadRequest, "unsupported_parameter", "encoding_format supports only \"float\"")
			return
		}

		dimensions, hasDimensions, err := parseRequestedDimensions(req.Dimensions)
		if err != nil {
			writeInvalidDimensions(w, 1, 0)
			return
		}

		snapshot := runtime.snapshot()
		if snapshot.State != engineReady {
			writeEngineStateResponse(w, snapshot)
			return
		}
		if !modelMatches(req.Model, snapshot.Model) {
			writeJSONError(w, http.StatusConflict, "model_not_loaded",
				fmt.Sprintf("requested model %q is not loaded; active model is %q", req.Model, snapshot.Model))
			return
		}

		metadata := runtime.modelMetadata(snapshot.Model, snapshot.Dimensions)
		minimumDimensions := metadata.MinimumDimensions
		if minimumDimensions < 1 {
			minimumDimensions = 1
		}
		if hasDimensions {
			if snapshot.Dimensions > 0 && dimensions > snapshot.Dimensions {
				writeInvalidDimensions(w, minimumDimensions, snapshot.Dimensions)
				return
			}
			if dimensions < minimumDimensions {
				writeInvalidDimensions(w, minimumDimensions, snapshot.Dimensions)
				return
			}
			if dimensions != snapshot.Dimensions && !metadata.SupportsDimensions {
				writeJSONError(w, http.StatusBadRequest, "unsupported_parameter",
					fmt.Sprintf("model %q does not support reduced dimensions", snapshot.Model))
				return
			}
		}

		admitted, ok := runtime.tryBegin()
		if !ok {
			writeEngineStateResponse(w, admitted)
			return
		}
		var inferenceErr error
		defer func() { runtime.finish(inferenceErr) }()
		// The active model may have changed while the request body was being read.
		// Admission freezes lifecycle changes, so validate once more against the
		// definitive model that will execute this request.
		if !modelMatches(req.Model, admitted.Model) {
			writeJSONError(w, http.StatusConflict, "model_not_loaded",
				fmt.Sprintf("requested model %q is not loaded; active model is %q", req.Model, admitted.Model))
			return
		}
		metadata = runtime.modelMetadata(admitted.Model, admitted.Dimensions)
		minimumDimensions = metadata.MinimumDimensions
		if minimumDimensions < 1 {
			minimumDimensions = 1
		}
		if hasDimensions {
			if admitted.Dimensions > 0 && dimensions > admitted.Dimensions {
				writeInvalidDimensions(w, minimumDimensions, admitted.Dimensions)
				return
			}
			if dimensions < minimumDimensions {
				writeInvalidDimensions(w, minimumDimensions, admitted.Dimensions)
				return
			}
			if dimensions != admitted.Dimensions && !metadata.SupportsDimensions {
				writeJSONError(w, http.StatusBadRequest, "unsupported_parameter",
					fmt.Sprintf("model %q does not support reduced dimensions", admitted.Model))
				return
			}
		}

		data := make([]map[string]any, 0, len(inputs))
		totalTokens := 0
		for index, input := range inputs {
			result, embedErr := runtime.embedText(input)
			if embedErr != nil {
				inferenceErr = embedErr
				log.Printf("[tsgo] POST /v1/embeddings: embed failed: %v", embedErr)
				writeJSONError(w, http.StatusInternalServerError, "inference_failed", "embedding inference failed")
				return
			}
			vector := result.Vector
			if hasDimensions && dimensions < len(vector) {
				vector, inferenceErr = truncateAndNormalizeEmbedding(vector, dimensions)
				if inferenceErr != nil {
					writeInvalidDimensions(w, minimumDimensions, len(result.Vector))
					return
				}
			}
			totalTokens += result.Tokens
			data = append(data, map[string]any{
				"object": "embedding", "index": index, "embedding": vector,
			})
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"object": "list",
			"model":  admitted.Model,
			"data":   data,
			"usage": map[string]int{
				"prompt_tokens": totalTokens,
				"total_tokens":  totalTokens,
			},
		})
	})
}
