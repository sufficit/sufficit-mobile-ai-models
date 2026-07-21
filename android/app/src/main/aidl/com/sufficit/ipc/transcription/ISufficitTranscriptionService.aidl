// Sufficit Transcription IPC contract, v1.
// This file must stay byte-identical across every app implementing this contract (there is no
// shared Gradle module between separate Sufficit apps) — see
// docs/ipc-transcription-contract.md for the canonical spec.
//
// Binding: resolve with an explicit Intent built from ACTION_BIND_TRANSCRIPTION_SERVICE scoped
// to the provider's package (implicit-then-explicit resolution, required since Android 5.0),
// then bindService(). The provider's <service> requires
// com.sufficit.ipc.transcription.permission.PROVIDE_TRANSCRIPTION (normal protection level) —
// callers must declare <uses-permission> for it.
package com.sufficit.ipc.transcription;

import com.sufficit.ipc.transcription.ISufficitTranscriptionCallback;

interface ISufficitTranscriptionService {
    const int ERROR_NOT_READY = 1;       // no model loaded/active on the provider right now
    const int ERROR_TIMEOUT = 2;         // provider took too long to answer
    const int ERROR_INFERENCE_FAILED = 3; // model ran but produced no usable result
    const int ERROR_CANCELLED = 4;       // cancel() was called before completion
    const int ERROR_INTERNAL = 5;        // unexpected provider-side failure

    /** Bump if this contract ever changes incompatibly. Callers should tolerate an unknown
     * (higher) version by falling back to only the methods they know. */
    int getProtocolVersion();

    /** True if transcribe() can currently be served without a cold-start delay (a model is
     * already loaded and its inference engine is resident). Callers should still handle
     * ERROR_NOT_READY from transcribe() itself — this is an optimization/status hint, not a
     * lock. */
    boolean isReady();

    /**
     * Transcribes 16kHz mono 16-bit PCM WAV audio. audio is a ParcelFileDescriptor (not a raw
     * byte[]) specifically to avoid the ~1MB combined binder transaction cap a longer audio
     * segment could hit. Result/errors arrive asynchronously via callback — implementations
     * must never block the calling thread for the multi-second duration of inference.
     * requestId is caller-chosen and echoed back in the callback so a caller with more than one
     * transcription in flight can tell them apart.
     */
    void transcribe(String requestId, in ParcelFileDescriptor audio, String languageHint, ISufficitTranscriptionCallback callback);

    /** Best-effort cancellation of a previously requested, still in-flight transcription.
     * No-op if requestId is unknown or already finished. */
    void cancel(String requestId);
}
