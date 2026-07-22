package tsgo

// POST /v1/embeddings handler — portable (no build tag): calls the platform-specific
// embed()/isEmbeddingModelLoaded()/embeddingModelID() (real cgo/llama.cpp implementation in
// embedding.go under "android", stub in embedding_stub.go otherwise) so buildRouter (also
// portable, tsgo.go) can reference this unconditionally regardless of which platform it's
// actually built for.

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
)

// embeddingsRequestBody is the OpenAI-compatible request shape this endpoint accepts — same
// contract llama-server's own /v1/embeddings understood, so no client (LocalEmbeddingTester.kt,
// the backend's real dispatch) needs to change. "model" is accepted but ignored: only one
// embedding model is ever resident at a time (mutual exclusion, see ModelRuntimeService kdoc —
// still true under the native architecture, just enforced by LoadEmbeddingModel's swap instead
// of subprocess kill/restart), so there's nothing to select between.
type embeddingsRequestBody struct {
	Input string `json:"input"`
}

// newEmbeddingsHandler serves POST /v1/embeddings entirely in-process via embed() — the
// in-process replacement for reverse-proxying to a spawned llama-server subprocess. Registered
// in buildRouter ahead of the "/" catch-all (newReverseProxy(ModelPort)), which no longer has
// anything embeddings-related to proxy to once LlamaServerManager's subprocess is removed
// (PLAN: native inference migration, phase 1 — see LoadEmbeddingModel's doc).
func newEmbeddingsHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20)) // 1MiB cap, same spirit as the old proxy's body limits
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"failed to read request body"}`))
			return
		}

		var req embeddingsRequestBody
		if err := json.Unmarshal(body, &req); err != nil || req.Input == "" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":"missing or invalid \"input\""}`))
			return
		}

		if !isEmbeddingModelLoaded() {
			log.Printf("[tsgo] POST /v1/embeddings: no embedding model loaded")
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(noModelBody))
			return
		}

		vec, err := embed(req.Input)
		if err != nil {
			log.Printf("[tsgo] POST /v1/embeddings: embed failed: %v", err)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte(`{"error":"embedding inference failed"}`))
			return
		}

		out, err := json.Marshal(map[string]any{
			"object": "list",
			"model":  embeddingModelID(),
			"data": []map[string]any{
				{
					"object":    "embedding",
					"index":     0,
					"embedding": vec,
				},
			},
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
