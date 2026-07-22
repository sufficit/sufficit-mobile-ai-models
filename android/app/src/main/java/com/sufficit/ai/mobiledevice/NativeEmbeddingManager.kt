package com.sufficit.ai.mobiledevice

import android.content.Context
import java.io.File

/**
 * [ModelServerManager] implementation for embeddings under the native-inference architecture —
 * replaces the old [LlamaServerManager] (removed), which spawned llama-server as a subprocess
 * bound to 127.0.0.1:ModelPort. Embedding inference is now in-process, via cgo bindings straight
 * to llama.cpp's C API (android-tsgo/embedding.go) — no subprocess, no local HTTP hop.
 *
 * The catch: that in-process model lives inside tsgo's Go runtime, and tsgo.Tsgo's Go globals
 * are per-OS-process (same gotcha documented all over this codebase — see
 * SyncForegroundService.kt's onCreate kdoc). The tailnet-facing HTTP server that actually needs
 * the loaded model runs in `:sync` (TailscaleManager/SyncForegroundService), not `:modelruntime`
 * (this class's process). This class can only record *intent* — "load this path" — and does so
 * by updating [loadedPath] and letting [ModelRuntimeService.broadcastStatus] carry it over to
 * :sync via the same ACTION_STATUS_CHANGED broadcast the transcription-model-name and installed-
 * models list already ride on. SyncForegroundService's receiver is what actually calls
 * `tsgo.Tsgo.loadEmbeddingModel`/`unloadEmbeddingModel` in the process where it matters.
 *
 * [isRunning] is therefore optimistic ("we've asked for this to be loaded"), not a live
 * confirmation that :sync has finished loading it — same staleness tradeoff
 * ModelRuntimeService.queryStatus's kdoc already documents for SharedPreferences-backed values
 * cross-process, and just as acceptable here: mutual exclusion (see ModelRuntimeService kdoc)
 * doesn't need microsecond precision, only eventual consistency.
 */
object NativeEmbeddingManager : ModelServerManager {
    @Volatile
    private var loadedPath: String? = null

    override val port: Int get() = tsgo.Tsgo.ModelPort.toInt()

    override fun isRunning(): Boolean = loadedPath != null

    override fun start(context: Context, modelFile: File): Boolean {
        loadedPath = modelFile.absolutePath
        return true
    }

    override fun switchTo(context: Context, modelFile: File): Boolean = start(context, modelFile)

    override fun stop() {
        loadedPath = null
    }

    /** Read by [ModelRuntimeService.broadcastStatus] to fill the status broadcast's embedding
     * path extra — null when nothing should be loaded. */
    fun currentPath(): String? = loadedPath

    /**
     * The backend has no reliable way to tell "this model is loaded for embeddings" from a
     * generic model id string alone — it falls back to guessing capability from the id
     * (AICapability.InferPrimary: `id.Contains("embed")`). Every model this device serves for
     * embeddings is always loaded in embedding mode (the only mode embedding.go's cgo wrapper
     * supports), so baking "embedding" into the alias isn't a hack for one model, it's just
     * true — and it's what makes that guess land correctly instead of silently defaulting to
     * "chat". Moved here from the old LlamaServerManager (removed) — same formula, still
     * deterministic from the filename alone, still what SyncForegroundService uses to
     * synthesize discovery catalog entries (GET /v1/models) for embedding models installed but
     * not currently loaded (see tsgo.go's embeddingModelEntry doc).
     */
    fun aliasFor(modelFile: File): String =
        "${modelFile.nameWithoutExtension.lowercase()}-embedding"
}
