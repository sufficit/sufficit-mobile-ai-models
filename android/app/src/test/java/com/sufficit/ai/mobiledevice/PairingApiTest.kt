package com.sufficit.ai.mobiledevice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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

        val result = api.announce(
            gatewayBaseUrl = server.url("/").toString(),
            token = "tok",
            deviceName = "dev",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)"
        )

        assertTrue(result is AnnounceResult.Success)
        result as AnnounceResult.Success
        assertEquals("p1", result.providerId)
        assertEquals("key1", result.tailnetJoinKey)
        assertEquals("https://login", result.tailnetLoginServer)
        assertEquals("node1", result.tailnetNodeName)

        val request = server.takeRequest()
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("dev", payload.getString("deviceName"))
        assertEquals("Samsung SM-A515F", payload.getString("deviceModel"))
        assertEquals("0.4.1 (5)", payload.getString("appVersion"))
    }

    @Test
    fun `announce success with null tailnetJoinKey`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"providerId":"p1","tailnetJoinKey":null}"""))

        val result = api.announce(
            gatewayBaseUrl = server.url("/").toString(),
            token = "tok",
            deviceName = "dev",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)"
        )

        assertTrue(result is AnnounceResult.Success)
        assertNull((result as AnnounceResult.Success).tailnetJoinKey)
    }

    @Test
    fun `announce failure with 401 surfaces error message from body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid token"}"""))

        val result = api.announce(
            gatewayBaseUrl = server.url("/").toString(),
            token = "tok",
            deviceName = "dev",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)"
        )

        assertTrue(result is AnnounceResult.Failure)
        assertEquals("invalid token", (result as AnnounceResult.Failure).message)
    }

    @Test
    fun `announce failure with 500 and no json body falls back to HTTP code`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.announce(
            gatewayBaseUrl = server.url("/").toString(),
            token = "tok",
            deviceName = "dev",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)"
        )

        assertTrue(result is AnnounceResult.Failure)
        assertEquals("HTTP 500", (result as AnnounceResult.Failure).message)
    }

    @Test
    fun `announce failure when connection is refused`() {
        val deadUrl = server.url("/").toString()
        server.shutdown()

        val result = api.announce(
            gatewayBaseUrl = deadUrl,
            token = "tok",
            deviceName = "dev",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)"
        )

        assertTrue(result is AnnounceResult.Failure)
        assertTrue((result as AnnounceResult.Failure).message.isNotEmpty())
    }

    @Test
    fun `self announce sends device inventory with OAuth identity`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"providerId":"p1"}"""))

        val result = api.selfAnnounce(
            gatewayBaseUrl = server.url("/").toString(),
            accessToken = "access-token",
            deviceInstanceId = "instance-1",
            deviceName = "SM-A515F (user 10)",
            deviceModel = "Samsung SM-A515F",
            appVersion = "0.4.1 (5)",
            contextId = "ctx-1"
        )

        assertTrue(result is AnnounceResult.Success)
        val request = server.takeRequest()
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("instance-1", payload.getString("deviceInstanceId"))
        assertEquals("SM-A515F (user 10)", payload.getString("deviceName"))
        assertEquals("Samsung SM-A515F", payload.getString("deviceModel"))
        assertEquals("0.4.1 (5)", payload.getString("appVersion"))
        assertEquals("ctx-1", payload.getString("contextId"))
    }
}
