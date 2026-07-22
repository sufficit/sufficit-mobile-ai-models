// Package tsgo embeds a Tailscale node inside the Sufficit Mobile AI Models Android
// app via tsnet — a userspace (netstack/gVisor) node, no VpnService/TUN device, no
// system-wide routing. It joins the tailnet with a preauthkey issued by the Sufficit
// backend and reverse-proxies inbound tailnet traffic on ModelPort to whichever local
// model server actually handles the request path — llama-server (embeddings,
// 127.0.0.1:ModelPort) or whisper-server (transcription, 127.0.0.1:WhisperPort). One
// external port/API for the backend regardless of which OS process is actually
// serving it (PLAN: Whisper support). Built as an .aar via `gomobile bind` and
// consumed from Kotlin.
package tsgo

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// ModelPort is the port llama-server listens on locally (embeddings) and the port
// exposed on the tailnet interface. Matches PHONE_PORT / DeviceModelServerPort
// convention used by the adb-tethered POC and the backend's TailscaleClientOptions.
const ModelPort = 8090

// WhisperPort is the port whisper-server listens on locally (speech-to-text). Not
// exposed directly on the tailnet — reached through the same external ModelPort via
// path-based routing in buildRouter, so the backend sees a single OpenAI-compatible
// API surface no matter which native process actually answers.
const WhisperPort = 8091

var (
	mu              sync.Mutex
	server          *tsnet.Server
	httpServer      *http.Server
	localHTTPServer *http.Server

	transcriptionMetaMu              sync.RWMutex
	activeModelName                  string
	installedTranscriptionModelNames []string
	deviceHostname                   string
)

// Status is returned by Status() as JSON — gomobile can't export structs directly,
// only primitive types, so the Kotlin side deserializes this string.
type Status struct {
	Running   bool   `json:"running"`
	TailnetIP string `json:"tailnetIp"`
	Hostname  string `json:"hostname"`
	Error     string `json:"error,omitempty"`
}

// androidInterface mirrors what the Kotlin side can read from
// java.net.NetworkInterface — Go on Android can't enumerate interfaces itself
// (net.Interfaces() needs a netlink socket, blocked by SELinux for regular apps:
// "route ip+net: netlinkrib: permission denied"). The Kotlin side collects this
// via the standard Java API (which Android itself permits) and calls
// SetInterfacesJSON before Start().
type androidInterface struct {
	Name  string   `json:"name"`
	Index int      `json:"index"`
	MTU   int      `json:"mtu"`
	Flags int      `json:"flags"` // net.Flags bitmask: Up=1, Broadcast=2, Loopback=4, PointToPoint=8, Multicast=16
	Addrs []string `json:"addrs"` // CIDR strings, e.g. "192.168.1.5/24"
}

// SetInterfacesJSON registers the device's network interfaces (collected on the
// Kotlin side via java.net.NetworkInterface) with tsnet's netmon so it can pick a
// source address and build magicsock without needing raw netlink access. Must be
// called before Start().
func SetInterfacesJSON(jsonStr string) string {
	var raw []androidInterface
	if err := json.Unmarshal([]byte(jsonStr), &raw); err != nil {
		return fmt.Sprintf("parse: %v", err)
	}

	list := make([]netmon.Interface, 0, len(raw))
	for _, r := range raw {
		ni := &net.Interface{
			Index: r.Index,
			MTU:   r.MTU,
			Name:  r.Name,
			Flags: net.Flags(r.Flags),
		}
		// Non-nil (even if it ends up empty): netmon.Interface.Addrs() only uses AltAddrs
		// when it's non-nil, falling back to the real net.Interface.Addrs() otherwise — which
		// hits the exact raw netlink syscall this whole file exists to avoid, fatally, deep
		// inside tsnet.Server.Listen() (SELinux denies it for untrusted_app on some devices).
		// An interface reported with zero parseable addresses must still short-circuit to
		// "no addresses" rather than silently falling through to that blocked path.
		addrs := []net.Addr{}
		for _, a := range r.Addrs {
			// net.ParseCIDR rejects the "%zone" suffix Android reports on IPv6 link-local
			// addresses (e.g. "fe80::1%wlan0/64") — strip it before parsing. Observed on a
			// real device: "dummy0"'s only address is a zone-suffixed link-local IPv6, so
			// without this its addresses would all fail to parse and hit the fallback above.
			addr := a
			if pct := strings.IndexByte(addr, '%'); pct != -1 {
				if slash := strings.IndexByte(addr[pct:], '/'); slash != -1 {
					addr = addr[:pct] + addr[pct+slash:]
				} else {
					addr = addr[:pct]
				}
			}
			if ip, ipnet, err := net.ParseCIDR(addr); err == nil {
				ipnet.IP = ip
				addrs = append(addrs, ipnet)
			}
		}
		list = append(list, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}

	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return list, nil
	})
	return ""
}

// Start joins the tailnet and begins proxying ModelPort. controlURL is the
// Headscale login-server URL, authKey is a preauthkey minted by the backend
// (POST /mobile/{token}/announce or /api/ai/mobile-devices/self-announce),
// hostname must match what the backend expects back (AIMobileDeviceAnnounceResult.
// TailnetNodeName) so future announces can resolve this node's IP, and stateDir is
// an app-writable directory (Android Context.getFilesDir()) tsnet persists its
// node identity/keys in — reusing the same stateDir across restarts avoids
// re-registering as a new node every time the app restarts.
func Start(controlURL, authKey, hostname, stateDir string) string {
	mu.Lock()
	defer mu.Unlock()

	if server != nil {
		return toJSON(Status{Error: "already started; call Stop() first"})
	}

	// Android apps have no $HOME, no working directory (Getwd returns "/"), and no
	// os.UserCacheDir() support — tailscale's logpolicy walks all three looking for
	// somewhere to persist log state and, finding nothing, falls back to
	// os.MkdirTemp("", ...), which needs $TMPDIR set (there's no global /tmp on
	// Android). Without this it panics: "no safe place found to store log state".
	tmpDir := stateDir + "/tmp"
	os.MkdirAll(tmpDir, 0700)
	os.Setenv("TMPDIR", tmpDir)

	// gomobile bind's stdlib log output doesn't reach logcat at all (confirmed: zero
	// "[tsnet]"-tagged lines ever appear, even benign startup messages) — redirect to a
	// plain file instead, retrievable via `adb shell run-as <pkg> --user <id> cat
	// files/tsgo-state/tsgo-debug.log`. This is how the netlink/SELinux root cause of the
	// tailnet-never-connects bug on some devices was actually found.
	if logFile, err := os.OpenFile(stateDir+"/tsgo-debug.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600); err == nil {
		log.SetOutput(logFile)
	}
	log.Printf("Start() called: controlURL=%s hostname=%s stateDir=%s", controlURL, hostname, stateDir)

	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL,
		AuthKey:    authKey,
		Ephemeral:  false,
		Logf:       func(format string, args ...any) { log.Printf("[tsnet] "+format, args...) },
	}

	ln, err := s.Listen("tcp", ":"+strconv.Itoa(ModelPort))
	if err != nil {
		return toJSON(Status{Error: fmt.Sprintf("listen: %v", err)})
	}

	transcriptionMetaMu.Lock()
	deviceHostname = hostname
	transcriptionMetaMu.Unlock()

	server = s
	router := buildRouter()
	httpServer = &http.Server{Handler: router}

	go func() {
		if err := httpServer.Serve(ln); err != nil && err != http.ErrServerClosed {
			log.Printf("[tsgo] http serve error: %v", err)
		}
	}()

	// Second, real (not tsnet-virtual) listener on loopback, same handler. Needed now that
	// embedding inference moved in-process into this same Go runtime (see embedding.go /
	// LoadEmbeddingModel's doc): this process (:sync) is the only one with a resident model,
	// so ModelRuntimeService's local test buttons (:modelruntime, LocalEmbeddingTester —
	// unlike LocalEmbeddingCliTester, which now calls TestEmbedding directly, no HTTP) need an
	// actual loopback socket to hit, not just the tsnet-virtual one only reachable from other
	// tailnet nodes. Harmless to also have this when nothing's testing: same 503 "no model
	// loaded" behavior as the tailnet-facing listener when nothing's resident.
	localLn, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(ModelPort))
	if err != nil {
		log.Printf("[tsgo] local loopback listen failed (local test buttons won't work, tailnet-facing serving is unaffected): %v", err)
	} else {
		log.Printf("[tsgo] local loopback listener up on 127.0.0.1:%d", ModelPort)
		localHTTPServer = &http.Server{Handler: router}
		go func() {
			if err := localHTTPServer.Serve(localLn); err != nil && err != http.ErrServerClosed {
				log.Printf("[tsgo] local http serve error: %v", err)
			}
		}()
	}

	return currentStatusLocked()
}

// Stop tears down the tailnet connection and stops proxying.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if httpServer != nil {
		_ = httpServer.Close()
		httpServer = nil
	}
	if localHTTPServer != nil {
		_ = localHTTPServer.Close()
		localHTTPServer = nil
	}
	if server != nil {
		_ = server.Close()
		server = nil
	}
}

// Status returns the current connection state as a JSON string (see Status struct).
func StatusJSON() string {
	mu.Lock()
	defer mu.Unlock()
	return currentStatusLocked()
}

func currentStatusLocked() string {
	if server == nil {
		return toJSON(Status{Running: false})
	}

	status := Status{Running: true, Hostname: server.Hostname}
	if lc, err := server.LocalClient(); err == nil {
		if st, err := lc.StatusWithoutPeers(context.Background()); err == nil && st.Self != nil {
			for _, ip := range st.Self.TailscaleIPs {
				if ip.Is4() {
					status.TailnetIP = ip.String()
					break
				}
			}
		}
	}
	return toJSON(status)
}

// SetActiveTranscriptionModel records which whisper.cpp model file is the device's configured
// transcription model. Called from the Kotlin side ONLY by SyncForegroundService, in response
// to ModelRuntimeService's ACTION_STATUS_CHANGED broadcast — not by WhisperServerManager
// directly, even though it's the piece that actually knows which model just started. Reason:
// tsgo's Go globals are per-OS-process, and WhisperServerManager runs in :modelruntime while
// the tsnet/HTTP proxy that reads activeModelName runs in :sync (started via
// TailscaleManager.start in SyncForegroundService) — two separate processes, two separate
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
// (buildRouter, started via Start() from TailscaleManager/SyncForegroundService) runs in :sync,
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

func toJSON(s Status) string {
	b, err := json.Marshal(s)
	if err != nil {
		return `{"running":false,"error":"status marshal failed"}`
	}
	return string(b)
}

// buildRouter is the single external API surface the backend talks to: audio
// transcription requests go to whisper-server, everything else (embeddings,
// /v1/models, /health) goes to llama-server. Two separate local OS processes, one
// external port — see package doc.
func buildRouter() http.Handler {
	mux := http.NewServeMux()
	// Registered before the "/v1/audio/" prefix handler below, but order doesn't matter to
	// ServeMux — it always picks the more specific pattern.
	mux.Handle("/v1/audio/translations", newWhisperTranslateReverseProxy(WhisperPort))
	mux.Handle("/v1/audio/", newWhisperReverseProxy(WhisperPort))
	mux.Handle("/v1/models", newModelsListProxy(ModelPort))
	// Served natively in-process (embedding.go) instead of reverse-proxying to a spawned
	// llama-server subprocess — see LoadEmbeddingModel's doc for why.
	mux.Handle("/v1/embeddings", newEmbeddingsHandler())
	mux.Handle("/health", newHealthHandler())
	// NOT a reverse proxy to 127.0.0.1:ModelPort anymore: this same process now ALSO listens
	// on that exact address (Start()'s localHTTPServer, for local test buttons) — proxying
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

// newHealthHandler reports this device as healthy whenever the tsnet node itself is up and
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

type startTimeCtxKey struct{}

// newWhisperReverseProxy is newReverseProxy plus augmentTranscriptionResponse — whisper.cpp's
// server already speaks a response shape close to sufficit-services-whisper's (the internal
// FastAPI/faster-whisper deployment this app's transcription endpoint needs to be a drop-in
// alternative for; see PLAN: Whisper API compatibility), but is missing a handful of fields
// (model/processing_time/device/server/cached) that service's own responses always include.
// Adding them here, once, in the proxy layer, means WhisperServerManager and whisper-server
// itself stay untouched — no native rebuild needed for this.
func newWhisperReverseProxy(port int) http.Handler {
	target := &url.URL{Scheme: "http", Host: "127.0.0.1:" + strconv.Itoa(port)}
	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(r *http.Request) {
		originalDirector(r)
		*r = *r.WithContext(context.WithValue(r.Context(), startTimeCtxKey{}, time.Now()))
	}
	proxy.ModifyResponse = augmentTranscriptionResponse
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("[tsgo] dial 127.0.0.1:%d failed (no model loaded?): %v", port, err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(noModelBody))
	}
	return proxy
}

// augmentTranscriptionResponse injects the extra top-level fields sufficit-services-whisper's
// responses always carry into whisper-server's native JSON body — left alone for non-JSON
// response_format values (text/srt/vtt use their own content types) and for anything that
// isn't a successful transcription result (error bodies, /v1/models docs stubs).
func augmentTranscriptionResponse(resp *http.Response) error {
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Type"), "application/json") {
		return nil
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	resp.Body.Close()

	var obj map[string]any
	if err := json.Unmarshal(body, &obj); err != nil {
		// Not a JSON object we understand — pass through unchanged rather than fail the request.
		resp.Body = io.NopCloser(bytes.NewReader(body))
		resp.ContentLength = int64(len(body))
		return nil
	}

	if _, isTranscription := obj["text"]; isTranscription {
		transcriptionMetaMu.RLock()
		obj["model"] = activeModelName
		obj["server"] = deviceHostname
		transcriptionMetaMu.RUnlock()
		obj["device"] = "cpu"
		obj["cached"] = false
		if start, ok := resp.Request.Context().Value(startTimeCtxKey{}).(time.Time); ok {
			obj["processing_time"] = time.Since(start).Seconds()
		}
		if wasTranslate, _ := resp.Request.Context().Value(translateCtxKey{}).(bool); wasTranslate {
			// sufficit-services-whisper always reports the OUTPUT language here for
			// /v1/audio/translations (translation output is always English) — whisper.cpp's
			// own "language" field is the DETECTED SOURCE language instead. Override to match;
			// whisper.cpp's source-language detection (when present, i.e. verbose_json) stays
			// available under detected_language, same field name the real service uses for it.
			obj["language"] = "english"
		}
	}

	out, err := json.Marshal(obj)
	if err != nil {
		return err
	}
	resp.Body = io.NopCloser(bytes.NewReader(out))
	resp.ContentLength = int64(len(out))
	resp.Header.Set("Content-Length", strconv.Itoa(len(out)))
	return nil
}

type translateCtxKey struct{}

// maxTranslateRewriteBytes bounds how much of the request body newWhisperTranslateReverseProxy
// buffers in memory to inject the translate=true field — generous for a voice note/short
// recording while still bounding worst-case memory use (mirrors the reasoning behind T2.2's
// body size cap on the embeddings proxy).
const maxTranslateRewriteBytes = 64 << 20 // 64MB

// newWhisperTranslateReverseProxy makes POST /v1/audio/translations work against
// whisper-server, which has no such route — whisper.cpp only supports translation via a
// per-request `translate=true` multipart field on its one configured --inference-path
// (/v1/audio/transcriptions), not a separate URL like sufficit-services-whisper. Rewrites the
// destination path and injects that field into the outgoing multipart body; everything else
// (response augmentation, model/hostname fields, error handling) is identical to
// newWhisperReverseProxy.
func newWhisperTranslateReverseProxy(port int) http.Handler {
	target := &url.URL{Scheme: "http", Host: "127.0.0.1:" + strconv.Itoa(port)}
	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(r *http.Request) {
		originalDirector(r)
		r.URL.Path = "/v1/audio/transcriptions"
		ctx := context.WithValue(r.Context(), startTimeCtxKey{}, time.Now())
		ctx = context.WithValue(ctx, translateCtxKey{}, true)
		*r = *r.WithContext(ctx)
		if err := injectTranslateField(r); err != nil {
			log.Printf("[tsgo] /v1/audio/translations: failed to inject translate field, forwarding unmodified: %v", err)
		}
	}
	proxy.ModifyResponse = augmentTranscriptionResponse
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("[tsgo] dial 127.0.0.1:%d failed (no model loaded?): %v", port, err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte(noModelBody))
	}
	return proxy
}

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

// newModelsListProxy makes GET /v1/models list every installed model this device can serve —
// not just whichever one each engine currently has loaded — alongside whatever llama-server
// itself reports for the embedding model it's actively running. Plain proxying, like every
// other non-audio path, would only ever show that one currently-loaded model: llama-server has
// no idea ModelRegistry has other embedding files sitting on disk, and no idea whisper-server or
// its TRANSCRIPTION slot exist at all.
func newModelsListProxy(port int) http.Handler {
	target := &url.URL{Scheme: "http", Host: "127.0.0.1:" + strconv.Itoa(port)}
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.ModifyResponse = mergeAdditionalModelsIntoList
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		// llama-server isn't dialable — most commonly because whisper is the currently resident
		// engine (mutual exclusion), so there's no "already in the response" embedding entry to
		// dedupe against here; list everything installed on both sides. Still worth a 200
		// rather than the generic noModelBody 503: unlike every other path here, "no embedding
		// server answering" doesn't mean "nothing this device can do" for a model-list request
		// specifically, and the backend can't distinguish an empty catalog poll from a dead
		// device otherwise.
		transcriptionMetaMu.RLock()
		transcriptionNames := installedTranscriptionModelNames
		embeddings := installedEmbeddingModels
		transcriptionMetaMu.RUnlock()

		w.Header().Set("Content-Type", "application/json")
		if len(transcriptionNames) == 0 && len(embeddings) == 0 {
			log.Printf("[tsgo] dial 127.0.0.1:%d failed (no model loaded?): %v", port, err)
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
		out, marshalErr := json.Marshal(map[string]any{
			"object": "list",
			"data":   entries,
		})
		if marshalErr != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(noModelBody))
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(out)
	}
	return proxy
}

// mergeAdditionalModelsIntoList appends one catalog entry per installed transcription model
// (see transcriptionModelListEntry) and per installed embedding model NOT already present in
// llama-server's real response (see embeddingModelListEntry) — leaving every field of the
// existing entries untouched. The currently-loaded embedding model is already in there with
// llama-server's own authoritative id/meta; deduped by id so it doesn't appear twice.
func mergeAdditionalModelsIntoList(resp *http.Response) error {
	transcriptionMetaMu.RLock()
	transcriptionNames := installedTranscriptionModelNames
	embeddings := installedEmbeddingModels
	transcriptionMetaMu.RUnlock()
	if len(transcriptionNames) == 0 && len(embeddings) == 0 {
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Type"), "application/json") {
		return nil
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	resp.Body.Close()

	var obj map[string]any
	if err := json.Unmarshal(body, &obj); err != nil {
		resp.Body = io.NopCloser(bytes.NewReader(body))
		resp.ContentLength = int64(len(body))
		return nil
	}

	data, _ := obj["data"].([]any)
	existingIDs := make(map[string]bool, len(data))
	for _, entry := range data {
		if m, ok := entry.(map[string]any); ok {
			if id, ok := m["id"].(string); ok {
				existingIDs[id] = true
			}
		}
	}

	for _, e := range embeddings {
		if !existingIDs[e.ID] {
			data = append(data, embeddingModelListEntry(e))
		}
	}
	for _, name := range transcriptionNames {
		data = append(data, transcriptionModelListEntry(name))
	}
	obj["data"] = data

	out, err := json.Marshal(obj)
	if err != nil {
		return err
	}
	resp.Body = io.NopCloser(bytes.NewReader(out))
	resp.ContentLength = int64(len(out))
	resp.Header.Set("Content-Length", strconv.Itoa(len(out)))
	return nil
}

// injectTranslateField rewrites r's multipart/form-data body to add a "translate"="true" field,
// preserving every other part (the audio file, language, prompt, etc.) byte-for-byte. Buffers
// the whole body — see maxTranslateRewriteBytes — since a reverse proxy can't otherwise inject
// a field into a body it's meant to stream through unmodified.
func injectTranslateField(r *http.Request) error {
	mediaType, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || !strings.HasPrefix(mediaType, "multipart/") {
		return fmt.Errorf("request is not multipart/form-data: %v", err)
	}
	boundary, ok := params["boundary"]
	if !ok {
		return fmt.Errorf("multipart Content-Type missing boundary")
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxTranslateRewriteBytes+1))
	if err != nil {
		return fmt.Errorf("read body: %w", err)
	}
	r.Body.Close()
	if len(body) > maxTranslateRewriteBytes {
		return fmt.Errorf("body exceeds %d bytes", maxTranslateRewriteBytes)
	}

	reader := multipart.NewReader(bytes.NewReader(body), boundary)
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("read part: %w", err)
		}
		if part.FormName() == "translate" {
			continue // caller's own value, if any — ours below is authoritative for this route.
		}
		w, err := writer.CreatePart(part.Header)
		if err != nil {
			return fmt.Errorf("recreate part %q: %w", part.FormName(), err)
		}
		if _, err := io.Copy(w, part); err != nil {
			return fmt.Errorf("copy part %q: %w", part.FormName(), err)
		}
	}
	if err := writer.WriteField("translate", "true"); err != nil {
		return fmt.Errorf("write translate field: %w", err)
	}
	if err := writer.Close(); err != nil {
		return fmt.Errorf("close multipart writer: %w", err)
	}

	r.Body = io.NopCloser(&buf)
	r.ContentLength = int64(buf.Len())
	r.Header.Set("Content-Type", writer.FormDataContentType())
	return nil
}
