package com.sufficit.ai.mobiledevice

import android.content.Context
import java.io.File

/**
 * [ModelServerManager] implementation for transcription under the native-inference architecture
 * — replaces the old [WhisperServerManager] (removed), which spawned whisper-server as a
 * subprocess bound to 127.0.0.1:WhisperPort. Transcription inference is now in-process, via cgo
 * bindings straight to whisper.cpp's C API (android-tsgo/transcription.go) — no subprocess, no
 * local HTTP hop. Mirrors [NativeEmbeddingManager] exactly — see its kdoc for the full
 * cross-process story (this class can only record *intent*; [SyncForegroundService]'s broadcast
 * receiver is what actually calls `tsgo.Tsgo.loadTranscriptionModel`/`unloadTranscriptionModel`
 * in the process where it matters, :sync, the one running tsgo's HTTP server).
 */
object NativeTranscriptionManager : ModelServerManager {
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

    /** Read by [ModelRuntimeService.broadcastStatus] to fill the status broadcast's
     * transcription path extra — null when nothing should be loaded. */
    fun currentPath(): String? = loadedPath
}
