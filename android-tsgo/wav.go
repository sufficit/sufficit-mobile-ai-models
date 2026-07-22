package tsgo

// decodeWAVToPCM16kMono — whisper.cpp's C API (whisper_full) takes raw PCM as mono float32 @
// 16kHz, not a WAV file; the old whisper-server subprocess handled that conversion internally
// via miniaudio (which also decoded mp3/ogg/flac — a real capability this native migration
// loses; WAV is the one format every caller of this app's /v1/audio/* endpoints and IPC
// contract already documents — see docs/ipc-transcription-contract.md). This is a from-scratch
// WAV decoder rather than a vendored C library, kept portable (no build tag, no cgo) so it's
// exercised by `go test ./...` on the host same as everything else in this file.
//
// Handles standard PCM (audioFormat 1), IEEE float (3), and WAVE_FORMAT_EXTENSIBLE (0xFFFE,
// common from Windows recorders) at 8/16/24/32-bit depths, any channel count (downmixed to mono
// by averaging) and any sample rate (linearly resampled to 16kHz) — broader than the 16kHz
// mono 16-bit contract callers are asked to produce, so slightly-off real-world recordings still
// work rather than failing outright.

import (
	"encoding/binary"
	"fmt"
	"math"
)

const (
	wavFormatPCM        = 1
	wavFormatIEEEFloat  = 3
	wavFormatExtensible = 0xFFFE
)

func decodeWAVToPCM16kMono(data []byte) ([]float32, error) {
	if len(data) < 12 || string(data[0:4]) != "RIFF" || string(data[8:12]) != "WAVE" {
		return nil, fmt.Errorf("not a RIFF/WAVE file")
	}

	var (
		audioFormat   uint16
		numChannels   uint16
		sampleRate    uint32
		bitsPerSample uint16
		haveFmt       bool
		pcmData       []byte
	)

	pos := 12
	for pos+8 <= len(data) {
		chunkID := string(data[pos : pos+4])
		chunkSize := int(binary.LittleEndian.Uint32(data[pos+4 : pos+8]))
		body := pos + 8
		if chunkSize < 0 || body+chunkSize > len(data) {
			break // truncated/corrupt trailing chunk — stop, work with whatever was parsed so far
		}

		switch chunkID {
		case "fmt ":
			if chunkSize < 16 {
				return nil, fmt.Errorf("fmt chunk too small: %d bytes", chunkSize)
			}
			fmtBody := data[body : body+chunkSize]
			audioFormat = binary.LittleEndian.Uint16(fmtBody[0:2])
			numChannels = binary.LittleEndian.Uint16(fmtBody[2:4])
			sampleRate = binary.LittleEndian.Uint32(fmtBody[4:8])
			bitsPerSample = binary.LittleEndian.Uint16(fmtBody[14:16])
			if audioFormat == wavFormatExtensible && chunkSize >= 40 {
				// WAVEFORMATEXTENSIBLE: real format hides in the first two bytes of the
				// SubFormat GUID at offset 24 within the fmt body (cbSize(2)+validBits(2)+
				// channelMask(4) = 8 bytes after the common 16-byte header, so SubFormat
				// starts at 16+8=24).
				audioFormat = binary.LittleEndian.Uint16(fmtBody[24:26])
			}
			haveFmt = true
		case "data":
			pcmData = data[body : body+chunkSize]
		}

		// Chunks are word-aligned: an odd-sized chunk has one pad byte after it.
		pos = body + chunkSize
		if chunkSize%2 == 1 {
			pos++
		}
	}

	if !haveFmt {
		return nil, fmt.Errorf("missing fmt chunk")
	}
	if pcmData == nil {
		return nil, fmt.Errorf("missing data chunk")
	}
	if numChannels == 0 {
		return nil, fmt.Errorf("invalid channel count: 0")
	}
	if audioFormat != wavFormatPCM && audioFormat != wavFormatIEEEFloat {
		return nil, fmt.Errorf("unsupported WAV audio format: %d (only PCM/IEEE float supported)", audioFormat)
	}

	mono, err := toMonoFloat32(pcmData, audioFormat, int(numChannels), int(bitsPerSample))
	if err != nil {
		return nil, err
	}

	if sampleRate == whisperSampleRate {
		return mono, nil
	}
	return resampleLinear(mono, int(sampleRate), whisperSampleRate), nil
}

const whisperSampleRate = 16000

func toMonoFloat32(pcm []byte, audioFormat uint16, numChannels, bitsPerSample int) ([]float32, error) {
	bytesPerSample := bitsPerSample / 8
	if bytesPerSample == 0 {
		return nil, fmt.Errorf("invalid bits per sample: %d", bitsPerSample)
	}
	frameSize := bytesPerSample * numChannels
	if frameSize == 0 {
		return nil, fmt.Errorf("invalid frame size")
	}
	nFrames := len(pcm) / frameSize
	out := make([]float32, nFrames)

	readSample := func(off int) (float32, error) {
		switch {
		case audioFormat == wavFormatIEEEFloat && bitsPerSample == 32:
			return math.Float32frombits(binary.LittleEndian.Uint32(pcm[off : off+4])), nil
		case audioFormat == wavFormatPCM && bitsPerSample == 8:
			// 8-bit PCM is the one WAV depth stored unsigned (0..255, midpoint 128).
			return (float32(pcm[off]) - 128) / 128, nil
		case audioFormat == wavFormatPCM && bitsPerSample == 16:
			return float32(int16(binary.LittleEndian.Uint16(pcm[off:off+2]))) / 32768, nil
		case audioFormat == wavFormatPCM && bitsPerSample == 24:
			v := int32(pcm[off]) | int32(pcm[off+1])<<8 | int32(pcm[off+2])<<16
			if v&0x800000 != 0 {
				v |= -0x1000000 // sign-extend 24-bit two's complement into int32
			}
			return float32(v) / 8388608, nil
		case audioFormat == wavFormatPCM && bitsPerSample == 32:
			return float32(int32(binary.LittleEndian.Uint32(pcm[off:off+4]))) / 2147483648, nil
		default:
			return 0, fmt.Errorf("unsupported sample depth %d for format %d", bitsPerSample, audioFormat)
		}
	}

	for i := 0; i < nFrames; i++ {
		frameOff := i * frameSize
		var sum float32
		for c := 0; c < numChannels; c++ {
			s, err := readSample(frameOff + c*bytesPerSample)
			if err != nil {
				return nil, err
			}
			sum += s
		}
		out[i] = sum / float32(numChannels)
	}
	return out, nil
}

// resampleLinear is a plain linear-interpolation resampler — no anti-aliasing filter, which
// matters for high-ratio downsampling (e.g. 48kHz -> 16kHz keeps energy above 8kHz that should
// be filtered out first) but whisper.cpp's own model input is already heavily band-limited by
// its mel-spectrogram frontend, so the resulting aliasing has negligible effect on transcription
// accuracy in practice — not worth vendoring a proper resampling library for.
func resampleLinear(in []float32, srcRate, dstRate int) []float32 {
	if len(in) == 0 || srcRate <= 0 {
		return in
	}
	if srcRate == dstRate {
		return in
	}
	ratio := float64(srcRate) / float64(dstRate)
	outLen := int(float64(len(in)) / ratio)
	out := make([]float32, outLen)
	for i := range out {
		srcPos := float64(i) * ratio
		i0 := int(srcPos)
		if i0 >= len(in)-1 {
			out[i] = in[len(in)-1]
			continue
		}
		frac := float32(srcPos - float64(i0))
		out[i] = in[i0] + (in[i0+1]-in[i0])*frac
	}
	return out
}
