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
 * Fires a sample transcription request against tsgo's local HTTP loopback listener (see
 * [NativeTranscriptionManager]/android-tsgo/transcription.go) — the speech-to-text counterpart
 * to [LocalEmbeddingTester], same "does this model load and answer at all" purpose and same
 * poll-the-real-endpoint-until-ready reasoning (see [LocalEmbeddingTester]'s kdoc for why a
 * separate /health probe isn't a reliable readiness signal under the native architecture).
 *
 * The sample audio is a synthesized silent WAV generated on the fly, not a bundled asset — this
 * repo has no real speech sample to ship, and generating one deterministically in code avoids
 * adding a binary asset just for a smoke test. Transcribing silence into an (expectedly empty or
 * near-empty) result still proves the full pipeline works: model loads, server answers, response
 * is well-formed JSON. That's the same bar [LocalEmbeddingTester] sets with a throwaway "test"
 * string — this isn't a quality/accuracy check.
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

        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val start = System.currentTimeMillis()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 503) {
                        return@use // not loaded yet — fall through to the delay below and retry
                    }
                    if (!response.isSuccessful) {
                        return TranscriptionTestResult.Failure("HTTP ${response.code}")
                    }
                    val elapsed = System.currentTimeMillis() - start
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!json.has("text")) {
                        return TranscriptionTestResult.Failure("resposta sem texto")
                    }
                    return TranscriptionTestResult.Success(text = json.optString("text"), latencyMs = elapsed)
                }
            } catch (ex: Exception) {
                android.util.Log.w("LocalTranscriptionTester", "test($port) attempt failed: ${ex.javaClass.simpleName}: ${ex.message}")
            }
            delay(500)
        }
        return TranscriptionTestResult.Failure("modelo não ficou pronto a tempo")
    }
}
