package com.sufficit.ai.mobiledevice

data class KnownGoodModel(
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    /** Null for kinds where it doesn't apply (e.g. [ModelKind.TRANSCRIPTION]). */
    val dimensions: Int?,
    val sizeGB: Double,
    val kind: ModelKind
)

/**
 * Hardcoded snapshot of this device's tested-models data, mirroring the Sufficit AI memory
 * observation "Device profile: Samsung Galaxy A51 (SM-A515F) — sufficit-mobile-ai-models
 * embedding server compatibility" (saved via the mcp__Sufficit_AI__memory_save tool during
 * Fase 6 testing). Purpose: the raw Hugging Face search in ModelsScreen returns dozens of
 * quant variants with no way to tell which one actually runs on THIS phone — this list
 * answers "which one is compatible" directly instead of making the user guess.
 *
 * This is the LOCAL, hardcoded half of the feature. The networked half — fetch this same
 * data from the Sufficit AI memory backend keyed by Build.MODEL/Build.HARDWARE, refreshed in
 * the background after a Sufficit (Modo B/OAuth) login, so a NEW device type benefits from
 * every other device's testing instead of only this one's — is a follow-up, not built yet.
 */
object DeviceModelCatalog {
    val all = listOf(
        KnownGoodModel(
            label = "gte-Qwen2-1.5B-instruct (Q4_K_M) — 1536 dimensões",
            fileName = "gte-Qwen2-1.5B-instruct-Q4_K_M.gguf",
            downloadUrl = ModelSource.DOWNLOAD_URL,
            dimensions = 1536,
            sizeGB = 1.12,
            kind = ModelKind.EMBEDDING
        ),
        KnownGoodModel(
            label = "Qwen3-Embedding-0.6B (Q8_0) — 1024 dimensões",
            fileName = "Qwen3-Embedding-0.6B-Q8_0.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf",
            dimensions = 1024,
            sizeGB = 0.64,
            kind = ModelKind.EMBEDDING
        ),
        // whisper.cpp's own ggml .bin models (not GGUF) — official ggerganov/whisper.cpp
        // Hugging Face repo. Multilingual variants (no ".en" suffix): this device serves a
        // Sufficit-wide tailnet, not a single-language deployment.
        KnownGoodModel(
            label = "Whisper base (multilíngue)",
            fileName = "ggml-base.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            dimensions = null,
            sizeGB = 0.14,
            kind = ModelKind.TRANSCRIPTION
        ),
        KnownGoodModel(
            label = "Whisper tiny (multilíngue)",
            fileName = "ggml-tiny.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            dimensions = null,
            sizeGB = 0.075,
            kind = ModelKind.TRANSCRIPTION
        )
        // Tried ggml-large-v3-turbo-q5_0.bin (574MB) on the Galaxy A51 reference device: loads
        // fine, no crash, /health stays responsive, but the full-size 32-layer encoder (only the
        // decoder is pruned in "turbo") never finished transcribing even 1s of audio after 3.5+
        // minutes at ~550% CPU with -ng (CPU-only build, no GPU backend compiled in). Not listed
        // here — this device's Cortex-A55 CPU can't run it in any practical amount of time.
        // Would need a GPU backend (Vulkan, since Samsung restricts third-party OpenCL access)
        // to be viable; not attempted.
    )

    fun recommended(kind: ModelKind): List<KnownGoodModel> = all.filter { it.kind == kind }
}
