package com.sufficit.ai.mobiledevice

import android.util.Log
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * OkHttp (unlike Chrome, which implements Happy Eyeballs — races IPv4/IPv6 and keeps
 * whichever connects first, in ~300ms) tries DNS results in order and gives each address
 * its full connectTimeout budget before moving to the next. Empirically, on this network,
 * IPv6 to ai.sufficit.com.br hangs for the entire connect+read timeout window (~25s) before
 * falling back to IPv4, which then succeeds instantly — Chrome and a plain curl both proved
 * the server side is fine (sub-second). Filtering to IPv4-only sidesteps the slow IPv6 path
 * entirely rather than trying to out-race it.
 */
private object Ipv4OnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4Only = all.filterIsInstance<java.net.Inet4Address>()
        return ipv4Only.ifEmpty { all }
    }
}

sealed class AnnounceResult {
    /**
     * [vpnEnrollment] é o envelope opaco emitido pelo Network Control. Os campos
     * tailnet* permanecem apenas durante a transição de versões do backend.
     * AIMobileDeviceAnnounceResult on the backend (see
     * AIAdminRuntime.MobileDevices.cs): server-assigned, never sent by this
     * client. [tailnetJoinKey] is only present while the device hasn't joined
     * the tailnet yet — a one-time preauthkey for [TailscaleManager].
     */
    data class Success(
        val providerId: String,
        val vpnEnrollment: String? = null,
        val tailnetJoinKey: String? = null,
        val tailnetLoginServer: String? = null,
        val tailnetNodeName: String? = null
    ) : AnnounceResult()

    data class Failure(val message: String) : AnnounceResult()
}

/**
 * Talks to the device gateway added by
 * docs/PLAN-202607091200-mobile-device-ai-provider.md in sufficit-ai. Two
 * pairing modes, same underlying upsert on the server side:
 *  - Modo A: [announce] — POST {gatewayBaseUrl}/api/ai/mobile-devices/pairing/announce,
 *    authenticated with `Authorization: Sufficit-Pairing <token>` so the
 *    credential never leaks through URLs or request logs.
 *  - Modo B: [selfAnnounce] — POST {gatewayBaseUrl}/api/ai/mobile-devices/self-announce,
 *    authenticated with the user's own OAuth Bearer token instead of a
 *    copy/pasted token.
 *
 * No model list in the request — sufficit-ai discovers models straight from
 * the device's own `GET /v1/models` (llama-server already exposes it), same
 * as any other OpenAI-compatible provider.
 *
 * `tailnetAddress` is deliberately never authoritative from the device — it's
 * omitted entirely for now (network reachability is a server-side concern,
 * see PLAN Fase 3/4). Registration/heartbeat succeed regardless; the address
 * gets filled in once Tailscale integration lands.
 */
class PairingApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(Ipv4OnlyDns)
        .addInterceptor { chain ->
            val request = chain.request()
            val start = System.currentTimeMillis()
            Log.d(TAG, "-> ${request.method} ${request.url}")
            try {
                val response = chain.proceed(request)
                Log.d(TAG, "<- ${response.code} ${request.url} (${System.currentTimeMillis() - start}ms)")
                response
            } catch (ex: Exception) {
                Log.w(TAG, "<- FAILED ${request.url} (${System.currentTimeMillis() - start}ms): ${ex.javaClass.simpleName} ${ex.message}")
                throw ex
            }
        }
        .build()
) {
    fun announce(
        gatewayBaseUrl: String,
        token: String,
        deviceName: String,
        deviceModel: String,
        appVersion: String
    ): AnnounceResult {
        val body = announceBody(deviceName, deviceModel, appVersion)
        val url = gatewayBaseUrl.trimEnd('/') + "/api/ai/mobile-devices/pairing/announce"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Sufficit-Pairing $token")
            .post(body)
            .build()
        return execute(request)
    }

    fun selfAnnounce(
        gatewayBaseUrl: String,
        accessToken: String,
        deviceInstanceId: String,
        deviceName: String,
        deviceModel: String,
        appVersion: String,
        contextId: String?
    ): AnnounceResult {
        val body = announceBody(deviceName, deviceModel, appVersion) {
            put("deviceInstanceId", deviceInstanceId)
            contextId?.let { put("contextId", it) }
        }
        val url = gatewayBaseUrl.trimEnd('/') + "/api/ai/mobile-devices/self-announce"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        return execute(request)
    }

    private fun announceBody(
        deviceName: String,
        deviceModel: String,
        appVersion: String,
        extra: JSONObject.() -> Unit = {}
    ) = JSONObject().apply {
        put("deviceName", deviceName)
        put("deviceModel", deviceModel)
        put("appVersion", appVersion)
        extra()
    }.toString().toRequestBody("application/json".toMediaType())

    private fun execute(request: Request): AnnounceResult = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                return AnnounceResult.Failure(error?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
            }
            val json = JSONObject(text)
            AnnounceResult.Success(
                providerId = json.optString("providerId"),
                vpnEnrollment = json.optStringOrNull("vpnEnrollment"),
                tailnetJoinKey = json.optStringOrNull("tailnetJoinKey"),
                tailnetLoginServer = json.optStringOrNull("tailnetLoginServer"),
                tailnetNodeName = json.optStringOrNull("tailnetNodeName")
            )
        }
    } catch (ex: IOException) {
        AnnounceResult.Failure(ex.message ?: "network error")
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null

    private companion object {
        const val TAG = "PairingApi"
    }
}
