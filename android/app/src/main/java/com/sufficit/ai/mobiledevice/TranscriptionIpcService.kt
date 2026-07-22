package com.sufficit.ai.mobiledevice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.sufficit.ipc.transcription.ISufficitTranscriptionCallback
import com.sufficit.ipc.transcription.ISufficitTranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Exported bound Service implementing the standardized Sufficit Transcription IPC contract
 * (aidl/com/sufficit/ipc/transcription/, docs/ipc-transcription-contract.md) — lets another
 * installed Sufficit app (e.g. sufficit-android-ai-gateway) transcribe audio through this
 * device's tsgo HTTP server (real loopback listener on [tsgo.Tsgo.ModelPort], see tsgo.go's
 * Start()) without depending on an undocumented port directly.
 *
 * Runs in the existing `:modelruntime` process (same as [ModelRuntimeService]/
 * [NativeTranscriptionManager]) so [isReady] can read [NativeTranscriptionManager]'s recorded
 * intent directly, in-process — [transcribe] itself still crosses to :sync via a real loopback
 * HTTP call, same as any other client of this app's OpenAI-compatible API, since the resident
 * whisper.cpp model only actually lives in :sync's copy of tsgo (see
 * [NativeTranscriptionManager]'s kdoc for the full cross-process story). Deliberately a SEPARATE
 * Service from [ModelRuntimeService] (whose `onBind()` intentionally returns null and is
 * designed as internal command/broadcast only, see its kdoc) rather than repurposing it, so the
 * external-facing binder surface has its own lifecycle/permission boundary independent from the
 * app's own UI-facing control channel.
 */
class TranscriptionIpcService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = ConcurrentHashMap<String, Call>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : ISufficitTranscriptionService.Stub() {
        override fun getProtocolVersion(): Int = 1

        override fun isReady(): Boolean = NativeTranscriptionManager.isRunning()

        override fun transcribe(
            requestId: String,
            audio: ParcelFileDescriptor,
            languageHint: String,
            callback: ISufficitTranscriptionCallback
        ) {
            if (!NativeTranscriptionManager.isRunning()) {
                runCatching { audio.close() }
                callback.onError(requestId, ISufficitTranscriptionService.ERROR_NOT_READY, "no transcription model active")
                return
            }

            // AIDL Stub methods already run off the binder thread pool, but transcription takes
            // multi-second wall time — launch and return immediately so we don't pin one of the
            // process's limited binder threads for the whole inference call.
            scope.launch {
                val wavBytes = try {
                    ParcelFileDescriptor.AutoCloseInputStream(audio).use { it.readBytes() }
                } catch (ex: Exception) {
                    callback.onError(requestId, ISufficitTranscriptionService.ERROR_INTERNAL, "failed to read audio: ${ex.message}")
                    return@launch
                }

                val port = tsgo.Tsgo.ModelPort.toInt()
                val formBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "segment.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
                if (languageHint.isNotBlank()) {
                    formBuilder.addFormDataPart("language", languageHint)
                }
                val request = Request.Builder()
                    .url("http://127.0.0.1:$port/v1/audio/transcriptions")
                    .post(formBuilder.build())
                    .build()

                val call = client.newCall(request)
                inFlight[requestId] = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            callback.onError(requestId, ISufficitTranscriptionService.ERROR_INFERENCE_FAILED, "HTTP ${response.code}")
                            return@use
                        }
                        val json = JSONObject(response.body?.string().orEmpty())
                        if (!json.has("text")) {
                            callback.onError(requestId, ISufficitTranscriptionService.ERROR_INFERENCE_FAILED, "response missing text")
                            return@use
                        }
                        callback.onResult(requestId, json.optString("text"))
                    }
                } catch (ex: IOException) {
                    if (call.isCanceled()) {
                        callback.onError(requestId, ISufficitTranscriptionService.ERROR_CANCELLED, "cancelled")
                    } else {
                        callback.onError(requestId, ISufficitTranscriptionService.ERROR_INTERNAL, ex.message ?: "IO error")
                    }
                } catch (ex: Exception) {
                    callback.onError(requestId, ISufficitTranscriptionService.ERROR_INTERNAL, ex.message ?: ex.javaClass.simpleName)
                } finally {
                    inFlight.remove(requestId)
                }
            }
        }

        override fun cancel(requestId: String) {
            inFlight[requestId]?.cancel()
        }
    }
}
