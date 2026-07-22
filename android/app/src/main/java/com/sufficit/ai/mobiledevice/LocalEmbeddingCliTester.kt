package com.sufficit.ai.mobiledevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Runs a real embedding entirely in-process via [tsgo.Tsgo.testEmbedding] — cgo bindings
 * straight to llama.cpp's C API (see android-tsgo/embedding.go), no subprocess, no HTTP. The
 * local counterpart to [LocalEmbeddingTester] (which still goes through the HTTP API): proves
 * "does this model load and produce a real embedding" without touching the API layer at all —
 * and is the same code path the tailnet-facing /v1/embeddings endpoint itself uses (see
 * tsgo.go's buildRouter), so this test is a genuine dry run of production behavior, not just a
 * parallel implementation of it.
 *
 * Safe to call from any process — unlike loading the model for real tailnet serving (which
 * must happen in :sync, see [tsgo.Tsgo.loadEmbeddingModel]'s doc), a local test's loaded model
 * is scoped to whichever process calls this (:modelruntime here) and doesn't affect what :sync
 * has resident.
 */
class LocalEmbeddingCliTester {
    suspend fun test(context: Context, modelFile: File): EmbeddingTestResult = withContext(Dispatchers.IO) {
        try {
            val resultJson = tsgo.Tsgo.testEmbedding(modelFile.absolutePath, "test")
            val json = JSONObject(resultJson)
            if (json.optBoolean("success")) {
                EmbeddingTestResult.Success(
                    dimensions = json.getInt("dimensions"),
                    latencyMs = json.getLong("latencyMs")
                )
            } else {
                EmbeddingTestResult.Failure(json.optString("error", "falha no teste"))
            }
        } catch (ex: Exception) {
            EmbeddingTestResult.Failure(ex.message ?: "falha no teste")
        }
    }
}
