package com.sufficit.ai.mobiledevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Background heartbeat — PLAN Fase 4 "heartbeat com política de bateria".
 * Keeps calling [performSync] periodically so the device stays "Online" in
 * /ai/mobile-devices without the user having to reopen the app and tap
 * "Sincronizar agora". Interval adapts to charging state: frequent while
 * plugged in, sparse on battery — matches the same reasoning as
 * sufficit-android-ai-gateway's ForegroundService (survive Doze, don't drain
 * the battery of a phone that's supposed to sit idle serving inference).
 *
 * Deliberately knows nothing about llama-server or model downloads anymore (Fase 6) — that
 * lives in [ModelRuntimeService], in its own separate process. This service is the "API"
 * layer (self-announce/tailnet heartbeat to the Sufficit backend) and must keep running even
 * if model loading/downloading crashes; splitting them into two processes is the only way
 * Android actually guarantees a crash in one can't take the other down.
 *
 * Started once the device is paired (Home screen), stopped on logout.
 *
 * Single owner of [performSync] (fix for the two-processes-syncing bug — see PLAN T1.1):
 * the UI process only sends [ACTION_SYNC_NOW]/[ACTION_LOGOUT] and listens for
 * [ACTION_SYNC_STATE]; it never calls [performSync] or touches [TailscaleManager] itself.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    private lateinit var store: PairingStore
    private lateinit var api: PairingApi
    private lateinit var oauth: OAuthManager
    private lateinit var tailscale: TailscaleManager
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var modelStatusReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        store = PairingStore(applicationContext)
        api = PairingApi()
        oauth = OAuthManager(applicationContext)
        tailscale = TailscaleManager(applicationContext)

        // tsnet snapshots interfaces once at Start() (SetInterfacesJSON) — a WiFi<->4G switch
        // or any other network change leaves it stuck with a stale list until the process dies
        // (PLAN T3.3). Drop the session here so the next syncOnce() reconnects with fresh ones.
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Also fires on this service's initial startup — syncOnce() is idempotent, no
                // harm done.
                scope.launch {
                    if (tailscale.isRunning()) tailscale.stop()
                    syncOnce()
                }
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)

        // tsgo.Tsgo's Go globals are per-OS-process — this process (:sync) is the one actually
        // running the tsnet/HTTP proxy that answers GET /v1/models and fills transcription
        // responses' "model" field, but ModelRuntimeService (which knows which transcription
        // model is configured) runs in :modelruntime, a completely separate process with its
        // own independent copy of the same Go library. Cross-process broadcast is the only way
        // to bridge that — reusing the status broadcast every other screen already listens to,
        // rather than inventing new IPC (PLAN: Whisper API compatibility with
        // sufficit-services-whisper).
        val syncRegistry = ModelRegistry(applicationContext)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ModelRuntimeService.ACTION_STATUS_CHANGED) return
                val name = intent.getStringExtra(ModelRuntimeService.EXTRA_ACTIVE_TRANSCRIPTION_MODEL).orEmpty()
                tsgo.Tsgo.setActiveTranscriptionModel(name)

                // The actual embedding model load/unload now happens here — this process is
                // the only one running tsgo's HTTP server (see NativeEmbeddingManager's kdoc
                // for the full cross-process story). ModelRuntimeService (:modelruntime) can
                // only ever record *intent*; this is where it's carried out for real.
                // loadEmbeddingModel is idempotent (no-ops if this exact path is already
                // loaded) — no need to track "did we already load this" separately here.
                val embeddingPath = intent.getStringExtra(ModelRuntimeService.EXTRA_EMBEDDING_MODEL_PATH)
                if (embeddingPath.isNullOrEmpty()) {
                    tsgo.Tsgo.unloadEmbeddingModel()
                } else {
                    val loadError = tsgo.Tsgo.loadEmbeddingModel(embeddingPath)
                    if (loadError.isNotEmpty()) {
                        android.util.Log.e("SyncForegroundService", "loadEmbeddingModel(\"$embeddingPath\") failed: $loadError")
                    }
                }

                // Every installed Whisper model should be discoverable (GET /v1/models), not
                // just whichever one is currently selected — a device commonly has more than
                // one downloaded at once. installedModels() is a plain filesystem scan, safe to
                // call from this process directly (unlike the SharedPreferences-backed "active
                // model" name above, which needs the broadcast to avoid a stale cross-process
                // read).
                val installedNames = syncRegistry.installedModels(applicationContext, ModelKind.TRANSCRIPTION)
                    .map { it.name }
                val installedNamesJson = org.json.JSONArray(installedNames).toString()
                tsgo.Tsgo.setInstalledTranscriptionModels(installedNamesJson)

                // Same idea for embeddings — a device can have more than one downloaded (e.g.
                // both DeviceModelCatalog recommendations). Only ONE .gguf is ever loaded at a
                // time (NativeEmbeddingManager), so any OTHER installed file needs its catalog
                // entry synthesized here rather than read off the live model; aliasFor is
                // deterministic from the filename alone (confirmed against a real device
                // response), so the id is guaranteed to match what the loaded model itself
                // would report — dimensions come from DeviceModelCatalog when the file matches
                // a known recommendation, 0 (omitted) otherwise.
                val installedEmbeddings = syncRegistry.installedModels(applicationContext, ModelKind.EMBEDDING)
                    .map { file ->
                        val dimensions = DeviceModelCatalog.all
                            .firstOrNull { it.kind == ModelKind.EMBEDDING && it.fileName == file.name }
                            ?.dimensions ?: 0
                        org.json.JSONObject()
                            .put("id", NativeEmbeddingManager.aliasFor(file))
                            .put("dimensions", dimensions)
                    }
                val installedEmbeddingsJson = org.json.JSONArray(installedEmbeddings).toString()
                tsgo.Tsgo.setInstalledEmbeddingModels(installedEmbeddingsJson)

                android.util.Log.i(
                    "SyncForegroundService",
                    "pushing models to tsgo (this process): active transcription=\"$name\", " +
                        "installed transcription=$installedNamesJson, installed embeddings=$installedEmbeddingsJson"
                )
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ModelRuntimeService.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        modelStatusReceiver = receiver
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        ModelRuntimeService.start(applicationContext)
        // Forces an immediate ACTION_STATUS_CHANGED broadcast rather than waiting for
        // ModelRuntimeService's own keep-alive loop to get around to one — this process needs
        // the current transcription model name as soon as possible, not eventually.
        ModelRuntimeService.queryStatus(applicationContext)

        when (intent?.action) {
            ACTION_SYNC_NOW -> scope.launch { syncOnce() }
            ACTION_LOGOUT -> {
                loopJob?.cancel()
                scope.launch {
                    tailscale.stop()
                    File(filesDir, "tsgo-state").deleteRecursively()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> ensureLoopRunning()
        }

        return START_STICKY
    }

    private fun ensureLoopRunning() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                syncOnce()
                delay(nextIntervalMs())
            }
        }
    }

    private suspend fun syncOnce() {
        if (!store.isPaired()) return
        val result = performSync(store, api, oauth, tailscale)
        val message = when (result) {
            is AnnounceResult.Success -> getString(R.string.sync_success)
            is AnnounceResult.Failure -> getString(R.string.sync_failure, result.message)
        }
        updateNotification(message)
        sendBroadcast(
            Intent(ACTION_SYNC_STATE).setPackage(packageName)
                .putExtra(EXTRA_SYNC_SUCCESS, result is AnnounceResult.Success)
                .putExtra(EXTRA_SYNC_MESSAGE, message)
                .putExtra(EXTRA_TAILNET_IP, tailscale.tailnetIp())
                .putExtra(EXTRA_LAST_SYNC_AT_MS, System.currentTimeMillis())
        )
    }

    override fun onDestroy() {
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        modelStatusReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        oauth.dispose()
        // The tsnet node must never outlive this service — it's the sole owner (PLAN T1.1).
        tailscale.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun nextIntervalMs(): Long =
        if (isCharging()) CHARGING_INTERVAL_MS else BATTERY_INTERVAL_MS

    private fun isCharging(): Boolean {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun createNotificationChannel() {
        // LOW (not MIN): MIN buries the notification entirely on some OEM skins (OneUI in
        // particular) — a user with no other way to tell "is this actually running" needs to
        // be able to just pull down the shade and see it. Still silent/no-popup, just visible.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.sync_notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String = getString(R.string.notification_starting)): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        // v2: importance bumped LOW->was MIN (buried the notification on some OEM skins) —
        // Android ignores importance changes on an existing channel id, so this needs a new id.
        private const val CHANNEL_ID = "sufficit_sync_v2"
        private const val NOTIFICATION_ID = 1001
        private const val CHARGING_INTERVAL_MS = 60_000L
        private const val BATTERY_INTERVAL_MS = 5 * 60_000L

        const val ACTION_SYNC_NOW = "com.sufficit.ai.mobiledevice.action.SYNC_NOW"
        const val ACTION_LOGOUT = "com.sufficit.ai.mobiledevice.action.LOGOUT"
        const val ACTION_SYNC_STATE = "com.sufficit.ai.mobiledevice.broadcast.SYNC_STATE"
        const val EXTRA_SYNC_SUCCESS = "syncSuccess"
        const val EXTRA_SYNC_MESSAGE = "syncMessage"
        const val EXTRA_TAILNET_IP = "tailnetIp"
        const val EXTRA_LAST_SYNC_AT_MS = "lastSyncAtMs"

        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun syncNow(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_SYNC_NOW)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the sync loop, disconnects tsnet, and wipes its state dir — the process that
         * owns those files (PLAN T1.2: without this, the next user paired on this device would
         * silently reuse the previous user's tsnet node identity). Caller must also stop
         * [ModelRuntimeService] separately — it's a different owner (its own registry/model). */
        fun logout(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_LOGOUT)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
