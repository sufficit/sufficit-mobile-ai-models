// Sufficit Transcription IPC contract, v1.
// This file must stay byte-identical across every app implementing this contract (there is no
// shared Gradle module between separate Sufficit apps) — see
// docs/ipc-transcription-contract.md for the canonical spec and error-code list.
package com.sufficit.ipc.transcription;

oneway interface ISufficitTranscriptionCallback {
    /** requestId echoes what was passed to transcribe(), for callers with more than one in flight. */
    void onResult(String requestId, String text);

    /** errorCode is one of ISufficitTranscriptionService.ERROR_* — see the .aidl for the list. */
    void onError(String requestId, int errorCode, String message);
}
