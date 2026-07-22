package com.sufficit.ai.mobiledevice

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class EmbeddingTestResult {
    data class Success(val dimensions: Int, val latencyMs: Long) : EmbeddingTestResult()
    data class Failure(val message: String) : EmbeddingTestResult()
}

/**
 * Fires a sample embedding request against tsgo's local HTTP loopback listener — this is a
 * quick on-device sanity check ("does this model load and answer at all"), not the same thing
 * as the AI Playground's test-via-backend flow (which goes through the tailnet and real
 * dispatch/capability logic).
 *
 * Polls the REAL /v1/embeddings endpoint itself, not a separate /health probe — under the
 * native in-process architecture, /health reports the tsgo router as up the instant the tailnet
 * node starts, regardless of whether ANY model is loaded (see tsgo.go's newHealthHandler doc),
 * so a /health-only readiness check races the ACTION_STATUS_CHANGED broadcast that tells :sync
 * which model to load (see NativeEmbeddingManager's kdoc) and can return "ready" before the
 * requested model has actually finished loading there. Retrying the real endpoint costs nothing
 * extra while not ready: embedding.go's HTTP handler checks isEmbeddingModelLoaded() and
 * returns 503 immediately, before paying for any inference — the only expensive attempt is the
 * one that actually succeeds. A freshly (re)started/switched model still needs real wall time
 * to finish loading, hence the retry loop rather than a single shot.
 */
class LocalEmbeddingTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) : HealthCheckable {
    private val healthClient = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()

    /** Single /health probe, 3s budget — used by [ModelRuntimeService]'s keep-alive loop to
     * tell "process alive but stuck" (OOM in progress, deadlock) apart from "process alive and
     * actually serving". Deliberately model-agnostic (see this class's own kdoc) — that's the
     * right question for the keep-alive loop ("is the resident process itself OK"), just not
     * for [test]'s "is THIS SPECIFIC model loaded" readiness check. */
    override suspend fun isHealthy(port: Int): Boolean = try {
        val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
        healthClient.newCall(request).execute().use { it.isSuccessful }
    } catch (ex: Exception) {
        android.util.Log.w("LocalEmbeddingTester", "isHealthy($port) failed: ${ex.javaClass.simpleName}: ${ex.message}")
        false
    }

    suspend fun test(port: Int, sampleText: String = "test", readyTimeoutMs: Long = 30_000L): EmbeddingTestResult {
        val body = JSONObject().put("input", sampleText).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/v1/embeddings")
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
                        return EmbeddingTestResult.Failure("HTTP ${response.code}")
                    }
                    val elapsed = System.currentTimeMillis() - start
                    val json = JSONObject(response.body?.string().orEmpty())
                    val data = json.optJSONArray("data") ?: JSONArray()
                    val embedding = data.optJSONObject(0)?.optJSONArray("embedding")
                        ?: return EmbeddingTestResult.Failure("resposta sem embedding")
                    return EmbeddingTestResult.Success(dimensions = embedding.length(), latencyMs = elapsed)
                }
            } catch (ex: Exception) {
                android.util.Log.w("LocalEmbeddingTester", "test($port) attempt failed: ${ex.javaClass.simpleName}: ${ex.message}")
            }
            delay(500)
        }
        return EmbeddingTestResult.Failure("modelo não ficou pronto a tempo")
    }
}
