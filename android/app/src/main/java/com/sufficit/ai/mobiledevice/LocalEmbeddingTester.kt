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
 * Fires a single sample embedding request against the locally-running llama-server (see
 * [LlamaServerManager]) — this is a quick on-device sanity check ("does this model load and
 * answer at all"), not the same thing as the AI Playground's test-via-backend flow (which
 * goes through the tailnet and real dispatch/capability logic). A freshly (re)started
 * llama-server needs a few seconds to finish loading the model into memory before /health
 * reports ready, so this polls rather than firing the embeddings call immediately.
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
     * actually serving". */
    override suspend fun isHealthy(port: Int): Boolean = try {
        val request = Request.Builder().url("http://127.0.0.1:$port/health").build()
        healthClient.newCall(request).execute().use { it.isSuccessful }
    } catch (ex: Exception) {
        android.util.Log.w("LocalEmbeddingTester", "isHealthy($port) failed: ${ex.javaClass.simpleName}: ${ex.message}")
        false
    }

    suspend fun test(port: Int, sampleText: String = "test", readyTimeoutMs: Long = 30_000L): EmbeddingTestResult {
        val ready = waitUntilReady(port, readyTimeoutMs)
        if (!ready) return EmbeddingTestResult.Failure("modelo não ficou pronto a tempo")

        return try {
            val body = JSONObject().put("input", sampleText).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/v1/embeddings")
                .post(body)
                .build()

            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    return EmbeddingTestResult.Failure("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val data = json.optJSONArray("data") ?: JSONArray()
                val embedding = data.optJSONObject(0)?.optJSONArray("embedding")
                    ?: return EmbeddingTestResult.Failure("resposta sem embedding")
                EmbeddingTestResult.Success(dimensions = embedding.length(), latencyMs = elapsed)
            }
        } catch (ex: Exception) {
            EmbeddingTestResult.Failure(ex.message ?: "falha no teste")
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
                android.util.Log.w("LocalEmbeddingTester", "waitUntilReady($port) probe failed: ${ex.javaClass.simpleName}: ${ex.message}")
            }
            delay(500)
        }
        return false
    }
}
