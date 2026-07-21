# Sufficit Transcription IPC contract

Standardized Android bound-service contract for one app to ask another installed Sufficit
app to transcribe audio through whatever speech-to-text model it already has running —
without depending on an app-specific, undocumented HTTP port.

First implemented between `sufficit-mobile-ai-models` (provider) and
`sufficit-android-ai-gateway` (consumer), 2026-07. Intended to be reused by any future
Sufficit app that wants to either provide or consume on-device transcription.

## Where it lives

- `app/src/main/aidl/com/sufficit/ipc/transcription/ISufficitTranscriptionService.aidl`
- `app/src/main/aidl/com/sufficit/ipc/transcription/ISufficitTranscriptionCallback.aidl`

The package `com.sufficit.ipc.transcription` is deliberately not tied to either app's own
package name. **These two files must stay byte-identical in every app that implements this
contract** — there is no shared Gradle module between separate Sufficit apps, and AIDL
requires the interface definition to match exactly on both sides of the binder.

## Binding

1. Consumer declares in its manifest:
   ```xml
   <uses-permission android:name="com.sufficit.ipc.transcription.permission.PROVIDE_TRANSCRIPTION" />
   <queries>
       <package android:name="<provider-application-id>" />
   </queries>
   ```
2. Consumer resolves an explicit `Intent` (implicit-then-explicit, required since Android 5.0):
   ```kotlin
   val probe = Intent("com.sufficit.ipc.transcription.ACTION_BIND_TRANSCRIPTION_SERVICE")
       .setPackage(providerPackageName)
   val resolved = packageManager.resolveService(probe, 0) ?: return // provider not installed/no matching service
   val bindIntent = Intent(probe.action).setClassName(providerPackageName, resolved.serviceInfo.name)
   context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
   ```
3. Provider declares the service with the same action in an `<intent-filter>`, `exported="true"`,
   gated by the permission below.

## Permission

`com.sufficit.ipc.transcription.permission.PROVIDE_TRANSCRIPTION`, `protectionLevel="normal"`.

Provider declares the `<permission>` and requires it on the `<service>`. Consumer declares
`<uses-permission>`. Normal (not `signature`) on purpose: consumer apps in this org don't all
have a release signing config yet, so a signature check would only work across matching debug
keystores and break real cross-app use. Revisit if/when Sufficit apps share a common release
signing key.

## Contract (v1)

```
interface ISufficitTranscriptionService {
    int getProtocolVersion();
    boolean isReady();
    void transcribe(String requestId, in ParcelFileDescriptor audio, String languageHint, ISufficitTranscriptionCallback callback);
    void cancel(String requestId);
}

oneway interface ISufficitTranscriptionCallback {
    void onResult(String requestId, String text);
    void onError(String requestId, int errorCode, String message);
}
```

- `audio` is a `ParcelFileDescriptor`, not a raw `byte[]` — avoids the ~1MB combined binder
  transaction cap a longer audio segment could hit. Audio format: 16kHz mono 16-bit PCM WAV.
- `transcribe()` is async (returns immediately; result/error arrives via `callback`) —
  inference takes multi-second wall time and must never block a binder thread.
- Error codes:

  | Code | Name | Meaning |
  |---|---|---|
  | 1 | `ERROR_NOT_READY` | No model loaded/active on the provider right now |
  | 2 | `ERROR_TIMEOUT` | Provider took too long to answer |
  | 3 | `ERROR_INFERENCE_FAILED` | Model ran but produced no usable result |
  | 4 | `ERROR_CANCELLED` | `cancel()` was called before completion |
  | 5 | `ERROR_INTERNAL` | Unexpected provider-side failure |
  | -1 | (consumer-local, not in the AIDL) | Client-side failure: bind timeout/failure, disconnect — no provider error code applies |

## Implementations

- **Provider**: `sufficit-mobile-ai-models` — `TranscriptionIpcService.kt`, runs in the app's
  existing `:modelruntime` process so it can read `WhisperServerManager`'s state in-process.
  `isReady()` reflects whether the on-device whisper.cpp server is currently resident (mutual
  exclusion with the embedding engine — see `ModelRuntimeService` kdoc — means it is not always
  on).
- **Consumer**: `sufficit-android-ai-gateway` — `CompanionTranscriptionClient.kt`
  (`transcription/` package), exposed as `TranscriptionMode.COMPANION` alongside the existing
  LOCAL/REMOTE backends.
