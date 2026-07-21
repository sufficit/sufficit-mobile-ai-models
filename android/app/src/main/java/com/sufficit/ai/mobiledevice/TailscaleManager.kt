package com.sufficit.ai.mobiledevice

import android.content.Context
import java.io.File
import java.net.NetworkInterface

/**
 * Thin Kotlin wrapper around the tsgo (tsnet-in-gomobile) bindings — see
 * android-tsgo/tsgo.go. Empirically validated end-to-end on a real device
 * (Samsung Galaxy A51): joins the Sufficit tailnet (self-hosted Headscale),
 * gets a 100.64.0.0/10 address, reachable from the *-ai backend servers.
 *
 * Two Android-specific fixes were required in the Go layer for this to work
 * at all — both documented there:
 *  - tsnet.Server can't enumerate interfaces itself (net.Interfaces() needs a
 *    netlink socket, blocked by SELinux for regular apps) — interfaces are
 *    collected here via the standard java.net.NetworkInterface API instead
 *    and pushed into Go via tsgo.SetInterfacesJSON before Start().
 *  - tailscale's logpolicy panics without $TMPDIR set (no /tmp, no $HOME,
 *    no os.UserCacheDir() support on Android) — handled entirely on the Go
 *    side using the stateDir passed to Start().
 */
class TailscaleManager(private val context: Context) {

    /**
     * Joins the tailnet using the preauthkey/hostname/login-server issued by the
     * backend on announce (AIMobileDeviceAnnounceResult.TailnetJoinKey/
     * TailnetLoginServerUrl/TailnetNodeName). Returns the parsed Status JSON.
     * Safe to call from a background thread only — blocks on the initial
     * WireGuard/DERP handshake.
     */
    fun start(loginServerUrl: String, authKey: String, hostname: String): String {
        val interfacesJson = collectInterfacesJson()
        val setInterfacesErr = tsgo.Tsgo.setInterfacesJSON(interfacesJson)
        android.util.Log.d("TailscaleManager", "setInterfacesJSON(\"$interfacesJson\") -> \"$setInterfacesErr\"")

        val stateDir = File(context.filesDir, "tsgo-state").apply { mkdirs() }
        return tsgo.Tsgo.start(loginServerUrl, authKey, hostname, stateDir.absolutePath)
    }

    fun stop() {
        tsgo.Tsgo.stop()
    }

    fun status(): String = tsgo.Tsgo.statusJSON()

    /** True only while *this process* has an active tsnet session — not persisted, doesn't
     * survive an app restart even if the node stays registered server-side. */
    fun isRunning(): Boolean = status().contains("\"running\":true")

    /** IPv4 da tailnet do nó atual, ou null se não conectado. */
    fun tailnetIp(): String? = try {
        org.json.JSONObject(status()).optString("tailnetIp").takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * Android's Go runtime can't enumerate network interfaces itself (raw netlink
     * socket is blocked by SELinux for regular apps) — collect them via the
     * standard Java API instead and hand them to tsgo.SetInterfacesJSON.
     */
    private fun collectInterfacesJson(): String {
        val items = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                val addr = ia.address
                if (addr != null) "${addr.hostAddress}/${ia.networkPrefixLength}" else null
            }
            var flags = 0
            if (ni.isUp) flags = flags or 1
            if (ni.supportsMulticast()) flags = flags or 16
            if (ni.isLoopback) flags = flags or 4
            if (ni.isPointToPoint) flags = flags or 8

            val addrsJson = addrs.joinToString(",") { "\"$it\"" }
            items.add(
                "{\"name\":\"${ni.name}\",\"index\":${ni.index},\"mtu\":${ni.mtu},\"flags\":$flags,\"addrs\":[$addrsJson]}"
            )
        }
        return "[${items.joinToString(",")}]"
    }
}
