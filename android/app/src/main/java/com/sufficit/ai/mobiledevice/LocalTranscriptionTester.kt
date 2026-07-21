package com.sufficit.ai.mobiledevice

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class TranscriptionTestResult {
    data class Success(val text: String, val latencyMs: Long) : TranscriptionTestResult()
    data class Failure(val message: String) : TranscriptionTestResult()
}

/**
 * Fires a single sample transcription request against the locally-running whisper-server (see
 * [WhisperServerManager]) — the speech-to-text counterpart to [LocalEmbeddingTester], same
 * "does this model load and answer at all" purpose and same poll-until-/health-ready reasoning.
 *
 * The sample audio is a synthesized silent WAV generated on the fly, not a bundled asset — this
 * repo has no real speech sample to ship, and generating one deterministically in code avoids
 * adding a binary asset just for a smoke test. whisper-server transcribing silence into an
 * (expectedly empty or near-empty) result still proves the full pipeline works: model loads,
 * server answers, response is well-formed JSON. That's the same bar [LocalEmbeddingTester] sets
 * with a throwaway "test" string — this isn't a quality/accuracy check.
 */
class LocalTranscriptionTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : HealthCheckable {
    private val healthClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()

    /** Single /health probe, 3s budget — same role as [LocalEmbeddingTester.isHealthy] in
     * [ModelRuntimeService]'s keep-alive loop. */
    override suspend fun isHealthy(port: Int): Boolean = try {
        val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
        healthClient.newCall(request).execute().use { it.isSuccessful }
    } catch (ex: Exception) {
        android.util.Log.w("LocalTranscriptionTester", "isHealthy($port) failed: ${ex.javaClass.simpleName}: ${ex.message}")
        false
    }

    suspend fun test(port: Int, readyTimeoutMs: Long = 30_000L): TranscriptionTestResult {
        val ready = waitUntilReady(port, readyTimeoutMs)
        if (!ready) return TranscriptionTestResult.Failure("modelo não ficou pronto a tempo")

        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "sample.wav",
                    silentWav().toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/v1/audio/transcriptions")
                .post(body)
                .build()

            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    return TranscriptionTestResult.Failure("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.has("text")) {
                    return TranscriptionTestResult.Failure("resposta sem texto")
                }
                TranscriptionTestResult.Success(text = json.optString("text"), latencyMs = elapsed)
            }
        } catch (ex: Exception) {
            TranscriptionTestResult.Failure(ex.message ?: "falha no teste")
        }
    }

    /** Polls with [healthClient] (3s budget per attempt), not [client] (30s read timeout) —
     * a single sluggish/stuck attempt on the slow client would burn the whole [timeoutMs]
     * budget in one shot instead of actually retrying. Found on-device: a phone running two
     * model servers at once can be slow enough that this mattered in practice, not just in
     * theory. */
    private suspend fun waitUntilReady(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
                healthClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return true
                }
            } catch (ex: Exception) {
                android.util.Log.w("LocalTranscriptionTester", "waitUntilReady($port) probe failed: ${ex.javaClass.simpleName}: ${ex.message}")
            }
            delay(500)
        }
        return false
    }
}
