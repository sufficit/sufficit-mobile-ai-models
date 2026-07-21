package com.sufficit.ai.mobiledevice

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Persists pairing state. Two independent credentials, matching the two
 * pairing modes in docs/PLAN-202607091200-mobile-device-ai-provider.md:
 *  - Modo A: `pairingToken`, the copy/pasted secret (only credential).
 *  - Modo B: `oauthAccessToken` + `oauthRefreshToken` (the real credential —
 *    the user's own Bearer token) + `deviceInstanceId` (non-secret, just
 *    keys "which device" for the backend, generated once and stable for the
 *    life of the install).
 * Both live in EncryptedSharedPreferences — never plain prefs or logs.
 *
 * Login and sync (self-announce) are deliberately separate steps: tokens are
 * saved the moment the OAuth exchange succeeds, independent of whether the
 * following self-announce call succeeds — a sync failure (backend not
 * deployed yet, device unreachable) must never throw away a valid login.
 */
class PairingStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "pairing_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var pairingToken: String?
        get() = prefs.getString(KEY_PAIRING_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_PAIRING_TOKEN, value).apply()

    var oauthAccessToken: String?
        get() = prefs.getString(KEY_OAUTH_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OAUTH_ACCESS_TOKEN, value).apply()

    var oauthRefreshToken: String?
        get() = prefs.getString(KEY_OAUTH_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_OAUTH_REFRESH_TOKEN, value).apply()

    /** Epoch millis when [oauthAccessToken] expires, or null if unknown. */
    var oauthAccessTokenExpiresAtMs: Long?
        get() = prefs.getLong(KEY_OAUTH_EXPIRES_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong(KEY_OAUTH_EXPIRES_AT, value ?: -1L).apply()

    /** Cached from `/connect/userinfo` — best-effort, only used for the home screen header. */
    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var avatarUrl: String?
        get() = prefs.getString(KEY_AVATAR_URL, null)
        set(value) = prefs.edit().putString(KEY_AVATAR_URL, value).apply()

    /** Stable, non-secret per-install id. Generated once, reused for every self-announce call. */
    val deviceInstanceId: String
        get() = prefs.getString(KEY_DEVICE_INSTANCE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_INSTANCE_ID, it).apply()
        }

    /** Tailnet login-server URL, cached from the last successful announce ([TailscaleManager]). */
    var tailnetLoginServer: String?
        get() = prefs.getString(KEY_TAILNET_LOGIN_SERVER, null)
        set(value) = prefs.edit().putString(KEY_TAILNET_LOGIN_SERVER, value).apply()

    /** Tailnet hostname this device must register as — must match what the backend expects back. */
    var tailnetNodeName: String?
        get() = prefs.getString(KEY_TAILNET_NODE_NAME, null)
        set(value) = prefs.edit().putString(KEY_TAILNET_NODE_NAME, value).apply()

    /** Logged in via Modo B (OAuth), regardless of whether sync has ever succeeded. */
    fun isLoggedIn(): Boolean = !oauthAccessToken.isNullOrBlank()

    fun isPaired(): Boolean =
        !pairingToken.isNullOrBlank() || !oauthAccessToken.isNullOrBlank()

    fun clearOAuthSession() {
        prefs.edit()
            .remove(KEY_OAUTH_ACCESS_TOKEN)
            .remove(KEY_OAUTH_REFRESH_TOKEN)
            .remove(KEY_OAUTH_EXPIRES_AT)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_AVATAR_URL)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_PAIRING_TOKEN = "pairing_token"
        const val KEY_OAUTH_ACCESS_TOKEN = "oauth_access_token"
        const val KEY_OAUTH_REFRESH_TOKEN = "oauth_refresh_token"
        const val KEY_OAUTH_EXPIRES_AT = "oauth_expires_at"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_EMAIL = "email"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_DEVICE_INSTANCE_ID = "device_instance_id"
        const val KEY_TAILNET_LOGIN_SERVER = "tailnet_login_server"
        const val KEY_TAILNET_NODE_NAME = "tailnet_node_name"
    }
}
