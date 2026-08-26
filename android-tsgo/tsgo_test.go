package tsgo

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
)

func resetEngineControllers() {
	embeddingEngine = newEngineController()
	transcriptionEngine = newEngineController()
}

func setInstalledEmbeddings(t *testing.T, entries ...embeddingModelEntry) {
	t.Helper()
	if entries == nil {
		entries = []embeddingModelEntry{}
	}
	payload, err := json.Marshal(entries)
	if err != nil {
		t.Fatal(err)
	}
	SetInstalledEmbeddingModels(string(payload))
}

func setEngineReady(engine *engineController, model string, dimensions int) {
	engine.beginLifecycle()
	engine.finishLifecycle(model, dimensions, nil)
}

func TestModelsListHandlerListsOnlyResidentModels(t *testing.T) {
	resetEngineControllers()
	setInstalledEmbeddings(t,
		embeddingModelEntry{ID: "active-embedding", Dimensions: 1024, SupportsDimensions: true, MinimumDimensions: 32},
		embeddingModelEntry{ID: "installed-but-idle", Dimensions: 1536},
	)
	setEngineReady(embeddingEngine, "active-embedding", 1024)
	setEngineReady(transcriptionEngine, "ggml-small-q8_0", 0)

	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}

	var got struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Data) != 2 {
		t.Fatalf("models=%v, want exactly the two resident engines", got.Data)
	}
	if got.Data[0]["id"] != "active-embedding" || got.Data[1]["id"] != "ggml-small-q8_0" {
		t.Fatalf("unexpected models: %v", got.Data)
	}
	parameters, _ := got.Data[0]["supported_parameters"].([]any)
	if len(parameters) != 1 || parameters[0] != "dimensions" {
		t.Fatalf("supported_parameters=%v", got.Data[0]["supported_parameters"])
	}
	meta, _ := got.Data[0]["meta"].(map[string]any)
	if meta["n_embd"] != float64(1024) || meta["min_dimensions"] != float64(32) {
		t.Fatalf("meta=%v", meta)
	}
}

func TestModelsListHandlerIncludesBusyResidentModel(t *testing.T) {
	resetEngineControllers()
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "active-embedding", Dimensions: 3})
	setEngineReady(embeddingEngine, "active-embedding", 3)
	if _, ok := embeddingEngine.tryBeginInference(); !ok {
		t.Fatal("failed to reserve engine")
	}
	defer embeddingEngine.finishInference(nil)

	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/v1/models", nil))
	if rec.Code != http.StatusOK || !bytes.Contains(rec.Body.Bytes(), []byte(`"status":"busy"`)) {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestModelsListHandlerReturns503WithoutResidentModel(t *testing.T) {
	resetEngineControllers()
	setInstalledEmbeddings(t, embeddingModelEntry{ID: "downloaded-only", Dimensions: 3})
	rec := httptest.NewRecorder()
	newModelsListHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/v1/models", nil))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestHealthReportsBothEngineStates(t *testing.T) {
	resetEngineControllers()
	setEngineReady(embeddingEngine, "active-embedding", 3)
	rec := httptest.NewRecorder()
	newHealthHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	if rec.Code != http.StatusOK || !bytes.Contains(rec.Body.Bytes(), []byte(`"model":"active-embedding"`)) {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func audioMultipartRequest(t *testing.T, model string) *http.Request {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	file, err := writer.CreateFormFile("file", "sample.wav")
	if err != nil {
		t.Fatal(err)
	}
	wav := buildWAV(t, 16000, 1, 16, []int32{0, 0, 0, 0})
	if _, err := file.Write(wav); err != nil {
		t.Fatal(err)
	}
	if model != "" {
		_ = writer.WriteField("model", model)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/v1/audio/transcriptions", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	return req
}

func readyAudioRuntime() (audioHTTPRuntime, *engineController, *int) {
	controller := newEngineController()
	setEngineReady(controller, "ggml-small-q8_0", 0)
	calls := 0
	return audioHTTPRuntime{
		snapshot: controller.snapshot,
		tryBegin: controller.tryBeginInference,
		finish:   controller.finishInference,
		transcribe: func([]float32, bool, string) (transcriptionResult, error) {
			calls++
			return transcriptionResult{Text: "teste", Language: "pt"}, nil
		},
	}, controller, &calls
}

func TestTranscriptionsHandlerValidatesModel(t *testing.T) {
	runtime, _, calls := readyAudioRuntime()
	rec := httptest.NewRecorder()
	newAudioHandlerWithRuntime(false, runtime).ServeHTTP(rec, audioMultipartRequest(t, "wrong-model"))
	if rec.Code != http.StatusConflict || *calls != 0 {
		t.Fatalf("status=%d calls=%d body=%s", rec.Code, *calls, rec.Body.String())
	}
}

func TestTranscriptionsHandlerReturns429WhenBusy(t *testing.T) {
	runtime, controller, calls := readyAudioRuntime()
	if _, ok := controller.tryBeginInference(); !ok {
		t.Fatal("failed to reserve engine")
	}
	defer controller.finishInference(nil)
	rec := httptest.NewRecorder()
	newAudioHandlerWithRuntime(false, runtime).ServeHTTP(rec, audioMultipartRequest(t, "ggml-small-q8_0"))
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" || *calls != 0 {
		t.Fatalf("status=%d retry=%q calls=%d body=%s", rec.Code, rec.Header().Get("Retry-After"), *calls, rec.Body.String())
	}
}

func TestTranscriptionsHandlerReturnsOpenAICompatibleResult(t *testing.T) {
	runtime, _, calls := readyAudioRuntime()
	rec := httptest.NewRecorder()
	newAudioHandlerWithRuntime(false, runtime).ServeHTTP(rec, audioMultipartRequest(t, "ggml-small-q8_0"))
	if rec.Code != http.StatusOK || *calls != 1 || !bytes.Contains(rec.Body.Bytes(), []byte(`"text":"teste"`)) {
		t.Fatalf("status=%d calls=%d body=%s", rec.Code, *calls, rec.Body.String())
	}
}

func TestTranscriptionsHandlerWrongMethod(t *testing.T) {
	runtime, _, _ := readyAudioRuntime()
	rec := httptest.NewRecorder()
	newAudioHandlerWithRuntime(false, runtime).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/v1/audio/transcriptions", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status=%d", rec.Code)
	}
}
