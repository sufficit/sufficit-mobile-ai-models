package com.sufficit.ai.mobiledevice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Routes {
    const val LOGIN = "login"
    const val TOKEN_PAIRING = "token_pairing"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val MODELS = "models"
}

class MainActivity : ComponentActivity() {

    private lateinit var store: PairingStore
    private lateinit var oauth: OAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()

        // Without a minimum hold, the exit animation below never gets a chance to play — on a
        // fast device onCreate finishes (and Compose is ready) well under a frame, so the
        // splash would otherwise vanish instantly with no visible transition at all.
        var splashMinDurationElapsed = false
        splashScreen.setKeepOnScreenCondition { !splashMinDurationElapsed }
        lifecycleScope.launch {
            delay(SPLASH_MIN_DURATION_MS)
            splashMinDurationElapsed = true
        }

        splashScreen.setOnExitAnimationListener { provider ->
            // provider.iconView is documented to sometimes return null (observed as a hard
            // crash — NullPointerException in SplashScreenViewProvider$ViewImpl31.getIconView
            // — on this Samsung/OneUI build). A failed exit animation must never take the whole
            // app launch down with it; fall back to just removing the splash immediately.
            try {
                val iconView = provider.iconView
                val fadeOut = ObjectAnimator.ofFloat(iconView, "alpha", 1f, 0f)
                val scaleUpX = ObjectAnimator.ofFloat(iconView, "scaleX", 1f, 1.15f)
                val scaleUpY = ObjectAnimator.ofFloat(iconView, "scaleY", 1f, 1.15f)
                AnimatorSet().apply {
                    playTogether(fadeOut, scaleUpX, scaleUpY)
                    duration = 260L
                    interpolator = AccelerateDecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = provider.remove()
                    })
                    start()
                }
            } catch (_: Exception) {
                provider.remove()
            }
        }

        super.onCreate(savedInstanceState)
        store = PairingStore(applicationContext)
        oauth = OAuthManager(applicationContext)

        val startDestination = if (store.isPaired()) Routes.HOME else Routes.LOGIN

        // A inferência local e sua publicação na VPN pertencem ao dispositivo, não à sessão
        // Sufficit. O pareamento só habilita announce/heartbeat na nuvem; 8090 deve continuar
        // disponível mesmo antes do login ou enquanto uma credencial é renovada.
        SufficitVpnClient.requestConsent(this)
        SyncForegroundService.start(applicationContext)

        setContent {
            SufficitTheme {
                Surface(modifier = Modifier.safeDrawingPadding()) {
                    SufficitApp(store = store, oauth = oauth, startDestination = startDestination)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        oauth.dispose()
    }

    private companion object {
        const val SPLASH_MIN_DURATION_MS = 450L
    }
}

/**
 * Navigation graph: docs/PLAN-202607091200-mobile-device-ai-provider.md "Camada 2".
 * login -> (Modo B: home directly) | (Modo A via token_pairing -> home) -> settings.
 *
 * Login (Modo B) and sync are separate steps on purpose: the OAuth token is
 * persisted the moment the exchange succeeds and the user lands on Home —
 * sync ("Sincronizar agora") is a distinct, retriable action that can fail
 * (backend not deployed, device unreachable) without ever touching the login.
 */
@Composable
private fun SufficitApp(store: PairingStore, oauth: OAuthManager, startDestination: String) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loginLoading by rememberSaveable { mutableStateOf(false) }
    var loginStatus by rememberSaveable { mutableStateOf<String?>(null) }


    var syncLoading by rememberSaveable { mutableStateOf(false) }
    var syncStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var networkAddress by rememberSaveable { mutableStateOf<String?>(null) }

    var pairingLoading by rememberSaveable { mutableStateOf(false) }
    var pairingStatus by rememberSaveable { mutableStateOf<String?>(null) }
    // True while the sync this screen kicked off is the initial token validation (PLAN T3.5),
    // not a routine "Sincronizar agora" — same ACTION_SYNC_STATE broadcast, different UI target.
    var awaitingPairingValidation by rememberSaveable { mutableStateOf(false) }

    var lastSyncAtMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastSyncOk by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var modelRunning by rememberSaveable { mutableStateOf(false) }

    val modelRegistry = remember { ModelRegistry(context) }
    var activeModelName by rememberSaveable { mutableStateOf(modelRegistry.activeModelFileName(ModelKind.EMBEDDING)) }

    // ModelRuntimeService runs in a different process — SharedPreferences reads from here
    // aren't guaranteed to see its writes promptly (a known cross-process caveat, not
    // something ModelRegistry itself protects against), so this listens for its broadcast
    // instead of re-reading modelRegistry after coming back from ModelsScreen.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ModelRuntimeService.ACTION_STATUS_CHANGED) {
                    activeModelName = intent.getStringExtra(ModelRuntimeService.EXTRA_ACTIVE_MODEL)
                    modelRunning = intent.getBooleanExtra(ModelRuntimeService.EXTRA_RUNNING, false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ModelRuntimeService.ACTION_STATUS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ModelRuntimeService.queryStatus(context)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Android 13+ (API 33) made POST_NOTIFICATIONS a runtime permission — declaring it in the
    // manifest is not enough. Without this, SyncForegroundService's persistent notification is
    // silently blocked at the OS level (importance=NONE) with no error anywhere: the service
    // itself runs fine, the user just has no way to tell it's running. Best-effort: the service
    // still works either way, this just makes it visible.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(Unit) {
        if (store.isPaired()) requestNotificationPermissionIfNeeded()
    }

    var homeUser by remember {
        mutableStateOf(
            HomeUser(
                displayName = store.displayName,
                email = store.email,
                avatarUrl = store.avatarUrl,
                viaOAuth = store.isLoggedIn()
            )
        )
    }

    fun refreshHomeUser() {
        homeUser = HomeUser(
            displayName = store.displayName,
            email = store.email,
            avatarUrl = store.avatarUrl,
            viaOAuth = store.isLoggedIn()
        )
    }

    // SyncForegroundService is the single owner of performSync/tsnet (PLAN T1.1) — this is the
    // only way the UI process learns the outcome of a sync it asked for. Also doubles as the
    // pairing-token validation result (PLAN T3.5): a token pairing kicks off the same syncNow(),
    // just with awaitingPairingValidation set so this routes to TokenPairingScreen instead.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != SyncForegroundService.ACTION_SYNC_STATE) return
                val success = intent.getBooleanExtra(SyncForegroundService.EXTRA_SYNC_SUCCESS, false)
                val message = intent.getStringExtra(SyncForegroundService.EXTRA_SYNC_MESSAGE)
                networkAddress = intent.getStringExtra(SyncForegroundService.EXTRA_TAILNET_IP)

                if (awaitingPairingValidation) {
                    awaitingPairingValidation = false
                    pairingLoading = false
                    if (success) {
                        refreshHomeUser()
                        requestNotificationPermissionIfNeeded()
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    } else {
                        store.pairingToken = null
                        pairingStatus = message
                    }
                } else {
                    syncLoading = false
                    syncStatus = message
                    lastSyncOk = success
                    lastSyncAtMs = intent.getLongExtra(SyncForegroundService.EXTRA_LAST_SYNC_AT_MS, 0L).takeIf { it > 0 }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(SyncForegroundService.ACTION_SYNC_STATE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun syncNow() {
        syncLoading = true
        syncStatus = null
        SyncForegroundService.syncNow(context)
        // Safety net — if the service never answers (killed, ANR), don't leave the button
        // spinning forever.
        scope.launch {
            delay(45_000)
            if (syncLoading) {
                syncLoading = false
                syncStatus = context.getString(R.string.sync_no_response)
            }
        }
    }

    val oauthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            loginLoading = false
            loginStatus = context.getString(R.string.login_cancelled)
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            loginStatus = null
            try {
                val tokenResponse = withContext(Dispatchers.IO) { oauth.exchangeCodeForTokens(data) }
                val accessToken = tokenResponse.accessToken
                    ?: throw IllegalStateException("resposta sem access_token")

                // Login succeeded — persist immediately. Sync is a separate concern
                // (see kdoc above) and must never be able to undo this.
                store.oauthAccessToken = accessToken
                store.oauthRefreshToken = tokenResponse.refreshToken
                store.oauthAccessTokenExpiresAtMs = tokenResponse.accessTokenExpirationTime

                try {
                    val info = withContext(Dispatchers.IO) { oauth.fetchUserInfo(accessToken) }
                    store.displayName = info.name
                    store.email = info.email
                    store.avatarUrl = info.pictureUrl
                } catch (_: Exception) {
                    // Best-effort — home screen falls back to a generic header.
                }

                loginLoading = false
                refreshHomeUser()
                SyncForegroundService.start(context)
                requestNotificationPermissionIfNeeded()
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            } catch (ex: Exception) {
                loginLoading = false
                loginStatus = context.getString(R.string.login_failed, ex.message.toString())
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 6 } }
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                loading = loginLoading,
                status = loginStatus,
                onLoginClick = {
                    loginLoading = true
                    loginStatus = null
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { oauth.buildAuthorizationIntent() }
                            oauthLauncher.launch(intent)
                        } catch (ex: Exception) {
                            loginLoading = false
                            loginStatus = context.getString(R.string.login_start_failed, ex.message.toString())
                        }
                    }
                },
                onUseTokenClick = { navController.navigate(Routes.TOKEN_PAIRING) }
            )
        }

        composable(Routes.TOKEN_PAIRING) {
            TokenPairingScreen(
                loading = pairingLoading,
                status = pairingStatus,
                onBack = { navController.popBackStack() },
                onPair = { token ->
                    // Token is saved immediately, but validated via a real sync before this
                    // screen navigates anywhere (PLAN T3.5) — a wrong/expired token used to
                    // only surface later as a generic "Falha na sincronização" on Home.
                    store.pairingToken = token
                    pairingLoading = true
                    pairingStatus = null
                    awaitingPairingValidation = true
                    // :sync is a separate process and an already-running SharedPreferences
                    // instance does not reliably observe writes made here. Carry the one-time
                    // pairing credential in this explicit, non-exported service command so the
                    // validation never depends on cross-process preference invalidation.
                    SyncForegroundService.syncNow(context, token)
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                user = homeUser,
                networkAddress = networkAddress,
                syncing = syncLoading,
                syncStatus = syncStatus,
                lastSyncAtMs = lastSyncAtMs,
                lastSyncOk = lastSyncOk,
                modelRunning = modelRunning,
                onSyncClick = { syncNow() },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                activeModelName = activeModelName,
                onManageModelsClick = { navController.navigate(Routes.MODELS) }
            )
        }

        composable(Routes.MODELS) {
            ModelsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                pairingModeLabel = if (store.isLoggedIn()) stringResource(R.string.home_account_sufficit) else stringResource(R.string.settings_pairing_mode_token),
                onBack = { navController.popBackStack() },
                onLogout = {
                    SyncForegroundService.logout(context)
                    ModelRuntimeService.stop(context)
                    store.clear()
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
