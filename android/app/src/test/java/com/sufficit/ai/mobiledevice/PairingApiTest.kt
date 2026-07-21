package com.sufficit.ai.mobiledevice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PairingApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = PairingApi(client = OkHttpClient())
    }

    @After
    fun tearDown() {
        try { server.shutdown() } catch (_: Exception) {}
    }

    @Test
    fun `announce success returns all tailnet fields`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"providerId":"p1","tailnetJoinKey":"key1","tailnetLoginServer":"https://login","tailnetNodeName":"node1"}"""
            )
        )

        val result = api.announce(gatewayBaseUrl = server.url("/").toString(), token = "tok", deviceName = "dev")

        assertTrue(result is AnnounceResult.Success)
        result as AnnounceResult.Success
        assertEquals("p1", result.providerId)
        assertEquals("key1", result.tailnetJoinKey)
        assertEquals("https://login", result.tailnetLoginServer)
        assertEquals("node1", result.tailnetNodeName)
    }

    @Test
    fun `announce success with null tailnetJoinKey`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"providerId":"p1","tailnetJoinKey":null}"""))

        val result = api.announce(gatewayBaseUrl = server.url("/").toString(), token = "tok", deviceName = "dev")

        assertTrue(result is AnnounceResult.Success)
        assertNull((result as AnnounceResult.Success).tailnetJoinKey)
    }

    @Test
    fun `announce failure with 401 surfaces error message from body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))

        val result = api.announce(gatewayBaseUrl = server.url("/").toString(), token = "tok", deviceName = "dev")

        assertTrue(result is AnnounceResult.Failure)
        assertEquals("invalid token", (result as AnnounceResult.Failure).message)
    }

    @Test
    fun `announce failure with 500 and no json body falls back to HTTP code`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.announce(gatewayBaseUrl = server.url("/").toString(), token = "tok", deviceName = "dev")

        assertTrue(result is AnnounceResult.Failure)
        assertEquals("HTTP 500", (result as AnnounceResult.Failure).message)
    }

    @Test
    fun `announce failure when connection is refused`() {
        val deadUrl = server.url("/").toString()
        server.shutdown()

        val result = api.announce(gatewayBaseUrl = deadUrl, token = "tok", deviceName = "dev")

        assertTrue(result is AnnounceResult.Failure)
        assertTrue((result as AnnounceResult.Failure).message.isNotEmpty())
    }
}
