package com.sufficit.ai.mobiledevice

import android.content.Context
import java.io.File

/**
 * Shared shape of [NativeEmbeddingManager] and [WhisperServerManager] — lets
 * [ModelRuntimeService] dispatch by [ModelKind] instead of duplicating every action's
 * dispatch logic per engine. Each implementation stays a singleton `object`, not a `class`:
 * same reasoning as [WhisperServerManager]'s own kdoc — one shared process handle per engine,
 * no split-brain about what's actually running.
 */
interface ModelServerManager {
    val port: Int
    fun isRunning(): Boolean
    fun start(context: Context, modelFile: File): Boolean
    fun switchTo(context: Context, modelFile: File): Boolean
    fun stop()
}

/** The one [NativeEmbeddingManager]/[WhisperServerManager] singleton for a given [ModelKind]. */
fun managerFor(kind: ModelKind): ModelServerManager = when (kind) {
    ModelKind.EMBEDDING -> NativeEmbeddingManager
    ModelKind.TRANSCRIPTION -> WhisperServerManager
}

/** Shared shape of [LocalEmbeddingTester] and [LocalTranscriptionTester] — lets
 * [ModelRuntimeService]'s keep-alive loop health-check either engine generically. Each
 * tester's actual `test()` method stays engine-specific (different result shape). */
interface HealthCheckable {
    suspend fun isHealthy(port: Int): Boolean
}

fun testerFor(kind: ModelKind): HealthCheckable = when (kind) {
    ModelKind.EMBEDDING -> ModelTesters.embedding
    ModelKind.TRANSCRIPTION -> ModelTesters.transcription
}

/** One shared instance per engine — testers are stateless besides their OkHttpClient, no
 * reason for [ModelRuntimeService] to build a fresh one per call. */
object ModelTesters {
    val embedding = LocalEmbeddingTester()
    val transcription = LocalTranscriptionTester()
    val embeddingCli = LocalEmbeddingCliTester()
    val transcriptionCli = LocalWhisperCliTester()
}
