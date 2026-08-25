package com.sufficit.ai.mobiledevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelCatalogTest {
    @Test
    fun `catalog filenames are unique and use secure download URLs`() {
        val models = DeviceModelCatalog.all

        assertEquals(models.size, models.map { it.fileName }.distinct().size)
        assertTrue(models.all { it.downloadUrl.startsWith("https://") })
        assertTrue(models.all { it.sizeGB > 0.0 })
    }

    @Test
    fun `quality transcription profiles use multilingual whisper models`() {
        val transcription = DeviceModelCatalog.recommended(ModelKind.TRANSCRIPTION)

        assertTrue(transcription.any { it.fileName == "ggml-small-q8_0.bin" })
        assertTrue(transcription.any { it.fileName == "ggml-medium-q5_0.bin" })
        assertTrue(transcription.any { it.fileName == "ggml-large-v3-turbo-q5_0.bin" })
        assertTrue(transcription.none { it.fileName.contains(".en.") || it.fileName.endsWith(".en.bin") })
        assertTrue(transcription.all { !it.details.isNullOrBlank() })
    }
}
