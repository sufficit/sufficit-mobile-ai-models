package com.sufficit.ai.mobiledevice

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HFModelSummary(
    val repoId: String,
    val downloads: Int
)

data class HFModelFile(
    val repoId: String,
    val fileName: String,
    val downloadUrl: String
)

/**
 * Minimal client for Hugging Face's public model API — no auth needed for search or listing
 * files on public repos. Search returns repos, not files: a repo commonly ships many GGUF
 * quants of wildly different sizes (see ModelSource kdoc), so file selection is a second step
 * (ModelsScreen: tap a repo -> [listGgufFiles] -> pick one), mirroring HF's own web UI flow
 * instead of guessing "the" file for a repo.
 */
class HuggingFaceModelSearch(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    fun search(query: String, limit: Int = 20): List<HFModelSummary> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=$limit"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val array = JSONArray(response.body?.string().orEmpty())
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HFModelSummary(repoId = id, downloads = obj.optInt("downloads"))
            }
        }
    }

    fun listGgufFiles(repoId: String): List<HFModelFile> {
        val url = "https://huggingface.co/api/models/$repoId"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val obj = JSONObject(response.body?.string().orEmpty())
            val siblings = obj.optJSONArray("siblings") ?: return emptyList()
            return (0 until siblings.length()).mapNotNull { i ->
                val fileName = siblings.optJSONObject(i)?.optString("rfilename")
                    ?.takeIf { it.endsWith(".gguf", ignoreCase = true) }
                    ?: return@mapNotNull null
                HFModelFile(
                    repoId = repoId,
                    fileName = fileName,
                    downloadUrl = "https://huggingface.co/$repoId/resolve/main/$fileName"
                )
            }
        }
    }
}
