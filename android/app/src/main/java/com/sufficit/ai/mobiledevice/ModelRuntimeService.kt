package com.sufficit.ai.mobiledevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns both [LlamaServerManager] (embeddings) and [WhisperServerManager] (transcription)
 * lifecycles and all model downloads — the one and only thing in the app allowed to touch
 * either. Runs in its own process (`android:process=":modelruntime"` in the manifest),
 * deliberately separate from [SyncForegroundService]'s process: a native subprocess crash, an
 * OOM during a model load, or an unhandled exception mid-download must never be able to take
 * the self-announce/tailnet heartbeat down with it — "a execução/download de um modelo não
 * pode derrubar o serviço de api, devem ser processos diferentes". Two separate processes is
 * the only way Android actually guarantees that (a crash only kills its own process).
 *
 * Only one engine is ever resident at a time — [activeEngineKind] tracks which, [managerFor] and
 * [testerFor] dispatch by [ModelKind] so this service doesn't duplicate every action's logic
 * per engine. Real RAM-safety hardening for the Galaxy A51 (3.6GB total): llama-server
 * (~1.1GB model) and whisper-server loaded together push this device into swap thrashing.
 * Mutual exclusion avoids that at the cost of a cold-start delay when switching which
 * capability is active; the external API shape doesn't change (a capability that isn't
 * currently resident just answers with tsgo's existing "no model loaded" 503, same as today
 * when a server hasn't started yet). Note this is NOT what was causing the infinite ~90s
 * restart loop found during the same investigation — that was a missing
 * `network_security_config.xml` blocking every one of this app's own OkHttp calls to
 * 127.0.0.1 (including every /health probe), fixed separately. Kept mutual exclusion anyway
 * because the memory pressure it prevents is real, independent of that bug.
 *
 * Communication is one-way-command / broadcast-result, not a bound service: the UI process
 * (MainActivity/ModelsScreen) sends [ACTION_SWITCH_TO]/[ACTION_TEST]/[ACTION_TEST_API]/
 * [ACTION_STOP] (each with an [EXTRA_MODEL_KIND]) via startService(), and this service reports
 * outcomes via explicit
 * (own-package-only) broadcasts — [ACTION_STATUS_CHANGED], [ACTION_DOWNLOAD_PROGRESS],
 * [ACTION_TEST_RESULT]. [LlamaServerManager]/[WhisperServerManager] themselves stay plain
 * Kotlin singletons (not cross-process safe on their own — see their own kdoc) precisely
 * because only this one process ever touches them now.
 */
class ModelRuntimeService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private val registry by lazy { ModelRegistry(applicationContext) }
    private val downloader by lazy { ModelDownloader() }
    // Guards against a manual ACTION_DOWNLOAD racing ensureModelProvisioned's auto-download, or
    // two manual downloads at once (any kind combination) — two ~1GB downloads at once on a
    // 4GB phone is its own kind of crash.
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()
    // Consecutive /health failures per kind while the process is alive — 3-strike restart
    // (PLAN T3.1), tracked separately since the two engines run/fail independently.
    private val healthFailureStreak = mutableMapOf(ModelKind.EMBEDDING to 0, ModelKind.TRANSCRIPTION to 0)
    // Which engine is allowed to be resident right now — mutual exclusion (see class kdoc for
    // why). In-memory only, resets to EMBEDDING on process restart same as everything else here;
    // that matches the original default (embedding auto-provisions, transcription doesn't).
    @Volatile
    private var activeEngineKind: ModelKind = ModelKind.EMBEDDING

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        migrateLegacyModel()
    }

    /** One-time move of the pre-Fase-6 single-model file into [ModelsDir], so an
     * already-installed app doesn't leave up to 2.3GB of dead weight sitting unused forever
     * (PLAN T3.4). */
    private fun migrateLegacyModel() {
        val legacy = ModelFile(applicationContext)
        if (!legacy.exists()) return
        val target = File(ModelsDir(applicationContext), "legacy-model.gguf")
        ModelsDir(applicationContext).mkdirs()
        if (legacy.renameTo(target)) {
            if (registry.activeModelFileName(ModelKind.EMBEDDING) == null) {
                registry.setActiveModelFileName(ModelKind.EMBEDDING, target.name)
            }
            legacy.parentFile?.delete()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        ensureLoopRunning()

        when (intent?.action) {
            ACTION_SWITCH_TO -> intent.getStringExtra(EXTRA_FILE_NAME)?.let { fileName ->
                val kind = intent.modelKind()
                scope.launch { handleSwitchTo(kind, fileName) }
            }
            ACTION_TEST -> intent.getStringExtra(EXTRA_FILE_NAME)?.let { fileName ->
                val kind = intent.modelKind()
                scope.launch { handleTestLocal(kind, fileName) }
            }
            ACTION_TEST_API -> intent.getStringExtra(EXTRA_FILE_NAME)?.let { fileName ->
                val kind = intent.modelKind()
                scope.launch { handleTestApi(kind, fileName) }
            }
            ACTION_STOP -> {
                val kind = intent.modelKind()
                scope.launch {
                    // ModelServerManager.stop() blocks up to ~7s waiting for the process to
                    // exit — must never run on the main thread (onStartCommand's caller).
                    managerFor(kind).stop()
                    broadcastStatus()
                }
            }
            ACTION_DOWNLOAD -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL)
                if (fileName != null && url != null) scope.launch { handleDownload(fileName, url) }
            }
            ACTION_DELETE -> intent.getStringExtra(EXTRA_FILE_NAME)?.let { fileName ->
                val kind = intent.modelKind()
                scope.launch { handleDelete(kind, fileName) }
            }
            ACTION_QUERY_STATUS -> broadcastStatus()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        LlamaServerManager.stop()
        WhisperServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.modelKind(): ModelKind =
        getStringExtra(EXTRA_MODEL_KIND)?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() }
            ?: ModelKind.EMBEDDING

    /** Keep-alive loop: same reasoning as the old SyncForegroundService copy of this logic
     * (see git history) — auto-provisions this device's recommended embedding model if none is
     * active yet, and restarts [activeEngineKind]'s server if it ever dies, so the device keeps
     * serving inference without anyone having to reopen ModelsScreen. Also recycles a process
     * that's alive but stuck (OOM in progress, deadlock) via a 3-strike /health check — see PLAN
     * T3.1. Not on the first failure: a freshly (re)started process takes real time to load the
     * model. Transcription is never auto-provisioned (PLAN: Whisper support) — downloading an
     * extra ~150MB model for a capability nobody asked to activate yet would be a surprise;
     * it only starts once the user explicitly picks a Whisper model in ModelsScreen. Only
     * [activeEngineKind] is maintained/auto-restarted here — the other kind is defensively
     * stopped every iteration (mutual exclusion, see class kdoc). */
    private fun ensureLoopRunning() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                if (isThermallySevere()) {
                    var anyStopped = false
                    for (kind in ModelKind.entries) {
                        val manager = managerFor(kind)
                        if (manager.isRunning()) {
                            manager.stop()
                            anyStopped = true
                        }
                    }
                    if (anyStopped) {
                        updateNotification(getString(R.string.models_thermal_paused))
                        broadcastStatus()
                    }
                    delay(LOOP_INTERVAL_MS)
                    continue
                }

                maintainEngine(activeEngineKind, autoProvision = activeEngineKind == ModelKind.EMBEDDING)
                deactivateOtherEngines(activeEngineKind)

                delay(LOOP_INTERVAL_MS)
            }
        }
    }

    /** Stops every engine except [keep] — the mutual-exclusion enforcement point (see class
     * kdoc: two native servers loaded together swap-thrash this device into an unrecoverable
     * restart loop). Safe to call unconditionally; [ModelServerManager.stop] no-ops if already
     * stopped. */
    private fun deactivateOtherEngines(keep: ModelKind) {
        for (other in ModelKind.entries) {
            if (other == keep) continue
            val otherManager = managerFor(other)
            if (otherManager.isRunning()) otherManager.stop()
        }
    }

    /** Brings the runtime back to "only [activeEngineKind] resident, running its registered
     * model" — used after a [handleTest] run against a different engine/model borrowed the
     * single resident slot. */
    private fun restoreActiveEngine() {
        deactivateOtherEngines(activeEngineKind)
        val manager = managerFor(activeEngineKind)
        val activeFile = registry.activeModelFile(applicationContext, activeEngineKind)
        if (activeFile != null) {
            if (!manager.isRunning()) manager.start(applicationContext, activeFile)
        } else {
            manager.stop()
        }
    }

    private suspend fun maintainEngine(kind: ModelKind, autoProvision: Boolean) {
        val manager = managerFor(kind)
        val tester = testerFor(kind)

        if (!manager.isRunning()) {
            healthFailureStreak[kind] = 0
            val activeFile = registry.activeModelFile(applicationContext, kind)
            if (activeFile != null) {
                manager.start(applicationContext, activeFile)
                broadcastStatus()
            } else if (autoProvision) {
                ensureEmbeddingModelProvisioned()
            }
            return
        }

        if (tester.isHealthy(manager.port)) {
            healthFailureStreak[kind] = 0
            return
        }

        val streak = (healthFailureStreak[kind] ?: 0) + 1
        healthFailureStreak[kind] = streak
        if (streak >= 3) {
            healthFailureStreak[kind] = 0
            val activeFile = registry.activeModelFile(applicationContext, kind)
            if (activeFile != null) {
                manager.switchTo(applicationContext, activeFile)
                broadcastStatus()
            }
        }
    }

    private suspend fun handleSwitchTo(kind: ModelKind, fileName: String) {
        val file = File(ModelsDir(applicationContext), fileName)
        deactivateOtherEngines(kind)
        managerFor(kind).switchTo(applicationContext, file)
        registry.setActiveModelFileName(kind, fileName)
        activeEngineKind = kind
        broadcastStatus()
    }

    /** Local/direct test — spawns llama-embedding or whisper-cli (see
     * scripts/build-llama-embedding.sh / scripts/build-whisper-server.sh) as a one-shot
     * process against [fileName] directly. No HTTP, no [ModelServerManager] server involved at
     * all — this only proves the GGUF/bin file loads and the model itself produces valid
     * output, decoupled from whether the API layer works. Stops BOTH engines first (not just
     * the other kind): the CLI test loads its own separate copy of the model into a new
     * process, which would double up RAM usage against whatever HTTP server happens to already
     * be resident for the same kind. [restoreActiveEngine] brings back whatever was actually
     * supposed to be running once the one-shot process exits. */
    private suspend fun handleTestLocal(kind: ModelKind, fileName: String) {
        val file = File(ModelsDir(applicationContext), fileName)
        for (k in ModelKind.entries) {
            val m = managerFor(k)
            if (m.isRunning()) m.stop()
        }

        val intent = Intent(ACTION_TEST_RESULT).setPackage(packageName)
            .putExtra(EXTRA_MODEL_KIND, kind.name)
            .putExtra(EXTRA_FILE_NAME, fileName)
        when (kind) {
            ModelKind.EMBEDDING -> when (val result = ModelTesters.embeddingCli.test(applicationContext, file)) {
                is EmbeddingTestResult.Success -> intent
                    .putExtra(EXTRA_SUCCESS, true)
                    .putExtra(EXTRA_DIMENSIONS, result.dimensions)
                    .putExtra(EXTRA_LATENCY_MS, result.latencyMs)
                is EmbeddingTestResult.Failure -> intent
                    .putExtra(EXTRA_SUCCESS, false)
                    .putExtra(EXTRA_ERROR, result.message)
            }
            ModelKind.TRANSCRIPTION -> when (val result = ModelTesters.transcriptionCli.test(applicationContext, file)) {
                is TranscriptionTestResult.Success -> intent
                    .putExtra(EXTRA_SUCCESS, true)
                    .putExtra(EXTRA_TRANSCRIPTION_TEXT, result.text)
                    .putExtra(EXTRA_LATENCY_MS, result.latencyMs)
                is TranscriptionTestResult.Failure -> intent
                    .putExtra(EXTRA_SUCCESS, false)
                    .putExtra(EXTRA_ERROR, result.message)
            }
        }
        sendBroadcast(intent)

        restoreActiveEngine()
        broadcastStatus()
    }

    /** Full-pipeline test — switches [kind] to run [fileName] as the actual resident HTTP
     * server (same as production usage) and tests it via the OpenAI-compatible API, exactly
     * like a real caller would. Complements [handleTestLocal]: that one proves the model
     * works, this one proves the server + API layer work too. */
    private suspend fun handleTestApi(kind: ModelKind, fileName: String) {
        val file = File(ModelsDir(applicationContext), fileName)
        val manager = managerFor(kind)
        // Already the one resident engine, running exactly this file? Test it in place. Anything
        // else (different kind, or a different model of the currently-resident kind) has to
        // borrow the single resident slot for the duration of the test — mutual exclusion means
        // there's nowhere else for it to run (see class kdoc).
        val alreadyCorrect = kind == activeEngineKind &&
            fileName == registry.activeModelFileName(kind) &&
            manager.isRunning()

        if (!alreadyCorrect) {
            deactivateOtherEngines(kind)
            manager.switchTo(applicationContext, file)
        }

        val intent = Intent(ACTION_TEST_RESULT).setPackage(packageName)
            .putExtra(EXTRA_MODEL_KIND, kind.name)
            .putExtra(EXTRA_FILE_NAME, fileName)
        when (kind) {
            ModelKind.EMBEDDING -> when (val result = ModelTesters.embedding.test(manager.port)) {
                is EmbeddingTestResult.Success -> intent
                    .putExtra(EXTRA_SUCCESS, true)
                    .putExtra(EXTRA_DIMENSIONS, result.dimensions)
                    .putExtra(EXTRA_LATENCY_MS, result.latencyMs)
                is EmbeddingTestResult.Failure -> intent
                    .putExtra(EXTRA_SUCCESS, false)
                    .putExtra(EXTRA_ERROR, result.message)
            }
            ModelKind.TRANSCRIPTION -> when (val result = ModelTesters.transcription.test(manager.port)) {
                is TranscriptionTestResult.Success -> intent
                    .putExtra(EXTRA_SUCCESS, true)
                    .putExtra(EXTRA_TRANSCRIPTION_TEXT, result.text)
                    .putExtra(EXTRA_LATENCY_MS, result.latencyMs)
                is TranscriptionTestResult.Failure -> intent
                    .putExtra(EXTRA_SUCCESS, false)
                    .putExtra(EXTRA_ERROR, result.message)
            }
        }
        sendBroadcast(intent)

        // Restore whatever is actually supposed to be running — testing a different
        // engine/model must never leave the resident slot stuck on it.
        if (!alreadyCorrect) restoreActiveEngine()
        broadcastStatus()
    }

    /** Downloads any model chosen from ModelsScreen's Hugging Face search or recommended list —
     * kind-agnostic (destination file extension already tells GGUF from ggml .bin apart, and
     * nothing here needs to know which server will eventually load it). Runs here (not
     * directly in the UI process) for the same reason as everything else in this service: a
     * failed/crashing download must stay isolated. Shares [downloadMutex] across both kinds —
     * two big downloads at once is risky regardless of which engine they're for. */
    private suspend fun handleDownload(fileName: String, url: String) = downloadMutex.withLock {
        val destination = File(ModelsDir(applicationContext), fileName)
        val result = downloader.download(url, destination) { progress ->
            sendBroadcast(
                Intent(ACTION_DOWNLOAD_PROGRESS).setPackage(packageName)
                    .putExtra(EXTRA_FILE_NAME, fileName)
                    .putExtra(EXTRA_PROGRESS, progress)
            )
        }
        val intent = Intent(ACTION_DOWNLOAD_RESULT).setPackage(packageName)
            .putExtra(EXTRA_FILE_NAME, fileName)
        when (result) {
            is ModelDownloadResult.Success -> intent.putExtra(EXTRA_SUCCESS, true)
            is ModelDownloadResult.Failure -> intent.putExtra(EXTRA_SUCCESS, false).putExtra(EXTRA_ERROR, result.message)
        }
        sendBroadcast(intent)
    }

    private fun handleDelete(kind: ModelKind, fileName: String) {
        if (fileName == registry.activeModelFileName(kind)) {
            managerFor(kind).stop()
            registry.setActiveModelFileName(kind, null)
            // Deleting the resident engine's active model leaves nothing to keep it exclusive
            // for — fall back to the original default (embedding auto-provisions itself again).
            if (kind == activeEngineKind) activeEngineKind = ModelKind.EMBEDDING
        }
        File(ModelsDir(applicationContext), fileName).delete()
        broadcastStatus()
    }

    private suspend fun ensureEmbeddingModelProvisioned() {
        if (!isOnWifi()) {
            updateNotification(getString(R.string.models_waiting_wifi))
            return
        }

        // Pick the largest recommended model that still fits this device's RAM with headroom
        // for KV cache/buffers (~1.5x the file size) and the OS itself (~1.5GB) — a device with
        // less RAM than the reference Galaxy A51 would OOM on the first entry otherwise
        // (PLAN T3.6).
        val am = getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val candidates = DeviceModelCatalog.recommended(ModelKind.EMBEDDING)
        val pick = candidates.firstOrNull { it.sizeGB * 1.5 < totalGB - 1.5 }
            ?: candidates.minByOrNull { it.sizeGB }
            ?: return
        val destination = File(ModelsDir(applicationContext), pick.fileName)
        if (!destination.exists()) {
            // A manual download from ModelsScreen may already hold the lock — don't block the
            // 30s keep-alive loop waiting on it, just retry auto-provisioning next iteration.
            if (!downloadMutex.tryLock()) return
            try {
                val result = downloader.download(pick.downloadUrl, destination) { progress ->
                    updateNotification(getString(R.string.models_downloading_progress, (progress * 100).toInt()))
                    sendBroadcast(
                        Intent(ACTION_DOWNLOAD_PROGRESS).setPackage(packageName)
                            .putExtra(EXTRA_FILE_NAME, pick.fileName)
                            .putExtra(EXTRA_PROGRESS, progress)
                    )
                }
                if (result is ModelDownloadResult.Failure) {
                    updateNotification(getString(R.string.models_download_failed_notification, result.message))
                    return
                }
            } finally {
                downloadMutex.unlock()
            }
        }

        registry.setActiveModelFileName(ModelKind.EMBEDDING, pick.fileName)
        LlamaServerManager.start(applicationContext, destination)
        broadcastStatus()
    }

    private fun broadcastStatus() {
        val embeddingRunning = LlamaServerManager.isRunning()
        val embeddingActive = registry.activeModelFileName(ModelKind.EMBEDDING)
        val transcriptionRunning = WhisperServerManager.isRunning()
        val transcriptionActive = registry.activeModelFileName(ModelKind.TRANSCRIPTION)

        val notificationParts = mutableListOf(
            if (embeddingRunning) {
                getString(R.string.models_active_notification, embeddingActive ?: getString(R.string.models_generic_fallback))
            } else {
                getString(R.string.models_none_active_notification)
            }
        )
        if (transcriptionActive != null) {
            notificationParts += if (transcriptionRunning) {
                getString(R.string.models_transcription_active_notification, transcriptionActive)
            } else {
                getString(R.string.models_transcription_stopped_notification)
            }
        }
        updateNotification(notificationParts.joinToString(" · "))

        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(packageName)
                .putExtra(EXTRA_RUNNING, embeddingRunning)
                .putExtra(EXTRA_ACTIVE_MODEL, embeddingActive)
                .putExtra(EXTRA_TRANSCRIPTION_RUNNING, transcriptionRunning)
                .putExtra(EXTRA_ACTIVE_TRANSCRIPTION_MODEL, transcriptionActive)
        )
    }

    /** Halts inference before the OS throttles/kills the process outright — a phone serving
     * inference while charging can genuinely overheat (PLAN T3.2). Auto-recovers: once the
     * status drops below SEVERE, the loop's normal !isRunning -> start branch turns it back
     * on, no separate resume path needed. */
    private fun isThermallySevere(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val pm = getSystemService(android.os.PowerManager::class.java)
        val status = pm?.currentThermalStatus ?: android.os.PowerManager.THERMAL_STATUS_NONE
        return status >= android.os.PowerManager.THERMAL_STATUS_SEVERE
    }

    private fun isOnWifi(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.modelruntime_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(statusText: String = getString(R.string.notification_starting)): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openAppIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sufficit_model_runtime"
        private const val NOTIFICATION_ID = 1002
        private const val LOOP_INTERVAL_MS = 30_000L

        const val ACTION_SWITCH_TO = "com.sufficit.ai.mobiledevice.action.SWITCH_TO"
        /** Local/direct test — see [handleTestLocal]. */
        const val ACTION_TEST = "com.sufficit.ai.mobiledevice.action.TEST"
        /** Full-pipeline (server + HTTP API) test — see [handleTestApi]. */
        const val ACTION_TEST_API = "com.sufficit.ai.mobiledevice.action.TEST_API"
        const val ACTION_STOP = "com.sufficit.ai.mobiledevice.action.STOP"
        const val ACTION_DOWNLOAD = "com.sufficit.ai.mobiledevice.action.DOWNLOAD"
        const val ACTION_DELETE = "com.sufficit.ai.mobiledevice.action.DELETE"
        const val ACTION_QUERY_STATUS = "com.sufficit.ai.mobiledevice.action.QUERY_STATUS"

        const val ACTION_STATUS_CHANGED = "com.sufficit.ai.mobiledevice.broadcast.STATUS_CHANGED"
        const val ACTION_DOWNLOAD_PROGRESS = "com.sufficit.ai.mobiledevice.broadcast.DOWNLOAD_PROGRESS"
        const val ACTION_DOWNLOAD_RESULT = "com.sufficit.ai.mobiledevice.broadcast.DOWNLOAD_RESULT"
        const val ACTION_TEST_RESULT = "com.sufficit.ai.mobiledevice.broadcast.TEST_RESULT"

        const val EXTRA_MODEL_KIND = "modelKind"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_DOWNLOAD_URL = "downloadUrl"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_DIMENSIONS = "dimensions"
        const val EXTRA_TRANSCRIPTION_TEXT = "transcriptionText"
        const val EXTRA_LATENCY_MS = "latencyMs"
        const val EXTRA_ERROR = "error"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_ACTIVE_MODEL = "activeModel"
        const val EXTRA_TRANSCRIPTION_RUNNING = "transcriptionRunning"
        const val EXTRA_ACTIVE_TRANSCRIPTION_MODEL = "activeTranscriptionModel"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ModelRuntimeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelRuntimeService::class.java))
        }

        fun switchTo(context: Context, kind: ModelKind, fileName: String) {
            val intent = Intent(context, ModelRuntimeService::class.java)
                .setAction(ACTION_SWITCH_TO)
                .putExtra(EXTRA_MODEL_KIND, kind.name)
                .putExtra(EXTRA_FILE_NAME, fileName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun test(context: Context, kind: ModelKind, fileName: String) {
            val intent = Intent(context, ModelRuntimeService::class.java)
                .setAction(ACTION_TEST)
                .putExtra(EXTRA_MODEL_KIND, kind.name)
                .putExtra(EXTRA_FILE_NAME, fileName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun testApi(context: Context, kind: ModelKind, fileName: String) {
            val intent = Intent(context, ModelRuntimeService::class.java)
                .setAction(ACTION_TEST_API)
                .putExtra(EXTRA_MODEL_KIND, kind.name)
                .putExtra(EXTRA_FILE_NAME, fileName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun download(context: Context, fileName: String, url: String) {
            val intent = Intent(context, ModelRuntimeService::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_DOWNLOAD_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }

        fun delete(context: Context, kind: ModelKind, fileName: String) {
            val intent = Intent(context, ModelRuntimeService::class.java)
                .setAction(ACTION_DELETE)
                .putExtra(EXTRA_MODEL_KIND, kind.name)
                .putExtra(EXTRA_FILE_NAME, fileName)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Request/response counterpart to [ACTION_STATUS_CHANGED] — SharedPreferences isn't
         * multi-process safe, so a UI screen that just opened can't trust a stale local read of
         * [ModelRegistry]; call this right after registering a status receiver to get a fresh
         * broadcast instead of waiting for the next unrelated status change. */
        fun queryStatus(context: Context) {
            val intent = Intent(context, ModelRuntimeService::class.java).setAction(ACTION_QUERY_STATUS)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
