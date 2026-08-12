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
	return fmt.Errorf("native embedding inference is only available on android")
}

func unloadEmbeddingModel() {}

func isEmbeddingModelLoaded() bool { return false }

func embeddingDimensions() int { return 0 }

func embed(text string) ([]float32, error) {
	return nil, fmt.Errorf("native embedding inference is only available on android")
}

func embeddingModelID() string { return "" }
