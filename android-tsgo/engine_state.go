package tsgo

import (
	"strings"
	"sync"
	"time"
)

type engineState string

const (
	engineIdle    engineState = "idle"
	engineLoading engineState = "loading"
	engineReady   engineState = "ready"
	engineBusy    engineState = "busy"
	engineFailed  engineState = "failed"
)

// engineSnapshot is deliberately small and JSON-friendly: it is used by the HTTP
// admission path, model discovery and /health without ever waiting for native inference.
type engineSnapshot struct {
	State      engineState `json:"state"`
	Model      string      `json:"model,omitempty"`
	Dimensions int         `json:"dimensions,omitempty"`
	BusySince  time.Time   `json:"busy_since,omitempty"`
	LastReady  time.Time   `json:"last_ready,omitempty"`
	LastError  string      `json:"last_error,omitempty"`
}

// engineController serializes lifecycle and inference operations before they reach
// llama.cpp/whisper.cpp. The gate has capacity one and admission is fail-fast: mobile
// devices never build an unbounded in-memory queue while a several-minute Whisper job runs.
type engineController struct {
	mu    sync.RWMutex
	gate  chan struct{}
	state engineSnapshot
}

func newEngineController() *engineController {
	gate := make(chan struct{}, 1)
	gate <- struct{}{}
	return &engineController{
		gate:  gate,
		state: engineSnapshot{State: engineIdle},
	}
}

var (
	embeddingEngine     = newEngineController()
	transcriptionEngine = newEngineController()
)

func (e *engineController) snapshot() engineSnapshot {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.state
}

// beginLifecycle marks the engine unavailable immediately, then waits for any current
// inference to finish. finishInference intentionally does not overwrite this loading state.
func (e *engineController) beginLifecycle() {
	e.mu.Lock()
	e.state.State = engineLoading
	e.state.LastError = ""
	e.mu.Unlock()
	<-e.gate
}

func (e *engineController) finishLifecycle(model string, dimensions int, err error) {
	e.mu.Lock()
	if err != nil {
		e.state = engineSnapshot{State: engineFailed, LastError: err.Error()}
	} else if strings.TrimSpace(model) == "" {
		e.state = engineSnapshot{State: engineIdle}
	} else {
		e.state = engineSnapshot{
			State:      engineReady,
			Model:      model,
			Dimensions: dimensions,
			LastReady:  time.Now().UTC(),
		}
	}
	e.mu.Unlock()
	e.gate <- struct{}{}
}

// tryBeginInference admits at most one native request. A second request is rejected
// immediately as busy instead of waiting behind work whose duration is unknown.
func (e *engineController) tryBeginInference() (engineSnapshot, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if e.state.State != engineReady {
		return e.state, false
	}
	select {
	case <-e.gate:
		e.state.State = engineBusy
		e.state.BusySince = time.Now().UTC()
		return e.state, true
	default:
		snapshot := e.state
		snapshot.State = engineBusy
		return snapshot, false
	}
}

func (e *engineController) finishInference(err error) {
	e.mu.Lock()
	// A lifecycle operation may already have changed busy -> loading while waiting
	// for the gate. In that case it owns the next state transition.
	if e.state.State == engineBusy {
		e.state.State = engineReady
		e.state.BusySince = time.Time{}
		if err != nil {
			e.state.LastError = err.Error()
		} else {
			e.state.LastError = ""
			e.state.LastReady = time.Now().UTC()
		}
	}
	e.mu.Unlock()
	e.gate <- struct{}{}
}
