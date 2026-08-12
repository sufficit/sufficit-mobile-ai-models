package com.sufficit.ai.mobiledevice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import br.com.sufficit.vpn.ipc.ISufficitVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Cliente do único VpnService do dispositivo. Não cria nó, TUN ou runtime WireGuard próprio. */
class SufficitVpnClient(private val context: Context) {
    private val bindMutex = Mutex()
    @Volatile private var remote: ISufficitVpnService? = null
    @Volatile private var bound = false

    @Volatile private var activeConnection: ServiceConnection? = null

    suspend fun connectAndPublish(enrollment: String?): Boolean {
        val service = getService() ?: return false
        return runCatching {
            check(service.protocolVersion >= PROTOCOL_VERSION) { "AIDL VPN incompatível" }
            if (!enrollment.isNullOrBlank()) service.enroll(enrollment)
            service.connect()
            service.registerLocalService(SERVICE_NAME, MODEL_PORT, "tcp")
        }.onSuccess { published ->
            if (published) Log.i(TAG, "serviço 8090 publicado na Sufficit VPN")
        }.onFailure { Log.w(TAG, "não foi possível publicar o serviço 8090", it) }
            .getOrDefault(false)
    }

    suspend fun unregisterLocalService() {
        runCatching { getService()?.unregisterLocalService(SERVICE_NAME, MODEL_PORT, "tcp") }
    }

    suspend fun vpnIpv4(): String? = runCatching {
        val status = getService()?.statusJson ?: return null
        JSONObject(status).optString("vpnIpv4").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun close() {
        activeConnection?.let { runCatching { context.unbindService(it) } }
        activeConnection = null
        remote = null
        bound = false
    }

    private suspend fun getService(): ISufficitVpnService? {
        remote?.let { return it }
        return bindMutex.withLock {
            remote?.let { return@withLock it }
            val result = CompletableDeferred<ISufficitVpnService?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    remote = ISufficitVpnService.Stub.asInterface(binder)
                    result.complete(remote)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    remote = null
                    bound = false
                }

                override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName?) {
                    onServiceDisconnected(name)
                    result.complete(null)
                }
            }
            activeConnection = connection
            bound = context.bindService(
                Intent(BIND_ACTION).setPackage(VPN_PACKAGE),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) result.complete(null)
            val service = withTimeoutOrNull(BIND_TIMEOUT_MS) { result.await() }
            if (service == null) {
                Log.w(TAG, "agente $VPN_PACKAGE indisponível")
                close()
                null
            } else {
                service
            }
        }
    }

    companion object {
        private const val TAG = "SufficitVpnClient"
        private const val VPN_PACKAGE = "br.com.sufficit.vpn"
        private const val BIND_ACTION = "br.com.sufficit.vpn.BIND"
        private const val CONSENT_ACTION = "br.com.sufficit.vpn.action.REQUEST_CONSENT"
        private const val PROTOCOL_VERSION = 1
        private const val MODEL_PORT = 8090
        private const val SERVICE_NAME = "mobile-ai-models"
        private const val BIND_TIMEOUT_MS = 5_000L

        fun requestConsent(context: Context) {
            val intent = Intent(CONSENT_ACTION).setPackage(VPN_PACKAGE)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "Sufficit VPN ainda não está instalada", it) }
        }
    }
}
