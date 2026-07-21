package com.sufficit.ai.mobiledevice

import java.io.ByteArrayOutputStream

/**
 * ~1s of 16kHz mono 16-bit quiet tone — the smallest input whisper.cpp's server accepts
 * without special-casing an empty file. A pure-zero (true silence) buffer isn't safe here:
 * found on-device that whisper_full_with_state fails to decode all-zero PCM against
 * ggml-base.bin specifically (ggml-tiny.bin tolerates it) and the server hangs instead of
 * returning an error — a real robustness gap in the native server worth fixing separately,
 * but not something our own smoke test needs to trigger. A low-amplitude tone is never truly
 * zero, sidesteps that decode failure, and is closer to what a real (quiet) recording looks
 * like anyway. Shared by [LocalTranscriptionTester] (HTTP) and [LocalWhisperCliTester] (local).
 */
internal fun silentWav(): ByteArray {
    val sampleRate = 16_000
    val samples = sampleRate // 1 second
    val dataSize = samples * 2 // 16-bit mono
    val out = ByteArrayOutputStream(44 + dataSize)

    fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun writeIntLE(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }
    fun writeShortLE(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    writeString("RIFF")
    writeIntLE(36 + dataSize)
    writeString("WAVE")
    writeString("fmt ")
    writeIntLE(16) // PCM fmt chunk size
    writeShortLE(1) // PCM
    writeShortLE(1) // mono
    writeIntLE(sampleRate)
    writeIntLE(sampleRate * 2) // byte rate
    writeShortLE(2) // block align
    writeShortLE(16) // bits per sample
    writeString("data")
    writeIntLE(dataSize)

    val amplitude = 200 // ~0.6% of full scale — quiet but never exactly zero
    val toneHz = 440.0
    for (i in 0 until samples) {
        val sample = (amplitude * kotlin.math.sin(2.0 * Math.PI * toneHz * i / sampleRate)).toInt()
        writeShortLE(sample)
    }

    return out.toByteArray()
}
