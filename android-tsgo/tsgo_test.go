package tsgo

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func setInstalled(t *testing.T, names ...string) {
	t.Helper()
	b, err := json.Marshal(names)
	if err != nil {
		t.Fatalf("marshal installed names: %v", err)
	}
	SetInstalledTranscriptionModels(string(b))
}

// setInstalledEmbeddings resets installedEmbeddingModels — every test that depends on its
// starting state (most, since it's a shared package var) must call this explicitly, since Go
// tests in one package run sequentially, not isolated.
func setInstalledEmbeddings(t *testing.T, entries ...embeddingModelEntry) {
	t.Helper()
	if entries == nil {
		entries = []embeddingModelEntry{}
	}
	b, err := json.Marshal(entries)
	if err != nil {
		t.Fatalf("marshal installed embeddings: %v", err)
	}
	SetInstalledEmbeddingModels(string(b))
}

func TestModelsListHandler_ListsAllInstalledEmbeddingAndTranscriptionModels(t *testing.T) {
	// newModelsListHandler builds the whole catalog from installedEmbeddingModels +
	// installedTranscriptionModelNames directly — no live dial to merge against (see its doc:
	// that used to be a real llama-server subprocess, now it'd just be this same process
	// answering itself). Kotlin already includes the currently-loaded embedding model in
	// installedEmbeddingModels (SyncForegroundService pushes the full installed list
	// unconditionally), so there's nothing left to dedupe.
	setInstalled(t, "ggml-tiny.bin", "ggml-base.bin")
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "gte-qwen2-1.5b-instruct-q4_k_m-embedding", Dimensions: 1536})

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
	}

	var got struct {
		Object string           `json:"object"`
		Data   []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if len(got.Data) != 3 {
		t.Fatalf("data has %d entries, want 3 (1 embedding + 2 transcription): %v", len(got.Data), got.Data)
	}

	embedding := got.Data[0]
	if embedding["id"] != "gte-qwen2-1.5b-instruct-q4_k_m-embedding" {
		t.Errorf("embedding entry id = %v", embedding["id"])
	}
	meta, _ := embedding["meta"].(map[string]any)
	if meta == nil || meta["n_embd"] != float64(1536) {
		t.Errorf("embedding entry meta.n_embd = %v, want 1536", embedding["meta"])
	}

	wantIDs := []string{"ggml-tiny", "ggml-base"}
	for i, wantID := range wantIDs {
		entry := got.Data[i+1]
		if entry["id"] != wantID {
			t.Errorf("transcription entry %d id = %v, want %s", i, entry["id"], wantID)
		}
		caps, _ := entry["capabilities"].([]any)
		if len(caps) != 1 || caps[0] != "transcription" {
			t.Errorf("transcription entry %d capabilities = %v, want [transcription]", i, entry["capabilities"])
		}
	}
}

func TestModelsListHandler_EmbeddingEntryOmitsMetaWhenDimensionsUnknown(t *testing.T) {
	setInstalled(t)
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "some-random-hf-model", Dimensions: 0})

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, req)

	var got struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if len(got.Data) != 1 {
		t.Fatalf("data has %d entries, want 1: %v", len(got.Data), got.Data)
	}
	if _, hasMeta := got.Data[0]["meta"]; hasMeta {
		t.Errorf("entry has meta with unknown dimensions: %v", got.Data[0])
	}
}

func TestModelsListHandler_NoTranscriptionEntryWhenNoneInstalled(t *testing.T) {
	setInstalled(t) // nothing installed
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "some-model", Dimensions: 0})

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, req)

	var got struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if len(got.Data) != 1 {
		t.Errorf("data has %d entries, want 1 (no transcription entry should be added): %v", len(got.Data), got.Data)
	}
}

func TestModelsListHandler_Returns503WhenNothingInstalled(t *testing.T) {
	setInstalled(t)
	setInstalledEmbeddings(t)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 (nothing installed)", rec.Code)
	}
}

// Real transcription inference only exists under the "android" build tag (cgo/whisper.cpp,
// arm64-only static libs) — isTranscriptionModelLoaded()'s host stub always reports false, so
// these are the only paths exercisable on the host, same limitation newEmbeddingsHandler already
// has (see embedding_http.go: no tests at all for the same reason). Real behavior is verified
// on-device (see PLAN: native transcription migration).

func TestTranscriptionsHandler_NoModelLoaded503(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/transcriptions", nil)
	rec := httptest.NewRecorder()
	newTranscriptionsHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 (no model loaded)", rec.Code)
	}
}

func TestTranscriptionsHandler_WrongMethod(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/v1/audio/transcriptions", nil)
	rec := httptest.NewRecorder()
	newTranscriptionsHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want 405", rec.Code)
	}
}

func TestTranslationsHandler_NoModelLoaded503(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/translations", nil)
	rec := httptest.NewRecorder()
	newTranslationsHandler().ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 (no model loaded)", rec.Code)
	}
}
