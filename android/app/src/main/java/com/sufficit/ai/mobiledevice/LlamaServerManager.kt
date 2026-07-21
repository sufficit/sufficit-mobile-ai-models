package com.sufficit.ai.mobiledevice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Launches the embedded llama-server as a native subprocess. The binary and its shared
 * libraries ship as jniLibs (installed into Context.applicationInfo.nativeLibraryDir, which —
 * unlike regular app-private storage — Android extracts with execute permission already set;
 * see docs/PLAN-202607091200-mobile-device-ai-provider.md "Fase 4").
 *
 * The executable is named libllamaserver.so (not llama-server) purely so the Android build
 * tool recognizes it as a native library to package and extract — it's not a JNI library and
 * is never loaded via System.loadLibrary(), just run as a subprocess.
 *
 * Built from a custom static NDK build (GGML_BACKEND_DL=OFF, -march=armv8-a): the official
 * prebuilt release uses runtime backend-plugin loading (GGML_BACKEND_DL=ON) that silently
 * fails in this app-private exec context, and even a from-source build targeting
 * -march=armv8.2-a crashes with SIGILL on Exynos 9611's Cortex-A55 "LITTLE" cores (big.LITTLE:
 * only the Cortex-A73 "big" cores support ARMv8.2-A) — armv8-a is the safe common baseline
 * across both core clusters.
 *
 * CPU-only: a GGML_VULKAN=ON build was tried (Fase 5) to offload to this device's Mali-G72,
 * but ggml_vk_create_buffer SIGSEGVs on host-buffer allocation on this GPU/driver — a real
 * crash, not a config issue, and llama-server doesn't catch it to fall back to CPU. Reverted;
 * revisit per-device (query GPU model, only pass -ngl on a verified-safe allowlist) rather
 * than shipping Vulkan unconditionally.
 *
 * Fase 6: the model is no longer fixed at build time — ModelsScreen lets the user download
 * and pick among several (see [ModelRegistry]), and can also briefly switch to a different
 * model just to test it. Both SyncForegroundService (the persistent, production instance) and
 * ModelsScreen (transient test runs) drive the same underlying OS process, always bound to
 * the same fixed port — a `class` here would let each caller hold its own [Process] handle
 * unaware of the other's, so this is a singleton `object`: one shared handle, no split-brain
 * about whether llama-server is running or which model it's actually serving.
 */
object LlamaServerManager : ModelServerManager {

    private const val TAG = "LlamaServerManager"

    @Volatile
    private var process: Process? = null

    /// Must match tsgo.ModelPort (android-tsgo/tsgo.go) — that's the port tsgo's proxy
    /// dials into locally to serve inbound tailnet traffic.
    override val port: Int get() = tsgo.Tsgo.ModelPort.toInt()

    override fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts llama-server bound to 127.0.0.1:[port] — the same port tsgo's proxy
     * (android-tsgo/tsgo.go) dials into to serve inbound tailnet traffic. Idempotent: no-ops
     * if already running (regardless of which model — call [stop] or [switchTo] to swap).
     */
    override fun start(context: Context, modelFile: File): Boolean {
        if (isRunning()) return true
        if (!modelFile.exists()) {
            Log.w(TAG, "model file missing: ${modelFile.absolutePath}")
            return false
        }

        val appContext = context.applicationContext
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libllamaserver.so")
        if (!binary.exists()) {
            Log.e(TAG, "llama-server binary missing from nativeLibraryDir: $nativeDir")
            return false
        }

        return try {
            val builder = ProcessBuilder(
                binary.absolutePath,
                "-m", modelFile.absolutePath,
                "--embedding",
                "--pooling", "last",
                "-c", "2048",
                "-b", "512",
                "-ub", "512",
                // A single parallel slot instead of the default 4 — each slot gets its own
                // full-size KV cache, and a phone serving one backend consumer at a time
                // doesn't need concurrent generation slots.
                "--parallel", "1",
                "-t", Runtime.getRuntime().availableProcessors().coerceAtMost(6).toString(),
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "--alias", aliasFor(modelFile)
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            builder.redirectErrorStream(true)
            builder.redirectOutput(File(appContext.filesDir, "llama-server.log"))

            process = builder.start()
            Log.i(TAG, "llama-server started, pid via process handle, model=${modelFile.name}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start llama-server", t)
            false
        }
    }

    /** Stops whatever is running (no-op if not running) and starts [modelFile] — for switching
     * models (ModelsScreen "Test" / "Usar este modelo"), where a stale process must never be
     * left serving the wrong model. */
    override fun switchTo(context: Context, modelFile: File): Boolean {
        stop()
        return start(context, modelFile)
    }

    /** Blocks up to ~7s until the OS process actually exits — otherwise a caller that
     * immediately restarts (see [switchTo]) can race the old process for the port before it's
     * released. Never call from the main thread. */
    override fun stop() {
        val p = process ?: return
        process = null
        p.destroy()
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * The backend has no reliable way to tell "this model was launched with --embedding" from
     * llama-server's own /v1/models response (server.cpp's get_model_info() reports the same
     * shape regardless of mode) — it falls back to guessing capability from the model id string
     * (AICapability.InferPrimary: `id.Contains("embed")`). Every model this device serves is
     * always started in --embedding mode (see the launch args above), so baking "embedding"
     * into the alias isn't a hack for one model, it's just true — and it's what makes that
     * guess land correctly instead of silently defaulting to "chat".
     */
    // Not private: SyncForegroundService reuses this exact formula to synthesize discovery
    // catalog entries (GET /v1/models) for embedding models installed but not currently loaded
    // — deterministic from the filename alone, so it's guaranteed to match what llama-server
    // itself would report once that file IS loaded (see tsgo.go's embeddingModelEntry doc).
    fun aliasFor(modelFile: File): String =
        "${modelFile.nameWithoutExtension.lowercase()}-embedding"
}
