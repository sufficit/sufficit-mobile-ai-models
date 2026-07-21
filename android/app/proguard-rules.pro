# gomobile bindings — accessed via generated JNI glue, must survive
-keep class tsgo.** { *; }
-keep class go.** { *; }
# AppAuth uses reflection over its own model classes in places
-keep class net.openid.appauth.** { *; }
# google-crypto-tink (pulled in by androidx.security:security-crypto for
# EncryptedSharedPreferences) references errorprone's compile-time-only annotations —
# absent at runtime, safe to ignore.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
