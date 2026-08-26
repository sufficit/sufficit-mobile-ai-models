package tsgo

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func readyEmbeddingRuntime(t *testing.T, supportsDimensions bool) (embeddingHTTPRuntime, *engineController, *int) {
	t.Helper()
	controller := newEngineController()
	controller.beginLifecycle()
	controller.finishLifecycle("test-embedding", 3, nil)
	calls := 0
	return embeddingHTTPRuntime{
		snapshot: controller.snapshot,
		tryBegin: controller.tryBeginInference,
		finish:   controller.finishInference,
		embedText: func(input string) (embeddingInferenceResult, error) {
			calls++
			return embeddingInferenceResult{Vector: []float32{3, 4, 12}, Tokens: 5}, nil
		},
		modelMetadata: func(id string, dimensions int) embeddingModelEntry {
			return embeddingModelEntry{
				ID: id, Dimensions: dimensions, SupportsDimensions: supportsDimensions,
				MinimumDimensions: 1,
			}
		},
	}, controller, &calls
}

func TestParseRequestedDimensions(t *testing.T) {
	tests := []struct {
		name        string
		raw         string
		want        int
		wantPresent bool
		wantError   bool
	}{
		{name: "omitted", wantPresent: false},
		{name: "integer", raw: "512", want: 512, wantPresent: true},
		{name: "zero", raw: "0", wantPresent: true, wantError: true},
		{name: "negative", raw: "-1", wantPresent: true, wantError: true},
		{name: "fraction", raw: "1.5", wantPresent: true, wantError: true},
		{name: "string", raw: `"512"`, wantPresent: true, wantError: true},
		{name: "null", raw: "null", wantPresent: true, wantError: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, present, err := parseRequestedDimensions(json.RawMessage(test.raw))
			if got != test.want || present != test.wantPresent || (err != nil) != test.wantError {
				t.Fatalf("got (%d, %t, %v), want (%d, %t, error=%t)",
					got, present, err, test.want, test.wantPresent, test.wantError)
			}
		})
	}
}

func TestParseEmbeddingInputs(t *testing.T) {
	for _, test := range []struct {
		raw  string
		want int
		err  bool
	}{
		{raw: `"one"`, want: 1},
		{raw: `["one","two"]`, want: 2},
		{raw: `[]`, err: true},
		{raw: `[1,2]`, err: true},
		{raw: `""`, err: true},
	} {
		got, err := parseEmbeddingInputs(json.RawMessage(test.raw))
		if (err != nil) != test.err || len(got) != test.want {
			t.Errorf("parseEmbeddingInputs(%s) = %v, %v", test.raw, got, err)
		}
	}
}

func TestTruncateAndNormalizeEmbedding(t *testing.T) {
	original := []float32{3, 4, 12}
	resized, err := truncateAndNormalizeEmbedding(original, 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(resized) != 2 || math.Abs(float64(resized[0]-0.6)) > 1e-6 || math.Abs(float64(resized[1]-0.8)) > 1e-6 {
		t.Fatalf("resized = %v", resized)
	}
	if original[0] != 3 || original[1] != 4 {
		t.Fatalf("input vector was mutated: %v", original)
	}
}

func TestTruncateAndNormalizeEmbeddingRejectsInvalidDimensions(t *testing.T) {
	for _, dimensions := range []int{-1, 0, 4} {
		if _, err := truncateAndNormalizeEmbedding([]float32{1, 2, 3}, dimensions); err == nil {
			t.Errorf("dimensions %d accepted", dimensions)
		}
	}
}

func TestEmbeddingsHandlerBatchDimensionsAndUsage(t *testing.T) {
	runtime, _, calls := readyEmbeddingRuntime(t, true)
	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(
		`{"model":"test-embedding","input":["one","two"],"dimensions":2,"encoding_format":"float"}`,
	))
	rec := httptest.NewRecorder()
	newEmbeddingsHandlerWithRuntime(runtime).ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body=%s", rec.Code, rec.Body.String())
	}
	if *calls != 2 {
		t.Fatalf("embed calls = %d, want 2", *calls)
	}
	var response struct {
		Model string `json:"model"`
		Data  []struct {
			Index     int       `json:"index"`
			Embedding []float32 `json:"embedding"`
		} `json:"data"`
		Usage struct {
			PromptTokens int `json:"prompt_tokens"`
			TotalTokens  int `json:"total_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Model != "test-embedding" || len(response.Data) != 2 || response.Usage.TotalTokens != 10 {
		t.Fatalf("unexpected response: %+v", response)
	}
	if len(response.Data[0].Embedding) != 2 || response.Data[1].Index != 1 {
		t.Fatalf("unexpected data: %+v", response.Data)
	}
}

func TestEmbeddingsHandlerRejectsWrongModelBeforeInference(t *testing.T) {
	runtime, _, calls := readyEmbeddingRuntime(t, true)
	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(
		`{"model":"another-model","input":"test"}`,
	))
	rec := httptest.NewRecorder()
	newEmbeddingsHandlerWithRuntime(runtime).ServeHTTP(rec, req)
	if rec.Code != http.StatusConflict || *calls != 0 {
		t.Fatalf("status=%d calls=%d body=%s", rec.Code, *calls, rec.Body.String())
	}
}

func TestEmbeddingsHandlerReturns429WhenBusy(t *testing.T) {
	runtime, controller, calls := readyEmbeddingRuntime(t, true)
	if _, ok := controller.tryBeginInference(); !ok {
		t.Fatal("failed to reserve controller")
	}
	defer controller.finishInference(nil)
	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(`{"input":"test"}`))
	rec := httptest.NewRecorder()
	newEmbeddingsHandlerWithRuntime(runtime).ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" || *calls != 0 {
		t.Fatalf("status=%d retry=%q calls=%d body=%s", rec.Code, rec.Header().Get("Retry-After"), *calls, rec.Body.String())
	}
}

func TestEmbeddingsHandlerRejectsUnsupportedOptions(t *testing.T) {
	for _, test := range []struct {
		name string
		body string
	}{
		{name: "dimensions without MRL", body: `{"input":"test","dimensions":2}`},
		{name: "base64 encoding", body: `{"input":"test","encoding_format":"base64"}`},
		{name: "above native", body: `{"input":"test","dimensions":4}`},
	} {
		t.Run(test.name, func(t *testing.T) {
			runtime, _, calls := readyEmbeddingRuntime(t, false)
			req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(test.body))
			rec := httptest.NewRecorder()
			newEmbeddingsHandlerWithRuntime(runtime).ServeHTTP(rec, req)
			if rec.Code != http.StatusBadRequest || *calls != 0 {
				t.Fatalf("status=%d calls=%d body=%s", rec.Code, *calls, rec.Body.String())
			}
		})
	}
}

func TestEmbeddingsHandlerRejectsOversizedBody(t *testing.T) {
	runtime, _, calls := readyEmbeddingRuntime(t, true)
	req := httptest.NewRequest(http.MethodPost, "/v1/embeddings", strings.NewReader(strings.Repeat("x", maxEmbeddingBodyBytes+1)))
	rec := httptest.NewRecorder()
	newEmbeddingsHandlerWithRuntime(runtime).ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge || *calls != 0 {
		t.Fatalf("status=%d calls=%d", rec.Code, *calls)
	}
}
