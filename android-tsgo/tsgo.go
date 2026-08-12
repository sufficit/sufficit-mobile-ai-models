// Package tsgo hosts the on-device OpenAI-compatible inference API. Networking is
// deliberately outside this package: sufficit-mobile-vpn owns the only Android
// VpnService and routes the device VPN address to this process' TCP listener.
package tsgo

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ModelPort is the port this process listens on locally and the port exposed on the
// tailnet interface — serves embeddings, transcription, /v1/models and /health all
// from the one in-process router (buildRouter). Matches PHONE_PORT /
// DeviceModelServerPort convention used by the adb-tethered POC and the backend's
// TailscaleClientOptions.
const ModelPort = 8090

var (
	mu         sync.Mutex
	httpServer *http.Server

	transcriptionMetaMu              sync.RWMutex
	activeModelName                  string
	installedTranscriptionModelNames []string
	deviceHostname                   string
)

// Status is returned by Status() as JSON — gomobile can't export structs directly,
// only primitive types, so the Kotlin side deserializes this string.
type Status struct {
	Running  bool   `json:"running"`
	Hostname string `json:"hostname"`
	Error    string `json:"error,omitempty"`
}

// Start exposes the inference router on every local interface. Binding 0.0.0.0 is
// intentional: Android's VpnService owns the VPN interface and the Headscale ACL limits
// remote reachability to authorized peers. Loopback callers continue to use 127.0.0.1:8090.
func Start(hostname string) string {
	mu.Lock()
	defer mu.Unlock()

	if httpServer != nil {
		return currentStatusLocked()
	}

	ln, err := net.Listen("tcp", "0.0.0.0:"+strconv.Itoa(ModelPort))
	if err != nil {
		return toJSON(Status{Error: fmt.Sprintf("listen: %v", err)})
	}

	transcriptionMetaMu.Lock()
	deviceHostname = hostname
	transcriptionMetaMu.Unlock()

	router := buildRouter()
	httpServer = &http.Server{Handler: router}
	server := httpServer

	go func() {
		if err := server.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Printf("[tsgo] http serve error: %v", err)
		}
	}()
	log.Printf("[tsgo] inference API listening on 0.0.0.0:%d", ModelPort)

	return currentStatusLocked()
}

// Stop closes only the inference listener. The shared VPN remains available to other apps.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if httpServer != nil {
		_ = httpServer.Close()
		httpServer = nil
	}
}

// Status returns the current connection state as a JSON string (see Status struct).
func StatusJSON() string {
	mu.Lock()
	defer mu.Unlock()
	return currentStatusLocked()
}

func currentStatusLocked() string {
	if httpServer == nil {
		return toJSON(Status{Running: false})
	}
	return toJSON(Status{Running: true, Hostname: deviceHostname})
}

// SetActiveTranscriptionModel records which whisper.cpp model file is the device's configured
// transcription model. Called from the Kotlin side ONLY by SyncForegroundService, in response
// to ModelRuntimeService's ACTION_STATUS_CHANGED broadcast — not by WhisperServerManager
// directly, even though it's the piece that actually knows which model just started. Reason:
// tsgo's Go globals are per-OS-process, and WhisperServerManager runs in :modelruntime while
// the HTTP API that reads activeModelName runs in :sync — two separate processes, two separate
// copies of every Go global, no shared memory. A call from :modelruntime's copy of this
// function would silently set a value nothing ever reads. SyncForegroundService also queries
// ModelRuntimeService for the current status once at its own startup (not just reactively),
// so this reflects "what's configured" (ModelRegistry's persisted value) rather than "what's
// resident in RAM at the exact instant of the last broadcast" — mutual exclusion (see
// ModelRuntimeService kdoc) means whisper-server is frequently not the resident engine, but the
// backend's model-discovery poll (GET /v1/models, see newModelsListProxy) should still list a
// transcription capability if the user has ever configured one.
//
// Fills the "model" field in augmentTranscriptionResponse (whisper-server's own JSON output has
// no such field) — which model actually answered THIS request. For the full device catalog
// (every installed transcription model, not just the one currently resident), see
// SetInstalledTranscriptionModels.
func SetActiveTranscriptionModel(name string) {
	transcriptionMetaMu.Lock()
	activeModelName = name
	transcriptionMetaMu.Unlock()
}

// SetInstalledTranscriptionModels records every whisper.cpp model file installed on this
// device — namesJSON is a JSON string array, e.g. ["ggml-tiny.bin","ggml-base.bin"]. Unlike
// SetActiveTranscriptionModel (one configured/resident model), a device can have several
// Whisper models downloaded at once (see ModelsScreen's "Instalados" section), and the
// backend's discovery poll (GET /v1/models, see newModelsListProxy) should list all of them so
// whoever's configuring routing on that side can choose — not just whichever one happens to be
// selected in the phone's own UI right now. Same cross-process reasoning as
// SetActiveTranscriptionModel: called by SyncForegroundService (:sync), which gets the list via
// a plain filesystem scan (ModelRegistry.installedModels — safe to call from either process,
// unlike the SharedPreferences-backed "active model" value), not by anything in :modelruntime.
func SetInstalledTranscriptionModels(namesJSON string) {
	var names []string
	if err := json.Unmarshal([]byte(namesJSON), &names); err != nil {
		log.Printf("[tsgo] SetInstalledTranscriptionModels: invalid JSON, ignoring: %v", err)
		return
	}
	transcriptionMetaMu.Lock()
	installedTranscriptionModelNames = names
	transcriptionMetaMu.Unlock()
}

// LoadEmbeddingModel loads modelPath as the resident in-process embedding model (see
// embedding.go) — replaces LlamaServerManager.start()/switchTo() from the old
// subprocess-per-engine architecture. Must be called from :sync, same cross-process reasoning
// as SetActiveTranscriptionModel: the HTTP server that actually serves /v1/embeddings
// (buildRouter, started by SyncForegroundService) runs in :sync,
// and Go globals — including the loaded llama_context this holds onto — don't cross process
// boundaries. A call made from :modelruntime would load a model into a copy of this package
// nothing ever serves requests from. Returns "" on success, an error message otherwise.
func LoadEmbeddingModel(modelPath string) string {
	if err := loadEmbeddingModel(modelPath); err != nil {
		return err.Error()
	}
	return ""
}

// UnloadEmbeddingModel frees the resident embedding model, if any. Safe to call unconditionally
// (e.g. before loading a different one, or when the user deletes the active model).
func UnloadEmbeddingModel() {
	unloadEmbeddingModel()
}

// EmbeddingModelLoaded reports whether an embedding model is currently resident in this process.
func EmbeddingModelLoaded() bool {
	return isEmbeddingModelLoaded()
}

// embeddingTestResult mirrors Kotlin's EmbeddingTestResult sealed class shape (see
// LocalEmbeddingCliTester.kt) — gomobile can't export structs directly, only primitive
// types/JSON strings, same reasoning as Status.
type embeddingTestResult struct {
	Success    bool   `json:"success"`
	Dimensions int    `json:"dimensions,omitempty"`
	LatencyMs  int64  `json:"latencyMs,omitempty"`
	Error      string `json:"error,omitempty"`
}

// TestEmbedding loads modelPath (if not already resident — see LoadEmbeddingModel) and runs one
// embedding over text, entirely in-process. This IS the "local, no-API" model test now:
// LocalEmbeddingCliTester.kt calls this instead of spawning the llama-embedding CLI subprocess
// it used to (PLAN: native inference migration — the CLI tester was itself only a few days old,
// added specifically to decouple "does this model work" from the HTTP/API layer; native
// in-process inference is a strictly better way to answer that same question, since there's no
// longer a subprocess to spawn at all). Safe to call from any process — unlike
// LoadEmbeddingModel/the tailnet-serving path, a local test's loaded model is scoped to
// whichever process calls this and doesn't need to be :sync.
func TestEmbedding(modelPath, text string) string {
	start := time.Now()
	if err := loadEmbeddingModel(modelPath); err != nil {
		return toEmbeddingTestJSON(embeddingTestResult{Error: err.Error()})
	}
	vec, err := embed(text)
	if err != nil {
		return toEmbeddingTestJSON(embeddingTestResult{Error: err.Error()})
	}
	return toEmbeddingTestJSON(embeddingTestResult{
		Success:    true,
		Dimensions: len(vec),
		LatencyMs:  time.Since(start).Milliseconds(),
	})
}

func toEmbeddingTestJSON(r embeddingTestResult) string {
	b, err := json.Marshal(r)
	if err != nil {
		return `{"success":false,"error":"result marshal failed"}`
	}
	return string(b)
}

// LoadTranscriptionModel loads modelPath as the resident in-process transcription model (see
// transcription.go) — replaces WhisperServerManager.start()/switchTo() from the old
// subprocess-per-engine architecture. Same cross-process/:sync-only calling requirement as
// LoadEmbeddingModel — see its doc. Returns "" on success, an error message otherwise.
func LoadTranscriptionModel(modelPath string) string {
	if err := loadTranscriptionModel(modelPath); err != nil {
		return err.Error()
	}
	return ""
}

// UnloadTranscriptionModel frees the resident transcription model, if any. Safe to call
// unconditionally.
func UnloadTranscriptionModel() {
	unloadTranscriptionModel()
}

// TranscriptionModelLoaded reports whether a transcription model is currently resident in this
// process.
func TranscriptionModelLoaded() bool {
	return isTranscriptionModelLoaded()
}

// transcriptionTestResult mirrors Kotlin's TranscriptionTestResult sealed class shape (see
// LocalWhisperCliTester.kt) — same reasoning as embeddingTestResult.
type transcriptionTestResult struct {
	Success   bool   `json:"success"`
	Text      string `json:"text,omitempty"`
	LatencyMs int64  `json:"latencyMs,omitempty"`
	Error     string `json:"error,omitempty"`
}

// TestTranscription loads modelPath (if not already resident) and runs one transcription over
// wavBytes, entirely in-process — the local/no-API test button's implementation, mirrors
// TestEmbedding. LocalWhisperCliTester.kt passes the same synthesized silent WAV
// LocalTranscriptionTester already used for its API-path smoke test — proving "model loads and
// answers" doesn't need real speech, same reasoning as TestEmbedding's throwaway "test" string.
// Safe to call from any process, same as TestEmbedding.
func TestTranscription(modelPath string, wavBytes []byte) string {
	start := time.Now()
	if err := loadTranscriptionModel(modelPath); err != nil {
		return toTranscriptionTestJSON(transcriptionTestResult{Error: err.Error()})
	}
	pcm, err := decodeWAVToPCM16kMono(wavBytes)
	if err != nil {
		return toTranscriptionTestJSON(transcriptionTestResult{Error: err.Error()})
	}
	result, err := transcribe(pcm, false, "")
	if err != nil {
		return toTranscriptionTestJSON(transcriptionTestResult{Error: err.Error()})
	}
	return toTranscriptionTestJSON(transcriptionTestResult{
		Success:   true,
		Text:      result.Text,
		LatencyMs: time.Since(start).Milliseconds(),
	})
}

func toTranscriptionTestJSON(r transcriptionTestResult) string {
	b, err := json.Marshal(r)
	if err != nil {
		return `{"success":false,"error":"result marshal failed"}`
	}
	return string(b)
}

func toJSON(s Status) string {
	b, err := json.Marshal(s)
	if err != nil {
		return `{"running":false,"error":"status marshal failed"}`
	}
	return string(b)
}

// buildRouter is the single external API surface the backend talks to — everything (audio
// transcription, embeddings, /v1/models, /health) is served in-process now, see package doc.
func buildRouter() http.Handler {
	mux := http.NewServeMux()
	// Registered before the "/v1/audio/" prefix handler below, but order doesn't matter to
	// ServeMux — it always picks the more specific pattern.
	mux.Handle("/v1/audio/translations", newTranslationsHandler())
	mux.Handle("/v1/audio/", newTranscriptionsHandler())
	mux.Handle("/v1/models", newModelsListHandler())
	// Served natively in-process (embedding.go/transcription.go) instead of reverse-proxying to
	// a spawned subprocess — see LoadEmbeddingModel's doc for why.
	mux.Handle("/v1/embeddings", newEmbeddingsHandler())
	mux.Handle("/health", newHealthHandler())
	// NOT a reverse proxy to 127.0.0.1:ModelPort: this same process listens
	// on all local interfaces (including loopback) — proxying
	// there from inside the process already serving it would just be a pointless self-loop.
	// Was only ever needed for llama-server routes this app doesn't use (chat/completions
	// etc.) now that /v1/embeddings and /v1/models are handled explicitly above; anything
	// else genuinely has nowhere to go.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"not found"}`))
	})
	return mux
}

// newHealthHandler reports this device as healthy whenever the inference listener is up and
// serving — matching the old llama-server subprocess's /health semantics (which only ever
// confirmed "the process answers HTTP", not "a model happens to be loaded"; an idle/no-model
// device is still a healthy device, just one with nothing to dispatch to right now).
func newHealthHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	})
}

// noModelBody is returned (as a synthetic 503) when the local model server for this
// path isn't dialable — fresh pairing, model still downloading, deleted, wrong
// kind of model active... The backend needs a real, parseable HTTP response ("device
// is up, no model right now") instead of a connection reset, which every HTTP client
// (including the backend's own) surfaces as an opaque transport failure with no way
// to distinguish "phone unreachable" from "phone reachable, nothing loaded yet".
const noModelBody = `{"error":"no model loaded","status":"idle"}`

// transcriptionModelListEntry is the OpenAI-shaped catalog entry the backend's discovery poll
// (GET /v1/models — see runtime/Connectors/OpenAI/OpenAIModelFetcher.cs in sufficit-ai) reads.
// "capabilities" is the field ProviderModelPayloadParser checks first, before falling back to
// name-sniffing the id — set explicitly rather than relying on the fallback (whisper.cpp model
// filenames like "ggml-tiny.bin" don't contain "whisper"/"transcri"/"asr" themselves).
func transcriptionModelListEntry(modelName string) map[string]any {
	id := strings.TrimSuffix(modelName, ".bin")
	return map[string]any{
		"id":           id,
		"object":       "model",
		"created":      0,
		"owned_by":     "whispercpp",
		"capabilities": []string{"transcription"},
	}
}

// embeddingModelEntry is one installed-but-possibly-not-currently-loaded embedding model, as
// pushed from Kotlin (see SetInstalledEmbeddingModels). ID must exactly match what llama-server
// itself would report if this file were loaded — LlamaServerManager.aliasFor(modelFile) is
// "${filename-without-extension.lowercase()}-embedding", passed to llama-server via --alias, so
// it's fully deterministic from the filename alone (confirmed against a real device response:
// "gte-Qwen2-1.5B-instruct-Q4_K_M.gguf" -> "gte-qwen2-1.5b-instruct-q4_k_m-embedding"). That
// determinism is what makes synthesizing THIS side safe, unlike a naive guess would be.
type embeddingModelEntry struct {
	ID         string `json:"id"`
	Dimensions int    `json:"dimensions"` // 0 = unknown (not in DeviceModelCatalog) — omitted from the entry.
}

var installedEmbeddingModels []embeddingModelEntry

// SetInstalledEmbeddingModels records every embedding model installed on this device (not just
// whichever one llama-server currently has loaded) — entriesJSON is a JSON array of
// {"id","dimensions"} objects, dimensions 0 if unknown. Same reasoning and cross-process
// wiring as SetInstalledTranscriptionModels: a device can have several embedding models
// downloaded (see ModelsScreen), and GET /v1/models's discovery poll should list all of them.
func SetInstalledEmbeddingModels(entriesJSON string) {
	var entries []embeddingModelEntry
	if err := json.Unmarshal([]byte(entriesJSON), &entries); err != nil {
		log.Printf("[tsgo] SetInstalledEmbeddingModels: invalid JSON, ignoring: %v", err)
		return
	}
	transcriptionMetaMu.Lock()
	installedEmbeddingModels = entries
	transcriptionMetaMu.Unlock()
}

// embeddingModelListEntry mirrors transcriptionModelListEntry — see its doc for why
// "capabilities" is set explicitly rather than relying on id-sniffing.
func embeddingModelListEntry(e embeddingModelEntry) map[string]any {
	entry := map[string]any{
		"id":           e.ID,
		"object":       "model",
		"created":      0,
		"owned_by":     "llamacpp",
		"capabilities": []string{"embedding"},
	}
	if e.Dimensions > 0 {
		entry["meta"] = map[string]any{"n_embd": e.Dimensions}
	}
	return entry
}

// newModelsListHandler serves GET /v1/models entirely in-process from installedEmbeddingModels
// + installedTranscriptionModelNames — replaces newModelsListProxy, which used to reverse-proxy
// to 127.0.0.1:ModelPort to get llama-server's own live response and merge in whatever it
// didn't already know about. That target doesn't exist anymore (embedding.go/transcription.go
// serve in-process; nothing external listens on ModelPort to dial), and dialing it would now
// just be this exact same process's own local loopback listener answering itself — an infinite
// self-proxy loop, not a bug this handler can have by construction. installedEmbeddingModels
// already carries every installed embedding model INCLUDING whichever one is currently loaded
// (SyncForegroundService pushes the full list unconditionally, with dimensions from
// DeviceModelCatalog) — there's no longer a separate "live" entry to dedupe against, so this is
// simpler than the two functions it replaces, not just safer.
func newModelsListHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		transcriptionMetaMu.RLock()
		transcriptionNames := installedTranscriptionModelNames
		embeddings := installedEmbeddingModels
		transcriptionMetaMu.RUnlock()

		w.Header().Set("Content-Type", "application/json")
		if len(transcriptionNames) == 0 && len(embeddings) == 0 {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(noModelBody))
			return
		}

		entries := make([]map[string]any, 0, len(transcriptionNames)+len(embeddings))
		for _, e := range embeddings {
			entries = append(entries, embeddingModelListEntry(e))
		}
		for _, name := range transcriptionNames {
			entries = append(entries, transcriptionModelListEntry(name))
		}
		out, err := json.Marshal(map[string]any{
			"object": "list",
			"data":   entries,
		})
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(out)
	})
}
