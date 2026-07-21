package tsgo

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// backendPort starts a fake whisper-server returning the given body/content-type and returns
// the local port newWhisperReverseProxy needs to dial it — mirrors how the real proxy reaches
// 127.0.0.1:WhisperPort, just on whatever port httptest.NewServer picks.
func backendPort(t *testing.T, contentType, body string) int {
	t.Helper()
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", contentType)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(backend.Close)

	_, portStr, err := net.SplitHostPort(backend.Listener.Addr().String())
	if err != nil {
		t.Fatalf("split backend addr: %v", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("parse backend port: %v", err)
	}
	return port
}

func TestAugmentTranscriptionResponse_AddsCompatibilityFields(t *testing.T) {
	port := backendPort(t, "application/json",
		`{"task":"transcribe","language":"en","duration":1.0,"text":" hello","segments":[]}`)

	SetActiveTranscriptionModel("ggml-tiny.bin")
	transcriptionMetaMu.Lock()
	deviceHostname = "test-phone"
	transcriptionMetaMu.Unlock()

	proxy := newWhisperReverseProxy(port)
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/transcriptions", nil)
	rec := httptest.NewRecorder()
	proxy.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}

	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v (body=%s)", err, rec.Body.String())
	}

	// sufficit-services-whisper's own responses always carry these — a client written against
	// that service (case-insensitive JSON deserialization) needs them present, even if the
	// values themselves are necessarily different on-device (see PLAN: Whisper API
	// compatibility).
	for _, field := range []string{"model", "processing_time", "device", "server", "cached"} {
		if _, ok := got[field]; !ok {
			t.Errorf("missing compatibility field %q in augmented response: %v", field, got)
		}
	}
	if got["model"] != "ggml-tiny.bin" {
		t.Errorf("model = %v, want ggml-tiny.bin", got["model"])
	}
	if got["server"] != "test-phone" {
		t.Errorf("server = %v, want test-phone", got["server"])
	}
	if got["device"] != "cpu" {
		t.Errorf("device = %v, want cpu", got["device"])
	}
	if got["cached"] != false {
		t.Errorf("cached = %v, want false", got["cached"])
	}

	// whisper.cpp's own fields must survive untouched.
	if got["text"] != " hello" {
		t.Errorf("text = %v, want ' hello' (original field lost)", got["text"])
	}
	if got["task"] != "transcribe" {
		t.Errorf("task = %v, want transcribe (original field lost)", got["task"])
	}
}

func TestAugmentTranscriptionResponse_LeavesNonJSONFormatsAlone(t *testing.T) {
	const vtt = "WEBVTT\n\n00:00.000 --> 00:01.000\nhello\n"
	port := backendPort(t, "text/vtt", vtt)

	proxy := newWhisperReverseProxy(port)
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/transcriptions", nil)
	rec := httptest.NewRecorder()
	proxy.ServeHTTP(rec, req)

	if rec.Body.String() != vtt {
		t.Errorf("vtt body was modified: got %q, want %q", rec.Body.String(), vtt)
	}
}

func TestAugmentTranscriptionResponse_LeavesNonTranscriptionJSONAlone(t *testing.T) {
	// e.g. whisper-server's /v1/audio/transcriptions/load or an error body with no "text" field
	// — must not gain the compatibility fields, since it isn't a transcription result.
	const errBody = `{"error":"no 'model' field in the request"}`
	port := backendPort(t, "application/json", errBody)

	proxy := newWhisperReverseProxy(port)
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/transcriptions", nil)
	rec := httptest.NewRecorder()
	proxy.ServeHTTP(rec, req)

	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if _, ok := got["model"]; ok {
		t.Errorf("non-transcription JSON body was augmented: %v", got)
	}
	if got["error"] != "no 'model' field in the request" {
		t.Errorf("original error field lost: %v", got)
	}
}

func TestTranslateProxy_RewritesPathAndInjectsField(t *testing.T) {
	var capturedPath string
	capturedFields := map[string]string{}
	var sawFile bool

	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.Path
		if err := r.ParseMultipartForm(10 << 20); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		for k, v := range r.MultipartForm.Value {
			if len(v) > 0 {
				capturedFields[k] = v[0]
			}
		}
		sawFile = len(r.MultipartForm.File["file"]) == 1

		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"task":"translate","language":"pt","duration":1.0,"text":" hola","segments":[]}`))
	}))
	defer backend.Close()

	_, portStr, err := net.SplitHostPort(backend.Listener.Addr().String())
	if err != nil {
		t.Fatalf("split backend addr: %v", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("parse backend port: %v", err)
	}

	SetActiveTranscriptionModel("ggml-tiny.bin")

	var reqBody bytes.Buffer
	mw := multipart.NewWriter(&reqBody)
	fw, err := mw.CreateFormFile("file", "sample.wav")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := fw.Write([]byte("fake-audio-bytes")); err != nil {
		t.Fatalf("write file part: %v", err)
	}
	if err := mw.WriteField("language", "pt"); err != nil {
		t.Fatalf("write language field: %v", err)
	}
	if err := mw.WriteField("translate", "false"); err != nil { // caller's value must be overridden
		t.Fatalf("write translate field: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close multipart writer: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/audio/translations", &reqBody)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	rec := httptest.NewRecorder()
	newWhisperTranslateReverseProxy(port).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
	}
	if capturedPath != "/v1/audio/transcriptions" {
		t.Errorf("backend saw path %q, want /v1/audio/transcriptions (whisper-server's real --inference-path)", capturedPath)
	}
	if capturedFields["translate"] != "true" {
		t.Errorf("translate field = %q, want true (caller's false must be overridden)", capturedFields["translate"])
	}
	if capturedFields["language"] != "pt" {
		t.Errorf("language field lost in rewrite: %v", capturedFields)
	}
	if !sawFile {
		t.Errorf("file part lost in multipart rewrite")
	}

	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if got["language"] != "english" {
		t.Errorf("response language = %v, want english (translation output language, not detected source)", got["language"])
	}
	if got["model"] != "ggml-tiny.bin" {
		t.Errorf("model field missing/wrong: %v", got)
	}
	if got["text"] != " hola" {
		t.Errorf("text = %v, want ' hola' (original field lost)", got["text"])
	}
}

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

func TestModelsListProxy_MergesAllInstalledTranscriptionEntries(t *testing.T) {
	// Real llama-server /v1/models shape (trimmed) — confirmed on-device, see PLAN: Whisper API
	// compatibility. Must survive completely unmodified; only new entries get appended, one per
	// installed transcription model — a device commonly has more than one downloaded at once
	// (see ModelsScreen's "Instalados" section), and all of them should be discoverable, not
	// just whichever one happens to be selected in the phone's own UI right now.
	const llamaBody = `{"object":"list","data":[{"id":"gte-qwen2-1.5b-instruct-q4_k_m-embedding","object":"model","created":1784042085,"owned_by":"llamacpp","meta":{"n_embd":1536}}]}`
	port := backendPort(t, "application/json", llamaBody)
	setInstalled(t, "ggml-tiny.bin", "ggml-base.bin")
	setInstalledEmbeddings(t) // only the one llama-server itself reports, nothing extra

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(port).ServeHTTP(rec, req)

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
		t.Fatalf("data has %d entries, want 3 (embedding + 2 transcription): %v", len(got.Data), got.Data)
	}

	embedding := got.Data[0]
	if embedding["id"] != "gte-qwen2-1.5b-instruct-q4_k_m-embedding" {
		t.Errorf("embedding entry id changed: %v", embedding)
	}
	meta, _ := embedding["meta"].(map[string]any)
	if meta == nil || meta["n_embd"] != float64(1536) {
		t.Errorf("embedding entry's meta.n_embd lost/changed: %v", embedding)
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

func TestModelsListProxy_AddsOtherInstalledEmbeddingsNotDuplicatingTheLoadedOne(t *testing.T) {
	// The device has two embedding models installed: gte-Qwen2 is currently loaded (appears in
	// llama-server's own real response already) and Qwen3-Embedding-0.6B is installed but not
	// resident. Only the second should get a synthesized entry appended — duplicating the first
	// under a Kotlin-guessed id would be worse than not listing it at all.
	const llamaBody = `{"object":"list","data":[{"id":"gte-qwen2-1.5b-instruct-q4_k_m-embedding","object":"model","created":1784042085,"owned_by":"llamacpp","meta":{"n_embd":1536}}]}`
	port := backendPort(t, "application/json", llamaBody)
	setInstalled(t) // no transcription models in this test
	setInstalledEmbeddings(t,
		embeddingModelEntry{ID: "gte-qwen2-1.5b-instruct-q4_k_m-embedding", Dimensions: 1536}, // already loaded — must be deduped
		embeddingModelEntry{ID: "qwen3-embedding-0.6b-q8_0-embedding", Dimensions: 1024},
	)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(port).ServeHTTP(rec, req)

	var got struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if len(got.Data) != 2 {
		t.Fatalf("data has %d entries, want 2 (loaded + one extra, no duplicate): %v", len(got.Data), got.Data)
	}
	if got.Data[0]["id"] != "gte-qwen2-1.5b-instruct-q4_k_m-embedding" {
		t.Errorf("first entry changed: %v", got.Data[0])
	}
	extra := got.Data[1]
	if extra["id"] != "qwen3-embedding-0.6b-q8_0-embedding" {
		t.Errorf("extra entry id = %v, want qwen3-embedding-0.6b-q8_0-embedding", extra["id"])
	}
	meta, _ := extra["meta"].(map[string]any)
	if meta == nil || meta["n_embd"] != float64(1024) {
		t.Errorf("extra entry meta.n_embd = %v, want 1024", extra["meta"])
	}
}

func TestModelsListProxy_EmbeddingEntryOmitsMetaWhenDimensionsUnknown(t *testing.T) {
	const llamaBody = `{"object":"list","data":[]}`
	port := backendPort(t, "application/json", llamaBody)
	setInstalled(t)
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "some-random-hf-model", Dimensions: 0})

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(port).ServeHTTP(rec, req)

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

func TestModelsListProxy_NoTranscriptionEntryWhenNoneInstalled(t *testing.T) {
	const llamaBody = `{"object":"list","data":[{"id":"some-model","object":"model"}]}`
	port := backendPort(t, "application/json", llamaBody)
	setInstalled(t) // nothing installed
	setInstalledEmbeddings(t)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(port).ServeHTTP(rec, req)

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

func TestModelsListProxy_FallsBackToInstalledOnlyWhenEmbeddingUnreachable(t *testing.T) {
	// Simulates the mutual-exclusion case: whisper is the resident engine, llama-server isn't
	// running at all, so dialing ModelPort fails outright (not just a non-200 response) — every
	// installed embedding model must show up here too, not just transcription ones, since
	// there's no "already in the response" entry to dedupe against.
	setInstalled(t, "ggml-base.bin", "ggml-tiny.bin")
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "gte-qwen2-1.5b-instruct-q4_k_m-embedding", Dimensions: 1536})
	const unreachablePort = 1 // reserved, guaranteed nothing listens here

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(unreachablePort).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (installed-only fallback), body=%s", rec.Code, rec.Body.String())
	}
	var got struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("response not valid JSON: %v", err)
	}
	if len(got.Data) != 3 {
		t.Fatalf("data has %d entries, want 3 (1 embedding + 2 transcription): %v", len(got.Data), got.Data)
	}
	if got.Data[0]["id"] != "gte-qwen2-1.5b-instruct-q4_k_m-embedding" {
		t.Errorf("embedding entry id = %v", got.Data[0]["id"])
	}
	if got.Data[1]["id"] != "ggml-base" || got.Data[2]["id"] != "ggml-tiny" {
		t.Errorf("transcription entries = %v, %v", got.Data[1]["id"], got.Data[2]["id"])
	}
}

func TestModelsListProxy_Returns503WhenNothingInstalledAndEmbeddingUnreachable(t *testing.T) {
	setInstalled(t)
	setInstalledEmbeddings(t)
	const unreachablePort = 1

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListProxy(unreachablePort).ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 (nothing installed, nothing reachable)", rec.Code)
	}
}
