package com.sufficit.ai.mobiledevice

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    // StatFs isn't available in a plain JVM unit test — skip the free-space check (PLAN T1.5/T5.1).
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ModelDownloader(client = OkHttpClient(), availableBytes = { null })
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
    }

    @Test
    fun `full download succeeds when body starts with GGUF magic`() = runBlocking {
        val content = "GGUF" + "x".repeat(20)
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val dest = File(tempFolder.root, "model.gguf")

        val result = downloader.download(server.url("/model.gguf").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Success)
        assertTrue(dest.exists())
        assertEquals(content, dest.readText())
        assertFalse(File(tempFolder.root, "model.gguf.part").exists())
    }

    @Test
    fun `download fails and deletes part file when body is not GGUF`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("NOTAGGUFFILEDATA"))
        val dest = File(tempFolder.root, "model.gguf")

        val result = downloader.download(server.url("/model.gguf").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Failure)
        assertFalse(dest.exists())
        assertFalse(File(tempFolder.root, "model.gguf.part").exists())
    }

    @Test
    fun `full download succeeds for ggml bin when body starts with ggml magic`() = runBlocking {
        // whisper.cpp's .bin models use legacy ggml magic (GGML_FILE_MAGIC, on-disk bytes
        // 'l','m','g','g'), not GGUF — confirmed against a real ggml-tiny.bin download.
        val content = "lmgg" + "x".repeat(20)
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val dest = File(tempFolder.root, "ggml-tiny.bin")

        val result = downloader.download(server.url("/ggml-tiny.bin").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Success)
        assertTrue(dest.exists())
        assertEquals(content, dest.readText())
    }

    @Test
    fun `download fails for ggml bin when body starts with GGUF magic instead`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("GGUFxxxxxxxxxxxxxxxx"))
        val dest = File(tempFolder.root, "ggml-tiny.bin")

        val result = downloader.download(server.url("/ggml-tiny.bin").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Failure)
        assertFalse(dest.exists())
    }

    @Test
    fun `resume appends to existing part file on 206`() = runBlocking {
        val dest = File(tempFolder.root, "model.gguf")
        val part = File(tempFolder.root, "model.gguf.part")
        part.writeBytes("GGUF".toByteArray())
        val rest = "restofthefile"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 4-${4 + rest.length - 1}/${4 + rest.length}")
                .setBody(rest)
        )

        val result = downloader.download(server.url("/model.gguf").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Success)
        assertEquals("GGUF$rest", dest.readText())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `restarts from zero when server ignores range and returns 200`() = runBlocking {
        val dest = File(tempFolder.root, "model.gguf")
        val part = File(tempFolder.root, "model.gguf.part")
        part.writeBytes("OLDDATA".toByteArray())
        val fresh = "GGUF" + "freshcontent"
        server.enqueue(MockResponse().setResponseCode(200).setBody(fresh))

        val result = downloader.download(server.url("/model.gguf").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Success)
        assertEquals(fresh, dest.readText())
    }

    @Test
    fun `part file is preserved when the connection drops mid-download`() = runBlocking {
        val dest = File(tempFolder.root, "model.gguf")
        val content = "GGUF" + "x".repeat(5000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(content)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val result = downloader.download(server.url("/model.gguf").toString(), dest) {}

        assertTrue(result is ModelDownloadResult.Failure)
        assertFalse(dest.exists())
        assertTrue(File(tempFolder.root, "model.gguf.part").exists())
    }
}
