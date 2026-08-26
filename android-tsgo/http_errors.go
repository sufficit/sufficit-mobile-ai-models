package tsgo

import (
	"fmt"
	"net/http"
)

func writeEngineStateResponse(w http.ResponseWriter, snapshot engineSnapshot) {
	switch snapshot.State {
	case engineBusy:
		w.Header().Set("Retry-After", "2")
		writeJSONError(w, http.StatusTooManyRequests, "engine_busy", "the active model is processing another request")
	case engineLoading:
		w.Header().Set("Retry-After", "5")
		writeJSONError(w, http.StatusServiceUnavailable, "engine_loading", "the model is loading")
	case engineFailed:
		detail := "the model failed to load"
		if snapshot.LastError != "" {
			detail = fmt.Sprintf("%s: %s", detail, snapshot.LastError)
		}
		writeJSONError(w, http.StatusServiceUnavailable, "engine_failed", detail)
	default:
		writeJSONError(w, http.StatusServiceUnavailable, "no_model_loaded", "no compatible model is loaded")
	}
}
