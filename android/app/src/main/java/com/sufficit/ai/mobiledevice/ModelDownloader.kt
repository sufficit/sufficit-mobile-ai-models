package com.sufficit.ai.mobiledevice

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * gte-Qwen2-1.5B-instruct (Q4_K_M, 1.12GB, 1536-dim) — the default suggestion pre-filled on
 * ModelsScreen's search, not a hardcoded destination anymore (Fase 6: [ModelDownloader.download]
 * takes any URL the user picked from search results).
 */
object ModelSource {
    const val DOWNLOAD_URL =
        "https://huggingface.co/gaianet/gte-Qwen2-1.5B-instruct-GGUF/resolve/main/gte-Qwen2-1.5B-instruct-Q4_K_M.gguf"
}

sealed class ModelDownloadResult {
    object Success : ModelDownloadResult()
    data class Failure(val message: String) : ModelDownloadResult()
}

/**
 * Streams a GGUF file (from Hugging Face search results, or any direct URL) to [destination] —
 * no bundling, no separate app: bundling a real embedding model made the APK enormous without
 * even being able to fit in memory on the reference test device. Downloads to a .part file
 * first and renames on success so a half-downloaded file is never mistaken for a usable model.
 *
 * Resumable: a ~1.1GB download over a real connection (or through however many app
 * reinstalls/restarts a debugging session needs) getting interrupted and restarting from byte
 * zero every single time is its own kind of bug — this sends `Range: bytes=<existing>-` when a
 * `.part` file already has bytes in it, and only falls back to starting over if the server
 * doesn't honor it (some don't — checked via the 206 status, not assumed). A failed/interrupted
 * download no longer deletes the `.part` file, specifically so the next attempt has something
 * to resume from.
 */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    // Injectable so JVM unit tests can skip it — android.os.StatFs isn't available outside a
    // real device/Robolectric. Default: real free space, or null (skip the check) if StatFs
    // itself throws for any reason.
    private val availableBytes: (File) -> Long? = { dir ->
        try {
            android.os.StatFs(dir.absolutePath).availableBytes
        } catch (_: Throwable) {
            null
        }
    }
) {
    suspend fun download(url: String, destination: File, onProgress: (Float) -> Unit): ModelDownloadResult {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, destination.name + ".part")
        val alreadyDownloaded = if (tmp.exists()) tmp.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (alreadyDownloaded > 0) {
            requestBuilder.header("Range", "bytes=$alreadyDownloaded-")
        }

        var total = -1L
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return ModelDownloadResult.Failure("HTTP ${response.code}")
                }

                val body = response.body ?: return ModelDownloadResult.Failure("resposta vazia")

                // 206 = server honored Range, this is a continuation — append and count the
                // bytes already on disk toward progress. Anything else (a plain 200, meaning
                // either we asked for the whole file or the server doesn't support Range and
                // just sent it all again) means start the .part file over from scratch.
                val resuming = response.code == 206
                val startOffset = if (resuming) alreadyDownloaded else 0L
                total = totalSize(response, startOffset, body.contentLength())

                if (total > 0) {
                    val required = total - startOffset + SAFETY_MARGIN_BYTES
                    val free = availableBytes(destination.parentFile!!)
                    if (free != null && free < required) {
                        return ModelDownloadResult.Failure(
                            "espaço insuficiente: precisa de ~${required / (1024 * 1024)}MB livres"
                        )
                    }
                }

                body.byteStream().use { input ->
                    FileOutputStream(tmp, resuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = startOffset
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
            }

            // Magic bytes differ by model format: GGUF ('G','G','U','F') for embedding models,
            // legacy ggml (GGML_FILE_MAGIC 0x67676d6c, little-endian on disk as 'l','m','g','g')
            // for whisper.cpp's .bin models — confirmed against a real ggml-tiny.bin download,
            // not assumed. A .part file truncated by a resume against a remote file that changed
            // underneath must never become an "installed" model.
            val (expectedMagic, formatName) = magicFor(destination)
            val magic = ByteArray(4)
            java.io.FileInputStream(tmp).use { it.read(magic) }
            if (!magic.contentEquals(expectedMagic)) {
                tmp.delete()
                return ModelDownloadResult.Failure("arquivo baixado não é $formatName válido — baixe novamente")
            }
            if (total > 0 && tmp.length() != total) {
                return ModelDownloadResult.Failure("download incompleto (${tmp.length()}/$total bytes) — tente de novo para retomar")
            }

            if (!tmp.renameTo(destination)) {
                return ModelDownloadResult.Failure("não foi possível finalizar o arquivo")
            }
            ModelDownloadResult.Success
        } catch (ex: Exception) {
            // Deliberately NOT deleting tmp — a network blip or app restart mid-download must
            // not throw away progress; the next call resumes from here via the Range header
            // above.
            ModelDownloadResult.Failure(ex.message ?: "falha no download")
        }
    }

    /** GGUF for `.gguf` (embedding models), legacy ggml magic for anything else (whisper.cpp's
     * `.bin` models — see [ModelKind.fileExtension]). */
    private fun magicFor(destination: File): Pair<ByteArray, String> =
        if (destination.name.endsWith(".gguf")) {
            byteArrayOf(0x47, 0x47, 0x55, 0x46) to "GGUF"
        } else {
            byteArrayOf(0x6c, 0x6d, 0x67, 0x67) to "ggml"
        }

    /** Prefers the authoritative total from Content-Range ("bytes start-end/total") on a 206
     * response — Content-Length on a partial response is only the remaining bytes, not the
     * whole file, and would make the progress bar jump backwards on every resume otherwise. */
    private fun totalSize(response: okhttp3.Response, startOffset: Long, contentLength: Long): Long {
        val contentRange = response.header("Content-Range")
        val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
        return totalFromRange ?: (startOffset + contentLength.coerceAtLeast(0L))
    }

    private companion object {
        const val SAFETY_MARGIN_BYTES = 250L * 1024 * 1024
    }
}
