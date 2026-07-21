package com.sufficit.ai.mobiledevice

import android.content.Context
import java.io.File

/**
 * Gateway address is an implementation detail — end users never see or type it.
 * `ai.sufficit.com.br` is the haproxy front door for the AI service (session-affinity
 * failover across eveo-ai/apoint-ai/castrum-ai nodes; see
 * sufficit-services-haproxy/haproxy-*-proxy.cfg, backend `back-application-ai`).
 */
object Config {
    const val DEFAULT_GATEWAY_URL = "https://ai.sufficit.com.br"
}

/**
 * Legacy single-model path from before the model manager (Fase 6). Exists only so
 * [ModelRuntimeService]'s startup migration can find and move an already-installed app's file
 * into [ModelsDir] once (PLAN T3.4) — nothing downloads here anymore, and nothing serves
 * straight from this path.
 */
fun ModelFile(context: Context): File = File(context.filesDir, "llama/model.gguf")

/** Directory holding every downloaded model, one file per model — see [ModelRegistry]. Both
 * kinds of models share this directory; [ModelKind.fileExtension] tells them apart. */
fun ModelsDir(context: Context): File = File(context.filesDir, "models")

/**
 * Which native engine a model file is for. Different binary ([LlamaServerManager] vs
 * [WhisperServerManager]), different local port (tsgo.ModelPort vs tsgo.WhisperPort), different
 * file format on disk — GGUF for embeddings, whisper.cpp's own ggml .bin for transcription.
 * Both can have an active model and a running server process at the same time; see PLAN
 * "Whisper support".
 */
enum class ModelKind(val fileExtension: String) {
    EMBEDDING(".gguf"),
    TRANSCRIPTION(".bin")
}

/**
 * Which of the files in [ModelsDir] is the one [SyncForegroundService]/[ModelRuntimeService]
 * actually starts and serves over the tailnet, per [ModelKind]. Downloading a model does not
 * make it active — the user picks one explicitly (ModelsScreen "Usar este modelo"), same
 * reasoning as login vs. sync being separate steps elsewhere in this app: an action with real
 * consequences (this one restarts the production server) should never be implicit.
 *
 * Cross-process ownership rule (PLAN T1.4): the `:modelruntime` process ([ModelRuntimeService])
 * is the only writer of the active-model prefs. UI processes only read them as an initial
 * guess — `SharedPreferences` isn't multi-process safe, so that read can be stale — and
 * otherwise trust [ModelRuntimeService.ACTION_STATUS_CHANGED] broadcasts (request a fresh one
 * with [ModelRuntimeService.queryStatus]) instead of re-reading this class.
 */
class ModelRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("model_registry_prefs", Context.MODE_PRIVATE)

    fun activeModelFileName(kind: ModelKind): String? = prefs.getString(keyFor(kind), null)

    fun setActiveModelFileName(kind: ModelKind, value: String?) {
        prefs.edit().putString(keyFor(kind), value).apply()
    }

    fun installedModels(context: Context, kind: ModelKind): List<File> =
        ModelsDir(context).listFiles { file -> file.isFile && file.name.endsWith(kind.fileExtension) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun activeModelFile(context: Context, kind: ModelKind): File? =
        activeModelFileName(kind)?.let { name -> File(ModelsDir(context), name).takeIf { it.exists() } }

    private fun keyFor(kind: ModelKind) = when (kind) {
        ModelKind.EMBEDDING -> KEY_ACTIVE_EMBEDDING_MODEL
        ModelKind.TRANSCRIPTION -> KEY_ACTIVE_TRANSCRIPTION_MODEL
    }

    private companion object {
        // Unchanged key name for EMBEDDING — installs that already picked an active embedding
        // model before Whisper support shipped keep it, no silent reset.
        const val KEY_ACTIVE_EMBEDDING_MODEL = "active_model_file_name"
        const val KEY_ACTIVE_TRANSCRIPTION_MODEL = "active_transcription_model_file_name"
    }
}
