package com.sufficit.ai.mobiledevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs llama-embedding (see scripts/build-llama-embedding.sh) directly against a GGUF file —
 * a one-shot process, no HTTP, no persistent server — the local counterpart to
 * [LocalEmbeddingTester]. Proves "does this model load and produce a real embedding" without
 * going through [LlamaServerManager]/the OpenAI-compatible API at all.
 *
 * Cross-compiled from the same llama.cpp source tag as the bundled libllama.so et al (see the
 * build script) specifically because the official prebuilt release's own llama-cli/
 * llama-embedding binaries fail to dynamically link on this app's libc++ (NDK r29 vs the r27c
 * everything else here is built with).
 */
class LocalEmbeddingCliTester {
    suspend fun test(context: Context, modelFile: File): EmbeddingTestResult = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libllamaembedding.so")
        if (!binary.exists()) {
            return@withContext EmbeddingTestResult.Failure("binário llama-embedding ausente")
        }

        val workDir = File(context.filesDir, "cli-test").apply { mkdirs() }
        val errorLog = File(workDir, "llama-embedding.log")
        var process: Process? = null
        return@withContext try {
            val builder = ProcessBuilder(
                binary.absolutePath,
                "-m", modelFile.absolutePath,
                "-p", "test",
                "--embd-output-format", "json",
                "--embd-normalize", "2"
            )
            builder.environment()["LD_LIBRARY_PATH"] = nativeDir
            // llama-embedding's own progress/model-load logging goes to stderr — keep it out of
            // stdout so the JSON response is the only thing there to parse.
            builder.redirectError(errorLog)

            val start = System.currentTimeMillis()
            val p = builder.start()
            process = p
            val stdout = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor(30, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - start
            if (!finished) {
                p.destroyForcibly()
                return@withContext EmbeddingTestResult.Failure("modelo não respondeu a tempo")
            }
            if (p.exitValue() != 0) {
                return@withContext EmbeddingTestResult.Failure("processo saiu com código ${p.exitValue()}")
            }

            val jsonStart = stdout.indexOf('{')
            val jsonEnd = stdout.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) {
                return@withContext EmbeddingTestResult.Failure("saída sem JSON")
            }
            val json = JSONObject(stdout.substring(jsonStart, jsonEnd + 1))
            val embedding = json.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding")
                ?: return@withContext EmbeddingTestResult.Failure("resposta sem embedding")
            EmbeddingTestResult.Success(dimensions = embedding.length(), latencyMs = elapsed)
        } catch (ex: Exception) {
            EmbeddingTestResult.Failure(ex.message ?: "falha no teste")
        } finally {
            process?.destroyForcibly()
            workDir.deleteRecursively()
        }
    }
}
