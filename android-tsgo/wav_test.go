package tsgo

import (
	"bytes"
	"encoding/binary"
	"math"
	"testing"
)

// buildWAV assembles a minimal PCM WAV file byte-for-byte — no test fixture file needed.
func buildWAV(t *testing.T, sampleRate uint32, numChannels, bitsPerSample uint16, samples []int32) []byte {
	t.Helper()
	bytesPerSample := int(bitsPerSample) / 8
	data := make([]byte, len(samples)*bytesPerSample)
	for i, s := range samples {
		off := i * bytesPerSample
		switch bitsPerSample {
		case 16:
			binary.LittleEndian.PutUint16(data[off:], uint16(int16(s)))
		case 8:
			data[off] = byte(s)
		default:
			t.Fatalf("unsupported bitsPerSample in test helper: %d", bitsPerSample)
		}
	}

	var buf bytes.Buffer
	buf.WriteString("RIFF")
	binary.Write(&buf, binary.LittleEndian, uint32(36+len(data)))
	buf.WriteString("WAVE")

	buf.WriteString("fmt ")
	binary.Write(&buf, binary.LittleEndian, uint32(16))
	binary.Write(&buf, binary.LittleEndian, uint16(1)) // PCM
	binary.Write(&buf, binary.LittleEndian, numChannels)
	binary.Write(&buf, binary.LittleEndian, sampleRate)
	byteRate := sampleRate * uint32(numChannels) * uint32(bytesPerSample)
	binary.Write(&buf, binary.LittleEndian, byteRate)
	blockAlign := numChannels * uint16(bytesPerSample)
	binary.Write(&buf, binary.LittleEndian, blockAlign)
	binary.Write(&buf, binary.LittleEndian, bitsPerSample)

	buf.WriteString("data")
	binary.Write(&buf, binary.LittleEndian, uint32(len(data)))
	buf.Write(data)

	return buf.Bytes()
}

func TestDecodeWAV_16kHzMono16Bit_PassesThroughUnchanged(t *testing.T) {
	wav := buildWAV(t, 16000, 1, 16, []int32{0, 16384, -16384, 32767, -32768})

	pcm, err := decodeWAVToPCM16kMono(wav)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if len(pcm) != 5 {
		t.Fatalf("got %d samples, want 5", len(pcm))
	}
	want := []float32{0, 0.5, -0.5, 32767.0 / 32768, -1}
	for i, w := range want {
		if math.Abs(float64(pcm[i]-w)) > 1e-4 {
			t.Errorf("sample %d = %v, want %v", i, pcm[i], w)
		}
	}
}

func TestDecodeWAV_StereoDownmixedToMono(t *testing.T) {
	// Interleaved L/R: (1.0, -1.0) should average to ~0.
	wav := buildWAV(t, 16000, 2, 16, []int32{32767, -32768})

	pcm, err := decodeWAVToPCM16kMono(wav)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	if len(pcm) != 1 {
		t.Fatalf("got %d frames, want 1 (one stereo frame downmixed)", len(pcm))
	}
	if math.Abs(float64(pcm[0])) > 0.01 {
		t.Errorf("downmixed sample = %v, want ~0", pcm[0])
	}
}

func TestDecodeWAV_ResamplesToTargetRate(t *testing.T) {
	// 32kHz input should resample down to half as many samples at 16kHz.
	samples := make([]int32, 320) // 10ms @ 32kHz
	wav := buildWAV(t, 32000, 1, 16, samples)

	pcm, err := decodeWAVToPCM16kMono(wav)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	want := 160 // 10ms @ 16kHz
	if len(pcm) != want {
		t.Errorf("got %d samples after resample, want %d", len(pcm), want)
	}
}

func TestDecodeWAV_RejectsNonWAV(t *testing.T) {
	if _, err := decodeWAVToPCM16kMono([]byte("not a wav file")); err == nil {
		t.Error("expected error for non-WAV input, got nil")
	}
}

func TestDecodeWAV_RejectsMissingDataChunk(t *testing.T) {
	var buf bytes.Buffer
	buf.WriteString("RIFF")
	binary.Write(&buf, binary.LittleEndian, uint32(36))
	buf.WriteString("WAVE")
	buf.WriteString("fmt ")
	binary.Write(&buf, binary.LittleEndian, uint32(16))
	binary.Write(&buf, binary.LittleEndian, uint16(1))
	binary.Write(&buf, binary.LittleEndian, uint16(1))
	binary.Write(&buf, binary.LittleEndian, uint32(16000))
	binary.Write(&buf, binary.LittleEndian, uint32(32000))
	binary.Write(&buf, binary.LittleEndian, uint16(2))
	binary.Write(&buf, binary.LittleEndian, uint16(16))

	if _, err := decodeWAVToPCM16kMono(buf.Bytes()); err == nil {
		t.Error("expected error for missing data chunk, got nil")
	}
}
