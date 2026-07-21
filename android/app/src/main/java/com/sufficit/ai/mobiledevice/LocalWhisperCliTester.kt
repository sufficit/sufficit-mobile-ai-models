package com.sufficit.ai.mobiledevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs whisper-cli (see scripts/build-whisper-server.sh) directly against a GGUF/bin model
 * file — a one-shot process, no HTTP, no persistent server — the local counterpart to
 * [LocalTranscriptionTester]. Proves "does this model load and produce a real transcription"
 * without going through [WhisperServerManager]/the OpenAI-compatible API at all.
 */
class LocalWhisperCliTester {
    suspend fun test(context: Context, modelFile: File): TranscriptionTestResult = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libwhispercli.so")
        if (!binary.exists()) {
            return@withContext TranscriptionTestResult.Failure("binário whisper-cli ausente")
        }

        val workDir = File(context.filesDir, "cli-test").apply { mkdirs() }
        val wavFile = File(workDir, "sample.wav")
        val outputBase = File(workDir, "result")
        val outputJson = File(workDir, "result.json")
        var process: Process? = null
        return@withContext try {
            wavFile.writeBytes(silentWav())

            val builder = ProcessBuilder(
                binary.absolutePath,
                "-m", modelFile.absolutePath,
                "-ng", "-nfa", "-np",
                "-oj", "-of", outputBase.absolutePath,
                wavFile.absolutePath
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            builder.redirectErrorStream(true)
            builder.redirectOutput(File(workDir, "whisper-cli.log"))

            val start = System.currentTimeMillis()
            val p = builder.start()
            process = p
            val finished = p.waitFor(30, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - start
            if (!finished) {
                p.destroyForcibly()
                return@withContext TranscriptionTestResult.Failure("modelo não respondeu a tempo")
            }
            if (p.exitValue() != 0) {
                return@withContext TranscriptionTestResult.Failure("processo saiu com código ${p.exitValue()}")
            }
            if (!outputJson.exists()) {
                return@withContext TranscriptionTestResult.Failure("saída não encontrada")
            }

            val json = JSONObject(outputJson.readText())
            val transcription = json.optJSONArray("transcription")
            val text = if (transcription != null && transcription.length() > 0) {
                transcription.getJSONObject(0).optString("text")
            } else {
                ""
            }
            TranscriptionTestResult.Success(text = text, latencyMs = elapsed)
        } catch (ex: Exception) {
            TranscriptionTestResult.Failure(ex.message ?: "falha no teste")
        } finally {
            process?.destroyForcibly()
            workDir.deleteRecursively()
        }
    }
}
