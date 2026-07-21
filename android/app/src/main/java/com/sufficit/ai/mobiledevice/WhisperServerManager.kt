package com.sufficit.ai.mobiledevice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Launches the embedded whisper-server (whisper.cpp) as a native subprocess — the
 * speech-to-text counterpart to [LlamaServerManager]. Same jniLibs-extraction trick (binary
 * named libwhisperserver.so so AGP packages/extracts it with exec permission; never loaded
 * via System.loadLibrary()), same CPU-only/-march=armv8-a build reasoning (see
 * [LlamaServerManager] kdoc for why: GGML_BACKEND_DL=OFF, armv8-a baseline for Exynos 9611's
 * Cortex-A55 "LITTLE" cores).
 *
 * whisper.cpp vendors its own ggml fork at a different revision than llama.cpp's — the
 * ggml/whisper shared libs are renamed with a `whisper-` prefix (see PLAN: Whisper support)
 * so they don't collide with llama's identically-named libggml*.so files in the same
 * jniLibs/arm64-v8a directory.
 *
 * Launched with `--inference-path /v1/audio/transcriptions` so its endpoint matches OpenAI's
 * Whisper API shape directly — [tsgo.Tsgo] routes paths under `/v1/audio/` to [port] precisely
 * so no path rewriting is needed anywhere in the chain (PLAN: one external API, two local
 * processes).
 *
 * A separate singleton `object` from [LlamaServerManager], not a shared abstraction: different
 * binary, different port, different request shape (multipart audio upload vs JSON). Both are
 * owned by [ModelRuntimeService] in the same `:modelruntime` process — but only one of the two
 * is ever actually resident at a time (mutual exclusion enforced by [ModelRuntimeService], see
 * its kdoc: running both loaded together pushes the reference test device into RAM/swap
 * pressure it doesn't have headroom for).
 */
object WhisperServerManager : ModelServerManager {

    private const val TAG = "WhisperServerManager"

    @Volatile
    private var process: Process? = null

    /// Must match tsgo.WhisperPort (android-tsgo/tsgo.go) — the local port tsgo's proxy
    /// forwards /v1/audio/* requests to.
    override val port: Int get() = tsgo.Tsgo.WhisperPort.toInt()

    override fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts whisper-server bound to 127.0.0.1:[port]. Idempotent: no-ops if already running
     * (regardless of which model — call [stop] or [switchTo] to swap).
     */
    override fun start(context: Context, modelFile: File): Boolean {
        if (isRunning()) return true
        if (!modelFile.exists()) {
            Log.w(TAG, "model file missing: ${modelFile.absolutePath}")
            return false
        }

        val appContext = context.applicationContext
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libwhisperserver.so")
        if (!binary.exists()) {
            Log.e(TAG, "whisper-server binary missing from nativeLibraryDir: $nativeDir")
            return false
        }

        return try {
            val builder = ProcessBuilder(
                binary.absolutePath,
                "-m", modelFile.absolutePath,
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--inference-path", "/v1/audio/transcriptions",
                "-t", Runtime.getRuntime().availableProcessors().coerceAtMost(6).toString(),
                // No GPU backend compiled in (CPU-only build, same reasoning as
                // LlamaServerManager) — explicit so a future build with GPU support doesn't
                // silently start trying to use it here.
                "-ng",
                // Found on-device: flash attention defaults to ON in this whisper.cpp build
                // and every /v1/audio/transcriptions request hangs forever under it on this
                // CPU (confirmed with a direct curl POST bypassing the app entirely — /health
                // stayed responsive the whole time, only inference never returned). Disabling
                // it is the fix, not a tuning knob.
                "-nfa"
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            builder.redirectErrorStream(true)
            builder.redirectOutput(File(appContext.filesDir, "whisper-server.log"))

            process = builder.start()
            // tsgo.Tsgo.setActiveTranscriptionModel is NOT called from here on purpose: this
            // class runs in the :modelruntime process, but the actual tsnet/HTTP proxy (the
            // only place that reads it) runs in :sync (TailscaleManager/SyncForegroundService)
            // — a different OS process with its own independent copy of every Go global. A
            // call made here would silently no-op against nothing. SyncForegroundService listens
            // for ModelRuntimeService's ACTION_STATUS_CHANGED broadcast and calls the setter
            // itself instead, in the process where it actually matters (PLAN: Whisper API
            // compatibility with sufficit-services-whisper).
            Log.i(TAG, "whisper-server started, pid via process handle, model=${modelFile.name}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start whisper-server", t)
            false
        }
    }

    /** Stops whatever is running (no-op if not running) and starts [modelFile] — mirrors
     * [LlamaServerManager.switchTo]. */
    override fun switchTo(context: Context, modelFile: File): Boolean {
        stop()
        return start(context, modelFile)
    }

    /** Blocks up to ~7s until the OS process actually exits — same port-reuse race as
     * [LlamaServerManager.stop]. Never call from the main thread. */
    override fun stop() {
        val p = process ?: return
        process = null
        p.destroy()
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
