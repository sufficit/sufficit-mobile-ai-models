package com.sufficit.ai.mobiledevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Runs a real transcription entirely in-process via [tsgo.Tsgo.testTranscription] — cgo
 * bindings straight to whisper.cpp's C API (see android-tsgo/transcription.go), no subprocess,
 * no HTTP. The local counterpart to [LocalTranscriptionTester] (which still goes through the
 * HTTP API): proves "does this model load and produce a real transcription" without touching
 * the API layer at all — and is the same code path the tailnet-facing /v1/audio/transcriptions
 * endpoint itself uses (see tsgo.go's buildRouter), so this test is a genuine dry run of
 * production behavior, not just a parallel implementation of it.
 *
 * Safe to call from any process — unlike loading the model for real tailnet serving (which
 * must happen in :sync, see [tsgo.Tsgo.loadTranscriptionModel]'s doc), a local test's loaded
 * model is scoped to whichever process calls this (:modelruntime here) and doesn't affect what
 * :sync has resident.
 */
class LocalWhisperCliTester {
    suspend fun test(context: Context, modelFile: File): TranscriptionTestResult = withContext(Dispatchers.IO) {
        try {
            val resultJson = tsgo.Tsgo.testTranscription(modelFile.absolutePath, silentWav())
            val json = JSONObject(resultJson)
            if (json.optBoolean("success")) {
                TranscriptionTestResult.Success(
                    text = json.optString("text"),
                    latencyMs = json.getLong("latencyMs")
                )
            } else {
                TranscriptionTestResult.Failure(json.optString("error", "falha no teste"))
            }
        } catch (ex: Exception) {
            TranscriptionTestResult.Failure(ex.message ?: "falha no teste")
        }
    }
}
