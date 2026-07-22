//go:build !android

package tsgo

// Portable stand-in for transcription.go's cgo/whisper.cpp bindings — same shape, but every
// function fails/no-ops instead of doing real inference. See embedding_stub.go's doc for why
// this pattern exists (lets `go test ./...` build without the android-arm64-only static libs).

import "fmt"

func loadTranscriptionModel(modelPath string) error {
	return fmt.Errorf("native transcription inference is only available on android")
}

func unloadTranscriptionModel() {}

func isTranscriptionModelLoaded() bool { return false }

func transcribe(pcm []float32, translate bool, language string) (transcriptionResult, error) {
	return transcriptionResult{}, fmt.Errorf("native transcription inference is only available on android")
}
