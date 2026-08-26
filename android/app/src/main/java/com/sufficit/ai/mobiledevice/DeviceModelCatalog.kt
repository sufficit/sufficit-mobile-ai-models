package com.sufficit.ai.mobiledevice

data class KnownGoodModel(
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    /** Null for kinds where it doesn't apply (e.g. [ModelKind.TRANSCRIPTION]). */
    val dimensions: Int?,
    /** Whether the model was trained for safe Matryoshka output truncation. */
    val supportsDimensions: Boolean = false,
    /** Smallest useful output accepted when [supportsDimensions] is true. */
    val minDimensions: Int? = null,
    val sizeGB: Double,
    val kind: ModelKind,
    /** Device-specific guidance shown below the size. Null for models without a benchmark. */
    val details: String? = null
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
            supportsDimensions = true,
            minDimensions = 32,
            sizeGB = 0.64,
            kind = ModelKind.EMBEDDING
        ),
        // whisper.cpp's own ggml .bin models (not GGUF) — official ggerganov/whisper.cpp
        // Hugging Face repo. Multilingual variants (no ".en" suffix): this device serves a
        // Sufficit-wide tailnet, not a single-language deployment.
        KnownGoodModel(
            label = "Whisper Large v3 Turbo (Q5_0) — qualidade alta",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            dimensions = null,
            sizeGB = 0.57,
            kind = ModelKind.TRANSCRIPTION,
            details = "Assíncrono; melhor leitura geral no Galaxy A51 (~7min55s para 7,6s telefônicos)"
        ),
        KnownGoodModel(
            label = "Whisper Medium (Q5_0) — dígitos separados",
            fileName = "ggml-medium-q5_0.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
            dimensions = null,
            sizeGB = 0.54,
            kind = ModelKind.TRANSCRIPTION,
            details = "Assíncrono; preservou melhor números ditados (~10min14s para 7,6s telefônicos)"
        ),
        KnownGoodModel(
            label = "Whisper Small (Q8_0) — melhor equilíbrio",
            fileName = "ggml-small-q8_0.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
            dimensions = null,
            sizeGB = 0.26,
            kind = ModelKind.TRANSCRIPTION,
            details = "Recomendado quando memória importa (~4min49s para 7,6s telefônicos)"
        ),
        KnownGoodModel(
            label = "Whisper base (multilíngue) — compatibilidade",
            fileName = "ggml-base.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            dimensions = null,
            sizeGB = 0.14,
            kind = ModelKind.TRANSCRIPTION,
            details = "Baixa precisão no corpus telefônico do Galaxy A51"
        ),
        KnownGoodModel(
            label = "Whisper tiny (multilíngue) — smoke test",
            fileName = "ggml-tiny.bin",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            dimensions = null,
            sizeGB = 0.075,
            kind = ModelKind.TRANSCRIPTION,
            details = "Menor download; não recomendado para transcrição telefônica de qualidade"
        )
    )

    fun recommended(kind: ModelKind): List<KnownGoodModel> = all.filter { it.kind == kind }
}
