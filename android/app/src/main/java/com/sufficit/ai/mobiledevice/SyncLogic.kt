package com.sufficit.ai.mobiledevice

import android.os.Build
import android.os.Process
import android.util.Log
import net.openid.appauth.AuthorizationException

/** Refresh proactively this long before actual expiry — clock skew + request latency buffer. */
private const val TOKEN_EXPIRY_BUFFER_MS = 60_000L

/**
 * Shared by the UI-triggered "Sincronizar agora" button and the background
 * heartbeat ([SyncForegroundService]) — one sync implementation, two callers.
 * Picks whichever credential is paired (OAuth Modo B takes priority if both
 * are somehow present) and calls the matching endpoint.
 *
 * Modo B tokens are short-lived: this refreshes proactively via
 * [OAuthManager.refreshAccessToken] before every self-announce so the device
 * doesn't silently go offline once the access token expires (see PairingStore
 * kdoc — login and sync are separate concerns, but sync still needs a live
 * token to succeed).
 */
suspend fun performSync(
    store: PairingStore,
    api: PairingApi,
    oauth: OAuthManager,
    vpn: SufficitVpnClient
): AnnounceResult {
    val pairingToken = store.pairingToken
    val accessToken = ensureFreshAccessToken(store, oauth)
    val deviceName = deviceDisplayName()
    val deviceModel = deviceHardwareModel()
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    val result = when {
        accessToken != null -> api.selfAnnounce(
            gatewayBaseUrl = Config.DEFAULT_GATEWAY_URL,
            accessToken = accessToken,
            deviceInstanceId = store.deviceInstanceId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            appVersion = appVersion,
            contextId = null
        )
        pairingToken != null -> api.announce(
            gatewayBaseUrl = Config.DEFAULT_GATEWAY_URL,
            token = pairingToken,
            deviceName = deviceName,
            deviceModel = deviceModel,
            appVersion = appVersion
        )
        else -> AnnounceResult.Failure("dispositivo não pareado")
    }

    if (result is AnnounceResult.Success) {
        ensureVpnConnected(store, vpn, result)
    }

    return result
}

/**
 * "Lenovo TB-J706F" alone collides when the same physical tablet runs this app under more
 * than one Android user profile — Build.MODEL is identical across profiles since it's the
 * same hardware, so each profile's self-announce silently overwrote/duplicated the other's
 * provider entry (found on-device: two identical "Lenovo TB-J706F" providers, one per user).
 * UserManager.getUserName() looks like the natural fix but throws SecurityException on this
 * app (confirmed live via logcat) — reading it needs MANAGE_USERS/GET_ACCOUNTS_PRIVILEGED, a
 * privileged permission a normal third-party app can never hold. Instead this uses Android's
 * UID-per-user allocation convention — each user's app UIDs start at userId*100000+appId — a
 * stable OS behavior readable via the public, unprivileged Process.myUid(). User 0 is the
 * device's primary/default profile, so the suffix is suppressed there and ordinary single-user
 * phones stay unchanged; any other profile gets "(user N)" appended.
 */
private fun deviceDisplayName(): String {
    val model = Build.MODEL ?: "Android device"
    val androidUserId = Process.myUid() / 100000
    val displayName = if (androidUserId == 0) model else "$model (user $androidUserId)"
    Log.d("SyncLogic", "device display name for self-announce: \"$displayName\"")
    return displayName
}

/**
 * Hardware inventory value, intentionally separate from [deviceDisplayName].
 * The latter adds the Android profile suffix when necessary, while this one
 * remains the actual device model shared by all profiles on the same hardware.
 */
private fun deviceHardwareModel(): String {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    return when {
        model.isBlank() && manufacturer.isBlank() -> "Android device"
        model.isBlank() -> manufacturer
        manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

/** Returns a live access token, refreshing it first if it's expired/near-expiry. Null if not logged in via Modo B. */
private suspend fun ensureFreshAccessToken(store: PairingStore, oauth: OAuthManager): String? {
    val accessToken = store.oauthAccessToken ?: return null
    val expiresAt = store.oauthAccessTokenExpiresAtMs
    val needsRefresh = expiresAt != null && expiresAt <= System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER_MS
    if (!needsRefresh) return accessToken

    val refreshToken = store.oauthRefreshToken ?: return accessToken // nothing to refresh with — try the stale token, let the server say so
    return try {
        val response = oauth.refreshAccessToken(refreshToken)
        val newAccessToken = response.accessToken ?: return accessToken
        store.oauthAccessToken = newAccessToken
        store.oauthAccessTokenExpiresAtMs = response.accessTokenExpirationTime
        // Some IdPs rotate the refresh token on use, some don't — only overwrite if a new one came back.
        response.refreshToken?.let { store.oauthRefreshToken = it }
        newAccessToken
    } catch (ex: AuthorizationException) {
        if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR && ex.error == "invalid_grant") {
            // A revoked, expired or already-rotated refresh token cannot recover
            // by retrying. Clear only the OAuth session so the UI asks for a
            // fresh login and the foreground sync loop stops hammering /token.
            Log.w("SyncLogic", "refresh token is no longer valid; clearing OAuth session")
            store.clearOAuthSession()
            null
        } else {
            Log.w("SyncLogic", "token refresh failed, trying stale token", ex)
            accessToken
        }
    } catch (ex: Exception) {
        Log.w("SyncLogic", "token refresh failed, trying stale token", ex)
        accessToken
    }
}

/** Entrega o envelope opaco ao agente VPN e publica o listener local já iniciado. */
private suspend fun ensureVpnConnected(
    store: PairingStore,
    vpn: SufficitVpnClient,
    result: AnnounceResult.Success
) {
    result.tailnetLoginServer?.let { store.tailnetLoginServer = it }
    result.tailnetNodeName?.let { store.tailnetNodeName = it }
    if (!vpn.connectAndPublish(result.vpnEnrollment)) {
        Log.w("SyncLogic", "Sufficit VPN indisponível; API local segue ativa em 8090")
    }
}
