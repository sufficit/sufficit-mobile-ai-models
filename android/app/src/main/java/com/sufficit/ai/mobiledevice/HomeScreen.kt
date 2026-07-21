package com.sufficit.ai.mobiledevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

data class HomeUser(
    val displayName: String?,
    val email: String?,
    val avatarUrl: String?,
    val viaOAuth: Boolean
)

/**
 * Header shows the logged-in Sufficit user (avatar + name, from
 * /connect/userinfo) when paired via Modo B, or a generic device icon +
 * "Pareado por token" when paired via Modo A (no Sufficit identity to show).
 */
@Composable
fun HomeScreen(
    user: HomeUser,
    networkAddress: String?,
    syncing: Boolean,
    syncStatus: String?,
    lastSyncAtMs: Long?,
    lastSyncOk: Boolean?,
    modelRunning: Boolean,
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    activeModelName: String?,
    onManageModelsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user.viaOAuth && user.avatarUrl != null) {
                    AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))

                Column {
                    Text(
                        if (user.viaOAuth) (user.displayName ?: stringResource(R.string.home_account_sufficit)) else stringResource(R.string.home_paired_by_token),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (user.viaOAuth && user.email != null) {
                        Text(
                            user.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.home_device_card_title), style = MaterialTheme.typography.titleSmall)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(
                                when {
                                    modelRunning && lastSyncOk == true -> Color(0xFF2E7D32)
                                    modelRunning || lastSyncOk == true -> Color(0xFFF9A825)
                                    else -> Color(0xFFC62828)
                                }
                            )
                        )
                        Text(
                            stringResource(
                                when {
                                    modelRunning && lastSyncOk == true -> R.string.home_status_operational
                                    modelRunning -> R.string.home_status_model_only
                                    lastSyncOk == true -> R.string.home_status_sync_only
                                    else -> R.string.home_status_waiting
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Read-only — the server decides this (Tailscale integration, PLAN Fase 3/4),
                    // never the device. Showing it here is informational only.
                    Column {
                        Text(
                            stringResource(R.string.home_network_address_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            networkAddress ?: stringResource(R.string.home_network_address_placeholder),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(
                        onClick = onSyncClick,
                        enabled = !syncing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.home_sync_now))
                        }
                    }

                    syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    lastSyncAtMs?.let {
                        Text(
                            stringResource(R.string.home_last_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.home_model_card_title), style = MaterialTheme.typography.titleSmall)

                    val noModelSelected = stringResource(R.string.home_no_model_selected)
                    val runningSuffix = stringResource(R.string.home_model_running_suffix)
                    val stoppedSuffix = stringResource(R.string.home_model_stopped_suffix)
                    Text(
                        buildString {
                            append(activeModelName ?: noModelSelected)
                            if (activeModelName != null) append(if (modelRunning) runningSuffix else stoppedSuffix)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedButton(onClick = onManageModelsClick, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_manage_models))
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SufficitTheme {
        HomeScreen(
            user = HomeUser(displayName = "Hugo", email = "hugo@sufficit.com.br", avatarUrl = null, viaOAuth = true),
            networkAddress = "100.64.0.12",
            syncing = false,
            syncStatus = "Sincronizado",
            lastSyncAtMs = null,
            lastSyncOk = true,
            modelRunning = true,
            onSyncClick = {},
            onSettingsClick = {},
            activeModelName = "gte-Qwen2-1.5B-instruct-Q4_K_M.gguf",
            onManageModelsClick = {}
        )
    }
}
