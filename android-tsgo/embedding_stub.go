//go:build !android

package tsgo

// Portable stand-in for embedding.go's cgo/llama.cpp bindings — same shape, but every function
// fails/no-ops instead of doing real inference. Lets `go test ./...` and other host-side
// tooling build and run without the android-arm64-only static libs from
// scripts/build-llama-static.sh (see embedding.go's doc). The real implementation only exists
// under the "android" build tag, matching the same portable-stub pattern this codebase already
// uses for platform-specific native inference implementations.

import "fmt"

func loadEmbeddingModel(modelPath string) error {
	err := fmt.Errorf("native embedding inference is only available on android")
	embeddingEngine.beginLifecycle()
	embeddingEngine.finishLifecycle("", 0, err)
	return err
}

func unloadEmbeddingModel() {
	embeddingEngine.beginLifecycle()
	embeddingEngine.finishLifecycle("", 0, nil)
}

func isEmbeddingModelLoaded() bool {
	state := embeddingEngine.snapshot().State
	return state == engineReady || state == engineBusy
}

func embeddingDimensions() int { return embeddingEngine.snapshot().Dimensions }

func embedWithUsage(text string) (embeddingInferenceResult, error) {
	return embeddingInferenceResult{}, fmt.Errorf("native embedding inference is only available on android")
}

func embed(text string) ([]float32, error) {
	result, err := embedWithUsage(text)
	return result.Vector, err
}

func embeddingModelID() string { return embeddingEngine.snapshot().Model }
