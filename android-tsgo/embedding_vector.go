package tsgo

import (
	"fmt"
	"math"
)

// truncateAndNormalizeEmbedding applies Matryoshka truncation to the requested prefix and
// restores unit length. The returned slice never aliases the native inference buffer.
func truncateAndNormalizeEmbedding(vector []float32, dimensions int) ([]float32, error) {
	if dimensions < 1 || dimensions > len(vector) {
		return nil, fmt.Errorf("dimensions must be between 1 and %d", len(vector))
	}

	resized := append([]float32(nil), vector[:dimensions]...)
	l2Normalize(resized)
	return resized, nil
}

func l2Normalize(vector []float32) {
	var sumSquares float64
	for _, value := range vector {
		sumSquares += float64(value) * float64(value)
	}
	if sumSquares == 0 {
		return
	}

	norm := float32(math.Sqrt(sumSquares))
	for index := range vector {
		vector[index] /= norm
	}
}
